package com.example.camara.recordings.util




// ---------- Imports ----------
//import androidx.navigation.compose.navArgument
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date
import java.util.TimeZone

import com.example.camara.recordings.api.BASE_URL
import com.example.camara.recordings.api.Recording
import com.example.camara.recordings.api.SERVER_TZ_ID


// ---- lightweight UI model for a single event row ----
internal data class DayEvent(
    val key: String,           // filename#offset
    val timeLabel: String,     // "HH:mm:ss"
    val url: String,
    val marks: List<Int>,
    val seekSec: Int,
    val startEpochMs: Long
)

// ---- flatten recordings with marks -> per-event rows ----
internal fun flattenToEvents(recs: List<Recording>): List<DayEvent> {
    val out = mutableListOf<Pair<Long, DayEvent>>()
    for (rec in recs) {
        val marks = rec.marks ?: emptyList()
        if (marks.isEmpty()) continue
        val start = parseServerIsoToEpochMs(rec.start_iso) ?: 0L
        for (m in marks.distinct().sorted()) {
            val ts = start + m * 1000L
            out += ts to DayEvent(
                key = rec.filename + "#" + m,
                timeLabel = formatHmsLocal(ts),
                url = rec.url,
                marks = marks,
                seekSec = m,
                startEpochMs = start
            )
        }
    }
    return out.sortedByDescending { it.first }.map { it.second }
}

internal fun detectionSummaryUrl(dateYmd: String): String =
    "${BASE_URL}/api/detection_day_summary/video?date=$dateYmd"







fun parseServerIsoToEpochMs(iso: String): Long? {
    // Has explicit zone? (Z or ±HH[:MM])
    val hasZone = iso.endsWith("Z") || (iso.length > 19 && (iso[19] == '+' || iso[19] == '-'))
    val sdf = if (hasZone) {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssX", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    } else {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone(SERVER_TZ_ID)
        }
    }
    return runCatching { sdf.parse(iso)?.time }.getOrNull()
}

internal fun formatHmsLocal(epochMs: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(epochMs))

