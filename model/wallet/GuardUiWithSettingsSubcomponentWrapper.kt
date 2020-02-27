package com.android.systemui.plugin.globalactions.wallet.model

import com.android.systemui.plugin.globalactions.wallet.common.Setting
import com.android.systemui.plugin.globalactions.wallet.common.WalletPluginSubcomponent
import com.android.systemui.plugin.globalactions.wallet.common.mapUiScopedSubcomponent
import com.android.systemui.plugin.globalactions.wallet.reactive.Logger


/**
 * Guards the invocation of [WalletPluginSubcomponent.getUiScopedSubcomponent] with additional checks
 * based on various settings. If these checks do not pass,
 * [WalletPluginSubcomponent.getUiScopedSubcomponent] will return `null`.
 *
 * @param deviceProvisionedSetting [Setting.Provider] that reflects whether or not the user has
 *  completed SetupWizard.
 * @param lockdownSetting [Setting.Provider] reflects whether user has enabled lockdown
 * @param walletAvailableSetting [Setting.Provider] that reflects whether or not Wallet is available
 *  on this device.
 * @param walletEnabledSetting [Setting.Provider] that reflects whether or not the user has enabled
 *  Wallet in Settings.
 */
/*internal*/ fun <F> WalletPluginSubcomponent<F>.guardUiWithSettings(
        deviceProvisionedSetting: Setting.Provider<Boolean>,
        lockdownSetting: Setting.Provider<Boolean>,
        walletAvailableSetting: Setting.Provider<Boolean>,
        walletEnabledSetting: Setting.Provider<Boolean>,
        logger: Logger
): WalletPluginSubcomponent<F?> =
    GuardUiWithSettingsSubcomponentWrapper(
            this,
            deviceProvisionedSetting,
            lockdownSetting,
            walletAvailableSetting,
            walletEnabledSetting,
            logger
    )

private class GuardUiWithSettingsSubcomponentWrapper<F>(
        val inner: WalletPluginSubcomponent<F>,
        val deviceProvisionedSetting: Setting.Provider<Boolean>,
        val lockdownSetting: Setting.Provider<Boolean>,
        val walletAvailableSetting: Setting.Provider<Boolean>,
        val walletEnabledSetting: Setting.Provider<Boolean>,
        val logger: Logger
) : WalletPluginSubcomponent<F?> {

    override val pluginLifetimeProcess = inner.pluginLifetimeProcess

    override fun getUiScopedSubcomponent(): F? {
        fun log(s: String) = logger("Suppressing cards & passes: $s")
        // Don't show the panel if the device hasn't completed SetupWizard
        if (!deviceProvisionedSetting.value) {
            log("device not yet provisioned")
            return null
        }
        // Don't show the panel if the device is in lockdown
        if (lockdownSetting.value) {
            log("device in lockdown")
            return null
        }
        // Don't show the panel if the wallet feature is unavailable on the device
        if (!walletAvailableSetting.value) {
            log("feature unavailable on device")
            return null
        }
        // Don't show the panel if the wallet feature is disabled by the user
        if (!walletEnabledSetting.value) {
            logger("feature disabled by user")
            return null
        }
        return inner.getUiScopedSubcomponent()
    }
}
