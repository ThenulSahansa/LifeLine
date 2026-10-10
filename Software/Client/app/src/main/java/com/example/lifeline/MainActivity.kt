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
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
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
            if (isBetterLocation(location, currentLocation)) {
                currentLocation = location
                runOnUiThread {
                    updateGpsDisplay()
                }
            }
        }
        override fun onProviderEnabled(provider: String) {
            startGpsUpdates()
        }
        override fun onProviderDisabled(provider: String) {
            updateGpsDisplay()
        }
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
                    if ((device != null) && !discoveredDevices.contains(device)) {
                        discoveredDevices.add(device)
                    }
                }
            }
        }
    }

    private lateinit var statusConnectContainer: View
    private lateinit var bluetoothStatusText: TextView
    private lateinit var bluetoothIcon: ImageView
    private lateinit var statusBadgeDot: TextView
    private lateinit var gpsStatusContainer: View
    private lateinit var gpsStatusText: TextView

    @Volatile
    private var isConnected = false

    private val executor = Executors.newSingleThreadExecutor()
    private val readExecutor = Executors.newSingleThreadExecutor()

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        val granted = permissions.values.all { it }
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

        statusConnectContainer = findViewById(R.id.statusConnectContainer)
        bluetoothStatusText = findViewById(R.id.textView2)
        bluetoothIcon = findViewById(R.id.bluetoothIcon)
        statusBadgeDot = findViewById(R.id.statusBadgeDot)
        gpsStatusContainer = findViewById(R.id.gpsStatusContainer)
        gpsStatusText = findViewById(R.id.gpsStatusText)

        // Register Bluetooth Discovery Receiver
        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        registerReceiver(bluetoothReceiver, filter)

        // SOS Buttons Setup (10 Emergency Profiles)
        setupSosButtons()

        statusConnectContainer.setOnClickListener {
            onStatusClicked()
        }

        gpsStatusContainer.setOnClickListener {
            onGpsChipClicked()
        }

        checkAndRequestInitialPermissions()
        updateGpsDisplay()
    }

    override fun onResume() {
        super.onResume()
        startGpsUpdates()
        updateGpsDisplay()
    }

    private fun setupSosButtons() {
        val sosMap = mapOf(
            R.id.sosMedicalButton to Triple("MEDICAL (Critical Injury)", "We need Medical assistance (Critical Injury)", false),
            R.id.sosFireButton to Triple("FIRE (Structure/Wildfire)", "We need Fire assistance (Structure/Wildfire)", false),
            R.id.sosSecurityButton to Triple("SECURITY (Hostile Threat)", "We need Security assistance (Hostile Threat)", false),
            R.id.sosTrappedButton to Triple("TRAPPED (Rubble / Rescue)", "We need Trapped assistance (Rubble / Rescue)", false),
            R.id.sosHazmatButton to Triple("HAZMAT (Gas / Chemical)", "We need Hazmat assistance (Gas / Chemical)", false),
            R.id.sosFloodButton to Triple("FLOOD (Severe Flooding)", "We need Flood assistance (Severe Flooding)", false),
            R.id.sosWeatherButton to Triple("WEATHER (Extreme Storm)", "We need Weather assistance (Extreme Storm)", false),
            R.id.sosFoodWaterButton to Triple("FOOD/WATER (Clean Supplies)", "We need Food & Clean water assistance", false),
            R.id.sosExtractionButton to Triple("EXTRACTION (Emergency Evac)", "We need Emergency evacuation assistance", false),
            R.id.sosOtherButton to Triple("OTHER EMERGENCY", "Custom Emergency Broadcast", true),
        )

        sosMap.forEach { (buttonId, triple) ->
            findViewById<Button>(buttonId)?.setOnClickListener {
                showSosDetailsDialog(triple.first, triple.second, isCustomType = triple.third)
            }
        }
    }

    private fun showSosDetailsDialog(
        emergencyTitle: String,
        baseMessage: String,
        isCustomType: Boolean = false,
    ) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_sos_details, null)

        val titleView = dialogView.findViewById<TextView>(R.id.dialogCategoryTitle)
        val presetCardView = dialogView.findViewById<View>(R.id.dialogPresetCard)
        val baseMessageView = dialogView.findViewById<TextView>(R.id.dialogBaseMessage)
        val customTypeLayout = dialogView.findViewById<View>(R.id.dialogCustomTypeLayout)
        val customTypeEditText = dialogView.findViewById<TextInputEditText>(R.id.dialogCustomTypeEditText)
        val gpsLocationView = dialogView.findViewById<TextView>(R.id.dialogGpsLocation)
        val inputLayout = dialogView.findViewById<TextInputLayout>(R.id.dialogInputLayout)
        val inputEditText = dialogView.findViewById<TextInputEditText>(R.id.dialogInputEditText)

        titleView.text = getString(R.string.sos_title_format, emergencyTitle)
        gpsLocationView.text = getGpsString()

        if (isCustomType) {
            presetCardView.visibility = View.GONE
            customTypeLayout.visibility = View.VISIBLE
            inputLayout.hint = getString(R.string.description_hint)
        } else {
            presetCardView.visibility = View.VISIBLE
            customTypeLayout.visibility = View.GONE
            baseMessageView.text = baseMessage
            inputLayout.hint = getString(R.string.additional_details_hint)
        }

        MaterialAlertDialogBuilder(this, R.style.Theme_LifeLine_Dialog)
            .setView(dialogView)
            .setPositiveButton(R.string.transmit_sos) { _, _ ->
                val finalMessage = if (isCustomType) {
                    val customType = customTypeEditText.text?.toString()?.trim() ?: ""
                    val description = inputEditText.text?.toString()?.trim() ?: ""
                    val effectiveType = customType.ifEmpty { "OTHER" }
                    if (description.isNotEmpty()) {
                        "OTHER ($effectiveType) - $description"
                    } else {
                        "OTHER ($effectiveType)"
                    }
                } else {
                    val extraDetails = inputEditText.text?.toString()?.trim() ?: ""
                    if (extraDetails.isNotEmpty()) {
                        "$baseMessage - $extraDetails"
                    } else {
                        baseMessage
                    }
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
        val hasFine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (!hasFine && !hasCoarse) return

        try {
            var bestLastLocation: Location? = null

            // 1. Request GPS Provider (Satellite)
            if (locMgr.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locMgr.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000L,
                    1f,
                    locationListener,
                )
                bestLastLocation = locMgr.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            }

            // 2. Request Network Provider (Cell/Wi-Fi - crucial indoors & fast fixes!)
            if (locMgr.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locMgr.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    1000L,
                    1f,
                    locationListener,
                )
                val lastNet = locMgr.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                if ((lastNet != null) && isBetterLocation(lastNet, bestLastLocation)) {
                    bestLastLocation = lastNet
                }
            }

            // 3. Request Passive Provider
            if (locMgr.isProviderEnabled(LocationManager.PASSIVE_PROVIDER)) {
                try {
                    locMgr.requestLocationUpdates(
                        LocationManager.PASSIVE_PROVIDER,
                        1000L,
                        1f,
                        locationListener,
                    )
                    val lastPassive = locMgr.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
                    if ((lastPassive != null) && isBetterLocation(lastPassive, bestLastLocation)) {
                        bestLastLocation = lastPassive
                    }
                } catch (_: Exception) {}
            }

            if ((bestLastLocation != null) && isBetterLocation(bestLastLocation, currentLocation)) {
                currentLocation = bestLastLocation
                updateGpsDisplay()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updateGpsDisplay() {
        gpsStatusText.text = getGpsString()
    }

    private fun getGpsString(): String {
        val hasFine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (!hasFine && !hasCoarse) {
            return "GPS: Permission Required"
        }

        val locMgr = locationManager
        val isGpsEnabled = locMgr?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true
        val isNetworkEnabled = locMgr?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true

        if (!isGpsEnabled && !isNetworkEnabled) {
            return "GPS: Location Disabled (Tap to Enable)"
        }

        val loc = currentLocation ?: getBestLastKnownLocation()

        return if (loc != null) {
            String.format(Locale.US, "GPS: %.6f, %.6f", loc.latitude, loc.longitude)
        } else {
            getString(R.string.gps_acquiring)
        }
    }

    @SuppressLint("MissingPermission")
    private fun getBestLastKnownLocation(): Location? {
        val locMgr = locationManager ?: return null
        var best: Location? = null
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        for (p in providers) {
            try {
                if (locMgr.isProviderEnabled(p)) {
                    val l = locMgr.getLastKnownLocation(p)
                    if ((l != null) && isBetterLocation(l, best)) {
                        best = l
                    }
                }
            } catch (_: Exception) {}
        }
        return best
    }

    private fun isBetterLocation(location: Location, currentBestLocation: Location?): Boolean {
        if (currentBestLocation == null) return true
        val timeDelta = location.time - currentBestLocation.time
        if (timeDelta > 60_000) return true
        if (timeDelta < -60_000) return false

        val accuracyDelta = (location.accuracy - currentBestLocation.accuracy).toInt()
        val isMoreAccurate = accuracyDelta < 0
        return isMoreAccurate || ((timeDelta > 0) && (accuracyDelta <= 200) && (location.provider == currentBestLocation.provider))
    }

    private fun onGpsChipClicked() {
        val locMgr = locationManager
        val isGpsEnabled = locMgr?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true
        val isNetworkEnabled = locMgr?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true

        if (!isGpsEnabled && !isNetworkEnabled) {
            try {
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            } catch (e: Exception) {
                Toast.makeText(this, "Unable to open location settings: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        } else {
            startGpsUpdates()
            updateGpsDisplay()
            Toast.makeText(this, "Refreshing location fix...", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendBluetoothMessage(message: String) {
        val gpsInfo = getGpsString()
        val fullMessage = "$message | $gpsInfo"

        if ((!isConnected) || (outputStream == null)) {
            MaterialAlertDialogBuilder(this, R.style.Theme_LifeLine_Dialog)
                .setTitle("🚨 SOS Broadcast Prepared")
                .setMessage("$fullMessage\n\n⚠️ Mesh Bluetooth is currently disconnected. Tap 'Connect' to pair with a nearest node and transmit.")
                .setPositiveButton("Connect Device") { _, _ ->
                    checkPermissionsAndConnect()
                }
                .setNegativeButton("OK", null)
                .show()
            return
        }

        executor.execute {
            try {
                val data = "$fullMessage\r\n".toByteArray(Charsets.UTF_8)
                outputStream?.write(data)
                outputStream?.flush()

                runOnUiThread {
                    Toast.makeText(this, "🚨 SOS Broadcast Transmitted over Mesh!", Toast.LENGTH_LONG).show()
                }
            } catch (e: IOException) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(this, "Transmission Failed: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun onStatusClicked() {
        if (isConnected) {
            MaterialAlertDialogBuilder(this, R.style.Theme_LifeLine_Dialog)
                .setTitle("Bluetooth Connected")
                .setMessage("Currently connected to mesh node. Do you want to disconnect?")
                .setPositiveButton("Disconnect") { _, _ ->
                    closeCurrentConnection()
                    updateStatus(getString(R.string.bt_disconnected))
                    Toast.makeText(this, "Disconnected from Bluetooth device.", Toast.LENGTH_SHORT).show()
                }
                .setNeutralButton("Switch Node") { _, _ ->
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
            MaterialAlertDialogBuilder(this, R.style.Theme_LifeLine_Dialog)
                .setTitle("Bluetooth Turned Off")
                .setMessage("Bluetooth is currently turned off on your device. Turn it on to scan for nearby mesh nodes?")
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
            MaterialAlertDialogBuilder(this, R.style.Theme_LifeLine_Dialog)
                .setTitle("📡 Nearby Bluetooth Devices")
                .setMessage("Scanning for nearest Bluetooth & LoRa mesh nodes...\n\nEnsure your device is powered on and in range.")
                .setPositiveButton("Scan Again") { _, _ ->
                    checkPermissionsAndConnect()
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        val deviceNames = combinedDevices.map { device ->
            val name = try { device.name } catch (_: SecurityException) { null } ?: "Unknown Device"
            val bondedText = try {
                if (device.bondState == BluetoothDevice.BOND_BONDED) "[Paired]" else "[Nearby]"
            } catch (_: SecurityException) { "" }
            "$name $bondedText\n${device.address}"
        }.toTypedArray()

        MaterialAlertDialogBuilder(this, R.style.Theme_LifeLine_Dialog)
            .setTitle(R.string.select_bluetooth_device)
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

        updateStatus("Connecting to $deviceName...")

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
                    updateStatus(getString(R.string.bt_connected, deviceName))
                    Toast.makeText(this, "Connected to $deviceName", Toast.LENGTH_SHORT).show()
                }

                startListeningForData()
            } catch (e: Exception) {
                e.printStackTrace()
                closeCurrentConnection()
                runOnUiThread {
                    updateStatus(getString(R.string.bt_disconnected))
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
                        MaterialAlertDialogBuilder(this, R.style.Theme_LifeLine_Dialog)
                            .setTitle("📡 Incoming Mesh Message")
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
                        updateStatus(getString(R.string.bt_disconnected))
                        Toast.makeText(this, "Bluetooth disconnected.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun updateStatus(statusText: String) {
        bluetoothStatusText.text = statusText
        val greenColor = ContextCompat.getColor(this, R.color.green_connected)
        val amberColor = ContextCompat.getColor(this, R.color.amber_primary)

        if (isConnected) {
            statusConnectContainer.setBackgroundResource(R.drawable.bg_pill_green)
            bluetoothStatusText.setTextColor(greenColor)
            bluetoothIcon.setColorFilter(greenColor)
            statusBadgeDot.backgroundTintList = ContextCompat.getColorStateList(this, R.color.green_connected)
        } else {
            statusConnectContainer.setBackgroundResource(R.drawable.bg_pill_amber)
            bluetoothStatusText.setTextColor(amberColor)
            bluetoothIcon.setColorFilter(amberColor)
            statusBadgeDot.backgroundTintList = ContextCompat.getColorStateList(this, R.color.amber_primary)
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