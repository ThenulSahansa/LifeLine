package com.example.lifeline

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject
import android.view.ViewGroup
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    companion object {
        // Nordic UART Service (NUS) & Generic BLE Serial UUIDs
        private val UART_SERVICE_UUID: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        private val UART_TX_CHAR_UUID: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E") // Phone -> Node (Write)
        private val UART_RX_CHAR_UUID: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E") // Node -> Phone (Notify)
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

        private const val PREFS_NAME = "lifeline_user_prefs"
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_USER_NIC = "user_nic"
        private const val KEY_SENT_MESSAGES_JSON = "sent_messages_json"
        private const val KEY_LAST_MSG_NUMBER = "last_msg_number"
    }

    data class SentMessageRecord(
        val messageNumber: String,
        val timestamp: String,
        val emergencyType: String,
        val fullAlertText: String,
        val messageId: String,
        val unpaddedNumber: String,
        var isReached: Boolean = false,
    )

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bleScanner: BluetoothLeScanner? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var readCharacteristic: BluetoothGattCharacteristic? = null
    private var currentMtu: Int = 23
    private val bleSendExecutor = Executors.newSingleThreadExecutor()

    private var locationManager: LocationManager? = null
    private var currentLocation: Location? = null

    private val discoveredDevices = mutableListOf<BluetoothDevice>()
    private var isScanning = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var statusConnectContainer: View
    private lateinit var bluetoothStatusText: TextView
    private lateinit var bluetoothIcon: ImageView
    private lateinit var statusBadgeDot: TextView
    private lateinit var gpsStatusContainer: View
    private lateinit var gpsStatusText: TextView

    private lateinit var btnToggleTerminal: View
    private lateinit var terminalOverlayCard: View
    private lateinit var btnCloseTerminal: View
    private lateinit var terminalLogTextView: TextView
    private lateinit var terminalScrollView: ScrollView

    private lateinit var btnToggleSentMessages: View
    private lateinit var sentMessagesOverlayCard: View
    private lateinit var btnCloseSentMessages: View
    private lateinit var sentMessagesContainer: ViewGroup
    private lateinit var sentMessagesEmptyView: View
    private val sentMessagesList = mutableListOf<SentMessageRecord>()

    @Volatile
    private var isConnected = false

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val device = result?.device ?: return
            if (!discoveredDevices.contains(device)) {
                discoveredDevices.add(device)
                val devName = try { device.name ?: device.address } catch (_: SecurityException) { device.address }
                appendTerminalLog("SCAN", "Found BLE Device: $devName")
            }
        }

        @SuppressLint("MissingPermission")
        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            results?.forEach { result ->
                val device = result.device
                if (!discoveredDevices.contains(device)) {
                    discoveredDevices.add(device)
                    val devName = try { device.name ?: device.address } catch (_: SecurityException) { device.address }
                    appendTerminalLog("SCAN", "Found BLE Device: $devName")
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            appendTerminalLog("SCAN_ERR", "BLE Scan failed code: $errorCode")
            runOnUiThread {
                Toast.makeText(this@MainActivity, "BLE Scan failed code: $errorCode", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            val deviceName = try {
                gatt?.device?.name ?: gatt?.device?.address ?: "Device"
            } catch (_: SecurityException) {
                "Device"
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                isConnected = true
                appendTerminalLog("CONNECT", "Connected to GATT Server: $deviceName")
                runOnUiThread {
                    updateStatus(getString(R.string.bt_connected, deviceName))
                    Toast.makeText(this@MainActivity, "Connected to $deviceName", Toast.LENGTH_SHORT).show()
                }
                // Request MTU 517 to prevent 20-byte payload truncation
                val mtuRequested = gatt?.requestMtu(517) ?: false
                if (!mtuRequested) {
                    gatt?.discoverServices()
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val wasConnected = isConnected
                isConnected = false
                currentMtu = 23
                appendTerminalLog("DISCONNECT", "Disconnected from $deviceName (Status: $status)")
                closeCurrentConnection()
                runOnUiThread {
                    updateStatus(getString(R.string.bt_disconnected))
                    if (wasConnected) {
                        Toast.makeText(this@MainActivity, "Disconnected from $deviceName", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            if ((status == BluetoothGatt.GATT_SUCCESS) && (mtu > 23)) {
                currentMtu = mtu
                appendTerminalLog("GATT", "Negotiated ATT MTU: $mtu bytes")
            } else {
                currentMtu = 23
                appendTerminalLog("GATT", "MTU negotiation failed ($status), default to 23 bytes")
            }
            gatt?.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if ((status == BluetoothGatt.GATT_SUCCESS) && (gatt != null)) {
                val service = gatt.getService(UART_SERVICE_UUID)
                if (service != null) {
                    writeCharacteristic = service.getCharacteristic(UART_TX_CHAR_UUID)
                    readCharacteristic = service.getCharacteristic(UART_RX_CHAR_UUID)
                    appendTerminalLog("GATT", "Matched Nordic UART Service (NUS)")
                } else {
                    appendTerminalLog("GATT", "NUS Service not found. Searching fallback characteristics...")
                    for (s in gatt.services) {
                        for (c in s.characteristics) {
                            if ((writeCharacteristic == null) &&
                                ((c.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0)
                            ) {
                                writeCharacteristic = c
                            }
                            if ((readCharacteristic == null) &&
                                ((c.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0)
                            ) {
                                readCharacteristic = c
                            }
                        }
                    }
                }

                appendTerminalLog("GATT", "TX Write Char: ${writeCharacteristic?.uuid ?: "None"}")
                appendTerminalLog("GATT", "RX Notify Char: ${readCharacteristic?.uuid ?: "None"}")

                // Enable Notification on Read/RX Characteristic
                readCharacteristic?.let { char ->
                    gatt.setCharacteristicNotification(char, true)
                    val descriptor = char.getDescriptor(CCCD_UUID)
                    if (descriptor != null) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                        } else {
                            @Suppress("DEPRECATION")
                            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            @Suppress("DEPRECATION")
                            gatt.writeDescriptor(descriptor)
                        }
                        appendTerminalLog("GATT", "Subscribed to BLE serial notifications")
                    }
                }
            } else {
                appendTerminalLog("GATT_ERR", "Service discovery failed with status: $status")
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handleIncomingData(value)
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
        ) {
            @Suppress("DEPRECATION")
            val value = characteristic?.value ?: return
            handleIncomingData(value)
        }
    }

    private fun handleIncomingData(value: ByteArray) {
        val message = String(value, Charsets.UTF_8).trim()
        if (message.isNotEmpty()) {
            appendTerminalLog("RX", message)
            checkAndHandleAckMessage(message)
        }
    }

    private fun checkAndHandleAckMessage(incomingMessage: String) {
        val ackMarker = "[ACK]"
        val ackIndex = incomingMessage.indexOf(ackMarker)
        if (ackIndex < 0) return

        val afterAck = incomingMessage.substring(ackIndex + ackMarker.length).trim()
        val colonIdx = afterAck.indexOf(':')
        val rawAckHash = if (colonIdx >= 0) afterAck.substring(colonIdx + 1).trim() else afterAck

        val nic = getUserNic()
        var updated = false
        var matchedNumber = ""

        synchronized(sentMessagesList) {
            for (record in sentMessagesList) {
                if (!record.isReached) {
                    if (Base85.matchesAck(nic, record.messageNumber, record.unpaddedNumber, rawAckHash) ||
                        record.messageId == rawAckHash ||
                        rawAckHash.contains(record.messageId)) {
                        record.isReached = true
                        updated = true
                        matchedNumber = record.messageNumber
                        break
                    }
                }
            }
        }

        if (updated) {
            saveSentMessages()
            runOnUiThread {
                renderSentMessagesList()
                Toast.makeText(
                    this@MainActivity,
                    "✓ Message #$matchedNumber Reached Destination!",
                    Toast.LENGTH_LONG
                ).show()
            }
            appendTerminalLog("ACK", "Delivery confirmed for Message #$matchedNumber (Status: REACHED)")
        }
    }

    private fun appendTerminalLog(tag: String, message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val logEntry = "[$timestamp] [$tag] $message\n"
        runOnUiThread {
            if (::terminalLogTextView.isInitialized) {
                terminalLogTextView.append(logEntry)
                terminalScrollView.post {
                    terminalScrollView.fullScroll(View.FOCUS_DOWN)
                }
            }
        }
    }

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

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        val granted = permissions.values.all { it }
        if (granted) {
            startGpsUpdates()
            checkPermissionsAndConnect()
        } else {
            Toast.makeText(this, "Permissions are required for Bluetooth BLE and GPS", Toast.LENGTH_SHORT).show()
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

        btnToggleTerminal = findViewById(R.id.btnToggleTerminal)
        terminalOverlayCard = findViewById(R.id.terminalOverlayCard)
        btnCloseTerminal = findViewById(R.id.btnCloseTerminal)
        terminalLogTextView = findViewById(R.id.terminalLogTextView)
        terminalScrollView = findViewById(R.id.terminalScrollView)

        btnToggleSentMessages = findViewById(R.id.btnToggleSentMessages)
        sentMessagesOverlayCard = findViewById(R.id.sentMessagesOverlayCard)
        btnCloseSentMessages = findViewById(R.id.btnCloseSentMessages)
        sentMessagesContainer = findViewById(R.id.sentMessagesContainer)
        sentMessagesEmptyView = findViewById(R.id.sentMessagesEmptyView)

        loadSentMessages()
        renderSentMessagesList()

        btnToggleTerminal.setOnClickListener {
            if (terminalOverlayCard.isVisible) {
                terminalOverlayCard.visibility = View.GONE
            } else {
                sentMessagesOverlayCard.visibility = View.GONE
                terminalOverlayCard.visibility = View.VISIBLE
                terminalScrollView.post {
                    terminalScrollView.fullScroll(View.FOCUS_DOWN)
                }
            }
        }

        btnCloseTerminal.setOnClickListener {
            terminalOverlayCard.visibility = View.GONE
        }

        btnToggleSentMessages.setOnClickListener {
            if (sentMessagesOverlayCard.isVisible) {
                sentMessagesOverlayCard.visibility = View.GONE
            } else {
                terminalOverlayCard.visibility = View.GONE
                renderSentMessagesList()
                sentMessagesOverlayCard.visibility = View.VISIBLE
            }
        }

        btnCloseSentMessages.setOnClickListener {
            sentMessagesOverlayCard.visibility = View.GONE
        }

        val btnAccount = findViewById<View>(R.id.btnAccount)
        btnAccount?.setOnClickListener {
            showAccountInfoDialog()
        }

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

        // Check if user setup is complete on first launch
        if (getUserName().isEmpty() || (getUserNic().length != 12)) {
            showUserProfileSetupDialog(isMandatory = true)
        }
    }

    private fun getUserName(): String {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        return prefs.getString(KEY_USER_NAME, "") ?: ""
    }

    private fun getUserNic(): String {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        return prefs.getString(KEY_USER_NIC, "") ?: ""
    }

    private fun saveUserProfile(name: String, nic: String) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit {
            putString(KEY_USER_NAME, name)
            putString(KEY_USER_NIC, nic)
        }
    }

    private fun showUserProfileSetupDialog(isMandatory: Boolean = false) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_user_profile, null)
        val nameInputLayout = dialogView.findViewById<TextInputLayout>(R.id.inputLayoutName)
        val nameEditText = dialogView.findViewById<TextInputEditText>(R.id.editTextName)
        val nicInputLayout = dialogView.findViewById<TextInputLayout>(R.id.inputLayoutNic)
        val nicEditText = dialogView.findViewById<TextInputEditText>(R.id.editTextNic)

        nameEditText.setText(getUserName())
        nicEditText.setText(getUserNic())

        val builder = MaterialAlertDialogBuilder(this, R.style.Theme_LifeLine_Dialog)
            .setView(dialogView)
            .setCancelable(!isMandatory)
            .setPositiveButton(R.string.save_profile, null)

        if (!isMandatory) {
            builder.setNegativeButton(android.R.string.cancel, null)
        }

        val dialog = builder.create()
        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = nameEditText.text?.toString()?.trim() ?: ""
            val nic = nicEditText.text?.toString()?.trim() ?: ""

            var isValid = true
            if (name.isEmpty()) {
                nameInputLayout.error = getString(R.string.invalid_name_error)
                isValid = false
            } else {
                nameInputLayout.error = null
            }

            if ((nic.length != 12) || !nic.all { it.isDigit() }) {
                nicInputLayout.error = getString(R.string.invalid_nic_error)
                isValid = false
            } else {
                nicInputLayout.error = null
            }

            if (isValid) {
                saveUserProfile(name, nic)
                Toast.makeText(this, "Profile saved successfully", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }
    }

    private fun showAccountInfoDialog() {
        val name = getUserName()
        val nic = getUserNic()

        if (name.isEmpty() || (nic.length != 12)) {
            showUserProfileSetupDialog(isMandatory = true)
            return
        }

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_account_info, null)
        val nameView = dialogView.findViewById<TextView>(R.id.accountViewName)
        val nicView = dialogView.findViewById<TextView>(R.id.accountViewNic)

        nameView.text = name
        nicView.text = nic

        MaterialAlertDialogBuilder(this, R.style.Theme_LifeLine_Dialog)
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.edit_profile) { _, _ ->
                showUserProfileSetupDialog(isMandatory = false)
            }
            .show()
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
                    val categoryName = emergencyTitle.substringBefore(" ").trim()
                    val extraDetails = inputEditText.text?.toString()?.trim() ?: ""
                    if (extraDetails.isNotEmpty()) {
                        "$categoryName - $baseMessage - $extraDetails"
                    } else {
                        "$categoryName - $baseMessage"
                    }
                }
                val chosenCategory = if (isCustomType) {
                    customTypeEditText.text?.toString()?.trim()?.ifEmpty { "OTHER" } ?: "OTHER"
                } else {
                    emergencyTitle.substringBefore(" ").trim()
                }
                sendSosMessage(finalMessage, chosenCategory)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun sendSosMessage(sosMessageText: String, emergencyCategory: String = "EMERGENCY") {
        val nic = getUserNic()
        if (nic.isEmpty() || (nic.length != 12)) {
            showUserProfileSetupDialog(isMandatory = true)
            return
        }

        val gatt = bluetoothGatt
        val writeChar = writeCharacteristic
        if (!isConnected || (gatt == null) || (writeChar == null)) {
            val dateTimePreview = SimpleDateFormat("dd:MM:yy HH:mm:ss", Locale.US).format(Date())
            val gpsPreview = getGpsString()
            val fullPreview = "$dateTimePreview | $sosMessageText | $gpsPreview"
            appendTerminalLog("TX_ERR", "Attempted transmit while disconnected")
            MaterialAlertDialogBuilder(this, R.style.Theme_LifeLine_Dialog)
                .setTitle("🚨 SOS Broadcast Prepared")
                .setMessage("$fullPreview\n\n⚠️ Mesh Bluetooth BLE is currently disconnected. Tap 'Connect' to pair with nearest node and transmit.")
                .setPositiveButton("Connect Device") { _, _ ->
                    checkPermissionsAndConnect()
                }
                .setNegativeButton("OK", null)
                .show()
            return
        }

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val nextNum = prefs.getInt(KEY_LAST_MSG_NUMBER, 0) + 1
        prefs.edit().putInt(KEY_LAST_MSG_NUMBER, nextNum).apply()

        val msgNumberStr = String.format(Locale.US, "%03d", nextNum)
        val msgId = Base85.generateMessageId(nic, msgNumberStr)
        val dateTimeStr = SimpleDateFormat("dd:MM:yy HH:mm:ss", Locale.US).format(Date())
        val gpsInfo = getGpsString()

        // New payload format with message id: <message id> : <timestamp> | <alert> | GPS: <lat, lon>
        val fullMessage = "$msgId : $dateTimeStr | $sosMessageText | $gpsInfo"

        val record = SentMessageRecord(
            messageNumber = msgNumberStr,
            timestamp = dateTimeStr,
            emergencyType = emergencyCategory,
            fullAlertText = sosMessageText,
            messageId = msgId,
            unpaddedNumber = nextNum.toString(),
            isReached = false,
        )
        synchronized(sentMessagesList) {
            sentMessagesList.add(record)
        }
        saveSentMessages()
        renderSentMessagesList()

        sendBluetoothRawMessage(fullMessage)
    }

    private fun loadSentMessages() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_SENT_MESSAGES_JSON, null)
        synchronized(sentMessagesList) {
            sentMessagesList.clear()
            if (!jsonStr.isNullOrEmpty()) {
                try {
                    val arr = JSONArray(jsonStr)
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        sentMessagesList.add(
                            SentMessageRecord(
                                messageNumber = obj.optString("messageNumber", "001"),
                                timestamp = obj.optString("timestamp", ""),
                                emergencyType = obj.optString("emergencyType", "EMERGENCY"),
                                fullAlertText = obj.optString("fullAlertText", ""),
                                messageId = obj.optString("messageId", ""),
                                unpaddedNumber = obj.optString("unpaddedNumber", "1"),
                                isReached = obj.optBoolean("isReached", false),
                            )
                        )
                    }
                } catch (_: Exception) {}
            }
        }
    }

    private fun saveSentMessages() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val arr = JSONArray()
        synchronized(sentMessagesList) {
            for (record in sentMessagesList) {
                val obj = JSONObject()
                obj.put("messageNumber", record.messageNumber)
                obj.put("timestamp", record.timestamp)
                obj.put("emergencyType", record.emergencyType)
                obj.put("fullAlertText", record.fullAlertText)
                obj.put("messageId", record.messageId)
                obj.put("unpaddedNumber", record.unpaddedNumber)
                obj.put("isReached", record.isReached)
                arr.put(obj)
            }
        }
        prefs.edit().putString(KEY_SENT_MESSAGES_JSON, arr.toString()).apply()
    }

    private fun renderSentMessagesList() {
        runOnUiThread {
            if (!::sentMessagesContainer.isInitialized) return@runOnUiThread
            sentMessagesContainer.removeAllViews()

            val snapshot = synchronized(sentMessagesList) { sentMessagesList.toList() }
            if (snapshot.isEmpty()) {
                sentMessagesEmptyView.visibility = View.VISIBLE
                return@runOnUiThread
            }
            sentMessagesEmptyView.visibility = View.GONE

            val inflater = LayoutInflater.from(this)
            for (item in snapshot.reversed()) {
                val itemView = inflater.inflate(R.layout.item_sent_message, sentMessagesContainer, false)
                val numView = itemView.findViewById<TextView>(R.id.itemMessageNumber)
                val statusView = itemView.findViewById<TextView>(R.id.itemDeliveryStatus)
                val typeView = itemView.findViewById<TextView>(R.id.itemEmergencyType)
                val timeView = itemView.findViewById<TextView>(R.id.itemTimestamp)
                val idView = itemView.findViewById<TextView>(R.id.itemMessageId)

                numView.text = "MSG #${item.messageNumber}"
                typeView.text = item.emergencyType
                timeView.text = item.timestamp
                val idShort = if (item.messageId.length > 20) item.messageId.substring(0, 20) + "..." else item.messageId
                idView.text = "ID: $idShort"

                if (item.isReached) {
                    statusView.text = getString(R.string.status_reached)
                    statusView.setBackgroundResource(R.drawable.bg_pill_green)
                    statusView.setTextColor(ContextCompat.getColor(this, R.color.green_connected))
                } else {
                    statusView.text = getString(R.string.status_pending)
                    statusView.setBackgroundResource(R.drawable.bg_pill_amber)
                    statusView.setTextColor(ContextCompat.getColor(this, R.color.amber_primary))
                }

                sentMessagesContainer.addView(itemView)
            }
        }
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

            // 1. Request GPS Provider
            if (locMgr.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locMgr.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000L,
                    1f,
                    locationListener,
                )
                bestLastLocation = locMgr.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            }

            // 2. Request Network Provider
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

    @SuppressLint("MissingPermission")
    private fun sendBluetoothRawMessage(fullMessage: String) {
        val gatt = bluetoothGatt
        val writeChar = writeCharacteristic

        if (!isConnected || (gatt == null) || (writeChar == null)) {
            appendTerminalLog("TX_ERR", "Attempted transmit while disconnected")
            MaterialAlertDialogBuilder(this, R.style.Theme_LifeLine_Dialog)
                .setTitle("🚨 SOS Broadcast Prepared")
                .setMessage("$fullMessage\n\n⚠️ Mesh Bluetooth BLE is currently disconnected. Tap 'Connect' to pair with nearest node and transmit.")
                .setPositiveButton("Connect Device") { _, _ ->
                    checkPermissionsAndConnect()
                }
                .setNegativeButton("OK", null)
                .show()
            return
        }

        val data = "$fullMessage\r\n".toByteArray(Charsets.UTF_8)

        val writeType = if ((writeChar.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }

        appendTerminalLog("TX", fullMessage)

        bleSendExecutor.execute {
            val maxPayload = (currentMtu - 3).coerceIn(20, 512)
            var offset = 0
            var allSuccess = true

            while (offset < data.size) {
                val chunkLen = min(maxPayload, data.size - offset)
                val chunk = data.copyOfRange(offset, offset + chunkLen)

                val chunkSuccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeCharacteristic(writeChar, chunk, writeType) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    writeChar.value = chunk
                    writeChar.writeType = writeType
                    @Suppress("DEPRECATION")
                    gatt.writeCharacteristic(writeChar)
                }

                if (!chunkSuccess) {
                    allSuccess = false
                    break
                }

                offset += chunkLen
                if (offset < data.size) {
                    try {
                        Thread.sleep(15)
                    } catch (_: InterruptedException) {}
                }
            }

            runOnUiThread {
                if (allSuccess) {
                    Toast.makeText(this@MainActivity, "🚨 SOS Broadcast Transmitted over BLE Mesh!", Toast.LENGTH_LONG).show()
                } else {
                    appendTerminalLog("TX_ERR", "Write characteristic failed")
                    Toast.makeText(this@MainActivity, "Transmission Failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun onStatusClicked() {
        if (isConnected) {
            MaterialAlertDialogBuilder(this, R.style.Theme_LifeLine_Dialog)
                .setTitle("Bluetooth BLE Connected")
                .setMessage("Currently connected to mesh BLE node. Do you want to disconnect?")
                .setPositiveButton("Disconnect") { _, _ ->
                    closeCurrentConnection()
                    updateStatus(getString(R.string.bt_disconnected))
                    Toast.makeText(this, "Disconnected from BLE device.", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, "Bluetooth BLE hardware is not supported on this device.", Toast.LENGTH_LONG).show()
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
                .setMessage("Bluetooth is currently turned off on your device. Turn it on to scan for nearby mesh BLE nodes?")
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

        startBleScan()
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
    private fun startBleScan() {
        val adapter = bluetoothAdapter ?: return
        if (isScanning) return

        bleScanner = adapter.bluetoothLeScanner
        if (bleScanner == null) return

        discoveredDevices.clear()

        // Include paired / bonded BLE devices initially
        val bonded = try {
            adapter.bondedDevices?.toList() ?: emptyList()
        } catch (_: SecurityException) {
            emptyList()
        }
        discoveredDevices.addAll(bonded)

        isScanning = true
        appendTerminalLog("SCAN", "Started BLE LE Scan...")
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            bleScanner?.startScan(null, settings, scanCallback)
        } catch (e: Exception) {
            e.printStackTrace()
            appendTerminalLog("SCAN_ERR", "Failed to start scan: ${e.localizedMessage}")
        }

        // Auto stop scanning after 8 seconds
        mainHandler.postDelayed(
            { stopBleScan() },
            8000L,
        )
    }

    @SuppressLint("MissingPermission")
    private fun stopBleScan() {
        if (isScanning) {
            isScanning = false
            appendTerminalLog("SCAN", "Stopped BLE LE Scan")
            try {
                bleScanner?.stopScan(scanCallback)
            } catch (_: Exception) {}
        }
    }

    @SuppressLint("MissingPermission")
    private fun showDeviceSelectionDialog() {
        val combinedDevices = discoveredDevices.toList()

        if (combinedDevices.isEmpty()) {
            MaterialAlertDialogBuilder(this, R.style.Theme_LifeLine_Dialog)
                .setTitle("📡 Scanning BLE Mesh Nodes...")
                .setMessage("Scanning for nearby Bluetooth Low Energy (BLE) nodes...\n\nEnsure your BLE node is powered on and advertising.")
                .setPositiveButton("Rescan") { _, _ ->
                    checkPermissionsAndConnect()
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        val deviceNames = combinedDevices.map { device ->
            val name = try { device.name } catch (_: SecurityException) { null } ?: "Unknown BLE Device"
            val bondedText = try {
                if (device.bondState == BluetoothDevice.BOND_BONDED) "[Paired]" else "[Discovered BLE]"
            } catch (_: SecurityException) { "" }
            "$name $bondedText\n${device.address}"
        }.toTypedArray()

        MaterialAlertDialogBuilder(this, R.style.Theme_LifeLine_Dialog)
            .setTitle(R.string.select_bluetooth_device)
            .setItems(deviceNames) { _, which ->
                connectToDevice(combinedDevices[which])
            }
            .setNeutralButton("🔄 Rescan") { _, _ ->
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

        stopBleScan()
        updateStatus("Connecting to $deviceName...")
        appendTerminalLog("CONNECT", "Connecting to GATT Server: $deviceName (${device.address})")

        closeCurrentConnection()

        @Suppress("DEPRECATION")
        bluetoothGatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
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

    @SuppressLint("MissingPermission")
    private fun closeCurrentConnection() {
        isConnected = false
        currentMtu = 23
        writeCharacteristic = null
        readCharacteristic = null
        try {
            bluetoothGatt?.disconnect()
        } catch (_: Exception) {}
        try {
            bluetoothGatt?.close()
        } catch (_: Exception) {}
        bluetoothGatt = null
    }

    override fun onDestroy() {
        super.onDestroy()
        stopBleScan()
        closeCurrentConnection()
        try {
            locationManager?.removeUpdates(locationListener)
        } catch (_: Exception) {}
    }
}