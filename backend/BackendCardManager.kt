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

package com.android.systemui.plugin.globalactions.wallet.backend

import android.app.PendingIntent
import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.service.quickaccesswallet.GetWalletCardsError
import android.service.quickaccesswallet.GetWalletCardsRequest
import android.service.quickaccesswallet.GetWalletCardsResponse
import android.service.quickaccesswallet.QuickAccessWalletClient
import android.service.quickaccesswallet.SelectWalletCardRequest
import android.service.quickaccesswallet.WalletCard
import android.service.quickaccesswallet.WalletServiceEvent
import com.android.systemui.plugin.globalactions.wallet.backend.model.CardSelector
import com.android.systemui.plugin.globalactions.wallet.backend.model.DrawableCreator
import com.android.systemui.plugin.globalactions.wallet.backend.model.ModelComponent
import com.android.systemui.plugin.globalactions.wallet.backend.model.ModelUiDeps
import com.android.systemui.plugin.globalactions.wallet.backend.model.QuickAccessWalletCardData
import com.android.systemui.plugin.globalactions.wallet.common.BackendAvailabilityChecker
import com.android.systemui.plugin.globalactions.wallet.common.BackgroundThreadRunner
import com.android.systemui.plugin.globalactions.wallet.common.CardDimens
import com.android.systemui.plugin.globalactions.wallet.common.CardManager
import com.android.systemui.plugin.globalactions.wallet.common.CardManager.Result
import com.android.systemui.plugin.globalactions.wallet.common.PendingIntentSender
import com.android.systemui.plugin.globalactions.wallet.common.PluginLifetimeProcess
import com.android.systemui.plugin.globalactions.wallet.common.WalletBackendSubcomponent
import com.android.systemui.plugin.globalactions.wallet.common.injectDeps
import com.android.systemui.plugin.globalactions.wallet.reactive.Completable
import com.android.systemui.plugin.globalactions.wallet.reactive.Eventual
import com.android.systemui.plugin.globalactions.wallet.reactive.completable
import com.android.systemui.plugin.globalactions.wallet.reactive.completableAction
import com.android.systemui.plugin.globalactions.wallet.reactive.eventual
import com.android.systemui.plugin.globalactions.wallet.reactive.eventualLazy
import com.android.systemui.plugin.globalactions.wallet.view.common.TopLevelViewModel
import java.util.function.Consumer

interface BackendUiDeps {
    val cardDimens: CardDimens
    val pendingIntentSender: PendingIntentSender<PendingIntent>
}

private const val MAX_CARDS = 5

private class BackendCardManager(
        private val cardDimens: CardDimens,
        private val client: QuickAccessWalletClient
) : CardManager<QuickAccessWalletCardData<Icon, PendingIntent>> {

    override val globalActionCards: Eventual<Result<QuickAccessWalletCardData<Icon, PendingIntent>>> =
            eventual {
                val request = GetWalletCardsRequest(
                        cardDimens.cardWidthPx,
                        cardDimens.cardHeightPx,
                        cardDimens.iconSizePx,
                        MAX_CARDS
                )
                client.getWalletCards(
                        request,
                        Consumer<GetWalletCardsResponse> { response ->
                            val cards = response.walletCards
                            if (cards.size > 0) {
                                complete(Result.Success(
                                        selectedIndex = response.selectedIndex,
                                        cards = cards.asSequence().map(::toModel),
                                        numCards = cards.size
                                ))
                            } else {
                                complete(Result.Failure(null)) //TODO: error message?
                            }
                        },
                        Consumer<GetWalletCardsError> {
                            complete(Result.Failure(it.message))
                        })
            }

    override val dismissRequest: Completable
        get() = completable {
            client.registerWalletServiceEventListener(Consumer<WalletServiceEvent> { event ->
                if (event.eventType == WalletServiceEvent.TYPE_NFC_PAYMENT_STARTED) {
                    complete(Unit)
                }
            })
        }
}

private fun toModel(card: WalletCard): QuickAccessWalletCardData<Icon, PendingIntent> = card.run {
    QuickAccessWalletCardData(
            cardImage,
            contentDescription,
            cardLabel,
            pendingIntent,
            cardIcon,
            cardId,
            0
    )
}

private class CardSelectorImpl(private val client: QuickAccessWalletClient) : CardSelector {
    override fun selectCard(cardId: String, cardType: Int) = completableAction {
        client.selectWalletCard(SelectWalletCardRequest(cardId))
    }
}

private class BackendAvailabilityCheckerImpl(
        client: QuickAccessWalletClient
) : BackendAvailabilityChecker {
    override val isAvailable = eventualLazy { client.isWalletServiceAvailable }
}

interface BackendSubcomponent :
        WalletBackendSubcomponent<BackendUiDeps, TopLevelViewModel<Drawable>> {

    val availabilityChecker: BackendAvailabilityChecker
}

fun BackendSubcomponent(
        bgThreadRunner: BackgroundThreadRunner,
        context: Context
): BackendSubcomponent =
        BackendSubcomponentImpl(bgThreadRunner, context)

private class BackendSubcomponentImpl(
        private val bgThreadRunner: BackgroundThreadRunner,
        private val context: Context
) : BackendSubcomponent {

    private val inner = ModelComponent<Icon, Drawable, PendingIntent>()
    private val client = QuickAccessWalletClient.create(context)

    override val pluginLifetimeProcess: PluginLifetimeProcess
        get() = inner.pluginLifetimeProcess

    override fun getUiScopedSubcomponent() = { deps: BackendUiDeps ->
        inner.getUiScopedSubcomponent().injectDeps(deps.toModelDeps())
    }

    override val availabilityChecker = BackendAvailabilityCheckerImpl(client)

    private fun BackendUiDeps.toModelDeps() = ModelUiDepsImpl(this)

    private inner class ModelUiDepsImpl(backendUiDeps: BackendUiDeps) :
            ModelUiDeps<Icon, Drawable, PendingIntent> {
        override val backend = BackendCardManager(backendUiDeps.cardDimens, client)
        override val pendingIntentSender = backendUiDeps.pendingIntentSender
        override val bitmapConverter = IconDrawableCreator(context)
        override val bgThreadRunner = this@BackendSubcomponentImpl.bgThreadRunner
        override val cardSelector = CardSelectorImpl(client)
    }
}

private class IconDrawableCreator(private val context: Context) : DrawableCreator<Icon, Drawable> {
    override fun toDrawable(data: Icon): Eventual<Drawable> = eventualLazy {
        data.loadDrawable(context)
    }
}