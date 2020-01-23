package com.android.systemui.plugin.globalactions.wallet.model

import com.android.systemui.plugin.globalactions.wallet.common.BackendAvailabilityChecker
import com.android.systemui.plugin.globalactions.wallet.common.Setting
import com.android.systemui.plugin.globalactions.wallet.common.WalletPluginSubcomponent
import com.android.systemui.plugin.globalactions.wallet.common.map
import com.android.systemui.plugin.globalactions.wallet.common.mergeWith
import com.android.systemui.plugin.globalactions.wallet.common.provider

/**
 * Guards the invocation of [WalletPluginSubcomponent.getUiScopedSubcomponent] with additional checks
 * based on various settings. If these checks do not pass,
 * [WalletPluginSubcomponent.getUiScopedSubcomponent] will return `null`.
 *
 * @param deviceProvisionedSetting [Setting.Provider] that reflects whether or not the user has
 *  completed SetupWizard.
 * @param lockdownSetting [Setting.Provider] reflects whether user has enabled lockdown
 * @param walletAvailableSetting [Setting] that controls whether or not Wallet is available on this
 *  device.
 * @param walletEnabledSetting [Setting.Provider] that reflects whether or not the user has enabled
 *  Wallet in Settings.
 * @param backendAvailabilityChecker Checks whether or not the Wallet backend is available.
 */
/*internal*/ fun <F> WalletPluginSubcomponent<F>.guardUiWithSettings(
        deviceProvisionedSetting: Setting.Provider<Boolean>,
        lockdownSetting: Setting.Provider<Boolean>,
        walletAvailableSetting: Setting<Boolean?>,
        walletEnabledSetting: Setting<Boolean?>,
        backendAvailabilityChecker: BackendAvailabilityChecker
): WalletPluginSubcomponent<F?> =
    GuardUiWithSettingsSubcomponentWrapper(
            this,
            deviceProvisionedSetting,
            lockdownSetting,
            walletAvailableSetting,
            walletEnabledSetting,
            backendAvailabilityChecker
    )

private class GuardUiWithSettingsSubcomponentWrapper<F>(
        val inner: WalletPluginSubcomponent<F>,
        val deviceProvisionedSetting: Setting.Provider<Boolean>,
        val lockdownSetting: Setting.Provider<Boolean>,
        val walletAvailableSetting: Setting<Boolean?>,
        val walletEnabledSetting: Setting<Boolean?>,
        backendAvailabilityChecker: BackendAvailabilityChecker
) : WalletPluginSubcomponent<F?> {

    val availabilitySyncer = BackendAvailabilitySynchronizer(
            backendAvailabilityChecker,
            walletAvailableSetting,
            walletEnabledSetting
    )

    override val pluginLifetimeProcess = availabilitySyncer.mergeWith(inner.pluginLifetimeProcess)

    override fun getUiScopedSubcomponent(): F? {
        val walletAvailableSettingProvider = walletAvailableSetting.provider().map { it ?: false }
        val walletEnabledSettingProvider = walletEnabledSetting.provider().map { it ?: false }
        // Don't show the panel if the device hasn't completed SetupWizard
        if (!deviceProvisionedSetting.value) {
            return null
        }
        // Don't show the panel if the device is in lockdown
        if (lockdownSetting.value) {
            return null
        }
        if (!walletAvailableSettingProvider.value) {
            // Asynchronously check if the panel is available, updating the setting for next
            // invocation.
            availabilitySyncer.checkAvailable()
            return null
        }
        if (walletEnabledSettingProvider.value) {
            return inner.getUiScopedSubcomponent()
        }
        return null
    }
}
