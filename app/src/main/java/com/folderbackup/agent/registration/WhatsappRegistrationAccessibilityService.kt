package com.folderbackup.agent.registration

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log
import com.folderbackup.agent.backup.WhatsappSessionExporter

class WhatsappRegistrationAccessibilityService : AccessibilityService() {

    private var lastActionAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return

        if (WhatsappMacroState.phase == WhatsappMacroState.Phase.FindWhatsAppIcon) {
            if (pkg == WhatsappSessionExporter.WHATSAPP_PACKAGE) {
                WhatsappMacroState.markDone()
                return
            }
            if (isLauncherPackage(pkg)) {
                val root = rootInActiveWindow ?: return
                val now = System.currentTimeMillis()
                if (now - lastActionAt < 400) return
                if (clickWhatsappIcon(root)) {
                    lastActionAt = now
                    WhatsappMacroState.markDone()
                }
            }
            return
        }

        if (pkg != WhatsappSessionExporter.WHATSAPP_PACKAGE &&
            pkg != "com.android.settings"
        ) {
            return
        }

        val root = rootInActiveWindow ?: return
        val now = System.currentTimeMillis()
        if (now - lastActionAt < 400) return

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
            WhatsappRegistrationState.Phase.EnterPairingCode -> {
                if (enterPairingCodeFlow(root)) {
                    lastActionAt = now
                    WhatsappRegistrationState.markPairingDone()
                }
            }
            else -> Unit
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility interrompido")
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
        val edit = when {
            fields.isNotEmpty() -> fields.first()
            else -> findFirstEditText(root)
        } ?: return false

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
        if (name.isNotBlank()) {
            val edit = findFirstEditText(root) ?: return false
            setText(edit, name)
        }
        val doneTexts = listOf("AVANÇAR", "PRÓXIMO", "NEXT", "CONTINUAR", "OK", "SALVAR", "CONCLUIR")
        for (text in doneTexts) {
            if (clickByText(root, text, partial = true)) return true
        }
        return false
    }

    private fun enterPairingCodeFlow(root: AccessibilityNodeInfo): Boolean {
        val code = WhatsappRegistrationState.pairingCode ?: return false

        val linkTexts = listOf(
            "VINCULAR COM NÚMERO",
            "VINCULAR COM NUMERO",
            "LINK WITH PHONE NUMBER",
            "LINK WITH PHONE",
            "USAR NÚMERO",
            "USAR NUMERO",
        )
        for (text in linkTexts) {
            if (clickByText(root, text, partial = true)) {
                return enterPairingCodeOnScreen(root, code)
            }
        }

        if (enterPairingCodeOnScreen(root, code)) return true

        val menuTexts = listOf("DISPOSITIVOS VINCULADOS", "LINKED DEVICES", "APARELHOS CONECTADOS")
        for (text in menuTexts) {
            if (clickByText(root, text, partial = true)) {
                return false
            }
        }
        return false
    }

    private fun enterPairingCodeOnScreen(root: AccessibilityNodeInfo, code: String): Boolean {
        val normalized = code.replace(Regex("[^A-Za-z0-9]"), "").uppercase()
        val edits = mutableListOf<AccessibilityNodeInfo>()
        collectEditTexts(root, edits)
        if (edits.isEmpty()) return false

        if (edits.size == 1) {
            if (!setText(edits.first(), normalized)) return false
        } else {
            normalized.take(edits.size).forEachIndexed { i, ch ->
                setText(edits[i], ch.toString())
            }
        }
        val confirm = listOf("VINCULAR", "LINK", "CONTINUAR", "OK", "AVANÇAR", "CONFIRMAR")
        for (text in confirm) {
            if (clickByText(root, text, partial = true)) return true
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
            "WhatsApp",
            "WhatsApp Business",
            "WA Business",
            "WABusiness",
        )
        for (label in labels) {
            val nodes = root.findAccessibilityNodeInfosByText(label)
            for (node in nodes) {
                if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true
                }
                if (performClickOnParent(node)) return true
            }
        }
        return false
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
    }
}
