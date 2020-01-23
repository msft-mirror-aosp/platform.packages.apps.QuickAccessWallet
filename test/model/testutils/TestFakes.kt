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

import com.android.systemui.plugin.globalactions.wallet.common.BackendAvailabilityChecker
import com.android.systemui.plugin.globalactions.wallet.common.BackgroundThreadRunner
import com.android.systemui.plugin.globalactions.wallet.common.CardManager
import com.android.systemui.plugin.globalactions.wallet.common.OnWalletDismissedListener
import com.android.systemui.plugin.globalactions.wallet.common.PendingIntentSender
import com.android.systemui.plugin.globalactions.wallet.common.Setting
import com.android.systemui.plugin.globalactions.wallet.backend.model.CardSelector
import com.android.systemui.plugin.globalactions.wallet.backend.model.DrawableCreator
import com.android.systemui.plugin.globalactions.wallet.reactive.Completable
import com.android.systemui.plugin.globalactions.wallet.reactive.Eventual
import com.android.systemui.plugin.globalactions.wallet.reactive.Potential
import com.android.systemui.plugin.globalactions.wallet.reactive.completableAction
import com.android.systemui.plugin.globalactions.wallet.reactive.completed
import com.android.systemui.plugin.globalactions.wallet.reactive.eventualLazy
import com.android.systemui.plugin.globalactions.wallet.reactive.neverComplete
import com.android.systemui.plugin.globalactions.wallet.reactive.neverEventual
import com.android.systemui.plugin.globalactions.wallet.reactive.neverPotential
import com.android.systemui.plugin.globalactions.wallet.view.common.GlobalActionCardViewModel

class FakeCardManager<T>(
        override val globalActionCards: Eventual<CardManager.Result<T>> = neverEventual(),
        override val dismissRequest: Completable = neverComplete()
) : CardManager<T>

fun <T> success(vararg values: T, selectedIndex: Int = 0) =
    CardManager.Result.Success(selectedIndex, values.asSequence(), values.size)

object FakeBackgroundThreadRunner : BackgroundThreadRunner {
    override fun <T> backgroundThread(eventual: Eventual<T>) = eventual
}

class TestViewModel(
        override val select: Completable = neverComplete(),
        override val click: Potential<Unit> = neverPotential()
) : GlobalActionCardViewModel

class TestCardManager<T>(
        override val globalActionCards: Eventual<CardManager.Result<T>> = neverEventual(),
        override val dismissRequest: Completable = neverComplete()
) : CardManager<T>

fun <T> settingProviderOf(setting: T) = object : Setting.Provider<T> {
    override val value: T get() = setting
}

class FakeSetting<T>(override var value: T) : Setting<T>

class FakePendingIntentSender<T>(private val result: Boolean = true) : PendingIntentSender<T> {
    override fun sendPendingIntent(pendingIntent: T): Boolean = result
}

class FakeCallbacks<T> : WalletUiCallbacks<T> {
    var dismissGlobalActionsMenuInvocationCount: Int = 0
        private set

    private val _errorMessages = mutableListOf<CharSequence>()
    val errorMessages: List<CharSequence> = _errorMessages

    private val _uiStates = mutableListOf<Pair<List<T>, Int>>()
    val uiStates: List<Pair<List<T>, Int>> = _uiStates

    override fun dismissGlobalActionsMenu() {
        dismissGlobalActionsMenuInvocationCount++
    }
    override fun showErrorMessage(error: CharSequence) {
        _errorMessages.add(error)
    }
    override fun populateUi(cards: List<T>, selectedIndex: Int) {
        _uiStates.add(cards to selectedIndex)
    }
}

object FakeOnWalletDismissedListener : OnWalletDismissedListener {
    var dismissCount: Int = 0
        private set

    override fun onWalletDismissed() {
        dismissCount++
    }
}

class FakeWalletDisabler(override val disableWallet: Completable = completed()) : WalletDisabler

class FakeBackendAvailabilityChecker(override val isAvailable: Eventual<Boolean>) :
        BackendAvailabilityChecker

class FakeDrawableCreator<D> : DrawableCreator<D, D> {
    private val _drawables = mutableListOf<D>()
    val drawables: List<D> = _drawables

    override fun toDrawable(data: D): Eventual<D> = eventualLazy {
        _drawables.add(data)
        data
    }
}

class FakeCardSelector : CardSelector {
    private val _selections = mutableListOf<Pair<String, Int>>()
    val selections: List<Pair<String, Int>> = _selections

    override fun selectCard(cardId: String, cardType: Int): Completable = completableAction {
        _selections.add(cardId to cardType)
    }
}

class FakeSettingsLauncher(private val result: Boolean = true) : SettingsLauncher {
    var numInvocations: Int = 0
        private set
    override fun showSettings(): Boolean {
        numInvocations++
        return result
    }
}