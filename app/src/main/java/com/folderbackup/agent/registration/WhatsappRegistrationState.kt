package com.folderbackup.agent.registration

/**
 * Estado compartilhado entre coordinators e [WhatsappRegistrationAccessibilityService].
 */
object WhatsappRegistrationState {
    enum class Phase {
        Idle,
        EnterPhone,
        WaitCode,
        EnterCode,
        ProfileSetup,
        Done,
        Failed,
        EnterPairingCode,
        PairingDone,
    }

    @Volatile
    var phase: Phase = Phase.Idle

    @Volatile
    var phoneE164: String? = null

    @Volatile
    var sessionLabel: String? = null

    @Volatile
    var displayName: String? = null

    @Volatile
    var smsCode: String? = null

    @Volatile
    var pairingCode: String? = null

    @Volatile
    var error: String? = null

    fun phoneDigitsForUi(): String {
        val raw = phoneE164 ?: return ""
        return raw.replace(Regex("[^0-9]"), "").removePrefix("55")
    }

    fun begin(phone: String, label: String, name: String?) {
        reset()
        phoneE164 = phone
        sessionLabel = label
        displayName = name
        phase = Phase.EnterPhone
    }

    fun beginPairing(code: String) {
        pairingCode = code.replace(Regex("[^A-Za-z0-9]"), "").uppercase()
        error = null
        phase = Phase.EnterPairingCode
    }

    fun submitCode(code: String) {
        smsCode = code.filter { it.isDigit() }
        if (phase == Phase.WaitCode) {
            phase = Phase.EnterCode
        }
    }

    fun markWaitCode() {
        if (phase == Phase.EnterPhone) phase = Phase.WaitCode
    }

    fun markProfileSetup() {
        phase = Phase.ProfileSetup
    }

    fun markDone() {
        phase = Phase.Done
    }

    fun markPairingDone() {
        phase = Phase.PairingDone
    }

    fun markFailed(msg: String) {
        error = msg
        phase = Phase.Failed
    }

    fun reset() {
        phase = Phase.Idle
        phoneE164 = null
        sessionLabel = null
        displayName = null
        smsCode = null
        pairingCode = null
        error = null
    }
}
