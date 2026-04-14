package com.example.camara

import android.content.Context
import android.content.pm.ActivityInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.math.abs

import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.material3.ButtonDefaults

import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

import tv.danmaku.ijk.media.player.IjkMediaPlayer
import tv.danmaku.ijk.media.player.IjkMediaPlayer.native_profileBegin

import androidx.compose.ui.zIndex

import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import android.util.Base64
import androidx.compose.foundation.border
import androidx.compose.ui.platform.LocalLayoutDirection
import com.example.camara.recordings.api.RecApiClient
import com.example.camara.recordings.api.Recording
import com.example.camara.recordings.api.SERVER_TZ_ID
import com.example.camara.recordings.player.IjkHttpPlayerWithControls
import com.example.camara.recordings.ui.RecordingsScreen
import com.example.camara.recordings.util.parseServerIsoToEpochMs
import org.json.JSONArray

import kotlinx.coroutines.selects.select

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone


//import android.widget.Toast




class MainActivity : ComponentActivity() {

    // ===== Shared camera template (each configured camera fills host/ports/creds at runtime) =====
    private val defaultRtspTemplate =
        "rtsp://{user}:{pass}@{host}:{rtspPort}/user={user}_password={pass}_channel=1_stream=1.sdp?real_stream"

    private val recProbeTrigger = MutableStateFlow(0)

    // Small HTTP client for the public-IP fetch
    private val http = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val prefsNet by lazy {
        getSharedPreferences(PREFS_NET, MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        val secureStore = SecureConfigStore(this)
        secureStore.migrateLegacyPresetNamesIfNeeded()
        val existingBackend = secureStore.loadBackend()

        if (!secureStore.isConfigured()) {
            setContent {
                MaterialTheme {
                    SetupScreen(
                        initialBackend = existingBackend,
                        onSave = { backend: BackendConfig, catalog: List<CameraCatalogItem>, cams: List<CameraCredentials>, nets: List<CameraNetworkConfig> ->
                            secureStore.saveBackend(backend)
                            secureStore.saveCameraCatalog(catalog)
                            secureStore.saveCameraCredentials(cams)
                            secureStore.saveCameraNetworkConfigs(nets)
                            RecApiClient.init(backend.baseUrl, backend.apiKey)
                            recreate()
                        }
                    )
                }
            }
            return
        }

        val backendCfg = requireNotNull(secureStore.loadBackend()) {
            "No se pudo cargar la configuración del backend"
        }
        RecApiClient.init(backendCfg.baseUrl, backendCfg.apiKey)

        val cameraCatalog = secureStore.loadCameraCatalog()
        val cameraIds = cameraCatalog.map { it.cameraId }
        val catalogById = cameraCatalog.associateBy { it.cameraId }
        val credsById = secureStore.loadCameraCredentials(cameraIds).associateBy { it.cameraId }
        val netsById = secureStore.loadCameraNetworkConfigs(cameraIds).associateBy { it.cameraId }

        val editableConfigsById = cameraIds.mapNotNull { cameraId ->
            val meta = catalogById[cameraId] ?: return@mapNotNull null
            val cred = credsById[cameraId] ?: return@mapNotNull null
            val net = netsById[cameraId] ?: return@mapNotNull null
            EditableCameraConfig(
                cameraId = cameraId,
                name = meta.name,
                username = cred.username,
                password = cred.password,
                localHost = net.localHost,
                remoteHost = net.remoteHost,
                localRtspPort = net.localRtspPort,
                localOnvifPort = net.localOnvifPort,
                remoteRtspPort = net.remoteRtspPort,
                remoteOnvifPort = net.remoteOnvifPort
            )
        }.associateBy { it.cameraId }

        val effectiveCameraConfigs = cameraIds.mapNotNull { cameraId ->
            val meta = catalogById[cameraId]
            val cred = credsById[cameraId]
            val net = netsById[cameraId]
            when {
                meta == null -> {
                    Log.w("MainActivity", "Falta metadata para $cameraId; se omite cámara")
                    null
                }
                cred == null -> {
                    Log.w("MainActivity", "Faltan credenciales para $cameraId; se omite cámara")
                    null
                }
                net == null -> {
                    Log.w("MainActivity", "Falta configuración de red para $cameraId; se omite cámara")
                    null
                }
                else -> {
                    val rtspUrl = fillRtspTemplate(
                        template = defaultRtspTemplate,
                        username = cred.username,
                        password = cred.password,
                        host = net.localHost,
                        rtspPort = net.localRtspPort
                    )
                    CameraConfig(
                        id = cameraId,
                        name = meta.name,
                        baseRtspUrl = rtspUrl,
                        localHost = net.localHost,
                        localRtspPort = net.localRtspPort,
                        localOnvifPort = net.localOnvifPort,
                        remoteHost = net.remoteHost,
                        remoteRtspPort = net.remoteRtspPort,
                        remoteOnvifPort = net.remoteOnvifPort
                    )
                }
            }
        }

        // 1) Render UI immediately with a neutral state
        setContent {
            MaterialTheme {
                var recordingsAvailable by remember { mutableStateOf(false) }
                // cameras will be filled after fast resolve
                var cameras by remember { mutableStateOf<List<Camera>>(emptyList()) }

                // very quick optional probe for recordings server (Wi‑Fi only)
                LaunchedEffect(Unit) {
                    recordingsAvailable = if (isOnWifi(this@MainActivity)) {
                        quickHealthCheck(http)
                    } else false
                }

                // Re-run the probe at start and whenever the trigger increments
                val trigger by recProbeTrigger.collectAsState(initial = 0)

                LaunchedEffect(trigger) {
                    recordingsAvailable = hasLanRecordings()
                }

                App(
                    cameras = cameras,
                    recordingsAvailable = recordingsAvailable,
                    backendConfig = backendCfg,
                    editableConfigsById = editableConfigsById,
                    onSaveBackendConfig = { backend ->
                        secureStore.saveBackend(backend)
                        RecApiClient.init(backend.baseUrl, backend.apiKey)
                        recreate()
                    },
                    onAddCamera = { newCam ->
                        val catalog = secureStore.loadCameraCatalog().toMutableList()
                        val credsMap = secureStore.loadCameraCredentials().associateBy { it.cameraId }.toMutableMap()
                        val netsMap = secureStore.loadCameraNetworkConfigs().associateBy { it.cameraId }.toMutableMap()

                        val nextNumber = (
                                catalog.mapNotNull { it.cameraId.removePrefix("cam-").toIntOrNull() }.maxOrNull() ?: 0
                                ) + 1
                        val newId = "cam-$nextNumber"

                        catalog += CameraCatalogItem(
                            cameraId = newId,
                            name = newCam.name.trim().ifBlank { "Cámara $nextNumber" }
                        )
                        credsMap[newId] = CameraCredentials(
                            cameraId = newId,
                            username = newCam.username.trim(),
                            password = newCam.password
                        )
                        netsMap[newId] = CameraNetworkConfig(
                            cameraId = newId,
                            localHost = newCam.localHost.trim(),
                            localRtspPort = newCam.localRtspPort,
                            localOnvifPort = newCam.localOnvifPort,
                            remoteHost = newCam.remoteHost?.trim()?.takeIf { it.isNotBlank() },
                            remoteRtspPort = newCam.remoteRtspPort,
                            remoteOnvifPort = newCam.remoteOnvifPort
                        )

                        secureStore.saveCameraCatalog(catalog)
                        secureStore.saveCameraCredentials(
                            catalog.mapNotNull { item -> credsMap[item.cameraId] }
                        )
                        secureStore.saveCameraNetworkConfigs(
                            catalog.mapNotNull { item -> netsMap[item.cameraId] }
                        )

                        recreate()
                    },
                    onSaveCameraConfig = { edited ->
                        val catalog = secureStore.loadCameraCatalog().toMutableList()
                        val credsMap = secureStore.loadCameraCredentials().associateBy { it.cameraId }.toMutableMap()
                        val netsMap = secureStore.loadCameraNetworkConfigs().associateBy { it.cameraId }.toMutableMap()

                        val idx = catalog.indexOfFirst { it.cameraId == edited.cameraId }
                        if (idx >= 0) {
                            catalog[idx] = catalog[idx].copy(
                                name = edited.name.trim().ifBlank { catalog[idx].name }
                            )
                        }

                        credsMap[edited.cameraId] = CameraCredentials(
                            cameraId = edited.cameraId,
                            username = edited.username.trim(),
                            password = edited.password
                        )

                        netsMap[edited.cameraId] = CameraNetworkConfig(
                            cameraId = edited.cameraId,
                            localHost = edited.localHost.trim(),
                            localRtspPort = edited.localRtspPort,
                            localOnvifPort = edited.localOnvifPort,
                            remoteHost = edited.remoteHost?.trim()?.takeIf { it.isNotBlank() },
                            remoteRtspPort = edited.remoteRtspPort,
                            remoteOnvifPort = edited.remoteOnvifPort
                        )

                        secureStore.saveCameraCatalog(catalog)
                        secureStore.saveCameraCredentials(
                            catalog.mapNotNull { item -> credsMap[item.cameraId] }
                        )
                        secureStore.saveCameraNetworkConfigs(
                            catalog.mapNotNull { item -> netsMap[item.cameraId] }
                        )

                        recreate()
                    },
                    onDeleteCamera = { cameraId ->
                        secureStore.removeCamera(cameraId)
                        recreate()
                    }
                )

                // 2) Kick off fast network resolution right after UI shows
                LaunchedEffect(Unit) {
                    val cachedIp = loadLastPublicIp() // returns quickly; your existing impl
                    val onWifi = isOnWifi(this@MainActivity)

                    // Resolve all cameras concurrently with an overall cap
                    val fast = FastResolver()
                    val envs: List<ResolvedEnv> = coroutineScope {
                        val cap = if (onWifi) OVERALL_CAP_MS_WIFI else OVERALL_CAP_MS_CELL
                        effectiveCameraConfigs.map { cfg ->
                            async(Dispatchers.IO) { fast.decideEnvFast(cfg, cachedIp, cap, onWifi) }
                        }.awaitAll()
                    }

                    // Map to UI model
                    cameras = envs.mapIndexed { i, env ->
                        Camera(
                            id = effectiveCameraConfigs[i].id,
                            name = effectiveCameraConfigs[i].name,
                            rtspUrl = env.rtspUrl,
                            onvifPort = env.onvifPort
                        )
                    }

                    // 3) Background: refresh public IP (not blocking the UI)
                    lifecycleScope.launch(Dispatchers.IO) {
                        if (ENABLE_GITHUB_PUBLIC_IP_REFRESH) {
                            val fetched = fetchPublicIpFromGithub()
                            if (!fetched.isNullOrBlank()) saveLastPublicIp(fetched)
                        }
                    }
                }
            }
        }

        // Optional: react to network changes (fast re-resolve)
        registerNetworkCallbackForReResolve()
    }


    // ===== Per-camera resolution =====
    private fun resolveEnvironmentFor(
        cfg: CameraConfig,
        publicIp: String?,
        onWifi: Boolean
    ): ResolvedEnv {
        // --- Helper: a couple of quick retries with slightly longer timeouts ---
        fun reach(host: String, port: Int): Boolean {
            if (canReach(host, port, timeoutMs = 900)) return true
            if (canReach(host, port, timeoutMs = 1400)) return true
            return false
        }

        // We probe LAN regardless of onWifi (helps with VPNs/bridged links).
        val rtspOk1  = reach(cfg.localHost, cfg.localRtspPort)
        val onvifOk1 = if (!rtspOk1) reach(cfg.localHost, cfg.localOnvifPort) else false

        // If Wi-Fi says "yes" but probes failed, give LAN one last slow chance.
        val rtspOk2  = if (onWifi && !rtspOk1 && !onvifOk1) canReach(cfg.localHost, cfg.localRtspPort, 2000) else false
        val onvifOk2 = if (onWifi && !rtspOk1 && !onvifOk1 && !rtspOk2) canReach(cfg.localHost, cfg.localOnvifPort, 2000) else false

        val reachLocal = rtspOk1 || onvifOk1 || rtspOk2 || onvifOk2

        if (reachLocal) {
            Log.i(
                "Env",
                "HOME: onWifi=$onWifi reach(rtsp=$rtspOk1/$rtspOk2, onvif=$onvifOk1/$onvifOk2) host=${cfg.localHost}"
            )
            return ResolvedEnv(
                rtspUrl   = cfg.baseRtspUrl,          // 192.168.x.x:554...
                onvifPort = cfg.localOnvifPort,       // 8899 on LAN
                mode      = NetMode.HOME
            )
        }

        val wanHost = effectiveWanHost(cfg, publicIp)
        if (wanHost != null) {
            val wanRtsp = rewriteRtspHostPort(cfg.baseRtspUrl, wanHost, cfg.remoteRtspPort)
            val mode = if (!cfg.remoteHost.isNullOrBlank()) NetMode.AWAY_FALLBACK else NetMode.AWAY_GITHUB
            Log.i(
                "Env",
                "AWAY_REMOTE: onWifi=$onWifi no-local-reach; using host=$wanHost rtspPort=${cfg.remoteRtspPort}"
            )
            return ResolvedEnv(
                rtspUrl   = wanRtsp,                  //  <WAN_HOST>:remoteRtspPort
                onvifPort = cfg.remoteOnvifPort,      //  remote PTZ port
                mode      = mode,
                publicIp  = wanHost
            )
        }

        // Last resort: no WAN host available; keep base URL (may still work on some setups)
        Log.w("Env", "AWAY_FALLBACK: onWifi=$onWifi no-local-reach; no WAN host available")
        return ResolvedEnv(
            rtspUrl   = cfg.baseRtspUrl,             // leaves LAN URL; better than nothing
            onvifPort = cfg.remoteOnvifPort,
            mode      = NetMode.AWAY_FALLBACK
        )
    }

    // Fast, tolerant LAN-only probe
    private suspend fun hasLanRecordings(): Boolean = withContext(Dispatchers.IO) {
        // try fast HTTP twice, then TCP fallback
        val host = recHostForProbe()
        val port = recPortForProbe()
        httpHealth(host, port, recHealthUrl(), 400, 500) ||
                httpHealth(host, port, recHealthUrl(), 700, 800) ||
                canReach(host, port, 800)
    }

    private fun httpHealth(
        host: String,
        port: Int,
        fullUrl: String,          // e.g. "http://backend.local:8080/api/health"
        connectMs: Long,
        readMs: Long
    ): Boolean {
        val fast = http.newBuilder()
            .connectTimeout(connectMs, TimeUnit.MILLISECONDS)
            .readTimeout(readMs, TimeUnit.MILLISECONDS)
            .callTimeout(connectMs + readMs + 300, TimeUnit.MILLISECONDS)
            .build()
        return runCatching {
            val req = Request.Builder().url(fullUrl).get().build()
            fast.newCall(req).execute().use { it.isSuccessful }
        }.getOrElse { false }
    }

    private fun recHealthUrl(): String {
        return runCatching {
            val base = RecApiClient.baseUrl.trim().trimEnd('/')
            if (base.isBlank()) REC_HEALTH_URL_FALLBACK else "$base/api/health"
        }.getOrElse { REC_HEALTH_URL_FALLBACK }
    }
    private fun registerNetworkCallbackForReResolve() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                // kick both camera re-resolve (your logic) and recordings re-probe
                recProbeTrigger.update { it + 1 }
            }
            override fun onLost(network: android.net.Network) {
                recProbeTrigger.update { it + 1 } // will likely flip to false
            }
        })
    }

    // ===== Fetch a *map* cam-id -> public IP from GitHub =====
    private fun fetchPublicIpFromGithub(): String? {
        if (!ENABLE_GITHUB_PUBLIC_IP_REFRESH || GH_PUBLIC_IP_URL.isBlank()) return null
        return runCatching {
            val req = Request.Builder().url(GH_PUBLIC_IP_URL).get().build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val body = resp.body?.string() ?: return@runCatching null

                // GitHub "contents" API returns JSON with base64 "content"
                val root = JSONObject(body)
                val encoded = root.optString("content", "")
                if (encoded.isBlank()) return@runCatching null

                val decoded = Base64.decode(encoded, Base64.DEFAULT)
                val json = JSONObject(String(decoded))

                // Prefer IPv4; if missing/blank, use IPv6
                val v4 = json.optString("ipv4", "").trim()
                if (v4.isNotEmpty()) return@runCatching v4

                val v6 = json.optString("ipv6", "").trim()
                if (v6.isNotEmpty()) return@runCatching v6

                null
            }
        }.getOrNull()
    }


    // ===== per-camera cache helpers =====
    private fun saveLastPublicIp(ip: String) {
        getSharedPreferences(PREFS_NET, MODE_PRIVATE)
            .edit().putString(KEY_LAST_PUBLIC_IP, ip).apply()
    }

    private fun loadLastPublicIp(): String? {
        return getSharedPreferences(PREFS_NET, MODE_PRIVATE)
            .getString(KEY_LAST_PUBLIC_IP, null)
    }
    private fun recHostForProbe(): String {
        return runCatching { URI(RecApiClient.baseUrl).host }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: REC_LAN_HOST
    }

    private fun recPortForProbe(): Int {
        return runCatching {
            val u = URI(RecApiClient.baseUrl)
            when {
                u.port != -1 -> u.port
                u.scheme.equals("https", ignoreCase = true) -> 443
                else -> 80
            }
        }.getOrElse { REC_LAN_PORT }
    }

    private fun recordingsServerReachable(): Boolean {
        // Prefer quick HTTP to /api/health (no API key needed). Fallback to raw TCP.
        val host = recHostForProbe()
        val port = recPortForProbe()
        return runCatching {
            val req = Request.Builder().url(recHealthUrl()).get().build()
            http.newCall(req).execute().use { it.isSuccessful }
        }.getOrElse { canReach(host, port, timeoutMs = 700) }
    }

    private fun isOnWifi(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun quickHealthCheck(http: OkHttpClient): Boolean {
        // Super-short timeouts so it never stalls the UI
        val fast = http.newBuilder()
            .connectTimeout(300, TimeUnit.MILLISECONDS)
            .readTimeout(300, TimeUnit.MILLISECONDS)
            .callTimeout(600, TimeUnit.MILLISECONDS)
            .build()

        val req = Request.Builder().url(recHealthUrl()).get().build()
        return runCatching {
            fast.newCall(req).execute().use { it.isSuccessful }
        }.getOrElse {
            // very fast TCP fallback
            canReach(recHostForProbe(), recPortForProbe(), timeoutMs = 300)
        }
    }

    private inner class FastResolver {
        suspend fun decideEnvFast(
            cfg: CameraConfig,
            cachedPublicIp: String?,
            overallCapMs: Long,
            onWifi: Boolean
        ): ResolvedEnv = withContext(Dispatchers.IO) {
            // Race LAN vs WAN using short probes
            val scope = CoroutineScope(coroutineContext + SupervisorJob())

            val lan = scope.async {
                // RTSP only for detection; keep it cheap
                probeTwice(cfg.localHost, cfg.localRtspPort)
            }

            val wan = scope.async {
                val wanHost = effectiveWanHost(cfg, cachedPublicIp)
                if (wanHost.isNullOrBlank()) false else probeTwice(wanHost, cfg.remoteRtspPort)
            }

            val winner = withTimeoutOrNull(overallCapMs) {
                var result: NetMode? = null
                var lanOpen = true
                var wanOpen = true
                while (result == null && (lanOpen || wanOpen)) {
                    select<Unit> {
                        if (lanOpen) lan.onAwait { ok ->
                            lanOpen = false
                            if (ok) result = NetMode.HOME
                        }
                        if (wanOpen) wan.onAwait { ok ->
                            wanOpen = false
                            if (ok) result = NetMode.AWAY_GITHUB
                        }
                    }
                }
                result
            }

            when (winner) {
                NetMode.HOME -> {
                    Log.i("Env", "HOME (fast)")
                    ResolvedEnv(
                        rtspUrl = cfg.baseRtspUrl,
                        onvifPort = cfg.localOnvifPort,
                        mode = NetMode.HOME
                    )
                }
                NetMode.AWAY_GITHUB -> {
                    val wanHost = effectiveWanHost(cfg, cachedPublicIp)!!
                    Log.i("Env", "AWAY_REMOTE (fast) host=$wanHost port=${cfg.remoteRtspPort}")
                    ResolvedEnv(
                        rtspUrl = rewriteRtspHostPort(cfg.baseRtspUrl, wanHost, cfg.remoteRtspPort),
                        onvifPort = cfg.remoteOnvifPort,
                        mode = NetMode.AWAY_GITHUB,
                        publicIp = wanHost
                    )
                }
                else -> {
                    // Nothing won within the cap → prefer configured/cached WAN host if present, else fallback
                    effectiveWanHost(cfg, cachedPublicIp)?.let { wanHost ->
                        ResolvedEnv(
                            rtspUrl = rewriteRtspHostPort(cfg.baseRtspUrl, wanHost, cfg.remoteRtspPort),
                            onvifPort = cfg.remoteOnvifPort,
                            mode = if (!cfg.remoteHost.isNullOrBlank()) NetMode.AWAY_FALLBACK else NetMode.AWAY_CACHE,
                            publicIp = wanHost
                        )
                    } ?: run {
                        ResolvedEnv(
                            rtspUrl = cfg.baseRtspUrl,
                            onvifPort = cfg.remoteOnvifPort,
                            mode = NetMode.AWAY_FALLBACK
                        )
                    }
                }
            }
        }

        private fun probeTwice(host: String, port: Int): Boolean {
            if (canReach(host, port, FAST_CONNECT_MS_1)) return true
            if (canReach(host, port, FAST_CONNECT_MS_2)) return true
            return false
        }
    }

}


/* ---------------------------------- UI ----------------------------------- */

@Composable
fun IjkRtspWithPtz(
    cameraId: String,
    rtspUrl: String,
    onvifPort: Int,
    forceTcp: Boolean = true,
    useHwDecodeHevc: Boolean = false, // set true if your cam is H.265
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val secureStore = remember(context) { SecureConfigStore(context) }

    // --- Fallback targets (LAN restream + WAN/nginx ONVIF) -- only for cam-2
    val recBackendHost = remember {
        runCatching { URI(RecApiClient.baseUrl).host.orEmpty() }.getOrDefault("")
    }
    val CAM2_RESTREAM_RTSP = remember(recBackendHost) {
        if (recBackendHost.isBlank()) "" else "rtsp://$recBackendHost:8554/cam2"
    }
    val CAM2_RESTREAM_HOST = recBackendHost
    val CAM2_WAN_ONVIF_PORT = 18901

    // Keep original credentials (username/password) from the *initial* URL
    val baseCamInfo = remember(cameraId) { parseCameraInfoFromRtsp(rtspUrl) }

    // We’ll drive playback & PTZ from *current* values so we can swap on the fly.
    var currentRtsp by remember(rtspUrl) { mutableStateOf(rtspUrl) }
    var currentOnvifPort by remember(onvifPort) { mutableStateOf(onvifPort) }
    val currentHost = remember(currentRtsp) { parseCameraInfoFromRtsp(currentRtsp)?.host.orEmpty() }

    // PTZ client: host follows the stream host; auth stays from base RTSP
    val ptzClient = remember(cameraId, currentHost, currentOnvifPort) {
        OnvifPtzClient(
            host = currentHost,
            port = currentOnvifPort,
            username = baseCamInfo?.username.orEmpty(),
            password = baseCamInfo?.password.orEmpty()
        )
    }
    var ptzReady by remember { mutableStateOf(false) }
    var presets by remember { mutableStateOf<List<PtzPreset>>(emptyList()) }
    var presetNameMap by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var ptzMenuOpen by remember { mutableStateOf(false) }
    var presetMenuOpen by remember { mutableStateOf(false) }
    var presetEditorOpen by remember { mutableStateOf(false) }

    // Load presets from camera + load local name map for that camera
    LaunchedEffect(cameraId, ptzClient) {
        // reset first (so UI shows a clean slate)
        ptzReady = false
        presets = emptyList()
        presetNameMap = emptyMap()

        // always load the names for this camera id
        presetNameMap = loadPresetNameMap(context, cameraId)

        // (re)initialize PTZ for this camera
        if (ptzClient != null && ptzClient.init()) {
            ptzReady = true
            presets = ptzClient.getPresets()
        }
    }



    // --- IJK state ---
    val ijk = remember {
        IjkMediaPlayer().apply {
            IjkMediaPlayer.loadLibrariesOnce(null)
            native_profileBegin("libijkplayer.so")
        }
    }
    var surface: android.view.Surface? by remember { mutableStateOf(null) }

    var isLoading by remember { mutableStateOf(true) }
    var restreamTried by remember(cameraId, rtspUrl) { mutableStateOf(false) }
    val mainHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }

    // Listeners
    DisposableEffect(ijk) {
        ijk.setOnInfoListener { _, what, _ ->
            when (what) {
                IjkMediaPlayer.MEDIA_INFO_BUFFERING_START -> isLoading = true
                IjkMediaPlayer.MEDIA_INFO_BUFFERING_END,
                IjkMediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START -> isLoading = false
            }
            false
        }
        ijk.setOnErrorListener { _, _, _ ->
            isLoading = false

            // 🔁 LAN fallback for cam-2: switch to restream + nginx ONVIF once
            val initialHost = parseCameraInfoFromRtsp(rtspUrl)?.host.orEmpty()
            val isLanInitial = isLikelyLanHost(initialHost)
            val alreadyRestream = currentHost == CAM2_RESTREAM_HOST

            if (!restreamTried && cameraId == "cam-2" && isLanInitial && !alreadyRestream && CAM2_RESTREAM_RTSP.isNotBlank()) {
                restreamTried = true
                mainHandler.post {
                    currentRtsp = CAM2_RESTREAM_RTSP
                    currentOnvifPort = CAM2_WAN_ONVIF_PORT  // use nginx ONVIF (WAN port) even on LAN
                    isLoading = true
                    // The prepare() effect below will re-run automatically
                }
                true // we handled the error by switching sources
            } else {
                false
            }
        }
        onDispose { }
    }

    // (Optional) tiny probe log – now pointed at currentRtsp
    LaunchedEffect(currentRtsp) {
        val (h, p) = hostPortFromRtsp(currentRtsp)
        if (h.isNotBlank() && p > 0) {
            val reply = withContext(Dispatchers.IO) { probeRtspGreeting(h, p) }
            Log.e("RTSP-Probe", "Probe $h:$p -> ${reply.firstLine()}")
        }
    }

    // Prepare / play with currentRtsp
    LaunchedEffect(currentRtsp, forceTcp, useHwDecodeHevc, surface) {
        if (surface == null) return@LaunchedEffect
        isLoading = true

        ijk.reset()
        ijk.setSurface(surface)
        ijk.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared", 1L)
        ijk.applyLowLatencyRtsp(useTcp = forceTcp, enableHevcHw = useHwDecodeHevc)
        ijk.setDataSource(currentRtsp)
        ijk.prepareAsync()
    }


    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    // React to app going to background/foreground to avoid the rtsp stream freezing
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> {
                    // App no longer visible – free network/decoders so the stream doesn’t stall.
                    runCatching { ijk.stop(); ijk.reset() }
                }
                androidx.lifecycle.Lifecycle.Event.ON_START -> {
                    // Visible again – re-prepare (if the surface is still there).
                    if (surface != null) {
                        isLoading = true
                        ijk.reset()
                        ijk.setSurface(surface)
                        ijk.applyLowLatencyRtsp(useTcp = forceTcp, enableHevcHw = useHwDecodeHevc)
                        ijk.setDataSource(currentRtsp)
                        ijk.prepareAsync()
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Cleanup
    DisposableEffect(Unit) {
        onDispose {
            runCatching {
                ijk.stop()
                ijk.reset()
                ijk.release()
                IjkMediaPlayer.native_profileEnd()
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        // --- Video layer ---
        AndroidView(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxHeight()
                .aspectRatio(16f / 9f, matchHeightConstraintsFirst = true),
            factory = { ctx ->
                android.view.TextureView(ctx).apply {
                    keepScreenOn = true
                    surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
                            surface = android.view.Surface(st)
                            ijk.setSurface(surface)
                        }
                        override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) {}
                        override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean {
                            ijk.setSurface(null)
                            surface?.release()
                            surface = null
                            return true
                        }
                        override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
                    }
                }
            }
        )

        // --- Drag-to-move + PTZ menus: reuse your existing composables ---
        DragToMoveLayer(ptzClient = ptzClient, ptzReady = ptzReady, modifier = Modifier.fillMaxSize())

        // Loading overlay ON TOP
        if (isLoading) {
            Box(
                Modifier
                    .matchParentSize()
                    .zIndex(10f) // ensure it’s above the TextureView
                    .background(Color(0x66000000))
            ) {
                Text(
                    "Cargando…",
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }

        // Bottom-end PTZ button/menu
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(12.dp)
        ) {
            if (!ptzMenuOpen) {
                SmallFloatingActionButton(
                    onClick = { ptzMenuOpen = true },
                    containerColor = MenuColors.accent,
                    contentColor = MenuColors.onAccent

                ) { Text("↕↔") }
            } else {
                Popup(alignment = Alignment.BottomEnd) {
                    Box {
                        PtzButtons(ptzClient = ptzClient, ptzReady = ptzReady, modifier = Modifier.padding(12.dp))
                        SmallFloatingActionButton(
                            onClick = { ptzMenuOpen = false },
                            modifier = Modifier.align(Alignment.TopEnd),
                            containerColor = MenuColors.accent,
                            contentColor = MenuColors.onAccent
                        ) { Text("×") }
                    }
                }
            }
        }

        // Top-start preset menu
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(12.dp)
        ) {
            if (!presetMenuOpen) {
                SmallFloatingActionButton(
                    onClick = { presetMenuOpen = true },
                    containerColor = MenuColors.accent,
                    contentColor = MenuColors.onAccent
                ) { Text("★") }
            } else {
                Popup(alignment = Alignment.TopStart) {
                    PresetMenu(
                        presets = presets,
                        localNames = presetNameMap,
                        onSelect = { token -> if (ptzReady && ptzClient != null) ptzClient.gotoPreset(token); presetMenuOpen = false },
                        onEditNames = { presetEditorOpen = true },
                        onClose = { presetMenuOpen = false },
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }

        if (presetEditorOpen) {
            PresetNameEditorDialog(
                presets = presets,
                localNames = presetNameMap,
                onDismiss = { presetEditorOpen = false },
                onSave = { updatedNames ->
                    secureStore.savePresetAliases(cameraId, updatedNames)
                    presetNameMap = secureStore.loadPresetAliases(cameraId)
                    presetEditorOpen = false
                }
            )
        }
    }
}


@Composable
private fun DragToMoveLayer(
    ptzClient: OnvifPtzClient?,
    ptzReady: Boolean,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    var origin by remember { mutableStateOf(Offset.Zero) }

    // How far from the start you need to drag to hit full speed
    val fullSpeedPx = with(density) { 140.dp.toPx() }
    val deadZonePx = with(density) { 16.dp.toPx() }
    val deadNorm = deadZonePx / fullSpeedPx

    Box(
        modifier = modifier.pointerInput(ptzReady) {
            detectDragGestures(
                onDragStart = { offset -> origin = offset },
                onDrag = { change, _ ->
                    if (!ptzReady || ptzClient == null) return@detectDragGestures
                    val d = change.position - origin
                    // Flip axes: dragging left/up moves camera left/up
                    var pan = (-d.x / fullSpeedPx).coerceIn(-1f, 1f)
                    var tilt = (-d.y / fullSpeedPx).coerceIn(-1f, 1f)
                    if (abs(pan) < deadNorm) pan = 0f
                    if (abs(tilt) < deadNorm) tilt = 0f
                    ptzClient.continuousMove(pan, tilt, 0f)
                    change.consume()
                },
                onDragEnd = { if (ptzReady && ptzClient != null) ptzClient.stop() },
                onDragCancel = { if (ptzReady && ptzClient != null) ptzClient.stop() }
            )
        }
    )
}

@Composable
private fun PtzButtons(
    ptzClient: OnvifPtzClient?,
    ptzReady: Boolean,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()

    // Hold behavior
    val SPEED = 0.7f
    val KEEPALIVE_MS = 350L   // resend ContinuousMove while held (not spammy)
    val NUDGE_MS = 280L       // tap nudge duration

    // Flip mapping
    val panLeft = +SPEED
    val panRight = -SPEED
    val tiltUp = +SPEED
    val tiltDown = -SPEED

    var holdJob by remember { mutableStateOf<Job?>(null) }

    fun startHold(pan: Float = 0f, tilt: Float = 0f) {
        if (!ptzReady || ptzClient == null) return
        ptzClient.continuousMove(pan, tilt, 0f)
        holdJob?.cancel()
        holdJob = scope.launch {
            while (isActive) {
                delay(KEEPALIVE_MS)
                ptzClient.continuousMove(pan, tilt, 0f)
            }
        }
    }

    fun stopHold() {
        holdJob?.cancel()
        holdJob = null
        if (ptzReady && ptzClient != null) ptzClient.stop()
    }

    fun nudge(pan: Float = 0f, tilt: Float = 0f) {
        if (!ptzReady || ptzClient == null) return
        ptzClient.continuousMove(pan, tilt, 0f)
        scope.launch {
            delay(NUDGE_MS)
            ptzClient.stop()
        }
    }

    @Composable
    fun HoldableButton(label: String, pan: Float, tilt: Float, modifier: Modifier = Modifier) {
        val interactionSource = remember { MutableInteractionSource() }
        LaunchedEffect(interactionSource, ptzReady) {
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> startHold(pan, tilt)
                    is PressInteraction.Release, is PressInteraction.Cancel -> stopHold()
                }
            }
        }
        Button(
            enabled = ptzReady,
            interactionSource = interactionSource,
            onClick = { nudge(pan, tilt) },
            colors = ButtonDefaults.buttonColors(
                containerColor = MenuColors.button,
                contentColor   = MenuColors.onButton
            ),
            modifier = modifier
        ) { Text(label) }
    }

    Surface(
        color = MenuColors.container,
        contentColor = MenuColors.onContainer,
        tonalElevation = 0.dp,
        shadowElevation = 8.dp,
        shape = RoundedCornerShape(14.dp),
        modifier = modifier
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(12.dp)
        ) {
            val btnMod = Modifier.size(56.dp)

            HoldableButton("↑", pan = 0f, tilt = tiltUp, modifier = btnMod)

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(vertical = 8.dp)
            ) {
                HoldableButton("←", pan = panLeft, tilt = 0f, modifier = btnMod)
                OutlinedButton(
                    enabled = ptzReady,
                    onClick = { stopHold() },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MenuColors.onContainer
                    ),
                    border = BorderStroke(1.dp, MenuColors.outline),
                    modifier = btnMod
                ) { Text("■") }

                HoldableButton("→", pan = panRight, tilt = 0f, modifier = btnMod)
            }

            HoldableButton("↓", pan = 0f, tilt = tiltDown, modifier = btnMod)
        }
    }
}

@Composable
private fun PresetMenu(
    presets: List<PtzPreset>,
    localNames: Map<String, String>,
    onSelect: (token: String) -> Unit,
    onEditNames: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MenuColors.container,
        contentColor = MenuColors.onContainer,
        tonalElevation = 0.dp,
        shadowElevation = 8.dp,
        shape = RoundedCornerShape(14.dp),
        modifier = modifier
            .widthIn(max = 260.dp) // keep it compact
            .heightIn(max = 320.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Posiciones", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = onEditNames,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MenuColors.accent
                        )
                    ) { Text("Editar") }
                    TextButton(
                        onClick = onClose,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MenuColors.accent
                        )
                    ) { Text("Cerrar") }
                }
            }
            Spacer(Modifier.height(6.dp))
            LazyColumn {
                items(presets) { p ->
                    val label = localNames[p.token] ?: p.name.ifBlank { p.token }
                    TextButton(
                        onClick = { onSelect(p.token) },
                        colors = ButtonDefaults.textButtonColors(
                            containerColor = MenuColors.presetButton,
                            contentColor = MenuColors.onPresetButton
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(label, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun PresetNameEditorDialog(
    presets: List<PtzPreset>,
    localNames: Map<String, String>,
    onDismiss: () -> Unit,
    onSave: (Map<String, String>) -> Unit
) {
    val orderedPresets = remember(presets) { presets.distinctBy { it.token } }
    val draftNames = remember(orderedPresets, localNames) {
        mutableStateMapOf<String, String>().apply {
            orderedPresets.forEach { preset ->
                this[preset.token] = localNames[preset.token].orEmpty()
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Editar posiciones") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (orderedPresets.isEmpty()) {
                    Text("No hay presets detectados todavía.")
                } else {
                    orderedPresets.forEach { preset ->
                        OutlinedTextField(
                            value = draftNames[preset.token].orEmpty(),
                            onValueChange = { draftNames[preset.token] = it },
                            label = { Text("Preset ${preset.token}") },
                            supportingText = {
                                Text("Nombre ONVIF: ${preset.name.ifBlank { preset.token }}")
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val cleaned = draftNames
                        .mapValues { (_, value) -> value.trim() }
                        .filterValues { it.isNotEmpty() }
                    onSave(cleaned)
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MenuColors.accent,
                    contentColor = MenuColors.onAccent
                )
            ) { Text("Guardar") }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MenuColors.accent
                ),
                border = BorderStroke(1.dp, MenuColors.accent)
            ) { Text("Cancelar") }
        }
    )
}

/* ----------------------------- ONVIF client ------------------------------- */

private class OnvifPtzClient(
    private val host: String,
    private val port: Int,
    private val username: String,
    private val password: String
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    //@Volatile private var deviceServiceUrlUsed: String? = null
    // Inside OnvifPtzClient class:
    @Volatile private var deviceServiceUrlUsed: String? = null


    private val http: OkHttpClient = OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .retryOnConnectionFailure(true)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        .build()

    private val soap12Media = "application/soap+xml; charset=utf-8".toMediaType()
    private val soap11Media = "text/xml; charset=utf-8".toMediaType()

    @Volatile private var ptzServiceUrl: String? = null
    @Volatile private var mediaServiceUrl: String? = null
    @Volatile private var profileToken: String? = null

    @Volatile private var lastSend = 0L
    private val sendIntervalMs = 90L

    suspend fun init(): Boolean = withContext(Dispatchers.IO) {
        try {
            // Try a few common device_service endpoints
            val candidates = buildList {
                add("http://$host:$port/onvif/device_service")
                if (port != 80) add("http://$host:80/onvif/device_service")
                if (port != 8899) add("http://$host:8899/onvif/device_service")
            }

            var capsResp: String? = null
            deviceServiceUrlUsed = null // reset each init

            for (url in candidates) {
                try {
                    capsResp = postSoap(
                        url = url,
                        action11 = "http://www.onvif.org/ver10/device/wsdl/GetCapabilities",
                        innerBody = """
                      <tds:GetCapabilities xmlns:tds="http://www.onvif.org/ver10/device/wsdl">
                        <tds:Category>All</tds:Category>
                      </tds:GetCapabilities>
                    """.trimIndent()
                    )
                    deviceServiceUrlUsed = url
                    break
                } catch (_: Exception) { /* try next */ }
            }

            // ✅ check the right variable
            if (capsResp == null || deviceServiceUrlUsed == null) return@withContext false

            mediaServiceUrl = capsResp.findTag("Media", "XAddr")
                ?: capsResp.findTag("Media2", "XAddr")
            ptzServiceUrl = capsResp.findTag("PTZ", "XAddr")

            Log.i("PTZ", "XAddr Media=$mediaServiceUrl, PTZ=$ptzServiceUrl")

            // Normalize to the host:port we actually reached
            deviceServiceUrlUsed?.let { used ->
                val base = URI(used)
                val p = if (base.port == -1) 80 else base.port
                mediaServiceUrl = mediaServiceUrl?.let { rewriteHostPort(it, base.host, p) }
                ptzServiceUrl   = ptzServiceUrl?.let   { rewriteHostPort(it, base.host, p) }
            }

            if (ptzServiceUrl.isNullOrBlank() || mediaServiceUrl.isNullOrBlank()) return@withContext false

            // GetProfiles → token
            val profilesResp = postSoap(
                url = mediaServiceUrl!!,
                action11 = "http://www.onvif.org/ver10/media/wsdl/GetProfiles",
                innerBody = """<trt:GetProfiles xmlns:trt="http://www.onvif.org/ver10/media/wsdl"/>"""
            )
            profileToken = profilesResp.findAttribute("Profiles", "token")
            !profileToken.isNullOrBlank()
        } catch (e: Exception) {
            Log.e("PTZ", "init failed: ${e.message}")
            false
        }
    }


    suspend fun getPresets(): List<PtzPreset> = withContext(Dispatchers.IO) {
        val ptzUrl = ptzServiceUrl ?: return@withContext emptyList()
        val token = profileToken ?: return@withContext emptyList()
        return@withContext try {
            val resp = postSoap(
                url = ptzUrl,
                action11 = "http://www.onvif.org/ver20/ptz/wsdl/GetPresets",
                innerBody = """
                  <tptz:GetPresets xmlns:tptz="http://www.onvif.org/ver20/ptz/wsdl">
                    <tptz:ProfileToken>${token.xml()}</tptz:ProfileToken>
                  </tptz:GetPresets>
                """.trimIndent()
            )
            resp.findPresets()
        } catch (e: Exception) {
            Log.w("PTZ", "getPresets failed: ${e.message}")
            emptyList()
        }
    }

    fun gotoPreset(presetToken: String) {
        val ptzUrl = ptzServiceUrl ?: return
        val token = profileToken ?: return
        scope.launch {
            val body = """
              <tptz:GotoPreset xmlns:tptz="http://www.onvif.org/ver20/ptz/wsdl">
                <tptz:ProfileToken>${token.xml()}</tptz:ProfileToken>
                <tptz:PresetToken>${presetToken.xml()}</tptz:PresetToken>
              </tptz:GotoPreset>
            """.trimIndent()
            try {
                postSoap(
                    url = ptzUrl,
                    action11 = "http://www.onvif.org/ver20/ptz/wsdl/GotoPreset",
                    innerBody = body
                )
            } catch (e: Exception) {
                Log.w("PTZ", "GotoPreset error: ${e.message}")
            }
        }
    }

    fun continuousMove(panSpeed: Float, tiltSpeed: Float, zoomSpeed: Float) {
        val ptzUrl = ptzServiceUrl ?: return
        val token = profileToken ?: return
        val now = System.currentTimeMillis()
        if (now - lastSend < sendIntervalMs) return
        lastSend = now

        scope.launch {
            val inner = """
              <tptz:ContinuousMove xmlns:tptz="http://www.onvif.org/ver20/ptz/wsdl">
                <tptz:ProfileToken>${token.xml()}</tptz:ProfileToken>
                <tptz:Velocity>
                  <tt:PanTilt xmlns:tt="http://www.onvif.org/ver10/schema" x="${panSpeed.fmt()}" y="${tiltSpeed.fmt()}"/>
                  <tt:Zoom xmlns:tt="http://www.onvif.org/ver10/schema" x="${zoomSpeed.fmt()}"/>
                </tptz:Velocity>
              </tptz:ContinuousMove>
            """.trimIndent()
            try {
                postSoap(
                    url = ptzUrl,
                    action11 = "http://www.onvif.org/ver20/ptz/wsdl/ContinuousMove",
                    innerBody = inner
                )
            } catch (e: Exception) {
                Log.w("PTZ", "ContinuousMove error: ${e.message}")
            }
        }
    }

    fun stop() {
        val ptzUrl = ptzServiceUrl ?: return
        val token = profileToken ?: return
        scope.launch {
            val inner = """
              <tptz:Stop xmlns:tptz="http://www.onvif.org/ver20/ptz/wsdl">
                <tptz:ProfileToken>${token.xml()}</tptz:ProfileToken>
                <tptz:PanTilt>true</tptz:PanTilt>
                <tptz:Zoom>true</tptz:Zoom>
              </tptz:Stop>
            """.trimIndent()
            try {
                postSoap(
                    url = ptzUrl,
                    action11 = "http://www.onvif.org/ver20/ptz/wsdl/Stop",
                    innerBody = inner
                )
            } catch (e: Exception) {
                Log.w("PTZ", "Stop error: ${e.message}")
            }
        }
    }

    /**
     * Try SOAP 1.2 first, fall back to SOAP 1.1 (some cameras close the connection on 1.2).
     */
    private fun postSoap(url: String, action11: String, innerBody: String): String {
        // --- SOAP 1.2 attempt ---
        runCatching {
            val body12 = soapEnvelope12(username, password, innerBody)
            val req12 = Request.Builder()
                .url(url)
                .header("Connection", "close")
                .post(body12.toRequestBody(soap12Media))
                .build()
            http.newCall(req12).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) error("HTTP ${resp.code}: $text")
                return text
            }
        }.onFailure { e ->
            Log.w("PTZ", "SOAP 1.2 failed (${e.message}), retrying SOAP 1.1…")
        }

        // --- SOAP 1.1 fallback ---
        val body11 = soapEnvelope11(username, password, innerBody)
        val req11 = Request.Builder()
            .url(url)
            .header("Connection", "close")
            .header("SOAPAction", action11)
            .post(body11.toRequestBody(soap11Media))
            .build()
        http.newCall(req11).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("HTTP ${resp.code}: $text")
            return text
        }
    }

    private fun rewriteHostPort(url: String, newHost: String, newPort: Int): String =
        try {
            val u = URI(url)
            URI(u.scheme ?: "http", u.userInfo, newHost, newPort, u.path, u.query, u.fragment).toString()
        } catch (_: Exception) { url }
}

/* ----------------------------- APP Main UI (camera list) ---------------------------- */
// --- App root with navigation: list -> player ---
@Composable
private fun App(
    cameras: List<Camera>,
    recordingsAvailable: Boolean,
    backendConfig: BackendConfig,
    editableConfigsById: Map<String, EditableCameraConfig>,
    onSaveBackendConfig: (BackendConfig) -> Unit,
    onAddCamera: (NewCameraConfig) -> Unit,
    onSaveCameraConfig: (EditableCameraConfig) -> Unit,
    onDeleteCamera: (String) -> Unit
) {
    val nav = rememberNavController()

    NavHost(navController = nav, startDestination = "cameras") {

        composable("cameras") {
            CameraListScreen(
                cameras = cameras,
                backendConfig = backendConfig,
                onOpen = { cam ->
                    val encoded = android.net.Uri.encode(cam.rtspUrl)
                    nav.navigate("player?camId=${cam.id}&rtsp=$encoded&port=${cam.onvifPort}")
                },
                onOpenRecordings = { cam ->
                    val camIdForServer = mapCamIdForServer(cam.id)
                    nav.navigate("recordings?camId=$camIdForServer")
                },
                recordingsAvailable = recordingsAvailable,
                editableConfigsById = editableConfigsById,
                onSaveBackendConfig = onSaveBackendConfig,
                onAddCamera = onAddCamera,
                onSaveCameraConfig = onSaveCameraConfig,
                onDeleteCamera = onDeleteCamera
            )
        }

        composable(
            route = "player?camId={camId}&rtsp={rtsp}&port={port}",
            arguments = listOf(
                navArgument("camId") { type = NavType.StringType },
                navArgument("rtsp")  { type = NavType.StringType },
                navArgument("port")  { type = NavType.IntType },
            )
        ) { backStackEntry ->
            val camId = requireNotNull(backStackEntry.arguments?.getString("camId"))
            val rtsp  = requireNotNull(backStackEntry.arguments?.getString("rtsp"))
            val port  = backStackEntry.arguments?.getInt("port") ?: 8899

            IjkRtspWithPtz(
                cameraId = camId,                 // NEW
                rtspUrl  = rtsp,
                onvifPort = port,
                forceTcp = true,
                useHwDecodeHevc = false,
                modifier = Modifier.fillMaxSize()
            )
        }

        // Recordings

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

                    val startEpoch = parseServerIsoToEpochMs(rec.start_iso) ?: 0L

                    // derive YYYY-MM-DD in the server TZ
                    val dateYmd = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                        timeZone = TimeZone.getTimeZone(SERVER_TZ_ID)
                    }.format(Date(startEpoch))

                    nav.navigate("playHttp?url=$encUrl&marks=$encMarks&seek=0&startEpoch=$startEpoch&cam=$camId&date=$dateYmd")
                },
                onOpenEvent = { url, marks, seek, startEpoch ->
                    val encUrl = Uri.encode(url)
                    val encMarks = Uri.encode(JSONArray(marks).toString())

                    val dateYmd = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                        timeZone = TimeZone.getTimeZone(SERVER_TZ_ID)
                    }.format(Date(startEpoch))

                    nav.navigate("playHttp?url=$encUrl&marks=$encMarks&seek=$seek&startEpoch=$startEpoch&cam=$camId&date=$dateYmd")
                }


            )
        }




        composable(
            route = "playHttp?url={url}&marks={marks}&seek={seek}&startEpoch={startEpoch}&cam={cam}&date={date}",
            arguments = listOf(
                navArgument("url")        { type = NavType.StringType },
                navArgument("marks")      { type = NavType.StringType; defaultValue = "[]" },
                navArgument("seek")       { type = NavType.IntType;    defaultValue = -1 },
                navArgument("startEpoch") { type = NavType.LongType;   defaultValue = -1L },
                navArgument("cam")        { type = NavType.StringType },
                navArgument("date")       { type = NavType.StringType }
            )
        ) { bs ->
            val urlRaw = requireNotNull(bs.arguments?.getString("url"))
            val url    = android.net.Uri.decode(urlRaw)
            val marksJson  = bs.arguments?.getString("marks") ?: "[]"
            val marks      = remember(marksJson) { val arr = JSONArray(marksJson); List(arr.length()) { i -> arr.getInt(i) } }
            val seekSec    = bs.arguments?.getInt("seek") ?: -1
            val startEpoch = bs.arguments?.getLong("startEpoch") ?: -1L
            val cam        = requireNotNull(bs.arguments?.getString("cam"))
            val date       = requireNotNull(bs.arguments?.getString("date"))

            // ---------- helpers para resolver "siguiente" ----------
            suspend fun fetchDay(camId: String, dateYmd: String): List<Pair<Long, Recording>> {
                val pageSize = 200
                val all = mutableListOf<Recording>()
                var page = 1
                while (true) {
                    val resp = RecApiClient.api.list(cam = camId, date = dateYmd, page = page, pageSize = pageSize, includeMarks = 1)
                    all += resp.items
                    if (page >= resp.pages) break
                    page++
                }
                return all.mapNotNull { r ->
                    parseServerIsoToEpochMs(r.start_iso)?.let { it to r }
                }.sortedBy { it.first }
            }


            fun plusOneDay(ymd: String): String {
                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                val cal = java.util.Calendar.getInstance().apply { time = sdf.parse(ymd)!!; add(java.util.Calendar.DATE, 1) }
                return sdf.format(cal.time)
            }

            suspend fun findNextRecording(camId: String, dateYmd: String, currentStartMs: Long, currentUrl: String): Recording? {
                val today    = fetchDay(camId, dateYmd)
                val tomorrow = fetchDay(camId, plusOneDay(dateYmd))
                val all      = (today + tomorrow).sortedBy { it.first }
                if (all.isEmpty()) return null

                val curDec = android.net.Uri.decode(currentUrl)

                var idx = all.indexOfFirst { (_, r) -> android.net.Uri.decode(r.url) == curDec }

                val EPS = 5_000L
                if (idx == -1) idx = all.indexOfFirst { (ms, _) -> kotlin.math.abs(ms - currentStartMs) <= EPS }
                if (idx == -1) {
                    idx = all.indexOfLast { (ms, _) -> ms <= currentStartMs + EPS }
                    if (idx == -1) idx = 0
                }
                return all.getOrNull(idx + 1)?.second
            }


            fun openNext(rec: Recording) {
                val encUrl   = android.net.Uri.encode(rec.url)
                val encMarks = android.net.Uri.encode(JSONArray(rec.marks ?: emptyList<Int>()).toString())
                val startMs  = parseServerIsoToEpochMs(rec.start_iso) ?: 0L

                val dateYmd  = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone(SERVER_TZ_ID)
                }.format(Date(startMs))

                nav.navigate("playHttp?url=$encUrl&marks=$encMarks&seek=0&startEpoch=$startMs&cam=$cam&date=$dateYmd")
            }


            // Resolver el siguiente
            var nextRec by remember(url, cam, date, startEpoch) { mutableStateOf<Recording?>(null) }
            LaunchedEffect(cam, date, url, startEpoch) {
                nextRec = runCatching { findNextRecording(cam, date, startEpoch, url) }.getOrNull()
            }

            // ---------- Reproductor ----------
            IjkHttpPlayerWithControls(
                url = url,
                markersSec = marks,
                initialSeekMs = if (seekSec >= 0) seekSec * 1000L else -1L,
                fileStartEpochMs = startEpoch.takeIf { it >= 0L },
                modifier = Modifier.fillMaxSize(),
                hasNext = nextRec != null,
                onNextClick = { nextRec?.let(::openNext) }
            )
        }


    }
}

private fun mapCamIdForServer(id: String) = id.replace("-", "")

private data class EditableCameraConfig(
    val cameraId: String,
    val name: String,
    val username: String,
    val password: String,
    val localHost: String,
    val remoteHost: String?,
    val localRtspPort: Int = 554,
    val localOnvifPort: Int = 8899,
    val remoteRtspPort: Int = 5554,
    val remoteOnvifPort: Int = 18899
)

private data class NewCameraConfig(
    val name: String,
    val username: String,
    val password: String,
    val localHost: String,
    val remoteHost: String?,
    val localRtspPort: Int = 554,
    val localOnvifPort: Int = 8899,
    val remoteRtspPort: Int = 5554,
    val remoteOnvifPort: Int = 18899
)

private fun isValidBackendHttpUrlMain(value: String): Boolean {
    return runCatching {
        val u = URI(value.trim())
        (u.scheme.equals("http", ignoreCase = true) || u.scheme.equals("https", ignoreCase = true)) &&
                !u.host.isNullOrBlank()
    }.getOrDefault(false)
}

private fun parsePortOrNullMain(value: String): Int? =
    value.trim().toIntOrNull()?.takeIf { it in 1..65535 }

@Composable
private fun CameraPortsAdvancedSection(
    localRtspPort: String,
    onLocalRtspPortChange: (String) -> Unit,
    localOnvifPort: String,
    onLocalOnvifPortChange: (String) -> Unit,
    remoteRtspPort: String,
    onRemoteRtspPortChange: (String) -> Unit,
    remoteOnvifPort: String,
    onRemoteOnvifPortChange: (String) -> Unit
) {
    val localRtspInvalid = localRtspPort.isNotBlank() && parsePortOrNullMain(localRtspPort) == null
    val localOnvifInvalid = localOnvifPort.isNotBlank() && parsePortOrNullMain(localOnvifPort) == null
    val remoteRtspInvalid = remoteRtspPort.isNotBlank() && parsePortOrNullMain(remoteRtspPort) == null
    val remoteOnvifInvalid = remoteOnvifPort.isNotBlank() && parsePortOrNullMain(remoteOnvifPort) == null

    Spacer(Modifier.height(8.dp))
    Text("Avanzado", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(6.dp))
    Card(shape = RoundedCornerShape(12.dp)) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Local",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = localRtspPort,
                        onValueChange = onLocalRtspPortChange,
                        label = { Text("RTSP") },
                        isError = localRtspInvalid,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = localOnvifPort,
                        onValueChange = onLocalOnvifPortChange,
                        label = { Text("ONVIF") },
                        isError = localOnvifInvalid,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Remoto",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = remoteRtspPort,
                        onValueChange = onRemoteRtspPortChange,
                        label = { Text("RTSP") },
                        isError = remoteRtspInvalid,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = remoteOnvifPort,
                        onValueChange = onRemoteOnvifPortChange,
                        label = { Text("ONVIF") },
                        isError = remoteOnvifInvalid,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            if (localRtspInvalid || localOnvifInvalid || remoteRtspInvalid || remoteOnvifInvalid) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Ingresá puertos válidos (1-65535).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

// --- Simple list UI ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CameraListScreen(
    cameras: List<Camera>,
    backendConfig: BackendConfig,
    onOpen: (Camera) -> Unit,
    onOpenRecordings: (Camera) -> Unit,
    recordingsAvailable: Boolean,
    editableConfigsById: Map<String, EditableCameraConfig>,
    onSaveBackendConfig: (BackendConfig) -> Unit,
    onAddCamera: (NewCameraConfig) -> Unit,
    onSaveCameraConfig: (EditableCameraConfig) -> Unit,
    onDeleteCamera: (String) -> Unit
) {
    var editingCamera by remember { mutableStateOf<EditableCameraConfig?>(null) }
    var pendingDeleteCamera by remember { mutableStateOf<Camera?>(null) }
    var expandedOptionsForCameraId by remember { mutableStateOf<String?>(null) }
    var showAddCameraDialog by remember { mutableStateOf(false) }
    var showBackendSettingsDialog by remember { mutableStateOf(false) }

    if (showAddCameraDialog) {
        AddCameraDialog(
            onDismiss = { showAddCameraDialog = false },
            onAdd = { newCam ->
                onAddCamera(newCam)
                showAddCameraDialog = false
            }
        )
    }

    if (showBackendSettingsDialog) {
        BackendSettingsDialog(
            initial = backendConfig,
            onDismiss = { showBackendSettingsDialog = false },
            onSave = { updated ->
                onSaveBackendConfig(updated)
                showBackendSettingsDialog = false
            }
        )
    }

    editingCamera?.let { current ->
        EditCameraConfigDialog(
            initial = current,
            onDismiss = { editingCamera = null },
            onSave = { updated ->
                onSaveCameraConfig(updated)
                editingCamera = null
            }
        )
    }

    pendingDeleteCamera?.let { cam ->
        AlertDialog(
            onDismissRequest = { pendingDeleteCamera = null },
            title = { Text("Quitar cámara") },
            text = {
                Text(
                    "¿Querés quitar ${cam.name}? Esta acción elimina su configuración guardada."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteCamera(cam.id)
                        pendingDeleteCamera = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MenuColors.accent,
                        contentColor = MenuColors.onAccent
                    )
                ) { Text("Quitar") }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { pendingDeleteCamera = null },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MenuColors.accent,
                        disabledContentColor = MenuColors.accent.copy(alpha = 0.38f)
                    ),
                    border = BorderStroke(1.dp, MenuColors.accent)
                ) { Text("Cancelar") }
            }
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("Cámaras") },
                actions = {
                    OutlinedButton(
                        onClick = { showAddCameraDialog = true },
                        modifier = Modifier.padding(end = 6.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MenuColors.accent,
                            disabledContentColor = MenuColors.accent.copy(alpha = 0.38f)
                        ),
                        border = BorderStroke(1.dp, MenuColors.accent)
                    ) {
                        Text("Agregar cámara")
                    }
                    OutlinedButton(
                        onClick = { showBackendSettingsDialog = true },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MenuColors.accent,
                            disabledContentColor = MenuColors.accent.copy(alpha = 0.38f)
                        ),
                        border = BorderStroke(1.dp, MenuColors.accent)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "Configuración"
                        )
                    }
                },
                windowInsets = WindowInsets.safeDrawing.only(
                    WindowInsetsSides.Top + WindowInsetsSides.Start + WindowInsetsSides.End
                )
            )
        }
    ) { padding ->
        LazyColumn(
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            if (cameras.isEmpty()) {
                item {
                    Card(shape = RoundedCornerShape(14.dp)) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("No hay cámaras para mostrar", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Podés agregar una cámara nueva desde esta pantalla sin volver a la configuración inicial.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            items(cameras, key = { it.id }) { cam ->
                Card(shape = RoundedCornerShape(14.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(cam.name, style = MaterialTheme.typography.titleMedium)
                            val host = parseCameraInfoFromRtsp(cam.rtspUrl)?.host ?: "—"
                            /*Text( //This shows the camera IP as a description below the name
                                text = host,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )*/
                        }
                        Button(
                            onClick = { onOpen(cam) },
                            colors = ButtonDefaults.textButtonColors(
                                containerColor = MenuColors.accent,
                                contentColor = MenuColors.onAccent
                            )
                        ) {
                            Text("Abrir")
                        }

                        Spacer(Modifier.width(2.dp))

                        //Recordings button
                        if(recordingsAvailable) {
                            OutlinedButton(
                                onClick = { onOpenRecordings(cam) },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MenuColors.accent),
                                border = BorderStroke(1.dp, MenuColors.accent)
                            ) { Text("Grabaciones") }
                        }

                        Spacer(Modifier.width(4.dp))

                        Box {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .border(
                                        BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                        RoundedCornerShape(6.dp)
                                    )
                                    .clickable {
                                        expandedOptionsForCameraId =
                                            if (expandedOptionsForCameraId == cam.id) null else cam.id
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.ArrowDropDown,
                                    contentDescription = "Más opciones",
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            DropdownMenu(
                                expanded = expandedOptionsForCameraId == cam.id,
                                onDismissRequest = { expandedOptionsForCameraId = null }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Editar") },
                                    onClick = {
                                        editingCamera = editableConfigsById[cam.id]
                                        expandedOptionsForCameraId = null
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Quitar") },
                                    onClick = {
                                        pendingDeleteCamera = cam
                                        expandedOptionsForCameraId = null
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}


@Composable
private fun PasswordToggleOutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    var visible by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    imageVector = if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (visible) "Ocultar" else "Mostrar"
                )
            }
        },
        modifier = modifier
    )
}


@Composable
private fun EditCameraConfigDialog(
    initial: EditableCameraConfig,
    onDismiss: () -> Unit,
    onSave: (EditableCameraConfig) -> Unit
) {
    var name by remember(initial.cameraId) { mutableStateOf(initial.name) }
    var username by remember(initial.cameraId) { mutableStateOf(initial.username) }
    var password by remember(initial.cameraId) { mutableStateOf(initial.password) }
    var localHost by remember(initial.cameraId) { mutableStateOf(initial.localHost) }
    var remoteHost by remember(initial.cameraId) { mutableStateOf(initial.remoteHost.orEmpty()) }
    var localRtspPort by remember(initial.cameraId) { mutableStateOf(initial.localRtspPort.toString()) }
    var localOnvifPort by remember(initial.cameraId) { mutableStateOf(initial.localOnvifPort.toString()) }
    var remoteRtspPort by remember(initial.cameraId) { mutableStateOf(initial.remoteRtspPort.toString()) }
    var remoteOnvifPort by remember(initial.cameraId) { mutableStateOf(initial.remoteOnvifPort.toString()) }

    val parsedLocalRtsp = parsePortOrNullMain(localRtspPort)
    val parsedLocalOnvif = parsePortOrNullMain(localOnvifPort)
    val parsedRemoteRtsp = parsePortOrNullMain(remoteRtspPort)
    val parsedRemoteOnvif = parsePortOrNullMain(remoteOnvifPort)
    val arePortsValid = parsedLocalRtsp != null && parsedLocalOnvif != null && parsedRemoteRtsp != null && parsedRemoteOnvif != null
    val isValid = username.isNotBlank() && password.isNotBlank() && localHost.isNotBlank() && arePortsValid

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Editar ${initial.name}") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nombre visible") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Usuario") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                PasswordToggleOutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Contraseña") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = localHost,
                    onValueChange = { localHost = it },
                    label = { Text("Host local") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = remoteHost,
                    onValueChange = { remoteHost = it },
                    label = { Text("Host remoto opcional") },
                    modifier = Modifier.fillMaxWidth()
                )

                CameraPortsAdvancedSection(
                    localRtspPort = localRtspPort,
                    onLocalRtspPortChange = { localRtspPort = it },
                    localOnvifPort = localOnvifPort,
                    onLocalOnvifPortChange = { localOnvifPort = it },
                    remoteRtspPort = remoteRtspPort,
                    onRemoteRtspPortChange = { remoteRtspPort = it },
                    remoteOnvifPort = remoteOnvifPort,
                    onRemoteOnvifPortChange = { remoteOnvifPort = it }
                )

                if (!isValid) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Completá usuario, contraseña, host local y puertos válidos (1-65535).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = isValid,
                onClick = {
                    onSave(
                        initial.copy(
                            name = name.trim().ifBlank { initial.name },
                            username = username.trim(),
                            password = password,
                            localHost = localHost.trim(),
                            remoteHost = remoteHost.trim().ifBlank { null },
                            localRtspPort = parsedLocalRtsp ?: initial.localRtspPort,
                            localOnvifPort = parsedLocalOnvif ?: initial.localOnvifPort,
                            remoteRtspPort = parsedRemoteRtsp ?: initial.remoteRtspPort,
                            remoteOnvifPort = parsedRemoteOnvif ?: initial.remoteOnvifPort
                        )
                    )
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MenuColors.accent,
                    contentColor = MenuColors.onAccent,
                    disabledContainerColor = MenuColors.accent.copy(alpha = 0.38f),
                    disabledContentColor = MenuColors.onAccent.copy(alpha = 0.38f)
                )
            ) { Text("Guardar") }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MenuColors.accent,
                    disabledContentColor = MenuColors.accent.copy(alpha = 0.38f)
                ),
                border = BorderStroke(1.dp, MenuColors.accent)
            ) { Text("Cancelar") }
        }
    )
}

@Composable
private fun AddCameraDialog(
    onDismiss: () -> Unit,
    onAdd: (NewCameraConfig) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var localHost by remember { mutableStateOf("") }
    var remoteHost by remember { mutableStateOf("") }
    var localRtspPort by remember { mutableStateOf("554") }
    var localOnvifPort by remember { mutableStateOf("8899") }
    var remoteRtspPort by remember { mutableStateOf("5554") }
    var remoteOnvifPort by remember { mutableStateOf("18899") }

    val parsedLocalRtsp = parsePortOrNullMain(localRtspPort)
    val parsedLocalOnvif = parsePortOrNullMain(localOnvifPort)
    val parsedRemoteRtsp = parsePortOrNullMain(remoteRtspPort)
    val parsedRemoteOnvif = parsePortOrNullMain(remoteOnvifPort)
    val arePortsValid = parsedLocalRtsp != null && parsedLocalOnvif != null && parsedRemoteRtsp != null && parsedRemoteOnvif != null
    val isValid = username.isNotBlank() && password.isNotBlank() && localHost.isNotBlank() && arePortsValid

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Agregar cámara") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nombre visible (opcional)") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Usuario") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                PasswordToggleOutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Contraseña") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = localHost,
                    onValueChange = { localHost = it },
                    label = { Text("Host local") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = remoteHost,
                    onValueChange = { remoteHost = it },
                    label = { Text("Host remoto opcional") },
                    modifier = Modifier.fillMaxWidth()
                )

                CameraPortsAdvancedSection(
                    localRtspPort = localRtspPort,
                    onLocalRtspPortChange = { localRtspPort = it },
                    localOnvifPort = localOnvifPort,
                    onLocalOnvifPortChange = { localOnvifPort = it },
                    remoteRtspPort = remoteRtspPort,
                    onRemoteRtspPortChange = { remoteRtspPort = it },
                    remoteOnvifPort = remoteOnvifPort,
                    onRemoteOnvifPortChange = { remoteOnvifPort = it }
                )

                if (!isValid) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Completá usuario, contraseña, host local y puertos válidos (1-65535).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = isValid,
                onClick = {
                    onAdd(
                        NewCameraConfig(
                            name = name.trim(),
                            username = username.trim(),
                            password = password,
                            localHost = localHost.trim(),
                            remoteHost = remoteHost.trim().ifBlank { null },
                            localRtspPort = parsedLocalRtsp ?: 554,
                            localOnvifPort = parsedLocalOnvif ?: 8899,
                            remoteRtspPort = parsedRemoteRtsp ?: 5554,
                            remoteOnvifPort = parsedRemoteOnvif ?: 18899
                        )
                    )
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MenuColors.accent,
                    contentColor = MenuColors.onAccent,
                    disabledContainerColor = MenuColors.accent.copy(alpha = 0.38f),
                    disabledContentColor = MenuColors.onAccent.copy(alpha = 0.38f)
                )
            ) { Text("Agregar") }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MenuColors.accent,
                    disabledContentColor = MenuColors.accent.copy(alpha = 0.38f)
                ),
                border = BorderStroke(1.dp, MenuColors.accent)
            ) { Text("Cancelar") }
        }
    )
}

@Composable
private fun BackendSettingsDialog(
    initial: BackendConfig,
    onDismiss: () -> Unit,
    onSave: (BackendConfig) -> Unit
) {
    var backendUrl by remember(initial.baseUrl) { mutableStateOf(initial.baseUrl) }
    var apiKey by remember(initial.apiKey) { mutableStateOf(initial.apiKey) }

    val backendUrlTrimmed = backendUrl.trim()
    val backendUrlValid = backendUrlTrimmed.isNotEmpty() && isValidBackendHttpUrlMain(backendUrlTrimmed)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Configuración de backend") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(
                    value = backendUrl,
                    onValueChange = { backendUrl = it },
                    label = { Text("Backend URL") },
                    isError = backendUrlTrimmed.isNotEmpty() && !backendUrlValid,
                    modifier = Modifier.fillMaxWidth()
                )
                if (backendUrlTrimmed.isNotEmpty() && !backendUrlValid) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Ingresá una URL válida con http:// o https://",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(Modifier.height(8.dp))
                PasswordToggleOutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                enabled = backendUrlValid,
                onClick = {
                    onSave(
                        BackendConfig(
                            baseUrl = backendUrlTrimmed,
                            apiKey = apiKey
                        )
                    )
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MenuColors.accent,
                    contentColor = MenuColors.onAccent,
                    disabledContainerColor = MenuColors.accent.copy(alpha = 0.38f),
                    disabledContentColor = MenuColors.onAccent.copy(alpha = 0.38f)
                )
            ) { Text("Guardar") }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MenuColors.accent,
                    disabledContentColor = MenuColors.accent.copy(alpha = 0.38f)
                ),
                border = BorderStroke(1.dp, MenuColors.accent)
            ) { Text("Cancelar") }
        }
    )
}

private fun IjkMediaPlayer.applyLowLatencyRtsp(
    useTcp: Boolean,
    enableHevcHw: Boolean
) {
    // Transport
    setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "rtsp_transport", if (useTcp) "tcp" else "udp")
    // Faster start / less rebuffer
    setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzemaxduration", 100L)
    setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "probesize", 1024L)
    setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "flush_packets", 1L)
    // Player buffering & drop policy
    setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering", 0L)
    setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop", 1L)
    // Hardware decode (on when cam is H.264/H.265)
    setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", 1L)
    setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-auto-rotate", 1L)
    setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-handle-resolution-change", 1L)
    setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-hevc", if (enableHevcHw) 1L else 0L)
    // Conservative network timeouts (microseconds in FFmpeg; IJK accepts Long)
    setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "timeout", 4_000_000L)
}
