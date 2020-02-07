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

package com.android.systemui.plugin.globalactions.wallet.model

import com.android.systemui.plugin.globalactions.wallet.common.CardManager
import com.android.systemui.plugin.globalactions.wallet.common.CardManager.Result.Failure
import com.android.systemui.plugin.globalactions.wallet.common.CardManager.Result.Success
import com.android.systemui.plugin.globalactions.wallet.common.OnWalletDismissedListener
import com.android.systemui.plugin.globalactions.wallet.common.Setting
import com.android.systemui.plugin.globalactions.wallet.reactive.BroadcastingEventSource
import com.android.systemui.plugin.globalactions.wallet.reactive.EventStream
import com.android.systemui.plugin.globalactions.wallet.reactive.completable
import com.android.systemui.plugin.globalactions.wallet.reactive.completed
import com.android.systemui.plugin.globalactions.wallet.reactive.emptyPotential
import com.android.systemui.plugin.globalactions.wallet.reactive.events
import com.android.systemui.plugin.globalactions.wallet.reactive.eventsOf
import com.android.systemui.plugin.globalactions.wallet.reactive.eventual
import com.android.systemui.plugin.globalactions.wallet.reactive.eventualOf
import com.android.systemui.plugin.globalactions.wallet.reactive.neverEvents
import com.android.systemui.plugin.globalactions.wallet.reactive.potential
import com.android.systemui.plugin.globalactions.wallet.reactive.potentialOf
import com.android.systemui.plugin.globalactions.wallet.reactive.setCancelAction
import com.android.systemui.plugin.globalactions.wallet.view.common.GlobalActionCardViewModel
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WalletPanelModelImplTests {

    private class TestController<T : GlobalActionCardViewModel>(
            viewModelManager: CardManager<T> = TestCardManager(),
            panelAvailableWhenLockedSetting: Setting.Provider<Boolean> = settingProviderOf(true),
            settingsLauncher: SettingsLauncher = FakeSettingsLauncher(true),
            dismissRequests: EventStream<Any> = neverEvents(),
            callbacks: WalletUiCallbacks<T> = FakeCallbacks(),
            lockEvents: EventStream<Boolean> = neverEvents(),
            selectedIndexEvents: EventStream<Int> = neverEvents(),
            clickEvents: EventStream<Int> = neverEvents(),
            settingsEvents: EventStream<Unit> = neverEvents(),
            onWalletDismissedListener: OnWalletDismissedListener = FakeOnWalletDismissedListener,
            lockedErrorMessage: CharSequence = "lockedErrorMessage"
    ) : WalletUiController<T> by
            WalletUiControllerImpl(
                    viewModelManager,
                    panelAvailableWhenLockedSetting,
                    settingsLauncher,
                    dismissRequests,
                    callbacks,
                    lockEvents,
                    selectedIndexEvents,
                    clickEvents,
                    settingsEvents,
                    onWalletDismissedListener,
                    lockedErrorMessage
            )

    @Test
    fun connect_awaitDismissAndLockEvents() {
        var lockEventSubscriptions = 0
        val lockEventSource = events<Boolean> {
            lockEventSubscriptions++
        }
        var dismissEventSubscriptions = 0
        val dismissEventSource = events<Any> {
            dismissEventSubscriptions++
        }
        val panel = TestController<Nothing>(
                dismissRequests = dismissEventSource,
                lockEvents = lockEventSource
        )

        panel.connect()

        assertThat(lockEventSubscriptions).isEqualTo(1)
        assertThat(dismissEventSubscriptions).isEqualTo(1)
    }

    @Test
    fun dismissRequests_invokeCallbacks_beforeAnyOtherEvents() {
        val dismissRequests = BroadcastingEventSource<Any>()
        var invokedCount = 0
        val fakeCallbacks = object : WalletUiCallbacks<TestViewModel> by FakeCallbacks() {
            override fun dismissGlobalActionsMenu() {
                invokedCount++
            }
        }
        val panel = TestController(
                dismissRequests = dismissRequests,
                callbacks = fakeCallbacks
        )

        dismissRequests.emitEvent(Unit)
        panel.connect()
        dismissRequests.emitEvent(Unit)
        assertThat(invokedCount).isEqualTo(1)
    }

    @Test
    fun lockEvent_queryCards_whenUnlocked() {
        var invokedCount = 0
        val getCards = eventual<CardManager.Result<TestViewModel>> {
            invokedCount++
        }
        val panel = TestController(
                viewModelManager = TestCardManager(getCards),
                lockEvents = eventsOf(false)
        )

        panel.connect()
        assertThat(invokedCount).isEqualTo(1)
    }

    @Test
    fun dismissRequests_invokeCallbacks_pendingQuery() {
        val dismissRequests = BroadcastingEventSource<Any>()
        var invokedCount = 0
        val fakeCallbacks = object : WalletUiCallbacks<TestViewModel> by FakeCallbacks() {
            override fun dismissGlobalActionsMenu() {
                invokedCount++
            }
        }
        val panel = TestController(
                dismissRequests = dismissRequests,
                callbacks = fakeCallbacks,
                lockEvents = eventsOf(false)
        )

        panel.connect()
        dismissRequests.emitEvent(Unit)
        assertThat(invokedCount).isEqualTo(1)
    }

    @Test
    fun lockEvents_requeryCards_whenStateChanges() {
        val lockEvents = BroadcastingEventSource<Boolean>()
        var invokedCount = 0
        val getCards = eventual<CardManager.Result<TestViewModel>> {
            invokedCount++
        }
        val panel = TestController(
                viewModelManager = TestCardManager(getCards),
                lockEvents = lockEvents
        )

        panel.connect()
        assertThat(invokedCount).isEqualTo(0)
        lockEvents.emitEvent(true)
        assertThat(invokedCount).isEqualTo(1)
        lockEvents.emitEvent(true)
        assertThat(invokedCount).isEqualTo(1)
        lockEvents.emitEvent(false)
        assertThat(invokedCount).isEqualTo(2)
    }

    @Test
    fun lockEvents_dontRequeryCards_whenStateChanges_onceUnlocked() {
        val lockEvents = BroadcastingEventSource<Boolean>()
        var invokedCount = 0
        val getCards = eventual<CardManager.Result<TestViewModel>> {
            invokedCount++
        }
        val panel = TestController(
                viewModelManager = TestCardManager(getCards),
                lockEvents = lockEvents
        )

        panel.connect()
        lockEvents.emitEvent(false)
        assertThat(invokedCount).isEqualTo(1)
        lockEvents.emitEvent(true)
        assertThat(invokedCount).isEqualTo(1)
    }

    @Test
    fun lockEvents_whenLocked_invokeCallbacks_whenUnavailable() {
        val lockEvents = BroadcastingEventSource<Boolean>()
        var errorCount = 0
        val fakeCallbacks = object : WalletUiCallbacks<TestViewModel> by FakeCallbacks() {
            override fun showErrorMessage(error: CharSequence) {
                errorCount++
            }
        }
        var queryCount = 0
        val getCards = eventual<CardManager.Result<TestViewModel>> {
            queryCount++
        }
        val panel = TestController(
                viewModelManager = TestCardManager(getCards),
                panelAvailableWhenLockedSetting = settingProviderOf(false),
                callbacks = fakeCallbacks,
                lockEvents = lockEvents
        )

        panel.connect()
        assertThat(queryCount).isEqualTo(0)
        lockEvents.emitEvent(true)
        assertThat(queryCount).isEqualTo(0)
        assertThat(errorCount).isEqualTo(1)
        lockEvents.emitEvent(true)
        assertThat(queryCount).isEqualTo(0)
        assertThat(errorCount).isEqualTo(1)
        lockEvents.emitEvent(false)
        assertThat(queryCount).isEqualTo(1)
        assertThat(errorCount).isEqualTo(1)
    }

    @Test
    fun showError_whenCardQueryFails() {
        val errors = mutableListOf<CharSequence>()
        val fakeCallbacks = object : WalletUiCallbacks<TestViewModel> by FakeCallbacks() {
            override fun showErrorMessage(error: CharSequence) {
                errors.add(error)
            }
        }
        val panel = TestController(
                viewModelManager = TestCardManager(eventualOf(Failure("foo"))),
                callbacks = fakeCallbacks,
                lockEvents = eventsOf(false)
        )
        panel.connect()
        assertThat(errors).containsExactly("foo")
    }

    @Test
    fun invokeCallbacks_afterSuccessfulQuery() {
        val invocations = mutableListOf<Pair<List<TestViewModel>, Int>>()
        val fakeCallbacks = object : WalletUiCallbacks<TestViewModel> by FakeCallbacks() {
            override fun populateUi(cards: List<TestViewModel>, selectedIndex: Int) {
                invocations.add(cards to selectedIndex)
            }
        }
        val viewModel1 = TestViewModel()
        val viewModel2 = TestViewModel()
        val panel = TestController(
                viewModelManager = TestCardManager(
                        eventualOf(
                                Success(
                                        1,
                                        sequenceOf(viewModel1, viewModel2),
                                        2
                                )
                        )
                ),
                callbacks = fakeCallbacks,
                lockEvents = eventsOf(false)
        )
        panel.connect()
        assertThat(invocations).containsExactly(listOf(viewModel1, viewModel2) to 1)
    }

    @Test
    fun subscribeToUiEvents_afterSuccessfulQuery() {
        var selectSubscriptions = 0
        val selectEventSource = events<Int> {
            selectSubscriptions++
        }
        var clickSubscriptions = 0
        val clickEventSource = events<Int> {
            clickSubscriptions++
        }
        var settingsSubscriptions = 0
        val settingsEventSource = events<Unit> {
            settingsSubscriptions++
        }
        val panel = TestController(
                viewModelManager = TestCardManager(
                        eventualOf(Success(0, sequenceOf(TestViewModel()), 1))
                ),
                lockEvents = eventsOf(false),
                selectedIndexEvents = selectEventSource,
                clickEvents = clickEventSource,
                settingsEvents = settingsEventSource
        )
        panel.connect()
        assertThat(selectSubscriptions).isEqualTo(1)
        assertThat(clickSubscriptions).isEqualTo(1)
        assertThat(settingsSubscriptions).isEqualTo(1)
    }

    @Test
    fun clickEvent_invokesViewModelClickHandler() {
        var subscriptions = 0
        val viewModel = TestViewModel(click = potential { subscriptions++ })
        val panel = TestController(
                viewModelManager = TestCardManager(
                        eventualOf(Success(0, sequenceOf(viewModel), 1))
                ),
                lockEvents = eventsOf(false),
                // click first card immediately
                clickEvents = eventsOf(0)
        )
        panel.connect()
        assertThat(subscriptions).isEqualTo(1)
    }

    @Test
    fun clickEvent_doesNotDismiss_whenViewModelClickHandlerHasNoResult() {
        var invocations = 0
        val fakeCallbacks = object : WalletUiCallbacks<TestViewModel> by FakeCallbacks() {
            override fun dismissGlobalActionsMenu() {
                invocations++
            }
        }
        val viewModel = TestViewModel(click = emptyPotential())
        val panel = TestController(
                viewModelManager = TestCardManager(
                        eventualOf(Success(0, sequenceOf(viewModel), 1))
                ),
                callbacks = fakeCallbacks,
                lockEvents = eventsOf(false),
                // click first card immediately
                clickEvents = eventsOf(0)
        )
        panel.connect()
        assertThat(invocations).isEqualTo(0)
    }

    @Test
    fun clickEvent_dismissesWallet_whenViewModelClickHandlerHasResult() {
        var invocations = 0
        val fakeCallbacks = object : WalletUiCallbacks<TestViewModel> by FakeCallbacks() {
            override fun dismissGlobalActionsMenu() {
                invocations++
            }
        }
        val viewModel = TestViewModel(click = potentialOf(Unit))
        val panel = TestController(
                viewModelManager = TestCardManager(
                        eventualOf(Success(0, sequenceOf(viewModel), 1))
                ),
                callbacks = fakeCallbacks,
                lockEvents = eventsOf(false),
                // click first card immediately
                clickEvents = eventsOf(0)
        )
        panel.connect()
        assertThat(invocations).isEqualTo(1)
    }

    @Test
    fun selectEvent_invokesViewModelSelectHandler() {
        var subscriptions = 0
        val viewModel = TestViewModel(select = completable { subscriptions++ })
        val panel = TestController(
                viewModelManager = TestCardManager(
                        eventualOf(Success(0, sequenceOf(viewModel), 1))
                ),
                lockEvents = eventsOf(false),
                // select first card immediately
                selectedIndexEvents = eventsOf(0)
        )
        panel.connect()
        assertThat(subscriptions).isEqualTo(1)
    }

    @Test
    fun selectEvent_onNextEmission_unsubscribesFromPreviousHandler() {
        var unsubscriptions = 0
        val viewModel1 = TestViewModel(
                select = completable { setCancelAction { unsubscriptions++ } })
        val viewModel2 = TestViewModel()
        val panel = TestController(
                viewModelManager = TestCardManager(
                        eventualOf(Success(0, sequenceOf(viewModel1, viewModel2), 1))
                ),
                lockEvents = eventsOf(false),
                // select first card, then second card
                selectedIndexEvents = eventsOf(0, 1)
        )
        panel.connect()
        assertThat(unsubscriptions).isEqualTo(1)
    }

    @Test
    fun settingsEvent_invokesSettingsLauncher() {
        val fakeSettingsLauncher = FakeSettingsLauncher()
        val panel = TestController(
                viewModelManager = TestCardManager(
                        eventualOf(Success(0, sequenceOf(TestViewModel()), 1))
                ),
                settingsLauncher = fakeSettingsLauncher,
                lockEvents = eventsOf(false),
                settingsEvents = eventsOf(Unit)
        )
        panel.connect()
        assertThat(fakeSettingsLauncher.numInvocations).isEqualTo(1)
    }

    @Test
    fun settingsEvent_dismissesWallet_whenSettingsLauncherReturnsTrue() {
        val fakeSettingsLauncher = FakeSettingsLauncher()
        val fakeCallbacks = FakeCallbacks<TestViewModel>()
        val panel = TestController(
                viewModelManager = TestCardManager(
                        eventualOf(Success(0, sequenceOf(TestViewModel()), 1))
                ),
                settingsLauncher = fakeSettingsLauncher,
                callbacks = fakeCallbacks,
                lockEvents = eventsOf(false),
                settingsEvents = eventsOf(Unit)
        )
        panel.connect()
        assertThat(fakeCallbacks.dismissGlobalActionsMenuInvocationCount).isEqualTo(1)
    }

    @Test
    fun settingsIntent_doesNotDismiss_whenPendingIntentSenderReturnsFalse() {
        val fakeSettingsLauncher = FakeSettingsLauncher(false)
        val fakeCallbacks = FakeCallbacks<TestViewModel>()
        val panel = TestController(
                viewModelManager = TestCardManager(
                        eventualOf(Success(0, sequenceOf(TestViewModel()), 1))
                ),
                settingsLauncher = fakeSettingsLauncher,
                callbacks = fakeCallbacks,
                lockEvents = eventsOf(false),
                settingsEvents = eventsOf(Unit)
        )
        panel.connect()
        assertThat(fakeCallbacks.dismissGlobalActionsMenuInvocationCount).isEqualTo(0)
    }

    @Test
    fun cardManagerDismissRequest_dismissesWallet_afterSuccessfulQuery() {
        val fakeCallbacks = FakeCallbacks<TestViewModel>()
        val panel = TestController(
                viewModelManager = TestCardManager(
                        eventualOf(Success(0, sequenceOf(TestViewModel()), 1)),
                        completed()
                ),
                callbacks = fakeCallbacks,
                lockEvents = eventsOf(false)
        )
        panel.connect()
        assertThat(fakeCallbacks.dismissGlobalActionsMenuInvocationCount).isEqualTo(1)
    }

    @Test
    fun subscribeToCardManagerDismissRequest_afterSuccessfulQuery() {
        var subscriptions = 0
        val dismissSource = completable {
            subscriptions++
        }
        val panel = TestController(
                viewModelManager = TestCardManager(
                        eventualOf(Success(0, sequenceOf(TestViewModel()), 1)),
                        dismissSource
                ),
                lockEvents = eventsOf(false)
        )
        panel.connect()
        assertThat(subscriptions).isEqualTo(1)
    }
}