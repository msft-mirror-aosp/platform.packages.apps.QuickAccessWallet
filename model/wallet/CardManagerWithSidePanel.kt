package com.android.systemui.plugin.globalactions.wallet.model

import com.android.systemui.plugin.globalactions.wallet.common.CardManager
import com.android.systemui.plugin.globalactions.wallet.common.CardManager.Result
import com.android.systemui.plugin.globalactions.wallet.reactive.Eventual
import com.android.systemui.plugin.globalactions.wallet.reactive.zipWith

/**
 * [CardManager] for top-level wallet view models.
 *
 * @param primary The "primary" source of cards for the Wallet
 * @param leftPanel Additional source of cards that are positioned to the left of the initial
 *                  selected card. If this successfully returns cards, then the selected card of the
 *                  primary manager is overridden and the first card is selected, in order to have
 *                  the left panel peeking on the left side.
 */
private class CardManagerWithSidePanel<T>(primary: CardManager<T>, leftPanel: CardManager<T>) :
        CardManager<T> {

    override val globalActionCards = primary.globalActionCards
            .zipWith(leftPanel.globalActionCards) { primaryResult, leftPanelResult ->
                when (primaryResult) {
                    is Result.Success ->
                        when (leftPanelResult) {
                            is Result.Success -> {
                                val selectedIndex =
                                    leftPanelResult.numCards + primaryResult.selectedIndex
                                val allCards = sequence {
                                    yieldAll(leftPanelResult.cards)
                                    yieldAll(primaryResult.cards)
                                }
                                val numCards = leftPanelResult.numCards + primaryResult.numCards
                                Result.Success(selectedIndex, allCards, numCards)
                            }
                            else -> primaryResult
                        }
                    else -> primaryResult
                }
            }
    override val dismissRequest = Eventual.race(primary.dismissRequest, leftPanel.dismissRequest)
}

/*internal*/ fun <T> CardManager<T>.withSidePanel(sidePanel: CardManager<T>): CardManager<T> =
    CardManagerWithSidePanel(this, sidePanel)