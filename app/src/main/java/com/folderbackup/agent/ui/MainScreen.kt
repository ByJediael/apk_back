package com.folderbackup.agent.ui

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.folderbackup.agent.data.AppPreferences
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(config) {
        if (config != null) viewModel.syncDraftFromConfig()
    }

    var apiUrl by remember { mutableStateOf(AppPreferences.DEFAULT_API_URL) }
    var token by remember { mutableStateOf("") }
    var deviceId by remember { mutableStateOf("") }

    LaunchedEffect(viewModel.draftApiUrl, viewModel.draftToken, viewModel.draftDeviceId) {
        apiUrl = viewModel.draftApiUrl
        token = viewModel.draftToken
        deviceId = viewModel.draftDeviceId
    }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(uri, flags)
        val label = DocumentsContract.getTreeDocumentId(uri).substringAfterLast('/')
        viewModel.onFolderPicked(label, uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Folder Backup Agent") })
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "Agente para n8n: consulta comandos na API, faz backup/restore das pastas autorizadas.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("API", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = apiUrl,
                            onValueChange = {
                                apiUrl = it
                                viewModel.draftApiUrl = it
                            },
                            label = { Text("URL base (ex: http://192.168.0.10:8080)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = token,
                            onValueChange = {
                                token = it
                                viewModel.draftToken = it
                            },
                            label = { Text("Token Bearer") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = deviceId,
                            onValueChange = {
                                deviceId = it
                                viewModel.draftDeviceId = it
                            },
                            label = { Text("Device ID") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                viewModel.draftApiUrl = apiUrl
                                viewModel.draftToken = token
                                viewModel.draftDeviceId = deviceId
                                viewModel.saveApiSettings()
                            }) {
                                Text("Salvar")
                            }
                            Button(onClick = {
                                viewModel.draftApiUrl = apiUrl
                                viewModel.draftToken = token
                                viewModel.draftDeviceId = deviceId
                                viewModel.testApiConnection()
                            }) {
                                Text("Testar API")
                            }
                        }
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Sincronizar só no Wi‑Fi", style = MaterialTheme.typography.bodyLarge)
                            Switch(
                                checked = config?.syncOnlyOnWifi ?: true,
                                onCheckedChange = viewModel::setWifiOnly,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = viewModel::syncNow,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.Sync, contentDescription = null)
                            Text("  Sincronizar agora")
                        }
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Pastas monitoradas", style = MaterialTheme.typography.titleMedium)
                    Button(onClick = { folderPicker.launch(null) }) {
                        Icon(Icons.Default.Folder, contentDescription = null)
                        Text("  Adicionar")
                    }
                }
            }

            val folders = config?.watchedFolders.orEmpty()
            if (folders.isEmpty()) {
                item {
                    Text("Nenhuma pasta. Toque em Adicionar e escolha DCIM, Download, etc.")
                }
            } else {
                items(folders, key = { it.id }) { folder ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(folder.label, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    folder.id,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                )
                            }
                            IconButton(onClick = { viewModel.removeFolder(folder.id) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Remover")
                            }
                        }
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Status", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(config?.lastStatusMessage ?: "—")
                        val last = config?.lastSyncAtMillis ?: 0L
                        if (last > 0L) {
                            val formatted = DateFormat.getDateTimeInstance().format(Date(last))
                            Text("Última atividade: $formatted", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            item {
                Text(
                    "Endpoints esperados:\n" +
                        "GET /api/v1/devices/{deviceId}/commands\n" +
                        "POST /api/v1/upload (multipart)\n" +
                        "PATCH /api/v1/jobs/{id}/progress\n" +
                        "GET /api/v1/backups/{id}/manifest\n" +
                        "GET /api/v1/health",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
