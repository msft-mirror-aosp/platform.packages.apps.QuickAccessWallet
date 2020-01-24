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
import com.android.systemui.plugin.globalactions.wallet.common.CardManager.Result.Disabled
import com.android.systemui.plugin.globalactions.wallet.common.CardManager.Result.Failure
import com.android.systemui.plugin.globalactions.wallet.common.CardManager.Result.Success
import com.android.systemui.plugin.globalactions.wallet.reactive.BroadcastingEventSource
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
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WalletPanelModelImplTests {

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
        val panel = WalletUiControllerImpl(
                TestCardManager(),
                settingProviderOf(true),
                FakeSettingsLauncher(true),
                dismissEventSource,
                FakeCallbacks(),
                lockEventSource,
                neverEvents(),
                neverEvents(),
                neverEvents(),
                FakeOnWalletDismissedListener,
                "lockedErrorMessage",
                FakeWalletDisabler()
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
        val panel = WalletUiControllerImpl(
                TestCardManager(),
                settingProviderOf(true),
                FakeSettingsLauncher(true),
                dismissRequests,
                fakeCallbacks,
                neverEvents(),
                neverEvents(),
                neverEvents(),
                neverEvents(),
                FakeOnWalletDismissedListener,
                "lockedErrorMessage",
                FakeWalletDisabler()
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
        val panel = WalletUiControllerImpl(
                TestCardManager(getCards),
                settingProviderOf(true),
                FakeSettingsLauncher(true),
                neverEvents(),
                FakeCallbacks(),
                eventsOf(false),
                neverEvents(),
                neverEvents(),
                neverEvents(),
                FakeOnWalletDismissedListener,
                "lockedErrorMessage",
                FakeWalletDisabler()
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
        val panel = WalletUiControllerImpl(
                TestCardManager(),
                settingProviderOf(true),
                FakeSettingsLauncher(true),
                dismissRequests,
                fakeCallbacks,
                eventsOf(false),
                neverEvents(),
                neverEvents(),
                neverEvents(),
                FakeOnWalletDismissedListener,
                "lockedErrorMessage",
                FakeWalletDisabler()
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
        val panel = WalletUiControllerImpl(
                TestCardManager(getCards),
                settingProviderOf(true),
                FakeSettingsLauncher(true),
                neverEvents(),
                FakeCallbacks(),
                lockEvents,
                neverEvents(),
                neverEvents(),
                neverEvents(),
                FakeOnWalletDismissedListener,
                "lockedErrorMessage",
                FakeWalletDisabler()
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
        val panel = WalletUiControllerImpl(
                TestCardManager(getCards),
                settingProviderOf(true),
                FakeSettingsLauncher(true),
                neverEvents(),
                FakeCallbacks(),
                lockEvents,
                neverEvents(),
                neverEvents(),
                neverEvents(),
                FakeOnWalletDismissedListener,
                "lockedErrorMessage",
                FakeWalletDisabler()
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
        val panel = WalletUiControllerImpl(
                TestCardManager(getCards),
                settingProviderOf(false),
                FakeSettingsLauncher(true),
                neverEvents(),
                fakeCallbacks,
                lockEvents,
                neverEvents(),
                neverEvents(),
                neverEvents(),
                FakeOnWalletDismissedListener,
                "lockedErrorMessage",
                FakeWalletDisabler()
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
        val panel = WalletUiControllerImpl(
                TestCardManager(eventualOf(Failure("foo"))),
                settingProviderOf(true),
                FakeSettingsLauncher(true),
                neverEvents(),
                fakeCallbacks,
                eventsOf(false),
                neverEvents(),
                neverEvents(),
                neverEvents(),
                FakeOnWalletDismissedListener,
                "lockedErrorMessage",
                FakeWalletDisabler()
        )
        panel.connect()
        assertThat(errors).containsExactly("foo")
    }

    @Test
    fun invokeCallbacks_whenCardManagerIsDisabled() {
        var dismissGlobalActionsMenuInvocations = 0
        var showErrorMessageInvocations = 0
        var populateUiInvocations = 0
        val fakeCallbacks = object : WalletUiCallbacks<TestViewModel> {
            override fun dismissGlobalActionsMenu() {
                dismissGlobalActionsMenuInvocations++
            }

            override fun showErrorMessage(error: CharSequence) {
                showErrorMessageInvocations++
            }

            override fun populateUi(cards: List<TestViewModel>, selectedIndex: Int) {
                populateUiInvocations++
            }
        }
        val panel = WalletUiControllerImpl(
                TestCardManager(eventualOf(Disabled())),
                settingProviderOf(true),
                FakeSettingsLauncher(true),
                neverEvents(),
                fakeCallbacks,
                eventsOf(false),
                neverEvents(),
                neverEvents(),
                neverEvents(),
                FakeOnWalletDismissedListener,
                "lockedErrorMessage",
                FakeWalletDisabler()
        )
        panel.connect()
        assertThat(dismissGlobalActionsMenuInvocations).isEqualTo(0)
        assertThat(showErrorMessageInvocations).isEqualTo(0)
        assertThat(populateUiInvocations).isEqualTo(0)
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
        val panel = WalletUiControllerImpl(
                TestCardManager(
                        eventualOf(
                                Success(
                                        1,
                                        sequenceOf(viewModel1, viewModel2),
                                        2
                                )
                        )
                ),
                settingProviderOf(true),
                FakeSettingsLauncher(true),
                neverEvents(),
                fakeCallbacks,
                eventsOf(false),
                neverEvents(),
                neverEvents(),
                neverEvents(),
                FakeOnWalletDismissedListener,
                "lockedErrorMessage",
                FakeWalletDisabler()
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
        val panel = WalletUiControllerImpl(
                TestCardManager(eventualOf(Success(0, sequenceOf(TestViewModel()), 1))),
                settingProviderOf(true),
                FakeSettingsLauncher(true),
                neverEvents(),
                FakeCallbacks(),
                eventsOf(false),
                selectEventSource,
                clickEventSource,
                settingsEventSource,
                FakeOnWalletDismissedListener,
                "lockedErrorMessage",
                FakeWalletDisabler()
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
        val panel = WalletUiControllerImpl(
                TestCardManager(eventualOf(Success(0, sequenceOf(viewModel), 1))),
                settingProviderOf(true),
                FakeSettingsLauncher(true),
                neverEvents(),
                FakeCallbacks(),
                eventsOf(false),
                neverEvents(),
                eventsOf(0), // click first card immediately
                neverEvents(),
                FakeOnWalletDismissedListener,
                "lockedErrorMessage",
                FakeWalletDisabler()
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
        val panel = WalletUiControllerImpl(
                TestCardManager(eventualOf(Success(0, sequenceOf(viewModel), 1))),
                settingProviderOf(true),
                FakeSettingsLauncher(true),
                neverEvents(),
                fakeCallbacks,
                eventsOf(false),
                neverEvents(),
                eventsOf(0),
                neverEvents(),
                FakeOnWalletDismissedListener,
                "lockedErrorMessage",
                FakeWalletDisabler()
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
        val panel = WalletUiControllerImpl(
                TestCardManager(eventualOf(Success(0, sequenceOf(viewModel), 1))),
                settingProviderOf(true),
                FakeSettingsLauncher(true),
                neverEvents(),
                fakeCallbacks,
                eventsOf(false),
                neverEvents(),
                eventsOf(0),
                neverEvents(),
                FakeOnWalletDismissedListener,
                "lockedErrorMessage",
                FakeWalletDisabler()
        )
        panel.connect()
        assertThat(invocations).isEqualTo(1)
    }

    @Test
    fun selectEvent_invokesViewModelSelectHandler() {
        var subscriptions = 0
        val viewModel = TestViewModel(select = completable { subscriptions++ })
        val panel = WalletUiControllerImpl(
                TestCardManager(eventualOf(Success(0, sequenceOf(viewModel), 1))),
                settingProviderOf(true),
                FakeSettingsLauncher(true),
                neverEvents(),
                FakeCallbacks(),
                eventsOf(false),
                eventsOf(0), // select first card immediately
                neverEvents(),
                neverEvents(),
                FakeOnWalletDismissedListener,
                "lockedErrorMessage",
                FakeWalletDisabler()
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
        val panel = WalletUiControllerImpl(
                TestCardManager(eventualOf(Success(0, sequenceOf(viewModel1, viewModel2), 1))),
                settingProviderOf(true),
                FakeSettingsLauncher(true),
                neverEvents(),
                FakeCallbacks(),
                eventsOf(false),
                eventsOf(0, 1), // select first card then second card
                neverEvents(),
                neverEvents(),
                FakeOnWalletDismissedListener,
                "lockedErrorMessage",
                FakeWalletDisabler()
        )
        panel.connect()
        assertThat(unsubscriptions).isEqualTo(1)
    }

    @Test
    fun settingsEvent_invokesSettingsLauncher() {
        val fakeSettingsLauncher = FakeSettingsLauncher()
        val panel = WalletUiControllerImpl(
                TestCardManager(eventualOf(Success(0, sequenceOf(TestViewModel()), 1))),
                settingProviderOf(true),
                fakeSettingsLauncher,
                neverEvents(),
                FakeCallbacks(),
                eventsOf(false),
                neverEvents(),
                neverEvents(),
                eventsOf(Unit),
                FakeOnWalletDismissedListener,
                "lockedErrorMessage",
                FakeWalletDisabler()
        )
        panel.connect()
        assertThat(fakeSettingsLauncher.numInvocations).isEqualTo(1)
    }

    @Test
    fun settingsEvent_dismissesWallet_whenSettingsLauncherReturnsTrue() {
        val fakeSettingsLauncher = FakeSettingsLauncher()

        val fakeCallbacks = FakeCallbacks<TestViewModel>()
        val panel = WalletUiControllerImpl(
                TestCardManager(eventualOf(Success(0, sequenceOf(TestViewModel()), 1))),
                settingProviderOf(true),
                fakeSettingsLauncher,
                neverEvents(),
                fakeCallbacks,
                eventsOf(false),
                neverEvents(),
                neverEvents(),
                eventsOf(Unit),
                FakeOnWalletDismissedListener,
                "lockedErrorMessage",
                FakeWalletDisabler()
        )
        panel.connect()
        assertThat(fakeCallbacks.dismissGlobalActionsMenuInvocationCount).isEqualTo(1)
    }

    @Test
    fun settingsIntent_doesNotDismiss_whenPendingIntentSenderReturnsFalse() {
        val fakeSettingsLauncher = FakeSettingsLauncher(false)
        val fakeCallbacks = FakeCallbacks<TestViewModel>()
        val panel = WalletUiControllerImpl(
                TestCardManager(eventualOf(Success(0, sequenceOf(TestViewModel()), 1))),
                settingProviderOf(true),
                fakeSettingsLauncher,
                neverEvents(),
                fakeCallbacks,
                eventsOf(false),
                neverEvents(),
                neverEvents(),
                eventsOf(Unit),
                FakeOnWalletDismissedListener,
                "lockedErrorMessage",
                FakeWalletDisabler()
        )
        panel.connect()
        assertThat(fakeCallbacks.dismissGlobalActionsMenuInvocationCount).isEqualTo(0)
    }

    @Test
    fun cardManagerDismissRequest_dismissesWallet_afterSuccessfulQuery() {
        val fakeCallbacks = FakeCallbacks<TestViewModel>()
        val panel = WalletUiControllerImpl(
                TestCardManager(
                        eventualOf(Success(0, sequenceOf(TestViewModel()), 1)),
                        completed()
                ),
                settingProviderOf(true),
                FakeSettingsLauncher(),
                neverEvents(),
                fakeCallbacks,
                eventsOf(false),
                neverEvents(),
                neverEvents(),
                neverEvents(),
                FakeOnWalletDismissedListener,
                "lockedErrorMessage",
                FakeWalletDisabler()
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
        val panel = WalletUiControllerImpl(
                TestCardManager(
                        eventualOf(Success(0, sequenceOf(TestViewModel()), 1)),
                        dismissSource
                ),
                settingProviderOf(true),
                FakeSettingsLauncher(),
                neverEvents(),
                FakeCallbacks(),
                eventsOf(false),
                neverEvents(),
                neverEvents(),
                neverEvents(),
                FakeOnWalletDismissedListener,
                "lockedErrorMessage",
                FakeWalletDisabler()
        )
        panel.connect()
        assertThat(subscriptions).isEqualTo(1)
    }
}