package com.ampere.nexusble

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** One recorded ride. */
data class TripRecord(
    val id: Long = 0,
    val startTime: Long,
    val endTime: Long,
    val distanceKm: Double,
    val startOdoKm: Double,
    val endOdoKm: Double,
    val startBatt: Int,
    val endBatt: Int,
    val maxSpeed: Int,
    val avgSpeed: Double,
    val durationSec: Long
)

/** Local SQLite store of past trips (framework SQLiteOpenHelper — no external deps). */
class TripDb(context: Context) : SQLiteOpenHelper(context.applicationContext, DB, null, VERSION) {

    companion object {
        const val DB = "nexus_trips.db"
        const val VERSION = 1
        const val TABLE = "trips"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                start_time INTEGER NOT NULL,
                end_time INTEGER NOT NULL,
                distance_km REAL NOT NULL,
                start_odo_km REAL NOT NULL,
                end_odo_km REAL NOT NULL,
                start_batt INTEGER NOT NULL,
                end_batt INTEGER NOT NULL,
                max_speed INTEGER NOT NULL,
                avg_speed REAL NOT NULL,
                duration_sec INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {
        // v1 only for now.
    }

    fun insert(t: TripRecord): Long {
        val cv = ContentValues().apply {
            put("start_time", t.startTime)
            put("end_time", t.endTime)
            put("distance_km", t.distanceKm)
            put("start_odo_km", t.startOdoKm)
            put("end_odo_km", t.endOdoKm)
            put("start_batt", t.startBatt)
            put("end_batt", t.endBatt)
            put("max_speed", t.maxSpeed)
            put("avg_speed", t.avgSpeed)
            put("duration_sec", t.durationSec)
        }
        return writableDatabase.insert(TABLE, null, cv)
    }

    fun all(): List<TripRecord> {
        val out = ArrayList<TripRecord>()
        readableDatabase.rawQuery("SELECT * FROM $TABLE ORDER BY start_time DESC", null).use { c ->
            while (c.moveToNext()) {
                out.add(
                    TripRecord(
                        id = c.getLong(c.getColumnIndexOrThrow("id")),
                        startTime = c.getLong(c.getColumnIndexOrThrow("start_time")),
                        endTime = c.getLong(c.getColumnIndexOrThrow("end_time")),
                        distanceKm = c.getDouble(c.getColumnIndexOrThrow("distance_km")),
                        startOdoKm = c.getDouble(c.getColumnIndexOrThrow("start_odo_km")),
                        endOdoKm = c.getDouble(c.getColumnIndexOrThrow("end_odo_km")),
                        startBatt = c.getInt(c.getColumnIndexOrThrow("start_batt")),
                        endBatt = c.getInt(c.getColumnIndexOrThrow("end_batt")),
                        maxSpeed = c.getInt(c.getColumnIndexOrThrow("max_speed")),
                        avgSpeed = c.getDouble(c.getColumnIndexOrThrow("avg_speed")),
                        durationSec = c.getLong(c.getColumnIndexOrThrow("duration_sec"))
                    )
                )
            }
        }
        return out
    }

    /** Most recent recorded ride, or null if none yet. */
    fun latest(): TripRecord? =
        readableDatabase.rawQuery("SELECT * FROM $TABLE ORDER BY start_time DESC LIMIT 1", null).use { c ->
            if (!c.moveToFirst()) return null
            TripRecord(
                id = c.getLong(c.getColumnIndexOrThrow("id")),
                startTime = c.getLong(c.getColumnIndexOrThrow("start_time")),
                endTime = c.getLong(c.getColumnIndexOrThrow("end_time")),
                distanceKm = c.getDouble(c.getColumnIndexOrThrow("distance_km")),
                startOdoKm = c.getDouble(c.getColumnIndexOrThrow("start_odo_km")),
                endOdoKm = c.getDouble(c.getColumnIndexOrThrow("end_odo_km")),
                startBatt = c.getInt(c.getColumnIndexOrThrow("start_batt")),
                endBatt = c.getInt(c.getColumnIndexOrThrow("end_batt")),
                maxSpeed = c.getInt(c.getColumnIndexOrThrow("max_speed")),
                avgSpeed = c.getDouble(c.getColumnIndexOrThrow("avg_speed")),
                durationSec = c.getLong(c.getColumnIndexOrThrow("duration_sec"))
            )
        }

    /** Pair(count, totalKm). */
    fun totals(): Pair<Int, Double> {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) c, IFNULL(SUM(distance_km),0) s FROM $TABLE", null
        ).use { c ->
            if (c.moveToFirst()) return Pair(c.getInt(0), c.getDouble(1))
        }
        return Pair(0, 0.0)
    }
}
