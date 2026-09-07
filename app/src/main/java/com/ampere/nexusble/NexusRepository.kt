package com.ampere.nexusble

import android.os.Handler
import android.os.Looper

/** Singleton bridge between the BLE service and the UI. */
object NexusRepository {
    enum class State { IDLE, SCANNING, CONNECTING, DISCOVERING, CONNECTED, DISCONNECTED }

    @Volatile var state: State = State.IDLE
    @Volatile var deviceName: String = ""
    @Volatile var deviceAddress: String = ""
    val telemetry = Telemetry()
    val logLines = ArrayDeque<String>()

    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<() -> Unit>()

    fun addListener(l: () -> Unit) { synchronized(listeners) { listeners.add(l) } }
    fun removeListener(l: () -> Unit) { synchronized(listeners) { listeners.remove(l) } }

    fun log(msg: String) {
        val line = "%tT  %s".format(System.currentTimeMillis(), msg)
        synchronized(logLines) {
            logLines.addLast(line)
            while (logLines.size > 200) logLines.removeFirst()
        }
        android.util.Log.d("NexusBLE", msg)
        notifyChanged()
    }

    fun notifyChanged() {
        val snapshot = synchronized(listeners) { listeners.toList() }
        main.post { snapshot.forEach { it() } }
    }
}
