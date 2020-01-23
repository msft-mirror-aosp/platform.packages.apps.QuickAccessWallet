package com.android.systemui.plugin.globalactions.wallet.model

import com.android.systemui.plugin.globalactions.wallet.common.Factory
import com.android.systemui.plugin.globalactions.wallet.common.WalletPluginSubcomponent
import com.android.systemui.plugin.globalactions.wallet.common.injectDeps
import com.android.systemui.plugin.globalactions.wallet.reactive.Subscription
import com.android.systemui.plugin.globalactions.wallet.reactive.subscribe

interface WalletPluginController<in D, out U> {
    fun onCreate(): Subscription
    fun onPanelShown(deps: D): U
}

fun <U> WalletPluginController<Unit, U>.onPanelShown() = onPanelShown(Unit)

/** Wraps this subcomponent into a [WalletPluginController]. */
fun <D, UI> WalletPluginSubcomponent<Factory<D, UI>?>.toPluginController()
        : WalletPluginController<D, UI?> =
    WalletPluginControllerImpl(this)

private class WalletPluginControllerImpl<D, U>(
        val model: WalletPluginSubcomponent<Factory<D, U>?>
) : WalletPluginController<D, U?> {

    override fun onCreate() = model.pluginLifetimeProcess.startProcess.subscribe()
    override fun onPanelShown(deps: D) = model.getUiScopedSubcomponent()?.injectDeps(deps)
}