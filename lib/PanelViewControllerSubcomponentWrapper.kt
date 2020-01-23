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

package com.android.systemui.plugin.globalactions.wallet

import android.app.PendingIntent
import android.content.Context
import android.graphics.drawable.Drawable
import com.android.systemui.plugin.globalactions.wallet.common.CardDimens
import com.android.systemui.plugin.globalactions.wallet.common.Factory
import com.android.systemui.plugin.globalactions.wallet.common.WalletPluginSubcomponent
import com.android.systemui.plugin.globalactions.wallet.common.injectDeps
import com.android.systemui.plugin.globalactions.wallet.common.mapUiScopedSubcomponent
import com.android.systemui.plugin.globalactions.wallet.model.UiModelComponent
import com.android.systemui.plugin.globalactions.wallet.reactive.EventStream
import com.android.systemui.plugin.globalactions.wallet.view.common.TopLevelViewModel
import com.android.systemui.plugins.GlobalActionsPanelPlugin
import com.android.systemui.plugins.GlobalActionsPanelPlugin.PanelViewController

/** Component for Wallet UI, scoped for the lifetime of a single invocation of Global Actions. */
typealias WalletUiComponent = UiModelComponent<TopLevelViewModel<Drawable>>

/** Factory for [WalletUiComponent] instances. */
interface WalletPanelComponentFactory {

    /**
     * Constructs a new [WalletUiComponent].
     *
     * @param callbacks UI callbacks, invoked when the UI should update to respect the state of
     *  the model.
     * @param cardDimens CardDimens containing the required measurements of card bitmaps
     *  provided from the backend.
     * @param pendingIntentSender Sends [PendingIntent]s in response to various user actions.
     * @param uiDismissRequests User-initiated requests to dismiss Wallet via a UI action
     * @param lockEvents Lock state updates from SystemUI
     * @param uiCardSelections [EventStream] signalling a user changed the current card
     *  selection, where each event carries the index of the newly selected card.
     * @param uiCardClicks [EventStream] signalling that the user clicked a card, where each
     *  event carries the index of the clicked card.
     * @param uiSettingsButtonClicks [EventStream] signalling that the user clicked the settings
     *  button
     */
    fun createPanelComponent(
            callbacks: PanelCallbacks,
            cardDimens: CardDimens,
            pendingIntentSender: PendingIntentSender,
            uiDismissRequests: EventStream<Any>,
            lockEvents: EventStream<Boolean>,
            uiCardSelections: EventStream<Int>,
            uiCardClicks: EventStream<Int>,
            uiSettingsButtonClicks: EventStream<Unit>
    ): WalletUiComponent
}

interface PanelComponentDeps {
    val callbacks: PanelCallbacks
    val cardDimens: CardDimens
    val pendingIntentSender: PendingIntentSender
    val uiDismissRequests: EventStream<Any>
    val lockEvents: EventStream<Boolean>
    val uiCardSelections: EventStream<Int>
    val uiCardClicks: EventStream<Int>
    val uiSettingsButtonClicks: EventStream<Unit>
}

/**
 * Dependencies required to instantiate [GlobalActionsPanelPlugin.PanelViewController] instances.
 */
data class PanelViewControllerDeps(

        /** Callbacks that can be used to control various parts of Global Actions. */
        val callbacks: GlobalActionsPanelPlugin.Callbacks,

        /** Whether or not the device is currently locked. */
        val isDeviceLocked: Boolean
)


/**
 * Wraps this subcomponent in a new one which produces a [PanelViewController] as the Ui.
 *
 * @param pluginContext Context for the wallet plugin, used to lookup resources in the wallet apk
 */
fun WalletPluginSubcomponent<
        Factory<PanelComponentDeps, UiModelComponent<TopLevelViewModel<Drawable>>>?>
.uiComponentToPanelViewController(
        pluginContext: Context
): WalletPluginSubcomponent<Factory<PanelViewControllerDeps, PanelViewController>?> =
        mapUiScopedSubcomponent { factory ->
            factory?.let {
                { deps: PanelViewControllerDeps ->
                    WalletPanelViewController(
                            pluginContext,
                            deps.callbacks,
                            deps.isDeviceLocked,
                            UiFactory(factory)
                    )
                }
            }
        }

private class UiFactory(
        val panelModelComponentFactory: Factory<
                PanelComponentDeps,
                UiModelComponent<TopLevelViewModel<Drawable>>>
) : WalletPanelComponentFactory {

    override fun createPanelComponent(
            callbacks: PanelCallbacks,
            cardDimens: CardDimens,
            pendingIntentSender: PendingIntentSender,
            uiDismissRequests: EventStream<Any>,
            lockEvents: EventStream<Boolean>,
            uiCardSelections: EventStream<Int>,
            uiCardClicks: EventStream<Int>,
            uiSettingsButtonClicks: EventStream<Unit>
    ): WalletUiComponent {
        val deps = object : PanelComponentDeps {
            override val uiDismissRequests = uiDismissRequests
            override val callbacks = callbacks
            override val lockEvents = lockEvents
            override val uiCardSelections = uiCardSelections
            override val uiCardClicks = uiCardClicks
            override val uiSettingsButtonClicks = uiSettingsButtonClicks
            override val cardDimens = cardDimens
            override val pendingIntentSender = pendingIntentSender
        }
        return object : WalletUiComponent {
            override val uiController =
                    panelModelComponentFactory.injectDeps(deps).uiController
        }
    }
}