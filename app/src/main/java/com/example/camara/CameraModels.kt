package com.example.camara

// ===== Per-camera config =====
data class CameraConfig(
    val id: String,
    val name: String,
    val baseRtspUrl: String,
    val localHost: String,
    val localRtspPort: Int = 554,
    val localOnvifPort: Int = 8899,
    val remoteHost: String? = null,
    val remoteRtspPort: Int = 5554,
    val remoteOnvifPort: Int = 18899
)

enum class NetMode { HOME, AWAY_GITHUB, AWAY_CACHE, AWAY_FALLBACK }

data class ResolvedEnv(
    val rtspUrl: String,
    val onvifPort: Int,
    val mode: NetMode,
    val publicIp: String? = null
)

data class PtzPreset(val token: String, val name: String)

data class CameraInfo(val host: String, val username: String?, val password: String?)

data class Camera(
    val id: String,
    val name: String,
    val rtspUrl: String,
    val onvifPort: Int = 8899
)
