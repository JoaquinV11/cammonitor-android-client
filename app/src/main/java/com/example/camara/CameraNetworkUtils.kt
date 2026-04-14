package com.example.camara

import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI

fun fillRtspTemplate(
    template: String,
    username: String,
    password: String,
    host: String,
    rtspPort: Int
): String {
    return template
        .replace("{user}", username)
        .replace("{pass}", password)
        .replace("{host}", host)
        .replace("{rtspPort}", rtspPort.toString())
}

fun effectiveWanHost(cfg: CameraConfig, cachedPublicIp: String?): String? {
    val configuredHost = cfg.remoteHost?.trim().orEmpty()
    if (configuredHost.isNotBlank()) return configuredHost
    val cachedIp = cachedPublicIp?.trim().orEmpty()
    if (cachedIp.isNotBlank()) return cachedIp
    return null
}

fun isLikelyLanHost(host: String): Boolean {
    val h = host.trim().lowercase()
    if (h.endsWith(".local")) return true
    if (h.startsWith("192.168.")) return true
    if (h.startsWith("10.")) return true
    return Regex("""^172\.(1[6-9]|2\d|3[0-1])\..+""").matches(h)
}

/** Quick TCP connect test to a host:port. */
fun canReach(host: String, port: Int, timeoutMs: Int): Boolean =
    runCatching {
        Socket().use { s -> s.connect(InetSocketAddress(host, port), timeoutMs) }
        true
    }.getOrElse { false }

/** Safe for user/pass in RTSP authority */
fun rewriteRtspHostPort(url: String, newHost: String, newPort: Int): String {
    val u = URI(url)
    val userInfo = u.userInfo
    val hostForAuthority =
        if (newHost.contains(':') && !newHost.startsWith("[") && !newHost.endsWith("]"))
            "[$newHost]" else newHost
    val authority = if (userInfo != null)
        "$userInfo@$hostForAuthority:$newPort"
    else
        "$hostForAuthority:$newPort"
    return URI(u.scheme, authority, u.path, u.query, u.fragment).toString()
}
