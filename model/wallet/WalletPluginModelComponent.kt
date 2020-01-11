package com.android.systemui.plugin.globalactions.wallet.model

import com.android.systemui.plugin.globalactions.wallet.common.BackendAvailabilityChecker
import com.android.systemui.plugin.globalactions.wallet.common.Combined
import com.android.systemui.plugin.globalactions.wallet.common.Factory
import com.android.systemui.plugin.globalactions.wallet.common.Setting
import com.android.systemui.plugin.globalactions.wallet.common.WalletPluginSubcomponent
import com.android.systemui.plugin.globalactions.wallet.common.WalletUiModelSubcomponent
import com.android.systemui.plugin.globalactions.wallet.common.injectDeps
import com.android.systemui.plugin.globalactions.wallet.common.mergeWith
import com.android.systemui.plugin.globalactions.wallet.reactive.EventStream
import com.android.systemui.plugin.globalactions.wallet.view.common.GlobalActionCardViewModel

/** Dependencies needed in order to instantiate a [UiModelComponent]. */
interface UiModelDependencies<VM> {

    /** Determines whether or not the UI should be shown when the device is locked. */
    val uiAvailableWhenLockedSetting: Setting.Provider<Boolean>

    /** Used to show the settings for Wallet. */
    val settingsLauncher: SettingsLauncher

    /** Requests from the user to dismiss Wallet, sourced from the UI. */
    val uiDismissRequests: EventStream<Any>

    /** Callbacks used to notify the UI of updates. */
    val callbacks: WalletUiCallbacks<VM>

    /** Events signaling that the device lock state has changed. */
    val lockEvents: EventStream<Boolean>

    /** Events signaling that the user has selected a card in the UI. */
    val selections: EventStream<Int>

    /** Events signaling that the user has clicked a card in the UI. */
    val clicks: EventStream<Int>

    /** Events signaling that the user has clicked the settings button in the UI. */
    val settingsClicks: EventStream<Unit>

    /**
     * Error message to be displayed when the Wallet cannot be shown due to the device being locked.
     */
    val lockedErrorMessage: CharSequence
}

/** A Plugin subcomponent that produces a [UiModelComponent] when the ui is shown. */
typealias WalletComponentModel<VM, D> =
        WalletPluginSubcomponent<UiModelComponentFactory<VM, D>?>

/** A [Factory] that constructs [UiModelComponent] instances */
typealias UiModelComponentFactory<VM, D> =
        Factory<Combined<UiModelDependencies<VM>, D>, UiModelComponent<VM>>

/**
 * Connects the given [backend] to the rest of the Wallet plugin logic, returning a complete Wallet
 * "component".
 *
 * @param backend Backend "subcomponent" for Wallet
 * @param deviceProvisionedSetting [Setting.Provider] that reflects whether or not the user has
 *  completed SetupWizard.
 * @param walletAvailableSetting [Setting] that controls whether or not Wallet is available on this
 *  device.
 * @param walletEnabledSetting [Setting.Provider] that reflects whether or not the user has enabled
 *  Wallet in Settings.
 * @param backendAvailabilityChecker Checks whether or not the Wallet backend is available.
 * @param featureFlag Feature flag controlling querying the backend for availability
 */
fun <D, VM : GlobalActionCardViewModel> WalletComponentModel(
        backend: WalletPluginSubcomponent<Factory<D, WalletUiModelSubcomponent<VM>>?>,
        walletAvailableSetting: Setting<Boolean?>,
        walletEnabledSetting: Setting<Boolean?>,
        deviceProvisionedSetting: Setting.Provider<Boolean>,
        lockdownSetting: Setting.Provider<Boolean>,
        backendAvailabilityChecker: BackendAvailabilityChecker
): WalletComponentModel<VM, D> =
        backend.uiSubcomponentToComponent(walletAvailableSetting).guardUiWithSettings(
                deviceProvisionedSetting,
                lockdownSetting,
                walletAvailableSetting,
                walletEnabledSetting,
                backendAvailabilityChecker
        )

/**
 * Wraps the backend UI subcomponents into a [UiModelComponent], which can be used to control the
 * Wallet UI.
 *
 * @param walletAvailableSetting Setting that controls whether wallet is available, used to disable
 *  wallet in the event the backend reports that it is disabled *after* the UI has been shown.
 */
/*internal*/ fun <VM : GlobalActionCardViewModel, D>
WalletPluginSubcomponent<Factory<D, WalletUiModelSubcomponent<VM>>?>.uiSubcomponentToComponent(
        walletAvailableSetting: Setting<Boolean?>
): WalletComponentModel<VM, D> =
        UiComponentWrapper(this, walletAvailableSetting)

private class UiComponentWrapper<VM : GlobalActionCardViewModel, D>(
        val walletSubcomponent: WalletPluginSubcomponent<
                Factory<D, WalletUiModelSubcomponent<VM>>?>,
        walletAvailableSetting: Setting<Boolean?>
) : WalletComponentModel<VM, D> {

    val walletDisabler = WalletDisablerImpl(walletAvailableSetting)

    override val pluginLifetimeProcess = walletDisabler
            .mergeWith(walletSubcomponent.pluginLifetimeProcess)

    override fun getUiScopedSubcomponent() =
            walletSubcomponent.getUiScopedSubcomponent()?.let { factory ->
                { deps: Combined<UiModelDependencies<VM>, D> -> UiComponent(deps, factory) }
            }

    inner class UiComponent(
            deps: Combined<UiModelDependencies<VM>, D>,
            uiModelFactory: Factory<D, WalletUiModelSubcomponent<VM>>
    ) : UiModelComponent<VM> {
        override val uiController =
                deps.first.run {
                    uiModelFactory.injectDeps(deps.second).run {
                        WalletUiControllerImpl(
                                uiCardManager,
                                uiAvailableWhenLockedSetting,
                                settingsLauncher,
                                uiDismissRequests,
                                callbacks,
                                lockEvents,
                                selections,
                                clicks,
                                settingsClicks,
                                onWalletDismissedListener,
                                lockedErrorMessage,
                                walletDisabler
                        )
                    }
                }
    }
}

/** Component for wallet ui scope. */
interface UiModelComponent<VM> {

    /**
     * UI Controller, receives forwarded events from the Wallet UI and updates the UI via provided
     * [WalletUiCallbacks].
     */
    val uiController: WalletUiController<VM>
}

/** Component for wallet plugin scope. */
interface PluginControllerComponent<D, UI> {

    /** Plugin controller, receives forwarded calls from plugin. */
    val pluginController: WalletPluginController<D, UI>
}

