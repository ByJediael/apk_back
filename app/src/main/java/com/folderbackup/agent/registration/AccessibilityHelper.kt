package com.folderbackup.agent.registration

import android.content.Context
import android.provider.Settings
object AccessibilityHelper {
    fun isServiceEnabled(context: Context): Boolean {
        val expected = "${context.packageName}/${WhatsappRegistrationAccessibilityService::class.java.canonicalName}"
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }
}
