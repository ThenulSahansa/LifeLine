package com.example.lifeline

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

data class TerminalMessage(
    val text: String,
    val isReceived: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
)

class TerminalAdapter(private val messages: MutableList<TerminalMessage>) :
    RecyclerView.Adapter<TerminalAdapter.ViewHolder>() {

    class ViewHolder(val textView: TextView) : RecyclerView.ViewHolder(textView)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_terminal_message, parent, false) as TextView
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val message = messages[position]
        val prefix = if (message.isReceived) "RX: " else "> "
        val fullText = prefix + message.text
        holder.textView.text = fullText
        holder.textView.setTextColor(
            if (message.isReceived) 0xFF00FF00.toInt() else 0xFF00CC00.toInt(),
        )
    }

    override fun getItemCount(): Int = messages.size

    fun addMessage(message: TerminalMessage) {
        messages.add(message)
        notifyItemInserted(messages.size - 1)
    }
}

class MainActivity : AppCompatActivity() {

    private val sppUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothSocket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    private var locationManager: LocationManager? = null
    private var currentLocation: Location? = null

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (location.provider == LocationManager.GPS_PROVIDER) {
                currentLocation = location
            }
        }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private val messagesList = mutableListOf<TerminalMessage>()
    private lateinit var terminalAdapter: TerminalAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var statusText: TextView
    private lateinit var titleView: TextView
    private lateinit var sosButton: Button

    @Volatile
    private var isConnected = false

    private val executor = Executors.newSingleThreadExecutor()
    private val readExecutor = Executors.newSingleThreadExecutor()

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.entries.all { it.value }
        if (granted) {
            startGpsUpdates()
            checkPermissionsAndConnect()
        } else {
            Toast.makeText(this, "Permissions are required for Bluetooth and GPS", Toast.LENGTH_SHORT).show()
        }
    }

    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            checkPermissionsAndConnect()
        } else {
            Toast.makeText(this, "Bluetooth must be enabled to connect", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter
        locationManager = getSystemService(LOCATION_SERVICE) as? LocationManager

        recyclerView = findViewById(R.id.recyclerView)
        statusText = findViewById(R.id.textView2)
        titleView = findViewById(R.id.textView)
        sosButton = findViewById(R.id.sosButton)

        terminalAdapter = TerminalAdapter(messagesList)
        recyclerView.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        recyclerView.adapter = terminalAdapter

        titleView.setOnClickListener {
            if (recyclerView.isVisible) {
                recyclerView.visibility = View.GONE
                sosButton.visibility = View.VISIBLE
            } else {
                recyclerView.visibility = View.VISIBLE
                sosButton.visibility = View.GONE
            }
        }

        sosButton.setOnClickListener {
            showEmergencySelectionDialog()
        }

        statusText.setOnClickListener {
            onStatusClicked()
        }

        checkAndRequestInitialPermissions()
        addTerminalMessage("Terminal Initialized. Tap status to connect Bluetooth.", isReceived = true)
    }

    private fun checkAndRequestInitialPermissions() {
        val permissions = getRequiredPermissions()
        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            requestPermissionsLauncher.launch(missingPermissions.toTypedArray())
        } else {
            startGpsUpdates()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startGpsUpdates() {
        val locMgr = locationManager ?: return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            try {
                if (locMgr.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    locMgr.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        2000L,
                        1f,
                        locationListener,
                    )
                    val lastGps = locMgr.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    if (lastGps != null) {
                        currentLocation = lastGps
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun getGpsString(): String {
        val loc = currentLocation
        if (loc != null) {
            return String.format(Locale.US, "GPS: %.6f,%.6f", loc.latitude, loc.longitude)
        }

        val locMgr = locationManager
        if (locMgr != null && (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED)) {
            try {
                val last = locMgr.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                if (last != null) {
                    currentLocation = last
                    return String.format(Locale.US, "GPS: %.6f,%.6f", last.latitude, last.longitude)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return "GPS: No Fix"
    }

    private fun showEmergencySelectionDialog() {
        val emergencyTypes = arrayOf(
            "🏥 Medical",
            "🏚️ Trapped",
            "🔥 Fire",
            "🌊 Flood",
            "👥 Missing person",
            "❓ Other",
        )

        MaterialAlertDialogBuilder(this)
            .setTitle("What type of emergency?")
            .setItems(emergencyTypes) { _, which ->
                val selected = emergencyTypes[which]
                if (selected.contains("Other")) {
                    showCustomEmergencyDialog()
                } else {
                    sendBluetoothMessage("SOS: $selected")
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showCustomEmergencyDialog() {
        val inputEditText = EditText(this).apply {
            hint = "Type custom emergency..."
            setTextColor(0xFF00FF00.toInt())
            setHintTextColor(0xFF008800.toInt())
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(32, 32, 32, 32)
        }

        val container = FrameLayout(this).apply {
            setPadding(48, 24, 48, 24)
            addView(inputEditText)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Custom Emergency")
            .setView(container)
            .setPositiveButton("Send") { _, _ ->
                val customText = inputEditText.text.toString().trim()
                if (customText.isNotEmpty()) {
                    sendBluetoothMessage("SOS: $customText")
                } else {
                    Toast.makeText(this, "Emergency message cannot be empty", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun sendBluetoothMessage(message: String) {
        if ((!isConnected) || (outputStream == null)) {
            Toast.makeText(this, "Not connected to any Bluetooth device. Tap status to connect.", Toast.LENGTH_LONG).show()
            return
        }

        val gpsInfo = getGpsString()
        val fullMessage = "$message | $gpsInfo"

        executor.execute {
            try {
                val data = "$fullMessage\r\n".toByteArray(Charsets.UTF_8)
                outputStream?.write(data)
                outputStream?.flush()

                runOnUiThread {
                    addTerminalMessage(fullMessage, isReceived = false)
                    Toast.makeText(this, "SOS Sent with GPS", Toast.LENGTH_SHORT).show()
                }
            } catch (e: IOException) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(this, "Failed to send SOS: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun onStatusClicked() {
        if (isConnected) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Disconnect Bluetooth")
                .setMessage("Do you want to disconnect from the current device?")
                .setPositiveButton("Disconnect") { _, _ ->
                    closeCurrentConnection()
                    updateStatus(getString(R.string.bt_disconnected))
                    addTerminalMessage("Disconnected from device.", isReceived = true)
                }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            checkPermissionsAndConnect()
        }
    }

    private fun checkPermissionsAndConnect() {
        val adapter = bluetoothAdapter
        if (adapter == null) {
            Toast.makeText(this, "Bluetooth is not supported on this device.", Toast.LENGTH_LONG).show()
            return
        }

        val permissions = getRequiredPermissions()
        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            requestPermissionsLauncher.launch(missingPermissions.toTypedArray())
            return
        }

        val isEnabled = try {
            adapter.isEnabled
        } catch (_: SecurityException) {
            false
        }

        if (!isEnabled) {
            Toast.makeText(
                this,
                "Bluetooth is turned off. Please turn on Bluetooth to connect.",
                Toast.LENGTH_LONG,
            ).show()

            MaterialAlertDialogBuilder(this)
                .setTitle("Bluetooth Turned Off")
                .setMessage("Bluetooth is currently turned off on your device. Would you like to turn it on?")
                .setPositiveButton("Enable") { _, _ ->
                    try {
                        val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                        enableBluetoothLauncher.launch(enableBtIntent)
                    } catch (e: Exception) {
                        Toast.makeText(this, "Unable to request Bluetooth enable: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        showDeviceSelectionDialog()
    }

    private fun getRequiredPermissions(): Array<String> {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            permissions.add(Manifest.permission.BLUETOOTH)
            permissions.add(Manifest.permission.BLUETOOTH_ADMIN)
        }
        return permissions.toTypedArray()
    }

    @SuppressLint("MissingPermission")
    private fun showDeviceSelectionDialog() {
        val pairedDevices = try {
            bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()
        } catch (_: SecurityException) {
            emptyList()
        }

        if (pairedDevices.isEmpty()) {
            Toast.makeText(this, getString(R.string.no_paired_devices), Toast.LENGTH_LONG).show()
            return
        }

        val deviceNames = pairedDevices.map { device ->
            "${device.name ?: "Unknown Device"}\n${device.address}"
        }.toTypedArray()

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.select_bluetooth_device)
            .setItems(deviceNames) { _, which ->
                connectToDevice(pairedDevices[which])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        val deviceName = try {
            device.name ?: device.address
        } catch (_: SecurityException) {
            device.address
        }

        updateStatus(getString(R.string.bt_connecting))
        addTerminalMessage("Connecting to $deviceName...", isReceived = true)

        executor.execute {
            try {
                closeCurrentConnection()

                try {
                    bluetoothAdapter?.cancelDiscovery()
                } catch (_: SecurityException) {}

                bluetoothSocket = device.createRfcommSocketToServiceRecord(sppUuid)
                bluetoothSocket?.connect()

                inputStream = bluetoothSocket?.inputStream
                outputStream = bluetoothSocket?.outputStream
                isConnected = true

                runOnUiThread {
                    updateStatus("Connected: $deviceName")
                    addTerminalMessage("Connected to $deviceName", isReceived = true)
                }

                startListeningForData()
            } catch (e: Exception) {
                e.printStackTrace()
                closeCurrentConnection()
                runOnUiThread {
                    updateStatus(getString(R.string.bt_disconnected))
                    addTerminalMessage("Connection failed: ${e.localizedMessage}", isReceived = true)
                    Toast.makeText(this, "Failed to connect: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun startListeningForData() {
        readExecutor.execute {
            val reader = BufferedReader(InputStreamReader(inputStream))
            try {
                while (isConnected && (bluetoothSocket?.isConnected == true)) {
                    val line = reader.readLine() ?: break
                    runOnUiThread {
                        addTerminalMessage(line, isReceived = true)
                    }
                }
            } catch (e: IOException) {
                e.printStackTrace()
            } finally {
                if (isConnected) {
                    closeCurrentConnection()
                    runOnUiThread {
                        updateStatus(getString(R.string.bt_disconnected))
                        addTerminalMessage("Bluetooth disconnected", isReceived = true)
                    }
                }
            }
        }
    }

    private fun addTerminalMessage(text: String, isReceived: Boolean) {
        terminalAdapter.addMessage(TerminalMessage(text, isReceived))
        if (terminalAdapter.itemCount > 0) {
            recyclerView.smoothScrollToPosition(terminalAdapter.itemCount - 1)
        }
    }

    private fun updateStatus(status: String) {
        statusText.text = status
    }

    private fun closeCurrentConnection() {
        isConnected = false
        try {
            inputStream?.close()
        } catch (_: Exception) {}
        try {
            outputStream?.close()
        } catch (_: Exception) {}
        try {
            bluetoothSocket?.close()
        } catch (_: Exception) {}
        inputStream = null
        outputStream = null
        bluetoothSocket = null
    }

    override fun onDestroy() {
        super.onDestroy()
        closeCurrentConnection()
        try {
            locationManager?.removeUpdates(locationListener)
        } catch (_: Exception) {}
        executor.shutdown()
        readExecutor.shutdown()
    }
}