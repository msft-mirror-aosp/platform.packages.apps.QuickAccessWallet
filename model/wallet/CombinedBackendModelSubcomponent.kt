package com.android.systemui.plugin.globalactions.wallet.model

import com.android.systemui.plugin.globalactions.wallet.common.CardManager
import com.android.systemui.plugin.globalactions.wallet.common.Combined
import com.android.systemui.plugin.globalactions.wallet.common.OnWalletDismissedListener
import com.android.systemui.plugin.globalactions.wallet.common.WalletBackendSubcomponent
import com.android.systemui.plugin.globalactions.wallet.common.WalletUiModelSubcomponent
import com.android.systemui.plugin.globalactions.wallet.common.injectDeps
import com.android.systemui.plugin.globalactions.wallet.common.mergeWith

/** Subcomponent comprised of both a primary and secondary subcomponent. */
typealias CombinedBackendModelSubcomponent<VM, PD, SD> =
        WalletBackendSubcomponent<Combined<PD, SD>, VM>

/**
 * Adds a "secondary" backend to this one. Cards from this backend are placed before the start of
 * the primary cards, and the selected index is ignored.
 *
 * @param secondary Backend used to supply the side-panel of cards.
 */
fun <VM, PD, SD> WalletBackendSubcomponent<PD, VM>.withSecondary(
        secondary: WalletBackendSubcomponent<SD, VM>
): CombinedBackendModelSubcomponent<VM, PD, SD> =
    CombinedSubcomponentImpl(this, secondary)

private class CombinedSubcomponentImpl<VM, PD, SD>(
        val primary: WalletBackendSubcomponent<PD, VM>,
        val secondary: WalletBackendSubcomponent<SD, VM>
) : CombinedBackendModelSubcomponent<VM, PD, SD> {

    override val pluginLifetimeProcess = primary.pluginLifetimeProcess
            .mergeWith(secondary.pluginLifetimeProcess)

    override fun getUiScopedSubcomponent() = { deps: Combined<PD, SD> ->
        val primaryUi = primary.getUiScopedSubcomponent().injectDeps(deps.first)
        val secondaryUi = secondary.getUiScopedSubcomponent().injectDeps(deps.second)
        UiSubcomponent(
                primaryUi.uiCardManager.withSidePanel(secondaryUi.uiCardManager),
                primaryUi.onWalletDismissedListener.mergeWith(secondaryUi.onWalletDismissedListener)
        )
    }

    class UiSubcomponent<VM>(
            override val uiCardManager: CardManager<VM>,
            override val onWalletDismissedListener: OnWalletDismissedListener
    ) : WalletUiModelSubcomponent<VM>
}