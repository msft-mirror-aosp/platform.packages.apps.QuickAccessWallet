package com.android.systemui.plugin.globalactions.wallet.model

import com.android.systemui.plugin.globalactions.wallet.common.Setting
import com.android.systemui.plugin.globalactions.wallet.common.WalletPluginSubcomponent
import com.android.systemui.plugin.globalactions.wallet.common.mapUiScopedSubcomponent

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
        walletEnabledSetting: Setting.Provider<Boolean>
): WalletPluginSubcomponent<F?> =
    GuardUiWithSettingsSubcomponentWrapper(
            this,
            deviceProvisionedSetting,
            lockdownSetting,
            walletAvailableSetting,
            walletEnabledSetting
    )

private class GuardUiWithSettingsSubcomponentWrapper<F>(
        val inner: WalletPluginSubcomponent<F>,
        val deviceProvisionedSetting: Setting.Provider<Boolean>,
        val lockdownSetting: Setting.Provider<Boolean>,
        val walletAvailableSetting: Setting.Provider<Boolean>,
        val walletEnabledSetting: Setting.Provider<Boolean>
) : WalletPluginSubcomponent<F?> {

    override val pluginLifetimeProcess = inner.pluginLifetimeProcess

    override fun getUiScopedSubcomponent(): F? {
        // Don't show the panel if the device hasn't completed SetupWizard
        if (!deviceProvisionedSetting.value) {
            return null
        }
        // Don't show the panel if the device is in lockdown
        if (lockdownSetting.value) {
            return null
        }
        // Don't show the panel if the wallet feature is unavailable on the device
        if (!walletAvailableSetting.value) {
            return null
        }
        // Don't show the panel if the wallet feature is disabled by the user
        if (!walletEnabledSetting.value) {
            return null
        }
        return inner.getUiScopedSubcomponent()
    }
}
