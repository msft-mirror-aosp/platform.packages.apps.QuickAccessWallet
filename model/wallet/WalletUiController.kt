package com.android.systemui.plugin.globalactions.wallet.model

import com.android.systemui.plugin.globalactions.wallet.common.CardManager
import com.android.systemui.plugin.globalactions.wallet.common.CardManager.Result
import com.android.systemui.plugin.globalactions.wallet.common.OnWalletDismissedListener
import com.android.systemui.plugin.globalactions.wallet.common.Setting
import com.android.systemui.plugin.globalactions.wallet.reactive.Completable
import com.android.systemui.plugin.globalactions.wallet.reactive.EventStream
import com.android.systemui.plugin.globalactions.wallet.reactive.Subscription
import com.android.systemui.plugin.globalactions.wallet.reactive.andThen
import com.android.systemui.plugin.globalactions.wallet.reactive.andThenCompletable
import com.android.systemui.plugin.globalactions.wallet.reactive.asEventStream
import com.android.systemui.plugin.globalactions.wallet.reactive.changes
import com.android.systemui.plugin.globalactions.wallet.reactive.completableAction
import com.android.systemui.plugin.globalactions.wallet.reactive.completeWhen
import com.android.systemui.plugin.globalactions.wallet.reactive.doOnCancel
import com.android.systemui.plugin.globalactions.wallet.reactive.eventualLazy
import com.android.systemui.plugin.globalactions.wallet.reactive.filter
import com.android.systemui.plugin.globalactions.wallet.reactive.flatMapPotential
import com.android.systemui.plugin.globalactions.wallet.reactive.map
import com.android.systemui.plugin.globalactions.wallet.reactive.mapConsume
import com.android.systemui.plugin.globalactions.wallet.reactive.mergeEvents
import com.android.systemui.plugin.globalactions.wallet.reactive.mergeWith
import com.android.systemui.plugin.globalactions.wallet.reactive.subscribe
import com.android.systemui.plugin.globalactions.wallet.reactive.switchCompletable
import com.android.systemui.plugin.globalactions.wallet.view.common.GlobalActionCardViewModel

interface WalletUiController<TViewModel> {
    fun connect(): Subscription
}

interface WalletUiCallbacks<TViewModel> {
    fun dismissGlobalActionsMenu()
    fun showErrorMessage(error: CharSequence)
    fun populateUi(cards: List<TViewModel>, selectedIndex: Int)
}

interface SettingsLauncher {
    fun showSettings(): Boolean
}

/*internal*/ class WalletUiControllerImpl<TViewModel: GlobalActionCardViewModel>(
        private val viewModelManager: CardManager<TViewModel>,
        private val panelAvailableWhenLockedSetting: Setting.Provider<Boolean>,
        private val settingsLauncher: SettingsLauncher,
        private val dismissRequests: EventStream<Any>,
        private val callbacks: WalletUiCallbacks<TViewModel>,
        private val lockEvents: EventStream<Boolean>,
        private val selectedIndexEvents: EventStream<Int>,
        private val clickEvents: EventStream<Int>,
        private val settingsEvents: EventStream<Unit>,
        private val onWalletDismissedListener: OnWalletDismissedListener,
        private val lockedErrorMessage: CharSequence,
        private val walletDisabler: WalletDisabler
) : WalletUiController<TViewModel> {

    private val queryCards: Completable =
        viewModelManager.globalActionCards.andThenCompletable { result ->
            when (result) {
                is Result.Success -> initializeWalletUI(result, viewModelManager.dismissRequest)
                is Result.Failure -> completableAction {
                    result.message?.let(callbacks::showErrorMessage)
                }
                is Result.Disabled -> walletDisabler.disableWallet
            }
        }

    private fun initializeWalletUI(result: Result.Success<TViewModel>, dismissRequest: Completable)
            : Completable {
        val cards = result.cards.toList()
        val selectedEvents = selectedIndexEvents.map { cards[it] }
        val clickEvents = clickEvents.map { cards[it] }
        val settingsEvents = settingsEvents.flatMapPotential {
            eventualLazy { settingsLauncher.showSettings() }.filter { it }
        }
        val dismissEvents = mergeEvents(
                clickEvents.flatMapPotential { it.click },
                settingsEvents,
                dismissRequest.asEventStream()
        )
        val handleUiEvents = selectedEvents.switchCompletable { it.select }
                .mergeWith(dismissEvents.mapConsume { callbacks.dismissGlobalActionsMenu() })
        val initializeWalletUI = completableAction {
            callbacks.populateUi(cards, result.selectedIndex)
        }
        return initializeWalletUI.andThen(handleUiEvents)
    }

    override fun connect(): Subscription = dismissRequests
            .mapConsume { callbacks.dismissGlobalActionsMenu() }
            .mergeWith(lockEvents.changes()
                    .completeWhen(false)
                    .switchCompletable { locked ->
                        if (locked && !panelAvailableWhenLockedSetting.value)
                            completableAction { callbacks.showErrorMessage(lockedErrorMessage) }
                        else
                            queryCards
                    })
            .doOnCancel(onWalletDismissedListener::onWalletDismissed)
            .subscribe()
}