package com.folderbackup.agent.registration

/**
 * Navegação no WhatsApp Business até "Conectar com número de telefone".
 */
object WhatsappLinkDeviceState {
    enum class Step {
        Idle,
        OpenOverflowMenu,
        TapConnectedDevices,
        TapConnectDevice,
        TapLinkWithPhone,
        ReadyForCode,
        EnterCode,
        ConfirmScamWarning,
        NameLinkedDevice,
        Done,
        Failed,
    }

    @Volatile
    var step: Step = Step.Idle

    @Volatile
    var deviceName: String? = null

    @Volatile
    var error: String? = null

    fun beginNavigation() {
        reset()
        step = Step.OpenOverflowMenu
    }

    fun beginEnterCode() {
        pairingCodeSubmitted = false
        step = Step.EnterCode
    }

    @Volatile
    var deviceNameApplied: Boolean = false

    /** Evita redigitar código (invalida pairing na Evolution). */
    @Volatile
    var pairingCodeSubmitted: Boolean = false

    @Volatile
    private var pairingCodeEntryInProgress: Boolean = false

    fun tryBeginCodeEntry(): Boolean {
        if (pairingCodeSubmitted) return false
        synchronized(this) {
            if (pairingCodeEntryInProgress || pairingCodeSubmitted) return false
            pairingCodeEntryInProgress = true
            return true
        }
    }

    fun endCodeEntry() {
        pairingCodeEntryInProgress = false
    }

    fun beginNameDevice(name: String) {
        deviceName = name.trim().ifBlank { DEFAULT_DEVICE_NAME }
        deviceNameApplied = false
        step = Step.NameLinkedDevice
    }

    fun markReadyForCode() {
        step = Step.ReadyForCode
    }

    fun markDone() {
        step = Step.Done
    }

    fun markFailed(msg: String) {
        error = msg
        step = Step.Failed
    }

    fun advanceFrom(stepDone: Step) {
        step = when (stepDone) {
            Step.OpenOverflowMenu -> Step.TapConnectedDevices
            Step.TapConnectedDevices -> Step.TapConnectDevice
            Step.TapConnectDevice -> Step.TapLinkWithPhone
            Step.TapLinkWithPhone -> Step.ReadyForCode
            Step.EnterCode -> Step.ConfirmScamWarning
            Step.ConfirmScamWarning -> Step.NameLinkedDevice
            Step.NameLinkedDevice -> Step.Done
            else -> step
        }
    }

    fun isNavigationActive(): Boolean =
        step in setOf(
            Step.OpenOverflowMenu,
            Step.TapConnectedDevices,
            Step.TapConnectDevice,
            Step.TapLinkWithPhone,
        )

    fun isActive(): Boolean = step != Step.Idle && step != Step.Failed

    fun needsA11y(): Boolean =
        isNavigationActive() || step == Step.EnterCode ||
        step == Step.ConfirmScamWarning || step == Step.NameLinkedDevice

    fun reset() {
        step = Step.Idle
        deviceName = null
        deviceNameApplied = false
        pairingCodeSubmitted = false
        pairingCodeEntryInProgress = false
        error = null
    }

    const val DEFAULT_DEVICE_NAME = "Evolution Backup"
}
