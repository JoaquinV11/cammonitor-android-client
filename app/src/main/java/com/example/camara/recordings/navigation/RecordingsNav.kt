package com.example.camara.recordings.navigation




// ---------- Imports ----------
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
//import androidx.navigation.compose.navArgument
import androidx.navigation.navArgument
import com.example.camara.recordings.util.parseServerIsoToEpochMs
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date
import java.util.TimeZone

import com.example.camara.recordings.api.RecApiClient
import com.example.camara.recordings.api.Recording
import com.example.camara.recordings.api.SERVER_TZ_ID
import com.example.camara.recordings.player.IjkHttpPlayerWithControls
import com.example.camara.recordings.ui.RecordingsScreen
import java.util.Calendar
import kotlin.math.abs


// ---------- Nav: register both screens ----------
fun NavGraphBuilder.recordingsFeature(nav: NavHostController) {
    // List of recordings (with marks count)
    composable(
        route = "recordings?camId={camId}",
        arguments = listOf(navArgument("camId") { type = NavType.StringType })
    ) { backStackEntry ->
        val camId = requireNotNull(backStackEntry.arguments?.getString("camId"))
        RecordingsScreen(
            camId = camId,
            nav = nav,
            onOpen = { rec ->
                val encUrl = Uri.encode(rec.url)
                val encMarks = Uri.encode(JSONArray(rec.marks ?: emptyList<Int>()).toString())
                val startMs = parseServerIsoToEpochMs(rec.start_iso) ?: 0L
                nav.navigate("playHttp?url=$encUrl&marks=$encMarks&seek=0&startEpoch=$startMs")
            },
            onOpenEvent = { url, marks, seek, startEpoch ->
                val encUrl = Uri.encode(url)
                val encMarks = Uri.encode(JSONArray(marks).toString())
                nav.navigate("playHttp?url=$encUrl&marks=$encMarks&seek=$seek&startEpoch=$startEpoch")
            }
        )
    }




    // HTTP player (IJK) with seek/ticks
    composable(
        route = "playHttp?url={url}&marks={marks}&seek={seek}&startEpoch={startEpoch}&cam={cam}&date={date}",
        arguments = listOf(
            navArgument("url")        { type = NavType.StringType },
            navArgument("marks")      { type = NavType.StringType; defaultValue = "[]" },
            navArgument("seek")       { type = NavType.StringType; defaultValue = "0" },
            navArgument("startEpoch") { type = NavType.StringType; defaultValue = "0" },
            navArgument("cam")        { type = NavType.StringType },
            navArgument("date")       { type = NavType.StringType }
        )
    ) { bs ->
        val url = requireNotNull(bs.arguments?.getString("url"))
        val marksJson = bs.arguments?.getString("marks") ?: "[]"
        val arr = JSONArray(marksJson)
        val marks = List(arr.length()) { i -> arr.getInt(i) }
        val seek = (bs.arguments?.getString("seek") ?: "0").toIntOrNull() ?: 0
        val startEpoch = (bs.arguments?.getString("startEpoch") ?: "0").toLongOrNull() ?: 0L
        val cam        = requireNotNull(bs.arguments?.getString("cam"))
        val date       = requireNotNull(bs.arguments?.getString("date"))
        val isSummary = cam == "summary"



        // helper para paginar el día y ordenar por inicio ascendente
        suspend fun fetchDay(camId: String, dateYmd: String): List<Pair<Long, Recording>> {
            val pageSize = 200
            val all = mutableListOf<Recording>()
            var page = 1
            while (true) {
                val resp = RecApiClient.api.list(
                    cam = camId, date = dateYmd, page = page, pageSize = pageSize, includeMarks = 1
                )
                all += resp.items
                if (page >= resp.pages) break
                page++
            }
            return all.mapNotNull { r ->
                parseServerIsoToEpochMs(r.start_iso)?.let { it to r }
            }.sortedBy { it.first }
        }


        // helper para +1 día
        fun plusOneDay(ymd: String): String {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val cal = Calendar.getInstance().apply { time = sdf.parse(ymd)!!; add(Calendar.DATE, 1) }
            return sdf.format(cal.time)
        }

        // busca el siguiente recording (cruza al día siguiente si estás en el último)
        // une día actual y siguiente
        suspend fun findNextRecording(
            camId: String,
            dateYmd: String,
            currentStartMs: Long,
            currentUrl: String
        ): Recording? {
            val today    = fetchDay(camId, dateYmd)
            val tomorrow = fetchDay(camId, plusOneDay(dateYmd))
            val all      = (today + tomorrow).sortedBy { it.first }
            if (all.isEmpty()) return null

            // --- Normalizar URL actual y preparar filename como fallback ---
            val curDec  = Uri.decode(currentUrl)
            val curFile = runCatching { Uri.parse(curDec).lastPathSegment }.getOrNull()

            // 1) match por URL decodificada (más fiable)
            var idx = all.indexOfFirst { (_, r) ->
                Uri.decode(r.url) == curDec
            }

            // 1b) si no matchea por URL, intentar por filename
            if (idx == -1 && curFile != null) {
                idx = all.indexOfFirst { (_, r) ->
                    val rFile = runCatching {
                        Uri.parse(Uri.decode(r.url)).lastPathSegment
                    }.getOrNull()
                    rFile == curFile || r.filename == curFile
                }
            }

            // 2) tolerancia por tiempo (por si hay tiny skews)
            val EPS = 5_000L
            if (idx == -1) {
                idx = all.indexOfFirst { (startMs, _) ->
                    abs(startMs - currentStartMs) <= EPS
                }
            }

            // 3) fallback por posición temporal
            if (idx == -1) {
                idx = all.indexOfLast { (startMs, _) -> startMs <= currentStartMs + EPS }
                if (idx == -1) idx = 0
            }

            return all.getOrNull(idx + 1)?.second
        }



        val urlArg     = requireNotNull(bs.arguments?.getString("url"))
        val currentUrl = Uri.decode(urlArg)

        // resolver y guardar el “siguiente”
        var nextRec by remember(url, cam, date, startEpoch) { mutableStateOf<Recording?>(null) }
        LaunchedEffect(cam, date, currentUrl, startEpoch) {
            nextRec = runCatching { findNextRecording(cam, date, startEpoch, currentUrl) }.getOrNull()
        }


        // navegación al siguiente
        fun open(rec: Recording) {
            val encUrl   = Uri.encode(rec.url)
            val encMarks = Uri.encode(JSONArray(rec.marks ?: emptyList<Int>()).toString())
            val startMs  = parseServerIsoToEpochMs(rec.start_iso) ?: 0L

            // Format the API “date” in the same timezone the server expects
            val recDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                timeZone = TimeZone.getTimeZone(SERVER_TZ_ID) // America/Montevideo
            }.format(Date(startMs))

            nav.navigate(
                "playHttp?url=$encUrl&marks=$encMarks&seek=0&startEpoch=$startMs&cam=$cam&date=$recDate"
            )
        }


        IjkHttpPlayerWithControls(
            url = url,
            markersSec = if (isSummary) emptyList() else marks,
            initialSeekMs = seek * 1000L,
            fileStartEpochMs = if (isSummary) null else startEpoch.takeIf { it > 0L },
            isRecording = !isSummary,            // avoids “Grabación en curso” label
            modifier = Modifier.fillMaxSize(),
            hasNext = if (isSummary) false else (nextRec != null),
            onNextClick = if (isSummary) null else ({ nextRec?.let(::open) })
        )
    }


}

