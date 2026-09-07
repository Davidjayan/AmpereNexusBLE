package com.ampere.nexusble

import android.content.Context

/**
 * Derives discrete trips from the live telemetry stream and persists them to [TripDb].
 * The scooter does not send ride history over BLE, so we detect rides ourselves:
 *   start  = first valid frame where speed > 0
 *   update = accumulate odometer / battery / max-speed while moving
 *   end    = idle for IDLE_MS with speed 0, OR side-stand down, OR disconnect
 * Trips shorter than MIN_KM are discarded (parking wiggles / noise).
 */
class TripRecorder(context: Context) {

    private val db = TripDb(context)

    private var active = false
    private var startTime = 0L
    private var startOdo = 0.0
    private var startBatt = 0
    private var endOdo = 0.0
    private var endBatt = 0
    private var maxSpeed = 0
    private var lastMovingMs = 0L

    companion object {
        const val IDLE_MS = 120_000L   // 2 min stationary ends a trip
        const val MIN_KM = 0.05        // ignore <50 m
    }

    @Synchronized
    fun onTelemetry(t: Telemetry) {
        if (t.odoKm < 0 || t.batteryPercent < 0) return   // no valid dashboard frame yet
        val now = System.currentTimeMillis()

        if (!active) {
            if (t.speedKmh > 0) startTrip(t, now)
            return
        }

        // active trip — keep extending
        endOdo = t.odoKm
        endBatt = t.batteryPercent
        if (t.speedKmh > maxSpeed) maxSpeed = t.speedKmh
        if (t.speedKmh > 0) lastMovingMs = now

        val stopped = (now - lastMovingMs) >= IDLE_MS
        if (stopped || t.sideStand == 1) finalizeTrip(lastMovingMs)
    }

    @Synchronized
    fun onDisconnect() {
        if (active) finalizeTrip(if (lastMovingMs > 0) lastMovingMs else System.currentTimeMillis())
    }

    private fun startTrip(t: Telemetry, now: Long) {
        active = true
        startTime = now
        startOdo = t.odoKm
        endOdo = t.odoKm
        startBatt = t.batteryPercent
        endBatt = t.batteryPercent
        maxSpeed = t.speedKmh
        lastMovingMs = now
        NexusRepository.log("Trip started @ odo=%.1f km, batt=%d%%".format(startOdo, startBatt))
    }

    private fun finalizeTrip(endTime: Long) {
        active = false
        val distance = (endOdo - startOdo).coerceAtLeast(0.0)
        if (distance < MIN_KM) {
            NexusRepository.log("Trip discarded (%.2f km)".format(distance))
            return
        }
        val durationSec = ((endTime - startTime) / 1000).coerceAtLeast(1)
        val avg = distance / (durationSec / 3600.0)
        val rec = TripRecord(
            startTime = startTime, endTime = endTime, distanceKm = distance,
            startOdoKm = startOdo, endOdoKm = endOdo, startBatt = startBatt, endBatt = endBatt,
            maxSpeed = maxSpeed, avgSpeed = avg, durationSec = durationSec
        )
        val id = db.insert(rec)
        NexusRepository.log("Trip saved #$id: %.2f km, %d%%→%d%%".format(distance, startBatt, endBatt))
    }
}
