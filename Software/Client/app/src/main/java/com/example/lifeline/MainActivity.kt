package com.example.lifeline

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val sppUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothSocket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    private var locationManager: LocationManager? = null
    private var currentLocation: Location? = null

    private val discoveredDevices = mutableListOf<BluetoothDevice>()

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (location.provider == LocationManager.GPS_PROVIDER) {
                currentLocation = location
            }
        }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    if (device != null && !discoveredDevices.contains(device)) {
                        discoveredDevices.add(device)
                    }
                }
            }
        }
    }

    private lateinit var statusText: Button
    private lateinit var titleView: TextView

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

        statusText = findViewById(R.id.textView2)
        titleView = findViewById(R.id.textView)

        // Register Bluetooth Discovery Receiver
        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        registerReceiver(bluetoothReceiver, filter)

        // SOS Buttons Setup (9 Emergency Profiles)
        setupSosButtons()

        statusText.setOnClickListener {
            onStatusClicked()
        }

        checkAndRequestInitialPermissions()
        Toast.makeText(this, "Life Line Initialized. Tap 'Connect' to view nearest devices.", Toast.LENGTH_LONG).show()
    }

    private fun setupSosButtons() {
        val sosMap = mapOf(
            R.id.sosMedicalButton to Pair("MEDICAL (Critical Injury)", "We need Medical assistance (Critical Injury)"),
            R.id.sosFireButton to Pair("FIRE (Structure/Wildfire)", "We need Fire assistance (Structure/Wildfire)"),
            R.id.sosSecurityButton to Pair("SECURITY (Hostile/Terrorist)", "We need Security assistance (Hostile/Terrorist)"),
            R.id.sosTrappedButton to Pair("TRAPPED (Rubble / Rescue)", "We need Trapped assistance (Rubble / Rescue)"),
            R.id.sosHazmatButton to Pair("HAZMAT (Gas/Chemical Leak)", "We need Hazmat assistance (Gas/Chemical Leak)"),
            R.id.sosFloodButton to Pair("FLOOD (Severe Flooding)", "We need Flood assistance (Severe Flooding)"),
            R.id.sosWeatherButton to Pair("WEATHER (Extreme Storm)", "We need Weather assistance (Extreme Storm)"),
            R.id.sosFoodWaterButton to Pair("FOOD/WATER (Clean Supplies)", "We need Food & Clean water assistance (Clean Supplies)"),
            R.id.sosExtractionButton to Pair("EXTRACTION (Emergency Evac)", "We need Emergency evacuation assistance (Emergency Evac)"),
        )

        sosMap.forEach { (buttonId, pair) ->
            findViewById<Button>(buttonId)?.setOnClickListener {
                showSosDetailsDialog(pair.first, pair.second)
            }
        }
    }

    private fun showSosDetailsDialog(emergencyTitle: String, baseMessage: String) {
        val inputEditText = EditText(this).apply {
            hint = "Additional details (optional)"
            setTextColor(0xFFFF6666.toInt())
            setHintTextColor(0xFF884444.toInt())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            setPadding(32, 32, 32, 32)
        }

        val container = FrameLayout(this).apply {
            setPadding(48, 24, 48, 24)
            addView(inputEditText)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("🚨 $emergencyTitle")
            .setView(container)
            .setPositiveButton("Send Broadcast") { _, _ ->
                val extraDetails = inputEditText.text.toString().trim()
                val finalMessage = if (extraDetails.isNotEmpty()) {
                    "$baseMessage - $extraDetails"
                } else {
                    baseMessage
                }
                sendSosMessage(finalMessage)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun sendSosMessage(sosMessageText: String) {
        val fullSosMessage = "SOS: $sosMessageText"
        sendBluetoothMessage(fullSosMessage)
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

    private fun sendBluetoothMessage(message: String) {
        val gpsInfo = getGpsString()
        val fullMessage = "$message | $gpsInfo"

        if ((!isConnected) || (outputStream == null)) {
            MaterialAlertDialogBuilder(this)
                .setTitle("SOS Broadcast Prepared")
                .setMessage("$fullMessage\n\n(Bluetooth is disconnected. Tap 'Connect' at top right to select a nearest device and transmit over mesh).")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        executor.execute {
            try {
                val data = "$fullMessage\r\n".toByteArray(Charsets.UTF_8)
                outputStream?.write(data)
                outputStream?.flush()

                runOnUiThread {
                    Toast.makeText(this, "🚨 SOS Broadcast Sent over Bluetooth!", Toast.LENGTH_LONG).show()
                }
            } catch (e: IOException) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(this, "Failed to send: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun onStatusClicked() {
        if (isConnected) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Bluetooth Connected")
                .setMessage("Currently connected to device. Do you want to disconnect?")
                .setPositiveButton("Disconnect") { _, _ ->
                    closeCurrentConnection()
                    updateStatus(getString(R.string.bt_disconnected))
                    Toast.makeText(this, "Disconnected from Bluetooth device.", Toast.LENGTH_SHORT).show()
                }
                .setNeutralButton("Switch Device") { _, _ ->
                    checkPermissionsAndConnect()
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
            Toast.makeText(this, "Bluetooth hardware is not supported on this device.", Toast.LENGTH_LONG).show()
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
            MaterialAlertDialogBuilder(this)
                .setTitle("Bluetooth Turned Off")
                .setMessage("Bluetooth is currently turned off on your device. Turn it on to view nearest devices?")
                .setPositiveButton("Turn On") { _, _ ->
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
        val adapter = bluetoothAdapter ?: return

        try {
            if (adapter.isDiscovering) {
                adapter.cancelDiscovery()
            }
            adapter.startDiscovery()
        } catch (_: SecurityException) {}

        val pairedDevices = try {
            adapter.bondedDevices?.toList() ?: emptyList()
        } catch (_: SecurityException) {
            emptyList()
        }

        val combinedDevices = mutableListOf<BluetoothDevice>()
        combinedDevices.addAll(pairedDevices)
        discoveredDevices.forEach { dev ->
            if (!combinedDevices.contains(dev)) {
                combinedDevices.add(dev)
            }
        }

        if (combinedDevices.isEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle("📡 Nearest Bluetooth Devices")
                .setMessage("Scanning for nearest Bluetooth & LoRa devices...\n\nMake sure your module is powered on and discoverable.")
                .setPositiveButton("Scan Again") { _, _ ->
                    checkPermissionsAndConnect()
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        val deviceNames = combinedDevices.map { device ->
            val name = try { device.name } catch (_: SecurityException) { null } ?: "Bluetooth Device"
            val bondedText = try {
                if (device.bondState == BluetoothDevice.BOND_BONDED) "[Paired]" else "[Nearby]"
            } catch (_: SecurityException) { "" }
            "$name $bondedText\n${device.address}"
        }.toTypedArray()

        MaterialAlertDialogBuilder(this)
            .setTitle("📡 Nearest Bluetooth Devices")
            .setItems(deviceNames) { _, which ->
                connectToDevice(combinedDevices[which])
            }
            .setNeutralButton("🔄 Scan Again") { _, _ ->
                checkPermissionsAndConnect()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        val deviceName = try {
            device.name ?: device.address
        } catch (_: SecurityException) {
            device.address
        }

        updateStatus("Connecting...")

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
                    updateStatus("🟢 $deviceName")
                    Toast.makeText(this, "Connected to $deviceName", Toast.LENGTH_SHORT).show()
                }

                startListeningForData()
            } catch (e: Exception) {
                e.printStackTrace()
                closeCurrentConnection()
                runOnUiThread {
                    updateStatus("Connect")
                    Toast.makeText(this, "Failed to connect to $deviceName", Toast.LENGTH_SHORT).show()
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
                        MaterialAlertDialogBuilder(this)
                            .setTitle("Incoming Message")
                            .setMessage(line)
                            .setPositiveButton("OK", null)
                            .show()
                    }
                }
            } catch (e: IOException) {
                e.printStackTrace()
            } finally {
                if (isConnected) {
                    closeCurrentConnection()
                    runOnUiThread {
                        updateStatus("Connect")
                        Toast.makeText(this, "Bluetooth disconnected.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun updateStatus(status: String) {
        statusText.text = status
        if (isConnected) {
            statusText.setBackgroundColor(0xFF004400.toInt())
            statusText.setTextColor(0xFF00FF00.toInt())
        } else {
            statusText.setBackgroundColor(0xFF221500.toInt())
            statusText.setTextColor(0xFFFFB000.toInt())
        }
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
        try {
            unregisterReceiver(bluetoothReceiver)
        } catch (_: Exception) {}
        closeCurrentConnection()
        try {
            locationManager?.removeUpdates(locationListener)
        } catch (_: Exception) {}
        executor.shutdown()
        readDestroy()
    }

    private fun readDestroy() {
        readExecutor.shutdown()
    }
}