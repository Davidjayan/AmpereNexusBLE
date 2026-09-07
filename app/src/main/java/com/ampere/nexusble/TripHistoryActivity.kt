package com.ampere.nexusble

import android.os.Bundle
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.text.SimpleDateFormat
import java.util.*

class TripHistoryActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_trips)

        val root = findViewById<LinearLayout>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }

        val db = TripDb(this)
        val trips = db.all()
        val (count, totalKm) = db.totals()

        findViewById<TextView>(R.id.tvTotals).text =
            "%d trips  ·  %.1f km total".format(count, totalKm)

        val list = findViewById<LinearLayout>(R.id.list)
        val inflater = LayoutInflater.from(this)
        val dfWhen = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault())

        if (trips.isEmpty()) {
            val tv = TextView(this).apply {
                text = "No trips recorded yet.\n\nRide with the app connected and completed rides " +
                        "will appear here automatically."
                setTextColor(0xFF8A94A3.toInt())
                textSize = 14f
                setPadding(8, 24, 8, 8)
            }
            list.addView(tv)
            return
        }

        for (t in trips) {
            val row = inflater.inflate(R.layout.item_trip, list, false)
            row.findViewById<TextView>(R.id.tDistance).text = "%.2f km".format(t.distanceKm)
            row.findViewById<TextView>(R.id.tWhen).text = dfWhen.format(Date(t.startTime))
            val mins = t.durationSec / 60
            val battUsed = (t.startBatt - t.endBatt).coerceAtLeast(0)
            row.findViewById<TextView>(R.id.tDetail).text =
                "%d min  ·  avg %.0f km/h  ·  max %d km/h  ·  battery %d%%→%d%% (-%d%%)"
                    .format(mins, t.avgSpeed, t.maxSpeed, t.startBatt, t.endBatt, battUsed)
            list.addView(row)
        }
    }
}
