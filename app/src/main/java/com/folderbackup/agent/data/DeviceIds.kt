package com.folderbackup.agent.data

import android.content.Context
import android.provider.Settings

object DeviceIds {
    /**
     * ID estável por aparelho (ANDROID_ID), curto e legível no backend.
     * Ex.: `dev-a1b2c3d4`
     */
    fun automatic(context: Context): String {
        val raw = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?.trim()
            .orEmpty()
        val hex = raw.lowercase().replace(Regex("[^0-9a-f]"), "")
        val short = when {
            hex.length >= 8 -> hex.take(8)
            hex.isNotEmpty() -> hex.padEnd(8, '0')
            else -> "unknown0"
        }
        return "dev-$short"
    }
}
