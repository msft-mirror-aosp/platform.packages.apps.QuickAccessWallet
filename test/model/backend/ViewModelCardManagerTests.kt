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

import com.android.systemui.plugin.globalactions.wallet.common.CardManager.Result.Disabled
import com.android.systemui.plugin.globalactions.wallet.common.CardManager.Result.Failure
import com.android.systemui.plugin.globalactions.wallet.common.CardManager.Result.Success
import com.android.systemui.plugin.globalactions.wallet.common.PendingIntentSender
import com.android.systemui.plugin.globalactions.wallet.model.FakeBackgroundThreadRunner
import com.android.systemui.plugin.globalactions.wallet.model.FakeCardManager
import com.android.systemui.plugin.globalactions.wallet.reactive.BroadcastingEventSource
import com.android.systemui.plugin.globalactions.wallet.reactive.Completable
import com.android.systemui.plugin.globalactions.wallet.reactive.Eventual
import com.android.systemui.plugin.globalactions.wallet.reactive.asCompletable
import com.android.systemui.plugin.globalactions.wallet.reactive.completable
import com.android.systemui.plugin.globalactions.wallet.reactive.eventualOf
import com.android.systemui.plugin.globalactions.wallet.reactive.first
import com.android.systemui.plugin.globalactions.wallet.reactive.getBlocking
import com.android.systemui.plugin.globalactions.wallet.reactive.map
import com.android.systemui.plugin.globalactions.wallet.reactive.subscribe
import com.android.systemui.plugin.globalactions.wallet.view.common.TopLevelViewModel
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ViewModelCardManagerTests {

    @Test
    fun globalActionCards_emitsFailure_ifBackendFails() {
        val manager = FakeCardManager(eventualOf(Failure<QuickAccessWalletCardData<Any, Any>>("foo")))
                .toViewModels(
                        FakePendingIntentSender(),
                        FakeDrawableCreator(),
                        FakeBackgroundThreadRunner,
                        FakeCardSelector()
                )
        val result = manager.globalActionCards.getBlocking()
        assertThat(result).isEqualTo(Failure<Any>("foo"))
    }

    @Test
    fun globalActionCards_emitsDisabled_ifBackendIsDisabled() {
        val manager = FakeCardManager(eventualOf(Disabled<QuickAccessWalletCardData<Any, Any>>()))
                .toViewModels(
                        FakePendingIntentSender(),
                        FakeDrawableCreator(),
                        FakeBackgroundThreadRunner,
                        FakeCardSelector()
                )
        val result = manager.globalActionCards.getBlocking()
        assertThat(result).isInstanceOf(Disabled::class.java)
    }

    @Test
    fun globalActionCards_emitsSuccess_whenBackendQuerySucceeds() {
        val fakeCard = QuickAccessWalletCardData(
                "cardImage",
                "contentDescription",
                "messageText",
                "clickIntent",
                "messageIcon",
                "cardId",
                10
        )
        val fakeSender = FakePendingIntentSender<String>(true)
        val fakeSelector = FakeCardSelector()
        val manager = FakeCardManager(eventualOf(Success(0, sequenceOf(fakeCard), 1)))
                .toViewModels(
                        fakeSender,
                        FakeDrawableCreator(),
                        FakeBackgroundThreadRunner,
                        fakeSelector
                )
        val result = manager.globalActionCards.getBlocking()
        assertThat(result).isInstanceOf(Success::class.java)
        val success = result as Success<*>
        assertThat(success.selectedIndex).isEqualTo(0)
        assertThat(success.numCards).isEqualTo(1)
        val card = success.cards.first()!!
        assertThat(card).isInstanceOf(TopLevelViewModel.BitmapViewModel::class.java)
        val viewModel = card as TopLevelViewModel.BitmapViewModel<*>
        assertThat(viewModel.cardImage).isEqualTo("cardImage")
        assertThat(viewModel.contentDescription).isEqualTo("contentDescription")
        assertThat(viewModel.cardLabel).isEqualTo("messageText")
        assertThat(viewModel.cardLabelImage).isEqualTo("messageIcon")
        viewModel.click.subscribe()
        assertThat(fakeSender.intents).containsExactly("clickIntent")
        viewModel.select.subscribe()
        assertThat(fakeSelector.cardSelections).containsExactly("cardId" to 10)
    }

    @Test
    fun globalActionCards_viewModelShowSettingsButton_isTrue_whenOnlyOneCardPresent() {
        val fakeCard = QuickAccessWalletCardData(
                "cardImage",
                "contentDescription",
                "messageText",
                "clickIntent",
                "messageIcon",
                "cardId",
                10
        )
        val manager = FakeCardManager(eventualOf(Success(0, sequenceOf(fakeCard), 1)))
                .toViewModels(
                        FakePendingIntentSender(),
                        FakeDrawableCreator(),
                        FakeBackgroundThreadRunner,
                        FakeCardSelector()
                )
        val result = manager.globalActionCards.getBlocking()
        val success = result as Success<*>
        val card = success.cards.first()!!
        val viewModel = card as TopLevelViewModel<*>
        assertThat(viewModel.showSettingsButton).isTrue()
    }

    @Test
    fun globalActionCards_viewModelShowSettingsButton_isFalse_whenMoreThanOneCardPresent() {
        val fakeCard = QuickAccessWalletCardData(
                "cardImage",
                "contentDescription",
                "messageText",
                "clickIntent",
                "messageIcon",
                "cardId",
                10
        )
        val manager = FakeCardManager(eventualOf(Success(1, sequenceOf(fakeCard, fakeCard), 2)))
                .toViewModels(
                        FakePendingIntentSender(),
                        FakeDrawableCreator(),
                        FakeBackgroundThreadRunner,
                        FakeCardSelector()
                )
        val result = manager.globalActionCards.getBlocking()
        val success = result as Success<*>
        val card = success.cards.first()!!
        val viewModel = card as TopLevelViewModel<*>
        assertThat(viewModel.showSettingsButton).isFalse()
    }

    @Test
    fun globalActionCards_viewModelClick_emitsNothing_ifClickIntentIsNull() {
        val fakeCard = QuickAccessWalletCardData(
                "cardImage",
                "contentDescription",
                "messageText",
                null,
                "messageIcon",
                "cardId",
                10
        )
        val manager = FakeCardManager(eventualOf(Success(0, sequenceOf(fakeCard), 1)))
                .toViewModels(
                        FakePendingIntentSender(true),
                        FakeDrawableCreator(),
                        FakeBackgroundThreadRunner,
                        FakeCardSelector()
                )
        val result = manager.globalActionCards.getBlocking()
        val success = result as Success<*>
        val card = success.cards.first()!!
        val viewModel = card as TopLevelViewModel<*>
        val clickResult = viewModel.click.getBlocking()
        assertThat(clickResult).isNull()
    }

    @Test
    fun globalActionCards_viewModelClick_emitsNothing_ifPendingIntentSenderReturnsFalse() {
        val fakeCard = QuickAccessWalletCardData(
                "cardImage",
                "contentDescription",
                "messageText",
                "clickIntent",
                "messageIcon",
                "cardId",
                10
        )
        val fakeSender = FakePendingIntentSender<String>(false)
        val manager = FakeCardManager(eventualOf(Success(0, sequenceOf(fakeCard), 1)))
                .toViewModels(
                        fakeSender,
                        FakeDrawableCreator(),
                        FakeBackgroundThreadRunner,
                        FakeCardSelector()
                )
        val result = manager.globalActionCards.getBlocking()
        val success = result as Success<*>
        val card = success.cards.first()!!
        val viewModel = card as TopLevelViewModel<*>
        val clickResult = viewModel.click.getBlocking()
        assertThat(fakeSender.intents).containsExactly("clickIntent")
        assertThat(clickResult).isNull()
    }

    @Test
    fun globalActionCards_convertsAllAvailableBitmapSimultaneously() {
        val fakeCard1 = QuickAccessWalletCardData(
                "cardImage1",
                "contentDescription",
                "messageText",
                "clickIntent",
                "messageIcon1",
                "cardId",
                10
        )
        val fakeCard2 = QuickAccessWalletCardData(
                "cardImage2",
                "contentDescription",
                "messageText",
                "clickIntent",
                null,
                "cardId",
                10
        )
        val pendingConversions = mutableListOf<String>()
        val conversion = BroadcastingEventSource<Any>()
        val fakeBitmapConverter = object : DrawableCreator<String, String> {
            override fun toDrawable(data: String): Eventual<String> {
                pendingConversions.add(data)
                return conversion.first().map { "converted-$data" }
            }
        }
        val manager = FakeCardManager(eventualOf(Success(0, sequenceOf(fakeCard1, fakeCard2), 2)))
                .toViewModels(
                        FakePendingIntentSender(),
                        fakeBitmapConverter,
                        FakeBackgroundThreadRunner,
                        FakeCardSelector()
                )
        var completed = false
        manager.globalActionCards.subscribe { result ->
            completed = true
            val success = result as Success<*>

            val cardImages = success.cards
                    .map { (it as TopLevelViewModel.BitmapViewModel<*>).cardImage }
                    .toList()
            assertThat(cardImages)
                    .containsExactly("converted-cardImage1", "converted-cardImage2")

            val messageIcons = success.cards
                    .map { (it as TopLevelViewModel.BitmapViewModel<*>).cardLabelImage }
                    .toList()
            assertThat(messageIcons).containsExactly("converted-messageIcon1", null)
        }
        assertThat(pendingConversions).containsAllOf("cardImage1", "cardImage2", "messageIcon1")
        conversion.complete()
        assertThat(completed).isTrue()
    }

    @Test
    fun dismissRequest_forwardsFromBackend() {
        val dismissal = BroadcastingEventSource<Any>()
        val manager =
            FakeCardManager<QuickAccessWalletCardData<Any, Any>>(
                    dismissRequest = dismissal.asCompletable()
            ).toViewModels(
                    FakePendingIntentSender(),
                    FakeDrawableCreator(),
                    FakeBackgroundThreadRunner,
                    FakeCardSelector()
            )
        var dismissed = false
        manager.dismissRequest.subscribe {
            dismissed = true
        }
        dismissal.complete()
        assertThat(dismissed).isTrue()
    }
}

private class FakeCardSelector : CardSelector {
    val cardSelections = mutableListOf<Pair<String, Int>>()
    override fun selectCard(cardId: String, cardType: Int): Completable =
        completable { cardSelections.add(cardId to cardType) }
}

private class FakeDrawableCreator<A>() : DrawableCreator<A, A> {
    override fun toDrawable(data: A): Eventual<A> = eventualOf(data)
}

private class FakePendingIntentSender<T>(
        private val success: Boolean = true
) : PendingIntentSender<T> {

    private val _intents = mutableListOf<T>()
    val intents: List<T> = _intents
    override fun sendPendingIntent(pendingIntent: T): Boolean {
        _intents.add(pendingIntent)
        return success
    }
}
