package com.ampere.nexusble

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.method.ScrollingMovementMethod
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.ampere.nexusble.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val ticker = Handler(Looper.getMainLooper())
    private val repo = NexusRepository

    private val listener: () -> Unit = { render() }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { startBleService() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        // Edge-to-edge (target SDK 35): pad content clear of the status/navigation bars.
        ViewCompat.setOnApplyWindowInsetsListener(b.content) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(
                v.paddingLeft, bars.top + dp(8),
                v.paddingRight, bars.bottom + dp(16)
            )
            insets
        }

        b.tvLog.movementMethod = ScrollingMovementMethod()

        b.btnRescan.setOnClickListener { service(NexusBleService.ACTION_RESCAN) }
        b.btnSync.setOnClickListener { service(NexusBleService.ACTION_SYNC_TIME) }
        b.btnForget.setOnClickListener { service(NexusBleService.ACTION_FORGET) }
        b.btnTrips.setOnClickListener { startActivity(Intent(this, TripHistoryActivity::class.java)) }

        requestPermsThenStart()
    }

    private fun requiredPerms(): Array<String> {
        val p = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            p += Manifest.permission.BLUETOOTH_SCAN
            p += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            p += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 33) p += Manifest.permission.POST_NOTIFICATIONS
        return p.toTypedArray()
    }

    private fun requestPermsThenStart() {
        val missing = requiredPerms().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startBleService() else permLauncher.launch(missing.toTypedArray())
    }

    private fun startBleService() {
        val i = Intent(this, NexusBleService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
    }

    private fun service(action: String) {
        startService(Intent(this, NexusBleService::class.java).setAction(action))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onResume() {
        super.onResume()
        repo.addListener(listener)
        ticker.post(tick)
        render()
    }

    override fun onPause() {
        super.onPause()
        repo.removeListener(listener)
        ticker.removeCallbacks(tick)
    }

    private val tick = object : Runnable {
        // Only the clock updates every second; data-driven UI refreshes via the listener.
        override fun run() { updateClock(); ticker.postDelayed(this, 1000) }
    }

    private fun updateClock() {
        // The scooter clock == phone time we sync to it, so show live phone time.
        val now = Date()
        b.tvClock.text = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(now)
        b.tvDate.text = SimpleDateFormat("EEE dd MMM", Locale.getDefault()).format(now)
    }

    private fun render() {
        val t = repo.telemetry
        b.tvStatus.text = when (repo.state) {
            NexusRepository.State.CONNECTED -> "● Connected"
            NexusRepository.State.SCANNING -> "◌ Scanning…"
            NexusRepository.State.CONNECTING -> "◌ Connecting…"
            NexusRepository.State.DISCOVERING -> "◌ Discovering…"
            NexusRepository.State.DISCONNECTED -> "○ Disconnected"
            else -> "Idle"
        }
        b.tvDevice.text = if (repo.deviceAddress.isNotEmpty())
            "${repo.deviceName.ifEmpty { "Nexus" }}  ·  ${repo.deviceAddress}" else "No device yet"

        b.tvBattery.text = if (t.batteryPercent >= 0) "${t.batteryPercent} %" else "-- %"
        b.pbBattery.progress = t.batteryPercent.coerceIn(0, 100)
        b.tvCharging.text = when {
            t.charging -> "⚡ Charging · ${"%.1f".format(t.timeToChargeHr)} h to full"
            t.chargeComplete == 1 -> "✓ Fully charged"
            else -> ""
        }
        b.tvTrip.text  = if (t.lastTripKm >= 0) "%.1f".format(t.lastTripKm) else "--"
        b.tvRange.text = if (t.rangeKm >= 0) "${t.rangeKm}" else "--"
        b.tvOdo.text   = if (t.odoKm >= 0) "%.1f".format(t.odoKm) else "--"
        b.tvSpeed.text = "${t.speedKmh}"
        b.tvMode.text  = t.mode
        b.tvReady.text = if (t.ready == 1) "READY" else if (t.sideStand == 1) "STAND" else ""

        b.tvLog.text = synchronized(repo.logLines) { repo.logLines.joinToString("\n") }
    }
}
