package com.example.camara.recordings.ui



// ----------
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
//import androidx.navigation.compose.navArgument
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlin.math.max
import okhttp3.Request
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.Image
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.asImageBitmap
import com.example.camara.MenuColors
import com.example.camara.recordings.util.DayEvent
import com.example.camara.recordings.util.detectionSummaryUrl
import com.example.camara.recordings.util.flattenToEvents
import com.example.camara.recordings.util.formatHmsLocal
import com.example.camara.recordings.util.parseServerIsoToEpochMs
import java.util.TimeZone

import com.example.camara.recordings.api.API_KEY
import com.example.camara.recordings.api.BASE_URL
import com.example.camara.recordings.api.Detection
import com.example.camara.recordings.api.RecApiClient
import com.example.camara.recordings.api.Recording
import java.util.Calendar


// ---------- UI: Recordings list ----------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingsScreen(
    camId: String,
    nav: NavHostController,
    onOpen: (Recording) -> Unit,
    onOpenEvent: (url: String, marks: List<Int>, seekSec: Int, startEpochMs: Long) -> Unit
) {
    // date formatters (API 24 safe)
    val ymd = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US) }
    val dmy = remember { SimpleDateFormat("dd-MM-yyyy", Locale.US) }
    val hms = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    val hmsLocal = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    var selectedDate by rememberSaveable(camId) { mutableStateOf(ymd.format(Date())) }

    // data
    var items by remember { mutableStateOf<List<Recording>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // detection state
    var detections by remember { mutableStateOf<List<Detection>>(emptyList()) }
    var detectionError by remember { mutableStateOf<String?>(null) }
    var classesError by remember { mutableStateOf<String?>(null) }
    var openDetectionError by remember { mutableStateOf<String?>(null) }
    var detectionClasses by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedDetectionClass by rememberSaveable(camId) { mutableStateOf<String?>(null) }

    val isoUtc = remember {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }

    // Detection summary state
    val scope = rememberCoroutineScope()

    var ensuring by remember { mutableStateOf(false) }
    var ensureError by remember { mutableStateOf<String?>(null) }



    // tabs: 0 = Grabaciones, 1 = Eventos, 2 = Detecciones
    var tabIndex     by rememberSaveable(camId) { mutableIntStateOf(0) }

    val recListState   = rememberLazyListState()
    val eventListState = rememberLazyListState()
    val detectionClassesListState = rememberLazyListState()
    val detectionListState = rememberLazyListState()

    val openRec: (Recording) -> Unit = { rec ->
        val encUrl   = Uri.encode(rec.url)
        val encMarks = Uri.encode(JSONArray(rec.marks ?: emptyList<Int>()).toString())
        val startMs  = parseServerIsoToEpochMs(rec.start_iso) ?: 0L
        // añadimos cam y date
        nav.navigate("playHttp?url=$encUrl&marks=$encMarks&seek=0&startEpoch=$startMs&cam=$camId&date=$selectedDate")
    }

    val openEvent: (String, List<Int>, Int, Long) -> Unit = { url, marks, seek, startEpoch ->
        val encUrl   = Uri.encode(url)
        val encMarks = Uri.encode(JSONArray(marks).toString())
        nav.navigate("playHttp?url=$encUrl&marks=$encMarks&seek=$seek&startEpoch=$startEpoch&cam=$camId&date=$selectedDate")
    }

    fun openSummary(dateYmdUtc: String) {
        val encUrl = Uri.encode(detectionSummaryUrl(dateYmdUtc))
        // Play as a “special” clip; we send cam=summary and startEpoch=0
        nav.navigate(
            "playHttp?url=$encUrl&marks=%5B%5D&seek=0&startEpoch=0&cam=summary&date=$dateYmdUtc"
        )
    }


    // fetch ALL pages for the selected day so morning/overnight files aren’t missing
    LaunchedEffect(camId, selectedDate, selectedDetectionClass) {
        loading = true
        error = null
        detectionError = null
        classesError = null

        runCatching {
            val pageSize = 200
            val out = mutableListOf<Recording>()
            var page = 1
            while (true) {
                val resp = RecApiClient.api.list(
                    cam = camId,
                    date = selectedDate,
                    page = page,
                    pageSize = pageSize,
                    includeMarks = 1
                )
                out += resp.items
                if (page >= resp.pages) break
                page++
            }
            out.toList()
        }.onSuccess { items = it }
            .onFailure { error = it.message }

        // fetch classes configured for this camera
        runCatching {
            RecApiClient.api.detectionClasses(cam = camId).items
        }.onSuccess { items ->
            detectionClasses = items
                .firstOrNull { it.camera.equals(camId, ignoreCase = true) }
                ?.classes
                ?.distinct()
                ?: emptyList()
            if (selectedDetectionClass != null && selectedDetectionClass !in detectionClasses) {
                selectedDetectionClass = null
            }
        }.onFailure { classesError = it.message }

        // fetch detections only when a class is selected
        if (selectedDetectionClass != null) {
            runCatching {
                val startIso = "${selectedDate}T00:00:00"
                val endIso   = "${selectedDate}T23:59:59"
                RecApiClient.api.detections(
                    cam = camId,
                    startIso = startIso,
                    endIso = endIso,
                    label = selectedDetectionClass,
                ).items
            }.onSuccess { detections = it }
                .onFailure { detectionError = it.message }
        } else {
            detections = emptyList()
        }

        loading = false
    }

    fun stepDay(delta: Int) {
        val cal = Calendar.getInstance().apply {
            time = ymd.parse(selectedDate)!!
            add(Calendar.DATE, delta)
        }
        selectedDate = ymd.format(cal.time)
    }

    val sortedItems = remember(items) {
        items.sortedByDescending { parseServerIsoToEpochMs(it.start_iso) ?: 0L }
    }

    val events = remember(sortedItems) { flattenToEvents(sortedItems) }

    val detectionItems = remember(detections) {
        detections.sortedByDescending { parseServerIsoToEpochMs(it.ts_iso) ?: 0L } // Z → UTC handled
    }


    // Build a searchable (startEpochMs, Recording) list for timestamp resolution
    val recsAscWithStart = remember(sortedItems) {
        sortedItems.mapNotNull { rec ->
            parseServerIsoToEpochMs(rec.start_iso)?.let { it to rec }
        }.sortedBy { it.first }
    }

    fun resolveRecordingFromSorted(
        tsEpochMs: Long,
        sortedAsc: List<Pair<Long, Recording>>,
    ): Pair<Long, Recording>? {
        if (sortedAsc.isEmpty()) return null
        val idx = sortedAsc.indexOfLast { it.first <= tsEpochMs }
        if (idx < 0) return null

        val (startEpoch, rec) = sortedAsc[idx]
        val nextStart = sortedAsc.getOrNull(idx + 1)?.first
        if (nextStart != null && tsEpochMs >= nextStart) return null

        return startEpoch to rec
    }

    suspend fun resolveRecordingForDetection(tsEpochMs: Long): Pair<Long, Recording>? {
        resolveRecordingFromSorted(tsEpochMs, recsAscWithStart)?.let { return it }

        val fetched = mutableListOf<Recording>()
        val pageSize = 200
        val maxPages = 12
        var page = 1
        var totalPages = 1

        while (page <= totalPages && page <= maxPages) {
            val resp = runCatching {
                RecApiClient.api.list(
                    cam = camId,
                    date = null,
                    page = page,
                    pageSize = pageSize,
                    includeMarks = 1,
                )
            }.getOrNull() ?: break

            fetched += resp.items
            totalPages = resp.pages
            page += 1
        }

        val sortedAsc = fetched
            .mapNotNull { rec -> parseServerIsoToEpochMs(rec.start_iso)?.let { it to rec } }
            .sortedBy { it.first }

        return resolveRecordingFromSorted(tsEpochMs, sortedAsc)
    }

    // Resolver to open the correct recording at the detection timestamp
    fun openDetectionAtTimestamp(
        tsIso: String,
        open: (url: String, marks: List<Int>, seekSec: Int, startEpochMs: Long) -> Unit = openEvent
    ) {
        val ts = parseServerIsoToEpochMs(tsIso)
        if (ts == null) {
            openDetectionError = "No se pudo interpretar el horario de la detección."
            return
        }

        scope.launch {
            val resolved = resolveRecordingForDetection(ts)
            if (resolved == null) {
                openDetectionError = "No se encontró una grabación disponible para esa detección."
                return@launch
            }
            val (startEpoch, rec) = resolved
            val seekSec = ((ts - startEpoch) / 1000L).toInt().coerceAtLeast(0)
            open(rec.url, rec.marks ?: emptyList(), seekSec, startEpoch)
        }
    }


    data class SummaryHead(
        val ok: Boolean,
        val mtime: Long = 0L,
        val videoMaxId: Long = 0L,
        val currentMaxId: Long = 0L,
        val generating: Boolean = false
    )

    suspend fun headSummary(client: OkHttpClient, url: String): SummaryHead =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url(url)
                .head()
                .addHeader("X-Api-Key", API_KEY)
                .addHeader("Cache-Control", "no-cache")
                .build()
            runCatching {
                client.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) return@use SummaryHead(false)
                    SummaryHead(
                        ok = true,
                        mtime = r.header("X-Video-Age-Seconds")?.let { System.currentTimeMillis() - it.toLong()*1000 } ?: 0L,
                        videoMaxId = r.header("X-Video-Max-Id")?.toLongOrNull() ?: 0L,
                        currentMaxId = r.header("X-Current-Max-Id")?.toLongOrNull() ?: 0L,
                        generating = r.header("X-Generating") == "1"
                    )
                }
            }.getOrDefault(SummaryHead(false))
        }





    // Suspend helper for Detection Summary
    // Server contract:
    // - HEAD: 200 ready, 204 no content, 404 not built yet
    // - GET: 200/206 ready, 204 no content, 409 generating
    suspend fun ensureDetectionSummaryReady(baseUrl: String, dateYmdUtc: String, apiKey: String): SummaryResult {
        val url = "$baseUrl/api/detection_day_summary/video?date=$dateYmdUtc"
        return when (val h = headStatus(url, apiKey)) {
            200 -> SummaryResult.Ready
            204 -> SummaryResult.NoContent
            404 -> when (val g = getWarmupStatus(url, apiKey)) {
                200, 206 -> SummaryResult.Ready
                204       -> SummaryResult.NoContent
                409, -1   -> SummaryResult.Waiting   // ⟵ treat timeout as “still generating”
                else      -> SummaryResult.Error("GET $url → HTTP $g")
            }
            -1  -> SummaryResult.Error("HEAD $url → network error")
            else -> SummaryResult.Error("HEAD $url → HTTP $h")
        }
    }





    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { stepDay(-1) }) { Text("‹") }
                        Text(dmy.format(ymd.parse(selectedDate)!!))
                        IconButton(onClick = { stepDay(+1) }) { Text("›") }
                    }
                },
                actions = {
                    if (tabIndex == 2) {
                        Button(
                            onClick = {
                                scope.launch {
                                    ensuring = true
                                    ensureError = null

                                    // If you need strict UTC-day name, convert before passing:
                                    val dateUtc = selectedDate  // (adjust if your UI date is local day)

                                    when (val r = ensureDetectionSummaryReady(
                                        baseUrl = RecApiClient.baseUrl,
                                        dateYmdUtc = dateUtc,
                                        apiKey = API_KEY
                                    )) {
                                        SummaryResult.Ready -> {
                                            ensuring = false
                                            openSummary(dateUtc)
                                        }
                                        SummaryResult.NoContent -> {
                                            ensuring = false
                                            ensureError = "No hay detecciones para ese día."
                                        }
                                        SummaryResult.Waiting -> {
                                            while (isActive) {
                                                delay(1500)
                                                when (val rr = ensureDetectionSummaryReady(
                                                    RecApiClient.baseUrl, dateUtc,
                                                    API_KEY
                                                )) {
                                                    SummaryResult.Ready      -> { ensuring = false; openSummary(dateUtc); break }
                                                    SummaryResult.NoContent  -> { ensuring = false; ensureError = "No hay detecciones para ese día."; break }
                                                    is SummaryResult.Error   -> { ensuring = false; ensureError = rr.msg; break }
                                                    SummaryResult.Waiting    -> { /* keep spinning */ }
                                                }
                                            }
                                        }
                                        is SummaryResult.Error -> {
                                            ensuring = false
                                            ensureError = r.msg
                                        }
                                    }

                                }
                            },
                            enabled = !ensuring,
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .height(36.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MenuColors.accent,
                                contentColor = MenuColors.onAccent
                            )
                        ) {
                            if (ensuring) {
                                CircularProgressIndicator(
                                    strokeWidth = 2.dp,
                                    modifier = Modifier
                                        .size(18.dp)
                                        .padding(end = 8.dp)
                                )
                            }
                            Text("Resumen")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .windowInsetsPadding(WindowInsets.navigationBars)
        ) {
            TabRow(selectedTabIndex = tabIndex) {
                Tab(
                    selected = tabIndex == 0,
                    onClick = { tabIndex = 0 },
                    selectedContentColor = MenuColors.tabSelected,
                    unselectedContentColor = MenuColors.tabUnselected,
                    text = { Text("Grabaciones") }
                )
                Tab(
                    selected = tabIndex == 1,
                    onClick = { tabIndex = 1 },
                    selectedContentColor = MenuColors.tabSelected,
                    unselectedContentColor = MenuColors.tabUnselected,
                    text = { Text("Eventos") }
                )
                Tab(
                    selected = tabIndex == 2,
                    onClick = { tabIndex = 2 },
                    selectedContentColor = MenuColors.tabSelected,
                    unselectedContentColor = MenuColors.tabUnselected,
                    text = { Text("Detecciones") }
                )
            }

            if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

            if (error != null) {
                Text("Error: $error", modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error)
            } else {
                when (tabIndex) {
                    0 -> LazyColumn(
                        state = recListState,
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(sortedItems, key = { it.filename }) { rec ->
                            RecordingRow(rec = rec, hms = hms, onOpen = openRec)
                        }
                    }
                    1 -> LazyColumn(
                        state = eventListState,
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(events, key = { it.key }) { ev ->
                            EventRow(ev = ev, onOpenEvent = openEvent)
                        }
                    }
                    2 -> LazyColumn(
                        state = if (selectedDetectionClass == null) detectionClassesListState else detectionListState,
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (selectedDetectionClass != null) {
                            item {
                                OutlinedButton(
                                    onClick = { selectedDetectionClass = null },
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = MenuColors.accent,
                                        disabledContentColor = MenuColors.accent.copy(alpha = 0.38f)
                                    ),
                                    border = BorderStroke(1.dp, MenuColors.accent)
                                ) {
                                    Text("Volver a clases")
                                }
                            }
                        }
                        if (classesError != null) {
                            item {
                                Text(
                                    "Error cargando clases: $classesError",
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        }
                        if (detectionError != null && selectedDetectionClass != null) {
                            item {
                                Text(
                                    "Error cargando detecciones: $detectionError",
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        }
                        if (selectedDetectionClass == null) {
                            if (detectionClasses.isEmpty() && classesError == null) {
                                item {
                                    Text(
                                        "No hay clases configuradas para esta cámara.",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(12.dp)
                                    )
                                }
                            }
                            items(detectionClasses, key = { it }) { cls ->
                                Card(onClick = { selectedDetectionClass = cls }) {
                                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                                        Text(cls, style = MaterialTheme.typography.titleMedium)
                                        Text(
                                            "Ver detecciones de esta clase",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        } else {
                            item {
                                Text(
                                    "Clase: $selectedDetectionClass",
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.padding(horizontal = 4.dp)
                                )
                            }
                            items(detectionItems, key = { it.id }) { detection ->
                                DetectionRow(
                                    detection = detection,
                                    onOpenEvent = openEvent,
                                    openAt = { tsIso, open -> openDetectionAtTimestamp(tsIso, open) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }



    // Check for Detection Summary loading
    if (ensuring) {
        AlertDialog(
            onDismissRequest = { /* block dismiss while generating */ },
            title = { Text("Generando resumen…") },
            text = { Text("Esto puede demorar según la cantidad de grabaciones del día.") },
            confirmButton = {
                TextButton(
                    enabled = false,
                    onClick = {},
                    colors = ButtonDefaults.textButtonColors(contentColor = MenuColors.accent)
                ) { Text("Esperando…") }
            }
        )
    }
    if (ensureError != null) {
        AlertDialog(
            onDismissRequest = { ensureError = null },
            title = { Text("No se pudo generar el resumen") },
            text  = { Text(ensureError!!) },
            confirmButton = {
                TextButton(
                    onClick = { ensureError = null },
                    colors = ButtonDefaults.textButtonColors(contentColor = MenuColors.accent)
                ) { Text("OK") }
            }
        )
    }

    if (openDetectionError != null) {
        AlertDialog(
            onDismissRequest = { openDetectionError = null },
            title = { Text("No se pudo abrir la detección") },
            text = { Text(openDetectionError!!) },
            confirmButton = {
                TextButton(
                    onClick = { openDetectionError = null },
                    colors = ButtonDefaults.textButtonColors(contentColor = MenuColors.accent)
                ) { Text("OK") }
            }
        )
    }


}

sealed class SummaryResult {
    data object Ready : SummaryResult()
    data object NoContent : SummaryResult()
    data object Waiting : SummaryResult()
    data class Error(val msg: String) : SummaryResult()
}

// Single OkHttp client
private val http by lazy {
    OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS)
        .build()
}


// Small helper for HEAD/GET
private suspend fun headStatus(url: String, apiKey: String): Int =
    withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).head()
            .addHeader("X-Api-Key", apiKey).addHeader("Cache-Control", "no-cache").build()
        runCatching { http.newCall(req).execute().use { it.code } }.getOrElse { -1 }
    }

private suspend fun getWarmupStatus(url: String, apiKey: String): Int =
    withContext(Dispatchers.IO) {
        val fast = http.newBuilder().callTimeout(4, TimeUnit.SECONDS).build()
        val req = Request.Builder().url(url).get()
            .addHeader("X-Api-Key", apiKey)
            .addHeader("Cache-Control", "no-cache")
            .addHeader("Range", "bytes=0-1")
            .build()
        runCatching { fast.newCall(req).execute().use { it.code } }.getOrElse { -1 }
    }



@Composable
private fun RecordingRow(
    rec: Recording,
    hms: SimpleDateFormat,
    onOpen: (Recording) -> Unit
) {

    val isoUtcLocal = remember {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }
    val timeLabel = runCatching {
        hms.format(Date(parseServerIsoToEpochMs(rec.start_iso)!!))
    }.getOrElse { rec.start_iso.replace('T',' ').takeLast(8) }

    val sizeMb = ((rec.size_bytes / (1024 * 1024)).toInt()).coerceAtLeast(1)
    val marksCount = rec.marks?.size ?: 0

    Card {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text(text = timeLabel, style = MaterialTheme.typography.titleMedium)
            Text(
                //text = "Eventos: $marksCount · Tamaño: ~${sizeMb} MB",
                text = "Eventos: $marksCount",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onOpen(rec) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MenuColors.accent,
                        contentColor = MenuColors.onAccent
                    )
                ) { Text("Ver") }
            }
        }
    }
}

@Composable
private fun RecordingCard(rec: Recording, onOpen: (Recording) -> Unit) {
    val dateTime = rec.start_iso.replace('T',' ')
    val sizeMb = max(1, (rec.size_bytes / (1024 * 1024)).toInt())
    val marksCount = rec.marks?.size ?: 0

    Card {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text(text = dateTime, style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Eventos: $marksCount · Tamaño: ~${sizeMb} MB",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onOpen(rec) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MenuColors.accent,
                        contentColor = MenuColors.onAccent
                    )
                ) { Text("Ver") }
            }
        }
    }
}

@Composable
private fun DetectionRow(
    detection: Detection,
    onOpenEvent: (url: String, marks: List<Int>, seekSec: Int, startEpochMs: Long) -> Unit,
    openAt: (String, (String, List<Int>, Int, Long) -> Unit) -> Unit
) {
    val isoUtcLocal = remember {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }
    val hmsLocal = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val timeLabel = runCatching {
        formatHmsLocal(parseServerIsoToEpochMs(detection.ts_iso)!!)
    }.getOrElse { detection.ts_iso.replace('T',' ').takeLast(8) }
    val label = detection.label?.takeIf { it.isNotBlank() } ?: "Deteccion"

    Card {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text("$label • $timeLabel", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            SnapshotThumb(detection.snapshot)
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { openAt(detection.ts_iso, onOpenEvent) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MenuColors.accent,
                    contentColor = MenuColors.onAccent
                )
            ) { Text("Ver") }
        }
    }
}



/**
 * Lightweight snapshot loader that fetches an image with X-Api-Key and shows a thumbnail.
 * Avoids adding a Coil dependency; keeps everything self-contained.
 */
@Composable
private fun SnapshotThumb(path: String?) {
    if (path.isNullOrBlank()) return

    val fullUrl = remember(path) {
        if (path.startsWith("http", ignoreCase = true)) path
        else "${BASE_URL}$path"
    }

    var bmp by remember(fullUrl) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(fullUrl) { mutableStateOf(false) }

    LaunchedEffect(fullUrl) {
        failed = false
        bmp = withContext(Dispatchers.IO) {
            runCatching {
                val client = OkHttpClient.Builder()
                    .readTimeout(4, TimeUnit.SECONDS)
                    .connectTimeout(4, TimeUnit.SECONDS)
                    .build()
                val req = Request.Builder()
                    .url(fullUrl)
                    .addHeader("X-Api-Key", API_KEY)
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    val bytes = resp.body?.bytes() ?: return@use null
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
            }.getOrNull()
        }
        if (bmp == null) failed = true
    }

    when {
        bmp != null -> {
            Image(
                bitmap = bmp!!.asImageBitmap(),
                contentDescription = "Detection snapshot",
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 240.dp)
            )
        }
        failed -> {
            Text(
                "No se pudo cargar la miniatura",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        else -> {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}




@Composable
private fun EventRow(
    ev: DayEvent,
    onOpenEvent: (url: String, marks: List<Int>, seekSec: Int, startEpochMs: Long) -> Unit // NEW
) {
    Card {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text(ev.timeLabel, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onOpenEvent(ev.url, ev.marks, ev.seekSec, ev.startEpochMs) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MenuColors.accent,
                        contentColor = MenuColors.onAccent
                    )
                ) {
                    Text("Ver evento")
                }
            }
        }
    }
}
