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
import com.android.systemui.plugin.globalactions.wallet.common.PendingIntentSender
import com.android.systemui.plugin.globalactions.wallet.common.WalletBackendSubcomponent
import com.android.systemui.plugin.globalactions.wallet.common.WalletUiModelSubcomponent
import com.android.systemui.plugin.globalactions.wallet.view.common.TopLevelViewModel

/** Dependencies for the backend needed only once the Wallet UI is shown */
interface ModelUiDeps<I, D, P> {

    /** [CardManager] responsible for querying card information */
    val backend: CardManager<QuickAccessWalletCardData<I, P>>

    /** [PendingIntentSender] to be invoked when a card is clicked */
    val pendingIntentSender: PendingIntentSender<P>

    /** Converts deserialized bitmaps into hardware ones */
    val bitmapConverter: DrawableCreator<I, D>

    /** Runs processes in a background thread */
    val bgThreadRunner: BackgroundThreadRunner

    /** Notifies backend of card selection */
    val cardSelector: CardSelector
}

/** Subcomponent for backend */
typealias ModelComponent<I, B, P> =
        WalletBackendSubcomponent<ModelUiDeps<I, B, P>, TopLevelViewModel<B>>

/** Creates a ModelComponent. */
fun <I, D, P> ModelComponent(): ModelComponent<I, D, P> = ModelComponentImpl()

private class ModelComponentImpl<I, D, P> : ModelComponent<I, D, P> {

    override fun getUiScopedSubcomponent() = { deps: ModelUiDeps<I, D, P> ->
        with(deps) {
            object : WalletUiModelSubcomponent<TopLevelViewModel<D>> {
                override val uiCardManager = backend.toViewModels(
                        pendingIntentSender,
                        bitmapConverter,
                        bgThreadRunner,
                        cardSelector
                )
            }
        }
    }
}

