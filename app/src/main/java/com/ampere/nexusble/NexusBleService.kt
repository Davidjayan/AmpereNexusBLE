package com.ampere.nexusble

import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

@SuppressLint("MissingPermission")
class NexusBleService : Service() {

    companion object {
        const val CHANNEL = "nexus_ble"
        const val NOTIF_ID = 42
        const val PREFS = "nexus_prefs"
        const val KEY_ADDR = "device_address"
        const val ACTION_FORGET = "com.ampere.nexusble.FORGET"
        const val ACTION_SYNC_TIME = "com.ampere.nexusble.SYNC_TIME"
        const val ACTION_RESCAN = "com.ampere.nexusble.RESCAN"
    }

    private val adapter: BluetoothAdapter? by lazy {
        (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }
    private var gatt: BluetoothGatt? = null
    private var scanner: BluetoothLeScanner? = null
    private var scanning = false
    private val handler = Handler(Looper.getMainLooper())
    private val repo = NexusRepository

    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val tripRecorder by lazy { TripRecorder(this) }

    // ---- lifecycle ----------------------------------------------------------
    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, buildNotification("Starting…"))
        registerReceiver(btStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        connectOrScan()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_FORGET -> {
                prefs.edit().remove(KEY_ADDR).apply()
                closeGatt()
                repo.log("Forgot saved device. Rescanning…")
                connectOrScan()
            }
            ACTION_SYNC_TIME -> syncClock()
            ACTION_RESCAN -> connectOrScan()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(btStateReceiver) }
        stopScan()
        closeGatt()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---- connect / scan -----------------------------------------------------
    private fun hasPerm(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun bleReady(): Boolean {
        val a = adapter ?: return false
        if (!a.isEnabled) { repo.state = NexusRepository.State.DISCONNECTED; repo.log("Bluetooth is OFF"); repo.notifyChanged(); return false }
        if (Build.VERSION.SDK_INT >= 31 &&
            (!hasPerm(android.Manifest.permission.BLUETOOTH_CONNECT) ||
             !hasPerm(android.Manifest.permission.BLUETOOTH_SCAN))) {
            repo.log("Missing BLUETOOTH_CONNECT/SCAN permission"); return false
        }
        return true
    }

    private fun connectOrScan() {
        if (!bleReady()) return
        val saved = prefs.getString(KEY_ADDR, null)
        if (saved != null) {
            val dev = runCatching { adapter?.getRemoteDevice(saved) }.getOrNull()
            if (dev != null) { repo.log("Auto-connecting saved device $saved"); connect(dev); return }
        }
        startScan()
    }

    private fun startScan() {
        val s = adapter?.bluetoothLeScanner ?: return
        if (scanning) return
        scanner = s
        scanning = true
        repo.state = NexusRepository.State.SCANNING
        repo.log("Scanning for Ampere Nexus…")
        repo.notifyChanged()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        s.startScan(null, settings, scanCallback)
        // safety: keep scanning but log every 15s; OS scan continues
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        runCatching { scanner?.stopScan(scanCallback) }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val dev = result.device
            val name = (runCatching { dev.name }.getOrNull() ?: result.scanRecord?.deviceName ?: "").trim()
            val match = name.isNotEmpty() &&
                    NexusProtocol.NAME_KEYWORDS.any { name.lowercase().contains(it) }
            if (match) {
                repo.log("Found '$name' [${dev.address}] rssi=${result.rssi}")
                prefs.edit().putString(KEY_ADDR, dev.address).apply()
                stopScan()
                connect(dev)
            }
        }
        override fun onScanFailed(errorCode: Int) { repo.log("Scan failed: $errorCode") }
    }

    private fun connect(device: BluetoothDevice) {
        closeGatt()
        repo.deviceAddress = device.address
        repo.deviceName = runCatching { device.name }.getOrNull() ?: repo.deviceName
        repo.state = NexusRepository.State.CONNECTING
        repo.log("Connecting to ${device.address} (autoConnect)…")
        repo.notifyChanged()
        updateNotification()
        // autoConnect = true → OS reconnects automatically whenever the scooter is in range.
        gatt = if (Build.VERSION.SDK_INT >= 23)
            device.connectGatt(this, true, gattCallback, BluetoothDevice.TRANSPORT_LE)
        else device.connectGatt(this, true, gattCallback)
    }

    private fun closeGatt() { runCatching { gatt?.close() }; gatt = null }

    // ---- GATT callback ------------------------------------------------------
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    repo.state = NexusRepository.State.DISCOVERING
                    repo.log("Connected. Requesting MTU / discovering services…")
                    repo.notifyChanged(); updateNotification()
                    handler.post { g.requestMtu(255) }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    repo.state = NexusRepository.State.DISCONNECTED
                    tripRecorder.onDisconnect()
                    repo.log("Disconnected (status=$status). OS will auto-reconnect when in range.")
                    repo.notifyChanged(); updateNotification()
                    // With autoConnect the OS keeps the pending connection; on hard errors, retry.
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        handler.postDelayed({ if (adapter?.isEnabled == true) connectOrScan() }, 4000)
                    }
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            repo.log("MTU=$mtu. Discovering services…")
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) { repo.log("Service discovery failed $status"); return }

            dumpGatt(g)

            // Find the notify characteristic anywhere in the GATT tree: prefer the known UUIDs,
            // otherwise fall back to the first characteristic that supports NOTIFY/INDICATE.
            val notifyChar =
                findCharAnywhere(g, NexusProtocol.CHAR_NOTIFY)
                    ?: findCharAnywhere(g, NexusProtocol.CHAR_PRIMUS_NOTIFY)
                    ?: findCharByProperty(g,
                        BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                        BluetoothGattCharacteristic.PROPERTY_INDICATE)
            if (notifyChar == null) {
                repo.log("No notify characteristic found in any service")
                return
            }
            repo.log("Using notify char ${notifyChar.uuid} (svc ${notifyChar.service.uuid})")
            g.setCharacteristicNotification(notifyChar, true)
            val cccd = notifyChar.getDescriptor(NexusProtocol.CCCD)
            if (cccd != null) {
                if (Build.VERSION.SDK_INT >= 33) {
                    g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION") g.writeDescriptor(cccd)
                }
            }
            repo.state = NexusRepository.State.CONNECTED
            repo.log("Subscribed to telemetry. Waiting for frames…")
            repo.notifyChanged(); updateNotification()
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            // Notifications enabled → push phone time to the scooter clock.
            handler.postDelayed({ syncClock() }, 600)
        }

        // Android 13+ signature
        override fun onCharacteristicChanged(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray
        ) = handleValue(value)

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION") handleValue(c.value ?: ByteArray(0))
        }
    }

    // ---- GATT tree helpers --------------------------------------------------
    /** Log every service, characteristic and its properties (one-time diagnostic). */
    private fun dumpGatt(g: BluetoothGatt) {
        repo.log("── GATT dump: ${g.services.size} services ──")
        for (s in g.services) {
            repo.log("SVC ${s.uuid}")
            for (c in s.characteristics) {
                repo.log("  CHR ${c.uuid} [${propLabel(c.properties)}]")
            }
        }
        repo.log("── end GATT dump ──")
    }

    private fun propLabel(p: Int): String {
        val out = mutableListOf<String>()
        if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) out += "R"
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) out += "W"
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) out += "Wn"
        if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) out += "N"
        if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) out += "I"
        return out.joinToString("/")
    }

    private fun findCharAnywhere(g: BluetoothGatt, uuid: java.util.UUID): BluetoothGattCharacteristic? {
        for (s in g.services) s.getCharacteristic(uuid)?.let { return it }
        return null
    }

    private fun findCharByProperty(g: BluetoothGatt, propMask: Int): BluetoothGattCharacteristic? {
        for (s in g.services) for (c in s.characteristics)
            if (c.properties and propMask != 0) return c
        return null
    }

    private fun handleValue(value: ByteArray) {
        if (value.isEmpty()) return
        val hex = NexusProtocol.toHex(value)
        val id = NexusProtocol.frameId(value)
        NexusProtocol.decode(hex, repo.telemetry)
        tripRecorder.onTelemetry(repo.telemetry)
        repo.log("RX $id  $hex")
        repo.notifyChanged()
        updateNotification()
    }

    // ---- write: set scooter clock ------------------------------------------
    private fun syncClock() {
        val g = gatt ?: return
        val wc = findCharAnywhere(g, NexusProtocol.CHAR_WRITE)
            ?: findCharAnywhere(g, NexusProtocol.CHAR_PRIMUS_WRITE)
            ?: findCharByProperty(g,
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)
        if (wc == null) { repo.log("No write characteristic found; cannot sync clock"); return }
        val payload = NexusProtocol.buildSetTimeCommand()
        repo.log("TX set-time (${payload.size} bytes) → ${wc.uuid}")
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(wc, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            @Suppress("DEPRECATION")
            wc.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION") wc.value = payload
            @Suppress("DEPRECATION") g.writeCharacteristic(wc)
        }
    }

    // ---- BT on/off receiver -------------------------------------------------
    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            when (i?.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_ON -> { repo.log("Bluetooth ON → reconnecting"); handler.postDelayed({ connectOrScan() }, 1500) }
                BluetoothAdapter.STATE_OFF -> { repo.state = NexusRepository.State.DISCONNECTED; repo.log("Bluetooth OFF"); repo.notifyChanged(); updateNotification() }
            }
        }
    }

    // ---- notification -------------------------------------------------------
    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL, "Nexus BLE", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NotificationManager::class.java)).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("Ampere Nexus")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    private fun updateNotification() {
        val t = repo.telemetry
        val text = when (repo.state) {
            NexusRepository.State.CONNECTED ->
                "🔋 ${if (t.batteryPercent>=0) "${t.batteryPercent}%" else "--"}   " +
                "Trip ${if (t.lastTripKm>=0) "%.1f km".format(t.lastTripKm) else "--"}   " +
                "Range ${if (t.rangeKm>=0) "${t.rangeKm} km" else "--"}"
            else -> repo.state.name.lowercase().replaceFirstChar { it.uppercase() }
        }
        runCatching {
            (getSystemService(NotificationManager::class.java))
                .notify(NOTIF_ID, buildNotification(text))
        }
    }
}
