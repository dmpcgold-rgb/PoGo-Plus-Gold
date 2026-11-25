package at.pogoplusgold.probe

import android.Manifest
import android.app.Activity
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.pm.PackageManager
import android.os.*
import android.widget.*
import java.util.UUID

@Suppress("MissingPermission")
class MainActivity : Activity() {
    private var id = UUID.randomUUID()
    private var token = UUID.randomUUID().toString()
    private var scanCount = 0
    private var extended = false
    private var advertisingSet: AdvertisingSet? = null
    private var serverRead = false
    private lateinit var addressInput: EditText
    private lateinit var extendedInput: CheckBox
    private val characteristicId = UUID.fromString("c7b9c351-f56b-4e92-9e52-8a7d70a1c101")
    private val handler = Handler(Looper.getMainLooper())
    private val manager by lazy { getSystemService(BluetoothManager::class.java) }
    private var server: BluetoothGattServer? = null
    private var client: BluetoothGatt? = null
    private var running = false
    private lateinit var logView: TextView
    private lateinit var startButton: Button
    private val perms = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
    private fun log(message: String) = runOnUiThread { logView.append("\n" + message) }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 64, 28, 28) }
        layout.addView(TextView(this).apply { text = "PoGo Plus Gold"; textSize = 28f })
        layout.addView(TextView(this).apply { text = "BLE-Diagnose 0.2 · kein Spiel-Emulator"; textSize = 16f })
        startButton = Button(this).apply { text = "BLE-Test starten"; setOnClickListener {
            if (perms.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) requestPermissions(perms, 1) else startProbe()
        } }
        layout.addView(startButton)
        extendedInput = CheckBox(this).apply { text = "Extended Advertising testen" }
        layout.addView(extendedInput)
        addressInput = EditText(this).apply { hint = "Optionale eigene Test-BLE-Adresse"; setSingleLine(true) }
        layout.addView(addressInput)
        layout.addView(TextView(this).apply { text = "Adresse nur für ein eigenes Testgerät; leer lassen für lokalen Scan." })
        layout.addView(Button(this).apply { text = "Test stoppen"; setOnClickListener { stopProbe() } })
        logView = TextView(this).apply { text = "Bereit. Bluetooth einschalten."; textSize = 15f; setTextIsSelectable(true) }
        layout.addView(ScrollView(this).apply { addView(logView) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(layout)
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && perms.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) startProbe()
        else log("Bluetooth-Berechtigungen fehlen.")
    }
    private fun startProbe() {
        if (running) return
        val adapter = manager.adapter
        if (adapter == null || !adapter.isEnabled) { log("Bluetooth ist deaktiviert oder fehlt."); return }
        if (adapter.bluetoothLeAdvertiser == null || adapter.bluetoothLeScanner == null) { log("BLE Advertising/Scan nicht verfügbar."); return }
        running = true
        id = UUID.randomUUID()
        token = UUID.randomUUID().toString()
        serverRead = false
        scanCount = 0
        extended = extendedInput.isChecked
        logView.text = "Android API: " + Build.VERSION.SDK_INT
        log("Extended Advertising: " + adapter.isLeExtendedAdvertisingSupported)
        log("2M PHY: " + adapter.isLe2MPhySupported + ", Coded PHY: " + adapter.isLeCodedPhySupported)
        log("Test-UUID: $id")
        if (extended && !adapter.isLeExtendedAdvertisingSupported) { log("Extended Advertising nicht unterstützt."); stopProbe(); return }
        startButton.isEnabled = false
        log("GATT-Testserver wird geöffnet.")
        server = manager.openGattServer(this, serverCallback)
        val service = BluetoothGattService(id, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(BluetoothGattCharacteristic(characteristicId, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ))
        if (server?.addService(service) != true) { log("GATT-Service konnte nicht registriert werden."); stopProbe(); return }
        handler.postDelayed({ if (running) { log("Zeitlimit. Scan-Pakete insgesamt: $scanCount. Server-Leseanfrage: $serverRead. Keine bestätigte lokale Verbindung."); stopProbe() } }, 45000)
    }
    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            runOnUiThread {
                if (!running) return@runOnUiThread
                log("GATT-Service: Status $status")
                if (status != BluetoothGatt.GATT_SUCCESS) { stopProbe(); return@runOnUiThread }
                val data = AdvertiseData.Builder().addServiceUuid(ParcelUuid(id)).build()
                if (extended) {
                    manager.adapter.bluetoothLeAdvertiser?.startAdvertisingSet(AdvertisingSetParameters.Builder().setLegacyMode(false).setConnectable(true).setScannable(false).build(), data, null, null, null, extendedCallback)
                } else manager.adapter.bluetoothLeAdvertiser?.startAdvertising(AdvertiseSettings.Builder().setConnectable(true).setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY).build(), data, advertiseCallback)
            }
        }
        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) {
            val value = token.toByteArray()
            serverRead = true
            server?.sendResponse(device, requestId, if (offset in 0..value.size) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_INVALID_OFFSET, offset, if (offset in 0..value.size) value.copyOfRange(offset,value.size) else null)
            log("Testserver erhielt Leseanfrage.")
        }
    }
    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settings: AdvertiseSettings) { runOnUiThread {
            if (!running) return@runOnUiThread
            beginScan()
        } }
        override fun onStartFailure(errorCode: Int) { runOnUiThread { log("Advertising fehlgeschlagen: $errorCode"); stopProbe() } }
    }
    private val extendedCallback = object : AdvertisingSetCallback() {
        override fun onAdvertisingSetStarted(set: AdvertisingSet?, txPower: Int, status: Int) { runOnUiThread {
            if (!running) return@runOnUiThread
            log("Extended Advertising Status: $status")
            if (status == AdvertisingSetCallback.ADVERTISE_SUCCESS) { advertisingSet = set; beginScan() } else stopProbe()
        } }
    }
    private fun beginScan() {
        log("Advertising erfolgreich. Ungefilterter Scan läuft.")
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).setLegacy(!extended).build()
        manager.adapter.bluetoothLeScanner?.startScan(null, settings, scanCallback)
        val address = addressInput.text.toString().trim()
        if (address.isNotEmpty()) {
            if (!BluetoothAdapter.checkBluetoothAddress(address)) log("Ungültige Testadresse.")
            else handler.postDelayed({ if (running && client == null) {
                log("Direkter GATT-Test zur angegebenen Adresse.")
                runCatching { client = manager.adapter.getRemoteDevice(address).connectGatt(this, false, clientCallback, BluetoothDevice.TRANSPORT_LE) }.onFailure { log("Direktverbindung: " + it.javaClass.simpleName) }
            } }, 15000)
        } else log("Direktverbindung übersprungen: keine eigene Testadresse verfügbar.")
    }
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) { runOnUiThread {
            if (!running) return@runOnUiThread
            scanCount++
            if (scanCount == 1 || scanCount % 50 == 0) log("Empfangene Scan-Pakete: $scanCount")
            if (client != null || result.scanRecord?.serviceUuids?.contains(ParcelUuid(id)) != true) return@runOnUiThread
            log("Eigene Test-UUID gefunden. Verbindungsversuch.")
            manager.adapter.bluetoothLeScanner?.stopScan(this)
            client = result.device.connectGatt(this@MainActivity, false, clientCallback, BluetoothDevice.TRANSPORT_LE)
        } }
        override fun onScanFailed(errorCode: Int) { runOnUiThread { log("Scan fehlgeschlagen: $errorCode"); stopProbe() } }
    }
    private val clientCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            log("Client-Verbindung: Status $status, Zustand $newState")
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) gatt.discoverServices()
        }
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val c = gatt.getService(id)?.getCharacteristic(characteristicId)
            if (status == BluetoothGatt.GATT_SUCCESS && c != null) gatt.readCharacteristic(c)
            else log("Testservice nicht gefunden.")
        }
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            log("Leseergebnis: $status, " + String(value))
            if (status == BluetoothGatt.GATT_SUCCESS && String(value) == token && serverRead) log("Eigener Testserver antwortete mit passendem Sitzungstoken.")
            runOnUiThread { stopProbe() }
        }
    }
    private fun stopProbe() {
        running = false
        handler.removeCallbacksAndMessages(null)
        runCatching { manager.adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        runCatching { manager.adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) }
        runCatching { manager.adapter?.bluetoothLeAdvertiser?.stopAdvertisingSet(extendedCallback) }; advertisingSet = null
        runCatching { client?.close() }; client = null
        runCatching { server?.close() }; server = null
        startButton.isEnabled = true
    }
    override fun onDestroy() { stopProbe(); super.onDestroy() }
}
