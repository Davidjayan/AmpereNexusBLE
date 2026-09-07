package com.ampere.nexusble

import java.util.UUID

/**
 * Ampere Nexus BLE protocol — reverse-engineered from com.greavesmobility.app.
 * See PROTOCOL.md. No pairing / bonding / crypto: connect, discover, subscribe, decode.
 */
object NexusProtocol {

    // Real GATT layout on the Nexus (verified live): each service holds ONE characteristic
    // whose UUID equals the service UUID. They are NOT nested under a single service — the
    // service is found via findCharAnywhere() across the whole tree, so grouping is irrelevant.
    //   20012202 [R/N]   — telemetry notify: scooter pushes 0150/0254 frames here after subscribe
    //   20032202 [W/N]   — write: clock / cluster writes (set-time frame)
    //   20022202 [R/W/N] — secondary cluster (name/DOB writes)
    //   20042202 [R/W/N] — fourth service (unused so far)
    val SERVICE_TELEMETRY: UUID = UUID.fromString("20012202-1234-1234-1234-220201042403")
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
                // time-to-charge is tenths of an hour; 999 (0x03E7) is the "not charging / N/A"
                // sentinel the cluster sends when nothing is plugged in.
                val ttcRaw       = le(b, 6, 8)
                t.timeToChargeHr = ttcRaw / 10.0
                t.chargeComplete = u(b, 13)
                t.reverseGear    = u(b, 15)
                t.ready          = u(b, 17)
                t.sideStand      = u(b, 19)
                t.charging       = ttcRaw in 1..998 && t.chargeComplete == 0
                t.lastStatusFrame = hex.uppercase()
            }
            // 0147 (call/live-loc) and 0551 (TPMS) are ignored for the dashboard.
        }
        t.lastUpdateMs = System.currentTimeMillis()
        return t
    }

    /**
     * Epoch seconds for the scooter RTC — RAW UTC, no timezone shift.
     *
     * Empirically the Nexus cluster firmware applies India time (+5:30) to the epoch itself before
     * displaying (Ampere is India-only). So we must send plain UTC: the scooter adds 5:30 and shows
     * correct IST. Sending local-shifted time here made it +5:30 too far ahead (21:47 → showed 03:17).
     * This matches the official app, which also sends raw floor(Date.now()/1000). The bytes still go
     * out little-endian — see [epochHexLE].
     */
    fun clockSeconds(nowMs: Long = System.currentTimeMillis()): Long = nowMs / 1000

    /**
     * 32-bit epoch as 4 LITTLE-ENDIAN bytes, hex (8 chars).
     *
     * The scooter is little-endian (see the LE odo/trip decode), and the official app's
     * getTimeHexNXGT() byte-swaps toString(16) into little-endian before sending. Sending plain
     * big-endian toString(16) makes the scooter mis-read the value: the fast-changing low byte
     * lands in its high byte, so the displayed clock jumps by hours on tiny real changes ("new
     * time every power cycle"). Emit little-endian so the scooter reconstructs the true epoch.
     */
    fun epochHexLE(epochSeconds: Long): String {
        val v = epochSeconds and 0xFFFFFFFFL
        return "%02x%02x%02x%02x".format(
            v and 0xFF, (v shr 8) and 0xFF, (v shr 16) and 0xFF, (v shr 24) and 0xFF
        )
    }

    /**
     * Build the "set scooter clock" write (Nexus / writeTimetoCluster).
     * `0102` + local-wall-clock-seconds (little-endian hex) + static cluster block + missedCalls(00) + `45`.
     */
    fun buildSetTimeCommand(epochSeconds: Long = clockSeconds()): ByteArray {
        val epochHex = epochHexLE(epochSeconds)
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
