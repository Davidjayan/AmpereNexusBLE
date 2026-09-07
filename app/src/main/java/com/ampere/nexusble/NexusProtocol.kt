package com.ampere.nexusble

import java.util.UUID

/**
 * Ampere Nexus BLE protocol — reverse-engineered from com.greavesmobility.app.
 * See PROTOCOL.md. No pairing / bonding / crypto: connect, discover, subscribe, decode.
 */
object NexusProtocol {

    // Telemetry service: scooter pushes 0150/0254 notifications here after subscribe.
    val SERVICE_TELEMETRY: UUID = UUID.fromString("20032202-1234-1234-1234-220201042403")
    val CHAR_NOTIFY: UUID       = UUID.fromString("20012202-1234-1234-1234-220201042403")
    val CHAR_WRITE: UUID        = UUID.fromString("20032202-1234-1234-1234-220201042403")

    // Secondary "cluster" service used by the app for name/DOB writes.
    val SERVICE_CLUSTER: UUID   = UUID.fromString("20022202-1234-1234-1234-220201042403")
    val CHAR_CLUSTER: UUID      = UUID.fromString("20022202-1234-1234-1234-220201042403")

    // Standard CCCD for enabling notifications.
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // Older Primus/Revos scooters (kept for completeness / auto-detect).
    val SERVICE_PRIMUS: UUID = UUID.fromString("0000a002-0000-1000-8000-00805f9b34fb")
    val CHAR_PRIMUS_NOTIFY: UUID = UUID.fromString("0000c306-0000-1000-8000-00805f9b34fb")
    val CHAR_PRIMUS_WRITE: UUID  = UUID.fromString("0000c304-0000-1000-8000-00805f9b34fb")

    /** Advertised-name keywords the official app matches for a Nexus. */
    val NAME_KEYWORDS = listOf("nex", "nexus", "ampere")

    fun toHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append("%02X".format(b.toInt() and 0xFF))
        return sb.toString()
    }

    fun fromHex(hex: String): ByteArray {
        val clean = hex.filter { !it.isWhitespace() }
        val out = ByteArray(clean.length / 2)
        var i = 0
        while (i < out.size) {
            out[i] = ((Character.digit(clean[i * 2], 16) shl 4) +
                    Character.digit(clean[i * 2 + 1], 16)).toByte()
            i++
        }
        return out
    }

    private fun u(b: ByteArray, i: Int): Int =
        if (i in b.indices) b[i].toInt() and 0xFF else 0

    /** Little-endian unsigned int over byte indices [start, endExclusive). */
    private fun le(b: ByteArray, start: Int, endExclusive: Int): Long {
        var v = 0L
        for (i in start until endExclusive) {
            if (i !in b.indices) break
            v = v or ((b[i].toLong() and 0xFF) shl (8 * (i - start)))
        }
        return v
    }

    fun frameId(b: ByteArray): String =
        if (b.size >= 2) "%02X%02X".format(b[0].toInt() and 0xFF, b[1].toInt() and 0xFF) else ""

    /**
     * Decode one notification frame into the shared [Telemetry] object (mutated & returned).
     * Mirrors parseDecodedAmpereValues() exactly.
     */
    fun decode(hex: String, t: Telemetry): Telemetry {
        val b = fromHex(hex.uppercase())
        when (frameId(b)) {
            "0150" -> {   // dashboard
                t.batteryPercent = u(b, 2)
                t.odoKm          = le(b, 4, 8) / 10.0
                t.lastTripKm     = le(b, 9, 11) / 10.0
                t.rangeKm        = u(b, 12)
                t.speedKmh       = u(b, 14)
                t.topSpeedKmh    = u(b, 16)
                t.avgSpeedKmh    = u(b, 18)
                t.lastDashboardFrame = hex.uppercase()
            }
            "0254" -> {   // status / charging
                t.mode = when (u(b, 2)) { 1 -> "ECO"; 2 -> "City"; 3 -> "Park"; else -> "Power" }
                t.limpHome       = u(b, 4)
                t.timeToChargeHr = le(b, 6, 8) / 10.0
                t.chargeComplete = u(b, 13)
                t.reverseGear    = u(b, 15)
                t.ready          = u(b, 17)
                t.sideStand      = u(b, 19)
                t.charging       = t.timeToChargeHr > 0.0 && t.chargeComplete == 0
                t.lastStatusFrame = hex.uppercase()
            }
            // 0147 (call/live-loc) and 0551 (TPMS) are ignored for the dashboard.
        }
        t.lastUpdateMs = System.currentTimeMillis()
        return t
    }

    /**
     * Build the "set scooter clock" write (Nexus / writeTimetoCluster).
     * `0102` + unix-epoch-seconds-hex + static cluster block + missedCalls(00) + `45`.
     */
    fun buildSetTimeCommand(epochSeconds: Long = System.currentTimeMillis() / 1000): ByteArray {
        val epochHex = epochSeconds.toString(16)
        val staticBlock =
            "40FF4100000000420000000043FF02FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF" +
            "03FFFFFFFFFFFFFFFFFFFFFFFF49FF4FFF44FFFF04FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF" +
            "05FFFFFF4D034E"
        val missedCalls = "00"
        val hex = "0102" + epochHex + staticBlock + missedCalls + "45"
        return fromHex(hex)
    }
}

/** Mutable snapshot of everything we know about the scooter. */
data class Telemetry(
    var batteryPercent: Int = -1,
    var odoKm: Double = -1.0,
    var lastTripKm: Double = -1.0,
    var rangeKm: Int = -1,
    var speedKmh: Int = 0,
    var topSpeedKmh: Int = 0,
    var avgSpeedKmh: Int = 0,
    var mode: String = "--",
    var limpHome: Int = 0,
    var timeToChargeHr: Double = 0.0,
    var chargeComplete: Int = 0,
    var reverseGear: Int = 0,
    var ready: Int = 0,
    var sideStand: Int = 0,
    var charging: Boolean = false,
    var lastUpdateMs: Long = 0,
    var lastDashboardFrame: String = "",
    var lastStatusFrame: String = ""
)
