package com.example.camara

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI

fun parseCameraInfoFromRtsp(rtsp: String): CameraInfo? = try {
    val u = URI(rtsp.trim())
    val host = u.host?.takeIf { it.isNotBlank() } ?: return null
    val userInfo = u.userInfo
    val username = userInfo?.substringBefore(':')
    val password = userInfo?.substringAfter(':')
    CameraInfo(host = host, username = username, password = password)
} catch (_: Exception) { null }

fun String.findTag(parent: String, childTag: String): String? {
    val regex = Regex("<[^>]*$parent[^>]*>.*?<[^>]*$childTag[^>]*>(.*?)</[^>]*$childTag[^>]*>", RegexOption.DOT_MATCHES_ALL)
    return regex.find(this)?.groupValues?.getOrNull(1)?.trim()
}

fun String.findAttribute(tagName: String, attr: String): String? {
    val regex = Regex("<[^>]*$tagName[^>]*\\s$attr\\s*=\\s*\"([^\"]+)\"")
    return regex.find(this)?.groupValues?.getOrNull(1)
}

fun String.findPresets(): List<PtzPreset> {
    val presetRegex = Regex(
        "<[^>]*Preset[^>]*token\\s*=\\s*\"([^\"]+)\"[^>]*>.*?<[^>]*Name[^>]*>(.*?)</[^>]*Name[^>]*>",
        RegexOption.DOT_MATCHES_ALL
    )
    return presetRegex.findAll(this).map {
        PtzPreset(token = it.groupValues[1], name = it.groupValues[2].trim())
    }.toList()
}

fun String.xml(): String = this
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&apos;")

fun Float.fmt(): String = String.format("%.3f", this.coerceIn(-1f, 1f))

suspend fun loadPresetNameMap(
    ctx: android.content.Context,
    camId: String
): Map<String, String> = withContext(Dispatchers.IO) {
    runCatching {
        SecureConfigStore(ctx).loadPresetAliases(camId)
    }.getOrElse { emptyMap() }
}

fun probeRtspGreeting(host: String, port: Int): String = runCatching {
    Socket().use { s ->
        s.connect(InetSocketAddress(host, port), 3000)
        val out = s.getOutputStream()
        val inp = s.getInputStream()
        out.write("OPTIONS rtsp://$host:$port/ RTSP/1.0\\r\\nCSeq: 1\\r\\n\\r\\n".toByteArray())
        out.flush()
        val buf = ByteArray(512)
        val n = inp.read(buf)
        if (n > 0) String(buf, 0, n) else "no-bytes"
    }
}.getOrElse { "error: ${it.message}" }

fun hostPortFromRtsp(rtsp: String): Pair<String, Int> = try {
    val u = URI(rtsp.trim())
    val host = u.host?.takeIf { it.isNotBlank() } ?: return "" to -1
    val p = when (u.port) {
        -1 -> 554
        in 1..65535 -> u.port
        else -> -1
    }
    host to p
} catch (_: Exception) { "" to -1 }

fun String.firstLine(max: Int = 120): String =
    lineSequence().firstOrNull()?.take(max) ?: take(max)

fun soapEnvelope12(username: String, password: String, innerBody: String): String = """
<?xml version="1.0" encoding="utf-8"?>
<s:Envelope
  xmlns:s="http://www.w3.org/2003/05/soap-envelope"
  xmlns:wsse="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd">
  <s:Header>
    <wsse:Security>
      <wsse:UsernameToken>
        <wsse:Username>${username.xml()}</wsse:Username>
        <wsse:Password Type="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordText">
${password.xml()}
        </wsse:Password>
      </wsse:UsernameToken>
    </wsse:Security>
  </s:Header>
  <s:Body>
$innerBody
  </s:Body>
</s:Envelope>
""".trimIndent()

fun soapEnvelope11(username: String, password: String, innerBody: String): String = """
<?xml version="1.0" encoding="utf-8"?>
<soapenv:Envelope
  xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/"
  xmlns:wsse="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd">
  <soapenv:Header>
    <wsse:Security>
      <wsse:UsernameToken>
        <wsse:Username>${username.xml()}</wsse:Username>
        <wsse:Password Type="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordText">
${password.xml()}
        </wsse:Password>
      </wsse:UsernameToken>
    </wsse:Security>
  </soapenv:Header>
  <soapenv:Body>
$innerBody
  </soapenv:Body>
</soapenv:Envelope>
""".trimIndent()
