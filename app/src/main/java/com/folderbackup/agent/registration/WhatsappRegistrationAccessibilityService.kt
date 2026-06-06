package com.folderbackup.agent.registration

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log
import com.folderbackup.agent.backup.WhatsappSessionExporter
import com.folderbackup.agent.service.BackupForegroundService

class WhatsappRegistrationAccessibilityService : AccessibilityService() {

    private var lastActionAt = 0L
    private var profileNameApplied = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        profileNameApplied = false
        if (!BackupForegroundService.isRunning) {
            WhatsappAutomationGate.releaseAll()
        }
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return

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
            if (now - lastActionAt < 1200) return
            if (handleLinkDeviceNavigation(root, now)) {
                lastActionAt = now
            }
            return
        }

        if (pkg != WhatsappSessionExporter.WHATSAPP_PACKAGE &&
            pkg != "com.android.settings"
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
        if (!WhatsappAutomationGate.allowAutomation()) return false
        if (!WhatsappLinkDeviceState.needsA11y()) return false
        val root = whatsappRoot() ?: return false
        val now = System.currentTimeMillis()
        // Digitar código: tenta a cada ciclo; menu: throttle 1,2s.
        val throttleMs = if (WhatsappLinkDeviceState.step == WhatsappLinkDeviceState.Step.EnterCode) {
            800L
        } else {
            1200L
        }
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

    private fun handleLinkDeviceNavigation(root: AccessibilityNodeInfo, now: Long): Boolean {
        when (WhatsappLinkDeviceState.step) {
            WhatsappLinkDeviceState.Step.OpenOverflowMenu -> {
                if (clickOverflowMenu(root)) {
                    WhatsappLinkDeviceState.advanceFrom(WhatsappLinkDeviceState.Step.OpenOverflowMenu)
                    Log.i(TAG, "Menu ⋮ aberto")
                    return true
                }
                // Fallback: Configurações → Aparelhos conectados
                val settings = listOf("CONFIGURAÇÕES", "CONFIGURACOES", "SETTINGS")
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
                    "LINK A DEVICE",
                    "CONNECT A DEVICE",
                    "ADD DEVICE",
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
                )
                for (text in texts) {
                    if (clickByText(root, text, partial = true)) {
                        WhatsappLinkDeviceState.advanceFrom(WhatsappLinkDeviceState.Step.TapLinkWithPhone)
                        return true
                    }
                }
            }
            WhatsappLinkDeviceState.Step.EnterCode -> {
                val code = WhatsappRegistrationState.pairingCode ?: return false
                val waRoot = whatsappRoot() ?: root
                if (enterPairingCodeOnScreen(waRoot, code)) {
                    WhatsappLinkDeviceState.markDone()
                    WhatsappRegistrationState.markPairingDone()
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
        val normalized = code.replace(Regex("[^A-Za-z0-9]"), "").uppercase()
        if (normalized.length < 8) {
            Log.w(TAG, "enterPairingCode: código curto ($normalized)")
            return false
        }

        val edits = findPairingCodeEdits(root)
        if (edits.isEmpty()) {
            val pkg = root.packageName?.toString().orEmpty()
            Log.w(TAG, "enterPairingCode: nenhum campo pkg=$pkg")
            return false
        }

        for (field in edits) {
            field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            setText(field, "")
        }

        val chars = normalized.take(edits.size.coerceAtMost(normalized.length))
        for (i in chars.indices) {
            val field = edits[i]
            field.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            if (!setText(field, chars[i].toString())) {
                Log.w(TAG, "setText falhou campo ${i + 1}/${edits.size}")
                return false
            }
        }

        if (!verifyPairingCodeEntered(edits, chars)) {
            Log.w(TAG, "Pairing code incompleto após digitar: $chars")
            return false
        }

        Log.i(TAG, "Pairing digitado: $chars campos=${edits.size}")
        clickPairingSubmit(root)
        return true
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

        if (edits.isEmpty()) {
            collectEditTextsByContentDesc(root, "campo 1 de 8", edits)
            if (edits.isEmpty()) {
                collectEditTextsByContentDesc(root, "campo", edits)
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
        if (edits.size < expected.length) return false
        for (i in expected.indices) {
            val ch = edits[i].text?.toString()?.trim().orEmpty()
                .replace(Regex("[^A-Za-z0-9]"), "")
                .uppercase()
            if (ch != expected[i].toString()) return false
        }
        return true
    }

    private fun clickByText(root: AccessibilityNodeInfo, text: String, partial: Boolean): Boolean {
        val nodes = root.findAccessibilityNodeInfosByText(text)
        for (node in nodes) {
            if (partial || node.text?.toString()?.equals(text, ignoreCase = true) == true) {
                if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true
                }
                if (performClickOnParent(node)) return true
            }
        }
        return false
    }

    private fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun performClickOnParent(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        repeat(6) {
            current = current?.parent ?: return@repeat
            if (current?.isClickable == true && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
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

    companion object {
        private const val TAG = "WaRegA11y"

        @Volatile
        var instance: WhatsappRegistrationAccessibilityService? = null

        fun pressHome(): Boolean {
            val service = instance ?: return false
            return service.performGlobalAction(GLOBAL_ACTION_HOME)
        }

        /** Chamado pelo coordinator em loop — não espera eventos passivos. */
        fun runLinkDeviceStep(): Boolean = instance?.tickLinkDevice() ?: false

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
    }
}
