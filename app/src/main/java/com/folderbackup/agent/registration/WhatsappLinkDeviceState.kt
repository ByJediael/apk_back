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
        Done,
        Failed,
    }

    @Volatile
    var step: Step = Step.Idle

    @Volatile
    var error: String? = null

    fun beginNavigation() {
        reset()
        step = Step.OpenOverflowMenu
    }

    fun beginEnterCode() {
        step = Step.EnterCode
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
            Step.EnterCode -> Step.Done
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

    fun needsA11y(): Boolean = isNavigationActive() || step == Step.EnterCode

    fun reset() {
        step = Step.Idle
        error = null
    }
}
