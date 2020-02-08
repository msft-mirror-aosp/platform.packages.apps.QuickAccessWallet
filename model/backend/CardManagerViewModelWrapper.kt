/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.plugin.globalactions.wallet.backend.model

import com.android.systemui.plugin.globalactions.wallet.common.BackgroundThreadRunner
import com.android.systemui.plugin.globalactions.wallet.common.CardManager
import com.android.systemui.plugin.globalactions.wallet.common.CardManager.Result.Failure
import com.android.systemui.plugin.globalactions.wallet.common.CardManager.Result.Success
import com.android.systemui.plugin.globalactions.wallet.common.PendingIntentSender
import com.android.systemui.plugin.globalactions.wallet.common.andThen
import com.android.systemui.plugin.globalactions.wallet.common.onBackgroundThread
import com.android.systemui.plugin.globalactions.wallet.reactive.Completable
import com.android.systemui.plugin.globalactions.wallet.reactive.Eventual
import com.android.systemui.plugin.globalactions.wallet.reactive.andThenPotentially
import com.android.systemui.plugin.globalactions.wallet.reactive.eventualOf
import com.android.systemui.plugin.globalactions.wallet.reactive.map
import com.android.systemui.plugin.globalactions.wallet.reactive.potentialLazy
import com.android.systemui.plugin.globalactions.wallet.reactive.potentialOf
import com.android.systemui.plugin.globalactions.wallet.reactive.zip
import com.android.systemui.plugin.globalactions.wallet.reactive.zipWith
import com.android.systemui.plugin.globalactions.wallet.view.common.BitmapCardViewModel
import com.android.systemui.plugin.globalactions.wallet.view.common.GlobalActionCardContainerViewModel
import com.android.systemui.plugin.globalactions.wallet.view.common.GlobalActionCardViewModel
import com.android.systemui.plugin.globalactions.wallet.view.common.TopLevelViewModel

/**
 * [CardManager] that fetches global action cards from backend and returns them as
 * [TopLevelViewModel]s which can be rendered in the Wallet UI.
 */
private class CardManagerViewModelWrapper<TIcon, TDrawable, TPendingIntent>(
        backend: CardManager<QuickAccessWalletCardData<TIcon, TPendingIntent>>,
        pendingIntentSender: PendingIntentSender<TPendingIntent>,
        bitmapConverter: DrawableCreator<TIcon, TDrawable>,
        bgThreadRunner: BackgroundThreadRunner,
        cardSelector: CardSelector
) : CardManager<TopLevelViewModel<TDrawable>> by backend.andThen({
    toViewModelManager(it, pendingIntentSender, bitmapConverter, bgThreadRunner, cardSelector)
})

/*internal*/ fun <TIcon, TDrawable, TPendingIntent>
CardManager<QuickAccessWalletCardData<TIcon, TPendingIntent>>.toViewModels(
        pendingIntentSender: PendingIntentSender<TPendingIntent>,
        bitmapConverter: DrawableCreator<TIcon, TDrawable>,
        bgThreadRunner: BackgroundThreadRunner,
        cardSelector: CardSelector
): CardManager<TopLevelViewModel<TDrawable>> =
    CardManagerViewModelWrapper(
            this,
            pendingIntentSender,
            bitmapConverter,
            bgThreadRunner,
            cardSelector
    )

/** Creates drawables to be rendered in the Wallet UI from data-layer representations. */
interface DrawableCreator<TIcon, TDrawable> {
    fun toDrawable(data: TIcon): Eventual<TDrawable>
}

private fun <TIcon, TDrawable, TPendingIntent> toViewModelManager(
        result: CardManager.Result<QuickAccessWalletCardData<TIcon, TPendingIntent>>,
        pendingIntentSender: PendingIntentSender<TPendingIntent>,
        bitmapConverter: DrawableCreator<TIcon, TDrawable>,
        transformer: BackgroundThreadRunner,
        cardSelector: CardSelector
): Eventual<CardManager.Result<TopLevelViewModel<TDrawable>>> =
    when (result) {
        is Success<QuickAccessWalletCardData<TIcon, TPendingIntent>> -> {
            fun toViewModel(index: Int, card: QuickAccessWalletCardData<TIcon, TPendingIntent>)
                    : Eventual<TopLevelViewModel<TDrawable>> {
                val showSettingsButton = index == result.numCards - 1
                val cardImage =
                    bitmapConverter.toDrawable(card.cardImage).onBackgroundThread(transformer)
                val cardIcon = potentialOf(card.messageIcon).andThenPotentially { icon ->
                    bitmapConverter.toDrawable(icon).onBackgroundThread(transformer)
                }
                return cardImage.zipWith(cardIcon).map { (cardImage, iconImage) ->
                    TopLevelViewModel.bitmapViewModel(
                            PictureCardViewModel(
                                    cardImage,
                                    card.cardContentDescription,
                                    card.cardId,
                                    card.cardType,
                                    cardSelector,
                                    card.clickIntent,
                                    card.messageText,
                                    iconImage,
                                    pendingIntentSender,
                                    showSettingsButton
                            )
                    )
                }
            }

            fun toResult(cards: List<TopLevelViewModel<TDrawable>>)
                    : CardManager.Result<TopLevelViewModel<TDrawable>> =
                Success(result.selectedIndex, cards.asSequence(), result.numCards)

            result.cards.mapIndexed(::toViewModel).toList().zip(::toResult)
        }
        is Failure -> eventualOf(Failure(result.message))
    }

/** Data needed from backend in order to construct ViewModels. */
data class QuickAccessWalletCardData<TIcon, TPendingIntent>(
        val cardImage: TIcon,
        val cardContentDescription: CharSequence,
        val messageText: CharSequence?,
        val clickIntent: TPendingIntent?,
        val messageIcon: TIcon?,
        val cardId: String,
        val cardType: Int
)

/** Handles selection of cards in the UI. */
interface CardSelector {
    fun selectCard(cardId: String, cardType: Int): Completable
}

/** View model for "picture" cards, where the card content consists of a single image. */
internal class PictureCardViewModel<TIcon, TPendingIntent>(
        override val cardImage: TIcon,
        override val contentDescription: CharSequence,
        cardId: String,
        cardType: Int,
        cardSelector: CardSelector,
        clickIntent: TPendingIntent?,
        override val cardLabel: CharSequence?,
        override val cardLabelImage: TIcon?,
        pendingIntentSender: PendingIntentSender<TPendingIntent>,
        override val showSettingsButton: Boolean
) : GlobalActionCardContainerViewModel<TIcon>,
        BitmapCardViewModel<TIcon>,
        GlobalActionCardViewModel {

    override val click = potentialLazy {
        clickIntent?.let { if (pendingIntentSender.sendPendingIntent(clickIntent)) Unit else null }
    }
    override val select = cardSelector.selectCard(cardId, cardType)
}