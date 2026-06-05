package com.folderbackup.agent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.folderbackup.agent.R
import com.folderbackup.agent.registration.AccessibilityHelper
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.folderbackup.agent.data.AppPreferences
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val config by viewModel.config.collectAsStateWithLifecycle()
    var a11yEnabled by remember { mutableStateOf(AccessibilityHelper.isServiceEnabled(context)) }

    var apiUrl by remember { mutableStateOf(AppPreferences.DEFAULT_API_URL) }
    var token by remember { mutableStateOf("") }
    var deviceId by remember { mutableStateOf("") }

    LaunchedEffect(config) {
        val cfg = config ?: return@LaunchedEffect
        viewModel.syncDraftFromConfig(cfg)
        apiUrl = viewModel.draftApiUrl
        token = viewModel.draftToken
        deviceId = viewModel.draftDeviceId
    }

    val cardColors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("WhatsApp Backup") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
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
                    "Agente remoto — configure uma vez. Limpar, backup e restaurar vêm pela API.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item {
                Card(modifier = Modifier.fillMaxWidth(), colors = cardColors) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Servidor (FCM)",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = apiUrl,
                            onValueChange = {
                                apiUrl = it
                                viewModel.draftApiUrl = it
                            },
                            label = { Text("URL base") },
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
                        Button(
                            onClick = {
                                viewModel.draftApiUrl = apiUrl
                                viewModel.draftToken = token
                                viewModel.draftDeviceId = deviceId
                                viewModel.saveApiSettings()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Salvar")
                        }
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth(), colors = cardColors) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Usar root (Magisk)", style = MaterialTheme.typography.bodyLarge)
                            Switch(
                                checked = config?.useRootEnabled ?: false,
                                onCheckedChange = viewModel::setUseRootEnabled,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = viewModel::testRootAccess,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Testar root")
                        }
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth(), colors = cardColors) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Montagem automática", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.accessibility_enable_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (a11yEnabled) "Acessibilidade: ativa" else "Acessibilidade: desativada",
                            color = if (a11yEnabled) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = {
                                viewModel.openAccessibilitySettings()
                                a11yEnabled = AccessibilityHelper.isServiceEnabled(context)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Abrir configurações de acessibilidade")
                        }
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth(), colors = cardColors) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Status", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(config?.lastStatusMessage ?: "Aguardando configuração…")
                        val last = config?.lastSyncAtMillis ?: 0L
                        if (last > 0L) {
                            val formatted = DateFormat.getDateTimeInstance().format(Date(last))
                            Text(
                                "Última atividade: $formatted",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        config?.deviceId?.takeIf { it.isNotBlank() }?.let { id ->
                            Text(
                                "Device: $id",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
}
