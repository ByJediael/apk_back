package com.folderbackup.agent.registration

import android.content.Context
import android.provider.Settings
object AccessibilityHelper {
    /** Settings do sistema — não exige serviço já bound (evita falso negativo após reinstall). */
    fun isServiceEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        if (enabled.isBlank()) return false
        val pkg = context.packageName
        val canonical = WhatsappRegistrationAccessibilityService::class.java.canonicalName
        val candidates = setOf(
            "$pkg/$canonical",
            "$pkg/.registration.WhatsappRegistrationAccessibilityService",
            "$pkg/${WhatsappRegistrationAccessibilityService::class.java.name}",
        )
        return enabled.split(':').any { entry ->
            candidates.any { it.equals(entry.trim(), ignoreCase = true) } ||
                (entry.startsWith(pkg, ignoreCase = true) &&
                    entry.contains("WhatsappRegistrationAccessibilityService", ignoreCase = true))
        }
    }

    fun isServiceConnected(): Boolean =
        WhatsappRegistrationAccessibilityService.instance != null
}
