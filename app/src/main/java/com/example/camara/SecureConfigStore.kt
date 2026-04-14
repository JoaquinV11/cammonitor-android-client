package com.example.camara

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject
import java.io.File

data class BackendConfig(
    val baseUrl: String,
    val apiKey: String
)

data class CameraCatalogItem(
    val cameraId: String,
    val name: String
)

data class CameraCredentials(
    val cameraId: String,
    val username: String,
    val password: String
)

data class CameraNetworkConfig(
    val cameraId: String,
    val localHost: String,
    val localRtspPort: Int = 554,
    val localOnvifPort: Int = 8899,
    val remoteHost: String? = null,
    val remoteRtspPort: Int = 5554,
    val remoteOnvifPort: Int = 18899
)

class SecureConfigStore(context: Context) {

    private val appContext = context.applicationContext

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = createSecurePrefs(context)

    fun saveBackend(config: BackendConfig) {
        val normalizedBaseUrl = config.baseUrl.trim().trimEnd('/')
        val normalizedApiKey = config.apiKey.trim()
        prefs.edit()
            .putString(KEY_BACKEND_URL, normalizedBaseUrl)
            .putString(KEY_BACKEND_API_KEY, normalizedApiKey)
            .apply()
    }

    fun loadBackend(): BackendConfig? {
        val baseUrl = prefs.getString(KEY_BACKEND_URL, null)?.trim()?.trimEnd('/').orEmpty()
        if (baseUrl.isBlank()) return null
        val apiKey = prefs.getString(KEY_BACKEND_API_KEY, "") ?: ""
        return BackendConfig(baseUrl = baseUrl, apiKey = apiKey)
    }

    fun saveCameraCatalog(list: List<CameraCatalogItem>) {
        val orderedIds = list.map { it.cameraId.trim() }.filter { it.isNotBlank() }
        val editor = prefs.edit()
        editor.putString(KEY_CAMERA_IDS, orderedIds.joinToString("|"))
        list.forEach { cam ->
            editor.putString(keyName(cam.cameraId), cam.name.trim())
        }
        editor.apply()
    }

    fun loadCameraCatalog(): List<CameraCatalogItem> {
        val storedIds = prefs.getString(KEY_CAMERA_IDS, null)
            ?.split('|')
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()

        if (storedIds.isNotEmpty()) {
            return storedIds.map { id ->
                CameraCatalogItem(
                    cameraId = id,
                    name = prefs.getString(keyName(id), null)?.trim().orEmpty().ifBlank {
                        defaultNameFor(id)
                    }
                )
            }
        }

        // Migration path from old fixed cam-1/cam-2 setup (no catalog key stored)
        val legacyIds = LEGACY_CAMERA_IDS.filter { id -> hasAnyLegacyDataFor(id) }
        return legacyIds.mapIndexed { index, id ->
            CameraCatalogItem(id, prefs.getString(keyName(id), null)?.trim().orEmpty().ifBlank { "Cámara ${index + 1}" })
        }
    }

    fun saveCameraCredentials(list: List<CameraCredentials>) {
        val editor = prefs.edit()
        list.forEach { cam ->
            editor.putString(keyUser(cam.cameraId), cam.username.trim())
            editor.putString(keyPass(cam.cameraId), cam.password)
        }
        editor.apply()
    }

    fun loadCameraCredentials(expectedIds: List<String>? = null): List<CameraCredentials> {
        val ids = expectedIds ?: loadCameraCatalog().map { it.cameraId }
        return ids.mapNotNull { id ->
            val user = prefs.getString(keyUser(id), null)
            val pass = prefs.getString(keyPass(id), null)
            if (!user.isNullOrBlank() && pass != null) {
                CameraCredentials(id, user, pass)
            } else {
                null
            }
        }
    }

    fun saveCameraNetworkConfigs(list: List<CameraNetworkConfig>) {
        val editor = prefs.edit()
        list.forEach { cam ->
            editor.putString(keyLocalHost(cam.cameraId), cam.localHost.trim())
            editor.putInt(keyLocalRtspPort(cam.cameraId), cam.localRtspPort)
            editor.putInt(keyLocalOnvifPort(cam.cameraId), cam.localOnvifPort)
            editor.putString(keyRemoteHost(cam.cameraId), cam.remoteHost?.trim().orEmpty())
            editor.putInt(keyRemoteRtspPort(cam.cameraId), cam.remoteRtspPort)
            editor.putInt(keyRemoteOnvifPort(cam.cameraId), cam.remoteOnvifPort)
        }
        editor.apply()
    }

    fun loadCameraNetworkConfigs(expectedIds: List<String>? = null): List<CameraNetworkConfig> {
        val ids = expectedIds ?: loadCameraCatalog().map { it.cameraId }
        return ids.mapNotNull { id ->
            val localHost = prefs.getString(keyLocalHost(id), null)?.trim().orEmpty()
            if (localHost.isBlank()) return@mapNotNull null

            CameraNetworkConfig(
                cameraId = id,
                localHost = localHost,
                localRtspPort = prefs.getInt(keyLocalRtspPort(id), 554),
                localOnvifPort = prefs.getInt(keyLocalOnvifPort(id), 8899),
                remoteHost = prefs.getString(keyRemoteHost(id), "")?.trim().takeUnless { it.isNullOrBlank() },
                remoteRtspPort = prefs.getInt(keyRemoteRtspPort(id), 5554),
                remoteOnvifPort = prefs.getInt(keyRemoteOnvifPort(id), 18899)
            )
        }
    }

    fun isConfigured(): Boolean {
        if (loadBackend() == null) return false

        val cameraIds = loadCameraCatalog().map { it.cameraId }
        if (cameraIds.isEmpty()) return false

        val configuredCredIds = loadCameraCredentials(cameraIds).map { it.cameraId }.toSet()
        val configuredNetIds = loadCameraNetworkConfigs(cameraIds).map { it.cameraId }.toSet()

        return cameraIds.all { it in configuredCredIds && it in configuredNetIds }
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    fun removeCamera(cameraId: String) {
        val id = cameraId.trim()
        if (id.isBlank()) return

        val remaining = loadCameraCatalog().filterNot { it.cameraId == id }
        val orderedIds = remaining.map { it.cameraId.trim() }.filter { it.isNotBlank() }

        val editor = prefs.edit()
            .putString(KEY_CAMERA_IDS, orderedIds.joinToString("|"))
            .remove(keyName(id))
            .remove(keyUser(id))
            .remove(keyPass(id))
            .remove(keyLocalHost(id))
            .remove(keyLocalRtspPort(id))
            .remove(keyLocalOnvifPort(id))
            .remove(keyRemoteHost(id))
            .remove(keyRemoteRtspPort(id))
            .remove(keyRemoteOnvifPort(id))

        prefs.all.keys
            .filter { it.startsWith(presetAliasPrefix(id)) }
            .forEach { editor.remove(it) }

        editor.apply()
    }

    fun loadPresetAliases(cameraId: String): Map<String, String> {
        val id = cameraId.trim()
        if (id.isBlank()) return loadAliasesForCamera("default")

        val defaults = loadAliasesForCamera("default").toMutableMap()
        val specific = loadAliasesForCamera(id)
        defaults.putAll(specific)
        return defaults
    }

    fun savePresetAliases(cameraId: String, aliases: Map<String, String>) {
        val id = cameraId.trim()
        if (id.isBlank()) return

        val cleaned = aliases
            .mapKeys { (token, _) -> token.trim() }
            .mapValues { (_, label) -> label.trim() }
            .filter { (token, label) -> token.isNotBlank() && label.isNotBlank() }

        val editor = prefs.edit()
        prefs.all.keys
            .filter { it.startsWith(presetAliasPrefix(id)) }
            .forEach { editor.remove(it) }

        cleaned.forEach { (token, label) ->
            editor.putString(presetAliasKey(id, token), label)
        }
        editor.apply()
    }

    fun migrateLegacyPresetNamesIfNeeded() {
        if (prefs.getBoolean(KEY_PRESET_MIGRATED, false)) return

        val jsonText = loadLegacyPresetJsonText()
        if (jsonText.isNullOrBlank()) {
            prefs.edit().putBoolean(KEY_PRESET_MIGRATED, true).apply()
            return
        }

        val parsed = runCatching { parseLegacyPresetNames(jsonText) }
            .getOrElse { emptyMap() }

        val editor = prefs.edit()
        parsed.forEach { (camId, names) ->
            val cleanCamId = camId.trim()
            if (cleanCamId.isBlank()) return@forEach
            names.forEach { (token, label) ->
                val cleanToken = token.trim()
                val cleanLabel = label.trim()
                if (cleanToken.isNotBlank() && cleanLabel.isNotBlank()) {
                    editor.putString(presetAliasKey(cleanCamId, cleanToken), cleanLabel)
                }
            }
        }
        editor.putBoolean(KEY_PRESET_MIGRATED, true).apply()
    }


    private fun hasAnyLegacyDataFor(cameraId: String): Boolean {
        return !prefs.getString(keyUser(cameraId), null).isNullOrBlank() ||
                !prefs.getString(keyLocalHost(cameraId), null).isNullOrBlank() ||
                !prefs.getString(keyName(cameraId), null).isNullOrBlank()
    }

    private fun defaultNameFor(cameraId: String): String {
        val idx = cameraId.removePrefix("cam-").toIntOrNull()
        return if (idx != null) "Cámara $idx" else cameraId
    }

    private fun loadAliasesForCamera(cameraId: String): Map<String, String> {
        val prefix = presetAliasPrefix(cameraId)
        return prefs.all.entries.mapNotNull { (key, value) ->
            if (!key.startsWith(prefix)) return@mapNotNull null
            val token = key.removePrefix(prefix).trim()
            val label = (value as? String)?.trim().orEmpty()
            if (token.isBlank() || label.isBlank()) null else token to label
        }.toMap()
    }

    private fun loadLegacyPresetJsonText(): String? {
        val localFile = File(appContext.filesDir, LEGACY_PRESET_FILE)
        if (localFile.exists()) {
            return runCatching { localFile.readText() }.getOrNull()
        }
        return runCatching {
            appContext.assets.open(LEGACY_PRESET_FILE).bufferedReader().use { it.readText() }
        }.getOrNull()
    }

    private fun parseLegacyPresetNames(jsonText: String): Map<String, Map<String, String>> {
        val root = JSONObject(jsonText)
        val names = root.names() ?: return emptyMap()

        var hasNested = false
        for (i in 0 until names.length()) {
            val key = names.getString(i)
            if (root.opt(key) is JSONObject) {
                hasNested = true
                break
            }
        }

        if (!hasNested) {
            return mapOf("default" to jsonObjectToStringMap(root))
        }

        val out = mutableMapOf<String, Map<String, String>>()
        for (i in 0 until names.length()) {
            val key = names.getString(i)
            val obj = root.optJSONObject(key) ?: continue
            out[key] = jsonObjectToStringMap(obj)
        }
        return out
    }

    private fun jsonObjectToStringMap(obj: JSONObject): Map<String, String> {
        val out = mutableMapOf<String, String>()
        val names = obj.names() ?: return emptyMap()
        for (i in 0 until names.length()) {
            val key = names.getString(i)
            val value = obj.optString(key, "").trim()
            if (value.isNotEmpty()) out[key] = value
        }
        return out
    }

    private fun presetAliasPrefix(cameraId: String) = "$KEY_PRESET_ALIAS_PREFIX${cameraId.trim()}|"
    private fun presetAliasKey(cameraId: String, presetToken: String) =
        "$KEY_PRESET_ALIAS_PREFIX${cameraId.trim()}|${presetToken.trim()}"

    private fun createSecurePrefs(context: Context): SharedPreferences {
        return runCatching {
            EncryptedSharedPreferences.create(
                context,
                SECURE_PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }.getOrElse { firstError ->
            Log.w("SecureConfigStore", "Encrypted prefs invalid; resetting local secure store", firstError)
            clearEncryptedPrefsArtifacts()
            EncryptedSharedPreferences.create(
                context,
                SECURE_PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
    }

    private fun clearEncryptedPrefsArtifacts() {
        runCatching { appContext.deleteSharedPreferences(SECURE_PREFS_NAME) }

        val sharedPrefsDir = File(appContext.applicationInfo.dataDir, "shared_prefs")
        if (!sharedPrefsDir.isDirectory) return

        sharedPrefsDir.listFiles().orEmpty().forEach { file ->
            val name = file.name
            if (
                name == "$SECURE_PREFS_NAME.xml" ||
                name.contains("androidx_security_crypto_encrypted_prefs_key_keyset") ||
                name.contains("androidx_security_crypto_encrypted_prefs_value_keyset")
            ) {
                runCatching { file.delete() }
            }
        }
    }

    private fun keyName(cameraId: String) = "cam_name_$cameraId"
    private fun keyUser(cameraId: String) = "cam_user_$cameraId"
    private fun keyPass(cameraId: String) = "cam_pass_$cameraId"
    private fun keyLocalHost(cameraId: String) = "cam_local_host_$cameraId"
    private fun keyLocalRtspPort(cameraId: String) = "cam_local_rtsp_port_$cameraId"
    private fun keyLocalOnvifPort(cameraId: String) = "cam_local_onvif_port_$cameraId"
    private fun keyRemoteHost(cameraId: String) = "cam_remote_host_$cameraId"
    private fun keyRemoteRtspPort(cameraId: String) = "cam_remote_rtsp_port_$cameraId"
    private fun keyRemoteOnvifPort(cameraId: String) = "cam_remote_onvif_port_$cameraId"

    private companion object {
        val LEGACY_CAMERA_IDS = listOf("cam-1", "cam-2")
        const val KEY_BACKEND_URL = "backend_url"
        const val KEY_BACKEND_API_KEY = "backend_api_key"
        const val KEY_CAMERA_IDS = "camera_ids"
        const val SECURE_PREFS_NAME = "secure_app_config"
        const val KEY_PRESET_ALIAS_PREFIX = "preset_alias_"
        const val KEY_PRESET_MIGRATED = "preset_names_migrated_v1"
        const val LEGACY_PRESET_FILE = "preset_names.json"
    }
}
