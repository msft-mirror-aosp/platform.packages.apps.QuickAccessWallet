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

import android.content.Context
import android.util.Log
import com.android.systemui.plugin.globalactions.wallet.backend.BackendSubcomponent
import com.android.systemui.plugin.globalactions.wallet.backend.BackendUiDeps
import com.android.systemui.plugin.globalactions.wallet.reactive.Subscription
import com.android.systemui.plugins.GlobalActionsPanelPlugin
import com.android.systemui.plugins.annotations.Requirements
import com.android.systemui.plugins.annotations.Requires

/** Plugin interface for Quick Access Wallet. Manages the plugin lifecycle. */
@Requirements(
        Requires(
                target = GlobalActionsPanelPlugin::class,
                version = GlobalActionsPanelPlugin.VERSION
        ),
        Requires(
                target = GlobalActionsPanelPlugin.Callbacks::class,
                version = GlobalActionsPanelPlugin.Callbacks.VERSION
        ),
        Requires(
                target = GlobalActionsPanelPlugin.PanelViewController::class,
                version = GlobalActionsPanelPlugin.PanelViewController.VERSION
        )
)
class WalletPluginService : GlobalActionsPanelPlugin {

    private lateinit var model: PluginController

    private var lifetimeSubscription: Subscription? = null

    override fun onCreate(sysuiContext: Context, pluginContext: Context) {
        Log.d("WalletDebug", "onCreate")

        // Workaround bug where onCreate is getting invoked multiple times
        lifetimeSubscription?.cancel()

        val aospBackendComponent =
                BackendSubcomponent(BackgroundThreadRunnerImpl, sysuiContext)
        val component = WalletLifetimeComponent(
                pluginContext,
                sysuiContext,
                aospBackendComponent,
                aospBackendComponent.availabilityChecker
        ) { panelDeps ->
            object : BackendUiDeps {
                override val cardDimens = panelDeps.cardDimens
                override val pendingIntentSender = panelDeps.pendingIntentSender
            }
        }

        model = component.pluginController
        lifetimeSubscription = model.onCreate()
    }

    override fun onPanelShown(
            callbacks: GlobalActionsPanelPlugin.Callbacks,
            isDeviceLocked: Boolean
    ): GlobalActionsPanelPlugin.PanelViewController? =
            model.onPanelShown(PanelViewControllerDeps(callbacks, isDeviceLocked))

    override fun onDestroy() {
        lifetimeSubscription?.cancel()
    }
}