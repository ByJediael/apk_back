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
            if (now - lastActionAt < 1200) return
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
