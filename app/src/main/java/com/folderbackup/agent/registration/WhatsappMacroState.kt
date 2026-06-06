package com.folderbackup.agent.registration

object WhatsappMacroState {
    enum class Phase {
        Idle,
        GoHome,
        FindWhatsAppIcon,
        Done,
        Failed,
    }

    @Volatile
    var phase: Phase = Phase.Idle

    @Volatile
    var error: String? = null

    @Volatile
    var usedFallback: Boolean = false

    fun beginHome() {
        reset()
        phase = Phase.GoHome
    }

    fun beginOpenWhatsapp() {
        error = null
        usedFallback = false
        phase = Phase.FindWhatsAppIcon
    }

    fun markDone(fallback: Boolean = false) {
        usedFallback = fallback
        phase = Phase.Done
    }

    fun markFailed(msg: String) {
        error = msg
        phase = Phase.Failed
    }

    fun reset() {
        phase = Phase.Idle
        error = null
        usedFallback = false
    }

    fun isActive(): Boolean = phase in setOf(Phase.GoHome, Phase.FindWhatsAppIcon)
}
