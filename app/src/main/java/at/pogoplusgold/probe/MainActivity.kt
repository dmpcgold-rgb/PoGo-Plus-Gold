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
    private val id = UUID.fromString("c7b9c351-f56b-4e92-9e52-8a7d70a1c100")
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
        layout.addView(TextView(this).apply { text = "Bluetooth-Machbarkeitstest · kein Spiel-Emulator"; textSize = 16f })
        startButton = Button(this).apply { text = "BLE-Test starten"; setOnClickListener {
            if (perms.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) requestPermissions(perms, 1) else startProbe()
        } }
        layout.addView(startButton)
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
        startButton.isEnabled = false
        log("GATT-Testserver wird geöffnet.")
        server = manager.openGattServer(this, serverCallback)
        val service = BluetoothGattService(id, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(BluetoothGattCharacteristic(characteristicId, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ))
        if (server?.addService(service) != true) { log("GATT-Service konnte nicht registriert werden."); stopProbe(); return }
        handler.postDelayed({ if (running) { log("Zeitlimit: kein weiterer Nachweis einer lokalen Verbindung. Ein Fehlschlag beweist keine generelle Unmöglichkeit."); stopProbe() } }, 30000)
    }
    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            runOnUiThread {
                if (!running) return@runOnUiThread
                log("GATT-Service: Status $status")
                if (status != BluetoothGatt.GATT_SUCCESS) { stopProbe(); return@runOnUiThread }
                manager.adapter.bluetoothLeAdvertiser?.startAdvertising(
                    AdvertiseSettings.Builder().setConnectable(true).setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY).build(),
                    AdvertiseData.Builder().addServiceUuid(ParcelUuid(id)).build(), advertiseCallback)
            }
        }
        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) {
            val value = "GOLD-PROBE".toByteArray()
            server?.sendResponse(device, requestId, if (offset in 0..value.size) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_INVALID_OFFSET, offset, if (offset in 0..value.size) value.copyOfRange(offset,value.size) else null)
            log("Testserver erhielt Leseanfrage.")
        }
    }
    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settings: AdvertiseSettings) { runOnUiThread {
            if (!running) return@runOnUiThread
            log("Advertising erfolgreich. Suche eigene Test-UUID.")
            manager.adapter.bluetoothLeScanner?.startScan(listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(id)).build()), ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback)
        } }
        override fun onStartFailure(errorCode: Int) { runOnUiThread { log("Advertising fehlgeschlagen: $errorCode"); stopProbe() } }
    }
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) { runOnUiThread {
            if (!running || client != null) return@runOnUiThread
            log("Test-UUID gefunden. Verbindungsversuch.")
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
            if (status == BluetoothGatt.GATT_SUCCESS && String(value) == "GOLD-PROBE") log("BLE-Test erfolgreich; lokale Herkunft muss separat verifiziert werden.")
            runOnUiThread { stopProbe() }
        }
    }
    private fun stopProbe() {
        running = false
        handler.removeCallbacksAndMessages(null)
        runCatching { manager.adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        runCatching { manager.adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) }
        runCatching { client?.close() }; client = null
        runCatching { server?.close() }; server = null
        startButton.isEnabled = true
    }
    override fun onDestroy() { stopProbe(); super.onDestroy() }
}
