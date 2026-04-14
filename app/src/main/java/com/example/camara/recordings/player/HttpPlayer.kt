package com.example.camara.recordings.player




// ----------
import android.graphics.SurfaceTexture
import android.net.Uri
import android.view.Surface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
//import androidx.navigation.compose.navArgument
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import tv.danmaku.ijk.media.player.IjkMediaPlayer
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date
import java.util.concurrent.TimeUnit
import okhttp3.Request
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.snapshotFlow
import com.example.camara.MenuColors
import kotlinx.coroutines.flow.sample
import java.util.TimeZone

import android.os.Handler
import android.os.Looper
import android.view.TextureView
import com.example.camara.recordings.api.API_KEY
import com.example.camara.recordings.api.SERVER_TZ_ID
import kotlin.math.abs
import kotlin.math.min


// ---------- UI: IJK HTTP player w/ seek + event ticks ----------
@Composable
fun IjkHttpPlayerWithControls(
    url: String,
    markersSec: List<Int> = emptyList(),
    initialSeekMs: Long = -1L,
    modifier: Modifier = Modifier,
    isRecording: Boolean = true,
    liveBackoffMs: Long = 60_000L,
    fileStartEpochMs: Long? = null,
    hasNext: Boolean = false,
    onNextClick: (() -> Unit)? = null
) {
    val ctx = LocalContext.current
    val ijk = remember {
        IjkMediaPlayer().apply {
            IjkMediaPlayer.loadLibrariesOnce(null)
            IjkMediaPlayer.native_profileBegin("libijkplayer.so")
        }
    }

    var surface: Surface? by remember { mutableStateOf(null) }
    var isLoading by remember { mutableStateOf(true) }
    var durationMs by remember { mutableStateOf(0L) }
    var positionMs by remember { mutableStateOf(0L) }
    var controlsVisible by remember { mutableStateOf(true) }

    var isPlaying by remember { mutableStateOf(false) }

    var isCompleted by remember { mutableStateOf(false) }

    val playUrl = remember(url) { Uri.decode(url) }

    // Treat any detection-day-summary URL as a clip with *relative* time
    val isSummaryUrl = remember(url) {
        Uri.decode(url).contains("/api/detection_day_summary/")
    }
    // Effective start epoch: null for summary → shows 0:00:00 style labels
    val startEpochRef: Long? = if (isSummaryUrl) null else fileStartEpochMs

    val PROGRESS_TTL_MS = 8_000L



    var isDragging by remember { mutableStateOf(false) }
    var dragF by remember { mutableStateOf(0f) }            // 0..1 during drag
    var lastSentSeekMs by remember { mutableStateOf(-1L) }  // last seek we actually sent

    // Tuning knobs:
    val throttleMs = 300L    // how often to allow seeks while dragging
    val minDeltaMs = 800L    // ignore seeks that change less than this since the last one


    val PREROLL_MS = 2500L

    val SNAP_MS = 1500L

    val SNAP_TO_TICK_MS = 300L  // visually snap the slider thumb to a tick within 300ms

    val CORRECTIVE_PREROLL_MS = 2000L

    val VIS_LEAD_MS = maxOf(PREROLL_MS, CORRECTIVE_PREROLL_MS) + 200L


    val scope = rememberCoroutineScope()

    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    fun postMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }



    // --- Progress polling (simple) ---
    var recordingSoFarMs by remember(playUrl) { mutableStateOf(Long.MAX_VALUE) }
    var lastProgressAtMs by remember(playUrl) { mutableStateOf(0L) }

    val client = remember {
        OkHttpClient.Builder()
            .readTimeout(3, TimeUnit.SECONDS)
            .connectTimeout(3, TimeUnit.SECONDS)
            .build()
    }

    fun headReq(u: String) = Request.Builder()
        .url(u)
        .head()
        .addHeader("X-Api-Key", API_KEY)
        .addHeader("Cache-Control", "no-cache")
        .build()

    LaunchedEffect(playUrl, isRecording) {
        if (!isRecording) { recordingSoFarMs = Long.MAX_VALUE; lastProgressAtMs = 0L; return@LaunchedEffect }
        val req = headReq(playUrl)
        while (isActive) {
            val secs = withContext(Dispatchers.IO) {
                runCatching { client.newCall(req).execute().use { it.header("X-Recording-Seconds")?.toLongOrNull() } }.getOrNull()
            }
            if (secs != null) {
                recordingSoFarMs = secs * 1000L
                lastProgressAtMs = System.currentTimeMillis()
            }
            delay(1500)
        }
    }

    val hasProgress = lastProgressAtMs != 0L &&
            (System.currentTimeMillis() - lastProgressAtMs) < PROGRESS_TTL_MS

    val seekCeilingMs = remember(durationMs, recordingSoFarMs, liveBackoffMs, hasProgress) {
        if (hasProgress) {
            val liveEdge = minOf(if (durationMs > 0) durationMs else Long.MAX_VALUE, recordingSoFarMs)
            (liveEdge - liveBackoffMs).coerceAtLeast(0L)
        } else durationMs
    }
    val seekMaxMs = if (hasProgress) seekCeilingMs else durationMs










    fun fmtHms(ms: Long): String {
        val totalS = (ms / 1000).coerceAtLeast(0)
        val h = totalS / 3600
        val m = (totalS % 3600) / 60
        val s = totalS % 60
        return String.format("%d:%02d:%02d", h, m, s)
    }

    fun seekToClamped(ms: Long) {
        val ceiling = if (seekCeilingMs > 0L) seekCeilingMs else Long.MAX_VALUE
        val t = ms.coerceIn(0L, ceiling)
        runCatching { ijk.seekTo(t) }
        controlsVisible = true
    }


    fun currentPos(): Long =
        runCatching { ijk.currentPosition }.getOrDefault(0L).coerceAtLeast(0L)

    fun preciseJumpTo(markMs: Long) {
        scope.launch {
            val prerolls = longArrayOf(PREROLL_MS, PREROLL_MS + 1200, PREROLL_MS + 2500)
            for (pre in prerolls) {
                val t = (minOf(markMs, seekCeilingMs) - pre).coerceAtLeast(0L)
                seekToClamped(t)
                delay(280) // settle
                val p = currentPos()

                if (p <= markMs - 200) {
                    val micro = (markMs - 220).coerceAtLeast(0L)
                    if (p < micro - 80) seekToClamped(micro)
                    break
                }
                // else overshot → try bigger preroll
            }

            // Final guarantee: don’t leave the head after the mark
            delay(200)
            val final = currentPos()
            if (final > markMs - 50) {
                val back = (markMs - CORRECTIVE_PREROLL_MS).coerceAtLeast(0L)
                seekToClamped(back)
            }
        }
    }




    // All event times in ms, sorted
    val markersMs = remember(markersSec) { markersSec.distinct().sorted().map { it * 1000L } }

    // Seekable ceiling (already computed in your code)
    val seekableMaxMs = seekMaxMs

    // Only consider marks you can actually reach right now (important for live edge)
    val visibleMarksMs by remember(markersMs, seekableMaxMs) {
        derivedStateOf { if (seekableMaxMs > 0) markersMs.filter { it in 0..seekableMaxMs } else emptyList() }
    }

    // How close we treat as "on/after" a mark when deciding the current one
    fun idxAtOrBefore(ms: Long, marks: List<Long>): Int =
        marks.indexOfLast { it <= ms + SNAP_MS }

    // Anchor the playhead to the seekable region
    val anchorMs = min(positionMs, seekableMaxMs)

    // The single source of truth: which event we are effectively on (−1 if before the first)
    val currentIdx by remember(anchorMs, visibleMarksMs) {
        derivedStateOf {
            if (visibleMarksMs.isEmpty()) -1 else run {
                val base = idxAtOrBefore(anchorMs, visibleMarksMs)
                val next = base + 1
                if (next in visibleMarksMs.indices &&
                    (visibleMarksMs[next] - anchorMs) <= VIS_LEAD_MS
                ) next else base
            }
        }
    }

    // Deterministic prev/next from the current index
    val prevIdx = if (currentIdx >= 0) currentIdx else -1
    val nextIdx = when {
        visibleMarksMs.isNotEmpty() && currentIdx == -1 -> 0
        currentIdx in 0 until visibleMarksMs.lastIndex -> currentIdx + 1
        else -> -1
    }




    fun goToEvent(i: Int) {
        if (visibleMarksMs.isEmpty()) return
        val idx = i.coerceIn(0, visibleMarksMs.lastIndex)
        preciseJumpTo(visibleMarksMs[idx])
    }

    // remembers where we are in a manual « / » chain; null = base off playhead
    var chainIdx by remember(url, visibleMarksMs) { mutableStateOf<Int?>(null) }

    // helper that ignores the VIS_LEAD promotion (strict “where are we really”)
    fun baseIdxFromPlayhead(): Int = idxAtOrBefore(anchorMs, visibleMarksMs)


    fun togglePlayPause() {
        runCatching {
            if (isPlaying) { ijk.pause() } else { ijk.start() }
            isPlaying = !isPlaying
            chainIdx = null
        }
    }




    LaunchedEffect(isDragging, seekMaxMs) {
        if (!isDragging) return@LaunchedEffect

        // sample() gives you the latest value at most once per throttleMs while dragging
        snapshotFlow { dragF }
            .sample(throttleMs)
            .collect { f ->
                if (seekMaxMs <= 0) return@collect
                val target = (f.coerceIn(0f, 1f) * seekMaxMs).toLong()
                if (abs(target - lastSentSeekMs) >= minDeltaMs) {
                    seekToClamped(target)
                    lastSentSeekMs = target
                }
            }
    }


    // Prepare & play
    LaunchedEffect(url, surface) {
        if (surface == null) return@LaunchedEffect
        postMain {
            isLoading = true
            isCompleted = false
        }
        ijk.reset()
        ijk.setSurface(surface)

        ijk.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "enable-accurate-seek", 1L)
        ijk.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared", 1L)
        ijk.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", 1L)
        ijk.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "timeout", 4_000_000L)

        val headers = mapOf("X-Api-Key" to API_KEY)
        ijk.setDataSource(ctx, Uri.parse(playUrl), headers)

        ijk.prepareAsync()
        ijk.setOnPreparedListener {
            postMain {
                durationMs = maxOf(ijk.duration, 0L)

                if (initialSeekMs >= 0) {
                    preciseJumpTo(initialSeekMs) // currentIdx will follow once position updates
                }




                isLoading = false
                ijk.start()
                isPlaying = true
                controlsVisible = true
                isCompleted = false
            }
        }


        ijk.setOnCompletionListener {
            postMain {
                if (hasProgress) {
                    // Estábamos en el borde vivo: no terminó realmente
                    val back = (seekCeilingMs - VIS_LEAD_MS).coerceAtLeast(0L)
                    seekToClamped(back)
                    isPlaying = false
                    isCompleted = false
                    controlsVisible = true
                } else {
                    // Archivo realmente terminó
                    isPlaying = false
                    isCompleted = true
                    controlsVisible = true
                }
            }
        }





        ijk.setOnInfoListener { _, what, _ ->
            postMain {
                when (what) {
                    IjkMediaPlayer.MEDIA_INFO_BUFFERING_START -> isLoading = true
                    IjkMediaPlayer.MEDIA_INFO_BUFFERING_END,
                    IjkMediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START -> isLoading = false
                }
            }
            false
        }
    }

    // Update UI position/duration
    LaunchedEffect(ijk) {
        while (true) {
            runCatching {
                positionMs = maxOf(ijk.currentPosition, 0L)
                durationMs = maxOf(ijk.duration, durationMs)
                isPlaying  = ijk.isPlaying
            }

            // Si por buffering/codec el player se pasa del techo, volvemos un poco.
            if (hasProgress && seekCeilingMs > 0 && positionMs > seekCeilingMs + 80) {
                val back = (seekCeilingMs - VIS_LEAD_MS).coerceAtLeast(0L)
                seekToClamped(back)
            }

            delay(200)
        }
    }


    // Auto-hide controls
    LaunchedEffect(controlsVisible, isLoading) {
        if (controlsVisible && !isLoading) {
            delay(2500)
            controlsVisible = false
        }
    }

    val density = LocalDensity.current
    val tickHitPx = with(density) { 14.dp.toPx() }

    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .windowInsetsPadding(WindowInsets.systemBars)
            .pointerInput(Unit) { detectTapGestures { controlsVisible = !controlsVisible } }
    ) {



        AndroidView(
            modifier = Modifier.align(Alignment.Center).fillMaxSize(),
            factory = {
                TextureView(ctx).apply {
                    keepScreenOn = true
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                            surface = Surface(st); ijk.setSurface(surface)
                        }
                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                            ijk.setSurface(null); surface?.release(); surface = null; return true
                        }
                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                    }
                }
            }
        )

        if (controlsVisible || isLoading) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(Color(0xAA000000))
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(12.dp)
            ) {

                // Snap the displayed position to the current tick if we’re very close,
                // so the thumb aligns with the tick visually.
                val displayMs = remember(positionMs, currentIdx, seekableMaxMs, visibleMarksMs) {
                    val snapped = if (currentIdx in visibleMarksMs.indices &&
                        abs(positionMs - visibleMarksMs[currentIdx]) <= SNAP_TO_TICK_MS
                    ) visibleMarksMs[currentIdx] else positionMs
                    min(snapped, seekableMaxMs)
                }
                val sliderValue = if (seekableMaxMs > 0) displayMs / seekableMaxMs.toFloat() else 0f




                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Left: Pause/Resume
                    Button(
                        onClick = { togglePlayPause() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MenuColors.accent,
                            contentColor = MenuColors.onAccent
                        )
                    ) {
                        Text(if (isPlaying) "Pausa" else "Reanudar")
                    }

                    val prevEnabled = when (val c = chainIdx) {
                        null -> baseIdxFromPlayhead() >= 0
                        else -> c > 0
                    }
                    val nextEnabled = when (val c = chainIdx) {
                        null -> (baseIdxFromPlayhead() + 1) <= visibleMarksMs.lastIndex
                        else -> c < visibleMarksMs.lastIndex
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            enabled = prevEnabled,
                            onClick = {
                                if (visibleMarksMs.isEmpty()) return@FilledTonalButton
                                val target = when (val c = chainIdx) {
                                    null -> baseIdxFromPlayhead().coerceAtLeast(0)      // first press: go to "current/previous"
                                    else -> (c - 1).coerceAtLeast(0)                    // keep walking back
                                }
                                chainIdx = target
                                goToEvent(target)
                            },
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MenuColors.presetButton,
                                contentColor = MenuColors.onPresetButton,
                                disabledContainerColor = MenuColors.presetButton.copy(alpha = 0.38f),
                                disabledContentColor = MenuColors.onPresetButton.copy(alpha = 0.38f)
                            )
                        ) { Text("«") }

                        FilledTonalButton(
                            enabled = nextEnabled,
                            onClick = {
                                if (visibleMarksMs.isEmpty()) return@FilledTonalButton
                                val base = baseIdxFromPlayhead()
                                val initial = (base + 1).coerceAtMost(visibleMarksMs.lastIndex) // first press: go to next
                                val target = when (val c = chainIdx) {
                                    null -> initial
                                    else -> (c + 1).coerceAtMost(visibleMarksMs.lastIndex)
                                }
                                chainIdx = target
                                goToEvent(target)
                            },
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MenuColors.presetButton,
                                contentColor = MenuColors.onPresetButton,
                                disabledContainerColor = MenuColors.presetButton.copy(alpha = 0.38f),
                                disabledContentColor = MenuColors.onPresetButton.copy(alpha = 0.38f)
                            )
                        ) { Text("»") }
                    }


                }


                Slider(
                    value = if (isDragging) dragF else sliderValue.coerceIn(0f, 1f),
                    onValueChange = { f ->
                        isDragging = true
                        chainIdx = null
                        dragF = f
                        // no immediate seek here — throttled collector above will handle it
                    },
                    onValueChangeFinished = {
                        // trailing seek to the exact final position
                        if (seekMaxMs > 0) {
                            val target = (dragF.coerceIn(0f, 1f) * seekMaxMs).toLong()
                            if (abs(target - lastSentSeekMs) >= 50L) {
                                seekToClamped(target)
                                lastSentSeekMs = target
                            }
                        }
                        isDragging = false
                    },
                    valueRange = 0f..1f,
                    colors = SliderDefaults.colors(
                        thumbColor = MenuColors.sliderThumb,
                        activeTrackColor = MenuColors.sliderTrackActive,
                        inactiveTrackColor = MenuColors.sliderTrack
                    )
                )




                // Ticks also scale to seekMaxMs, so they line up with the slider/thumb
                val markerFractions = remember(visibleMarksMs, seekableMaxMs) {
                    if (seekableMaxMs <= 0) emptyList()
                    else visibleMarksMs.map { it.toFloat() / seekableMaxMs.toFloat() }
                }

                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(16.dp)
                        .pointerInput(markerFractions, seekableMaxMs) {
                            detectTapGestures { pos ->
                                if (seekableMaxMs <= 0) return@detectTapGestures
                                val w = size.width
                                val x = pos.x
                                val nearest = markerFractions.withIndex()
                                    .minByOrNull { (_, f) -> abs(x - f * w) }
                                if (nearest != null && abs(x - nearest.value * w) <= tickHitPx) {
                                    goToEvent(nearest.index)
                                } else {
                                    val frac = (x / w).coerceIn(0f, 1f)
                                    chainIdx = null
                                    seekToClamped((frac * seekableMaxMs).toLong())
                                }
                            }
                        }
                ) {
                    Canvas(Modifier.matchParentSize().padding(horizontal = 12.dp)) {
                        // baseline
                        drawRect(
                            color = Color.White.copy(alpha = 0.25f),
                            size = Size(size.width, 2f),
                            topLeft = Offset(0f, size.height / 2f - 1f)
                        )
                        // ticks (highlight currentIdx)
                        markerFractions.forEachIndexed { i, f ->
                            val x = f * size.width
                            val height = if (i == currentIdx) 16f else 12f
                            drawRect(
                                color = Color.White,
                                topLeft = Offset(x - 1f, (size.height / 2f) - height / 2f),
                                size = Size(2f, height)
                            )
                        }
                        // live-edge notch
                        if (hasProgress) {
                            val x = size.width
                            drawRect(
                                color = Color.White.copy(alpha = 0.6f),
                                topLeft = Offset(x - 1f, 0f),
                                size = Size(2f, size.height)
                            )
                        }
                    }
                }



                val posS = (positionMs / 1000)
                val durS = (durationMs / 1000)

                // Compute times for the label
                val curMsClamped = min(positionMs, seekMaxMs)
                val endAnchorMs  = if (seekMaxMs > 0) seekMaxMs else durationMs
                val extra = if (hasProgress) " • Grabación en curso" else ""

                // Absolute for normal recordings, relative for summary
                val labelText = if (startEpochRef != null) {
                    val fmt = remember {
                        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).apply {
                            timeZone = TimeZone.getTimeZone(SERVER_TZ_ID)
                        }
                    }
                    val curAbs = startEpochRef + curMsClamped
                    val endAbs = startEpochRef + endAnchorMs
                    "${fmt.format(Date(curAbs))} / ${fmt.format(Date(endAbs))}$extra"
                } else {
                    fun fmtHms(ms: Long): String {
                        val totalS = (ms / 1000).coerceAtLeast(0)
                        val h = totalS / 3600
                        val m = (totalS % 3600) / 60
                        val s = totalS % 60
                        return String.format("%d:%02d:%02d", h, m, s)
                    }
                    "${fmtHms(curMsClamped)} / ${fmtHms(endAnchorMs)}"
                }


                Text(
                    text = labelText,
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }


        val endAnchorMs = if (seekMaxMs > 0) seekMaxMs else durationMs
        val curClamped  = min(positionMs, endAnchorMs)
        val END_EPSILON_MS = 1200L
        val nearEnd = endAnchorMs > 0 && (endAnchorMs - curClamped) <= END_EPSILON_MS && !isLoading


        /*val showDebug = true
        if (showDebug) {
            Surface(
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                color = Color(0x66000000)
            ) {
                Text(
                    text = buildString {
                        appendLine("hasNext=$hasNext")
                        appendLine("completed=$isCompleted")
                        appendLine("nearEnd=$nearEnd")
                        appendLine("pos=$positionMs ms")
                        appendLine("dur=$durationMs ms")
                        appendLine("seekMax=$seekMaxMs ms")
                        appendLine("endAnchor=$endAnchorMs ms")
                    },
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(8.dp)
                )
            }
        }*/

        /*val showDebug = true
        if (showDebug) {
            Surface(
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                color = Color(0x66000000)
            ) {
                Text(
                    text = buildString {
                        appendLine("hasProgress=$hasProgress")
                        appendLine("recSoFarMs=$recordingSoFarMs")
                        appendLine("durationMs=$durationMs")
                        appendLine("seekMaxMs=$seekMaxMs")
                    },
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(8.dp)
                )
            }
        }*/



        if ((isCompleted || (nearEnd && !hasProgress)) && hasNext) {
            Button(
                onClick = { onNextClick?.invoke() },
                modifier = Modifier.align(Alignment.TopStart).padding(12.dp)
            ) { Text("Siguiente video >") }
        }



        if (isLoading) {
            Text("Cargando…", color = Color.White, modifier = Modifier.align(Alignment.Center))
        }
        LaunchedEffect(isLoading) { if (isLoading) controlsVisible = true }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching {
                ijk.stop(); ijk.reset(); ijk.release()
                IjkMediaPlayer.native_profileEnd()
            }
        }
    }
}







