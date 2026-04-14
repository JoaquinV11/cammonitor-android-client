package com.example.camara

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import java.net.URI

@Composable
private fun PasswordToggleTextField(
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

data class SetupCameraDraft(
    val name: String = "",
    val username: String = "",
    val password: String = "",
    val localHost: String = "",
    val remoteHost: String = "",
    val localRtspPort: String = "554",
    val localOnvifPort: String = "8899",
    val remoteRtspPort: String = "5554",
    val remoteOnvifPort: String = "18899"
)

private fun isValidHttpUrl(value: String): Boolean {
    return runCatching {
        val u = URI(value.trim())
        (u.scheme.equals("http", ignoreCase = true) || u.scheme.equals("https", ignoreCase = true)) && !u.host.isNullOrBlank()
    }.getOrDefault(false)
}

private fun parsePortOrNull(value: String): Int? =
    value.trim().toIntOrNull()?.takeIf { it in 1..65535 }

private fun SetupCameraDraft.hasValidPorts(): Boolean =
    parsePortOrNull(localRtspPort) != null &&
            parsePortOrNull(localOnvifPort) != null &&
            parsePortOrNull(remoteRtspPort) != null &&
            parsePortOrNull(remoteOnvifPort) != null

private fun SetupCameraDraft.isComplete(): Boolean =
    username.isNotBlank() && password.isNotBlank() && localHost.isNotBlank() && hasValidPorts()

@Composable
private fun SetupPortsAdvancedSection(
    draft: SetupCameraDraft,
    onDraftChange: (SetupCameraDraft) -> Unit
) {
    val localRtspInvalid = draft.localRtspPort.isNotBlank() && parsePortOrNull(draft.localRtspPort) == null
    val localOnvifInvalid = draft.localOnvifPort.isNotBlank() && parsePortOrNull(draft.localOnvifPort) == null
    val remoteRtspInvalid = draft.remoteRtspPort.isNotBlank() && parsePortOrNull(draft.remoteRtspPort) == null
    val remoteOnvifInvalid = draft.remoteOnvifPort.isNotBlank() && parsePortOrNull(draft.remoteOnvifPort) == null

    Spacer(Modifier.height(8.dp))
    Text("Avanzado", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(6.dp))
    Card(modifier = Modifier.fillMaxWidth()) {
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
                        value = draft.localRtspPort,
                        onValueChange = { onDraftChange(draft.copy(localRtspPort = it)) },
                        label = { Text("RTSP") },
                        isError = localRtspInvalid,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = draft.localOnvifPort,
                        onValueChange = { onDraftChange(draft.copy(localOnvifPort = it)) },
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
                        value = draft.remoteRtspPort,
                        onValueChange = { onDraftChange(draft.copy(remoteRtspPort = it)) },
                        label = { Text("RTSP") },
                        isError = remoteRtspInvalid,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = draft.remoteOnvifPort,
                        onValueChange = { onDraftChange(draft.copy(remoteOnvifPort = it)) },
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
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
fun SetupScreen(
    initialBackend: BackendConfig? = null,
    onSave: (BackendConfig, List<CameraCatalogItem>, List<CameraCredentials>, List<CameraNetworkConfig>) -> Unit
) {
    var backendUrl by remember(initialBackend?.baseUrl) {
        mutableStateOf(initialBackend?.baseUrl.orEmpty())
    }
    var apiKey by remember(initialBackend?.apiKey) {
        mutableStateOf(initialBackend?.apiKey.orEmpty())
    }
    var cameraDrafts by remember { mutableStateOf(listOf<SetupCameraDraft>()) }

    val backendUrlTrimmed = backendUrl.trim()
    val backendUrlValid = isValidHttpUrl(backendUrlTrimmed)
    val hasAtLeastOneCamera = cameraDrafts.isNotEmpty()
    val allCamerasValid = cameraDrafts.all { it.isComplete() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.Top
    ) {
        Text("Configuración inicial", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = backendUrl,
            onValueChange = { backendUrl = it },
            label = { Text("Backend URL (ej: http://192.168.0.10:8080)") },
            isError = backendUrlTrimmed.isNotEmpty() && !backendUrlValid,
            modifier = Modifier.fillMaxWidth()
        )
        if (backendUrlTrimmed.isNotEmpty() && !backendUrlValid) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Ingresá una URL válida con http:// o https://",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
        Spacer(Modifier.height(8.dp))
        PasswordToggleTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("Backend API Key") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Cámaras", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(
                onClick = {
                    val camIndex = cameraDrafts.size
                    cameraDrafts = cameraDrafts + SetupCameraDraft(
                        name = "Cámara ${camIndex + 1}",
                        remoteRtspPort = (5554 + (camIndex * 2)).toString(),
                        remoteOnvifPort = (18899 + (camIndex * 2)).toString()
                    )
                },
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MenuColors.accent,
                    disabledContentColor = MenuColors.accent.copy(alpha = 0.38f)
                ),
                border = BorderStroke(1.dp, MenuColors.accent)
            ) {
                Text("Agregar cámara")
            }
        }

        if (cameraDrafts.isEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Todavía no agregaste ninguna cámara.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        cameraDrafts.forEachIndexed { index, draft ->
            val displayIndex = index + 1
            Spacer(Modifier.height(12.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = draft.name.ifBlank { "Cámara $displayIndex" },
                            style = MaterialTheme.typography.titleMedium
                        )
                        OutlinedButton(
                            onClick = {
                                cameraDrafts = cameraDrafts.toMutableList().also { it.removeAt(index) }
                            },
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MenuColors.accent,
                                disabledContentColor = MenuColors.accent.copy(alpha = 0.38f)
                            ),
                            border = BorderStroke(1.dp, MenuColors.accent)
                        ) {
                            Text("Eliminar")
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = draft.name,
                        onValueChange = { value ->
                            cameraDrafts = cameraDrafts.toMutableList().also {
                                it[index] = it[index].copy(name = value)
                            }
                        },
                        label = { Text("Nombre visible (ej: Frente, Patio)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = draft.username,
                        onValueChange = { value ->
                            cameraDrafts = cameraDrafts.toMutableList().also {
                                it[index] = it[index].copy(username = value)
                            }
                        },
                        label = { Text("Usuario") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    PasswordToggleTextField(
                        value = draft.password,
                        onValueChange = { value ->
                            cameraDrafts = cameraDrafts.toMutableList().also {
                                it[index] = it[index].copy(password = value)
                            }
                        },
                        label = { Text("Contraseña") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = draft.localHost,
                        onValueChange = { value ->
                            cameraDrafts = cameraDrafts.toMutableList().also {
                                it[index] = it[index].copy(localHost = value)
                            }
                        },
                        label = { Text("Host local (ej: 192.168.1.20)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = draft.remoteHost,
                        onValueChange = { value ->
                            cameraDrafts = cameraDrafts.toMutableList().also {
                                it[index] = it[index].copy(remoteHost = value)
                            }
                        },
                        label = { Text("Host remoto opcional (DDNS/Tailscale)") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    SetupPortsAdvancedSection(
                        draft = draft,
                        onDraftChange = { updated ->
                            cameraDrafts = cameraDrafts.toMutableList().also {
                                it[index] = updated
                            }
                        }
                    )
                }
            }
        }

        if (hasAtLeastOneCamera && !allCamerasValid) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Completá usuario, contraseña, host local y puertos válidos (1-65535) en todas las cámaras o eliminá las que no uses.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }

        Spacer(Modifier.height(20.dp))
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                val drafts = cameraDrafts
                val catalog = drafts.mapIndexed { index, cam ->
                    CameraCatalogItem(
                        cameraId = "cam-${index + 1}",
                        name = cam.name.trim().ifBlank { "Cámara ${index + 1}" }
                    )
                }
                val creds = drafts.mapIndexed { index, cam ->
                    CameraCredentials("cam-${index + 1}", cam.username.trim(), cam.password)
                }
                val nets = drafts.mapIndexed { index, cam ->
                    CameraNetworkConfig(
                        cameraId = "cam-${index + 1}",
                        localHost = cam.localHost.trim(),
                        localRtspPort = parsePortOrNull(cam.localRtspPort) ?: 554,
                        localOnvifPort = parsePortOrNull(cam.localOnvifPort) ?: 8899,
                        remoteHost = cam.remoteHost.trim().ifBlank { null },
                        remoteRtspPort = parsePortOrNull(cam.remoteRtspPort) ?: (5554 + (index * 2)),
                        remoteOnvifPort = parsePortOrNull(cam.remoteOnvifPort) ?: (18899 + (index * 2))
                    )
                }

                onSave(
                    BackendConfig(baseUrl = backendUrlTrimmed, apiKey = apiKey.trim()),
                    catalog,
                    creds,
                    nets
                )
            },
            enabled = backendUrlValid && hasAtLeastOneCamera && allCamerasValid,
            colors = ButtonDefaults.buttonColors(
                containerColor = MenuColors.accent,
                contentColor = MenuColors.onAccent,
                disabledContainerColor = MenuColors.accent.copy(alpha = 0.38f),
                disabledContentColor = MenuColors.onAccent.copy(alpha = 0.38f)
            )
        ) {
            Text("Guardar configuración")
        }
    }
}
