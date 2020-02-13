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
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import com.android.systemui.plugin.globalactions.wallet.common.Combined
import com.android.systemui.plugin.globalactions.wallet.common.Factory
import com.android.systemui.plugin.globalactions.wallet.common.WalletPluginSubcomponent
import com.android.systemui.plugin.globalactions.wallet.common.WalletUiModelSubcomponent
import com.android.systemui.plugin.globalactions.wallet.common.contraMap
import com.android.systemui.plugin.globalactions.wallet.common.map
import com.android.systemui.plugin.globalactions.wallet.common.mapUiScopedSubcomponent
import com.android.systemui.plugin.globalactions.wallet.common.provider
import com.android.systemui.plugin.globalactions.wallet.common.zip
import com.android.systemui.plugin.globalactions.wallet.model.PluginControllerComponent
import com.android.systemui.plugin.globalactions.wallet.model.SettingsLauncher
import com.android.systemui.plugin.globalactions.wallet.model.UiModelDependencies
import com.android.systemui.plugin.globalactions.wallet.model.WalletComponentModel
import com.android.systemui.plugin.globalactions.wallet.model.toPluginController
import com.android.systemui.plugin.globalactions.wallet.reactive.AndroidLogger
import com.android.systemui.plugin.globalactions.wallet.settings.BooleanSetting
import com.android.systemui.plugin.globalactions.wallet.settings.LockdownSetting
import com.android.systemui.plugin.globalactions.wallet.view.common.TopLevelViewModel
import com.android.systemui.plugins.GlobalActionsPanelPlugin.PanelViewController

private const val SETTINGS_INTENT_ACTION = "com.android.settings.GLOBAL_ACTIONS_PANEL_SETTINGS"

/** Top-level component for Quick Access Wallet, scoped for the lifetime of the plugin. */
interface WalletLifetimeComponent :
        PluginControllerComponent<PanelViewControllerDeps, PanelViewController?>

/**
 * [WalletLifetimeComponent] smart constructor.
 *
 * @param pluginContext [Context] for the plugin APK, used to resolve resources included with the
 *  plugin
 * @param sysuiContext [Context] for SystemUI, used when SystemUI privileges and resources are
 *  needed.
 * @param backendSubcomponent [WalletPluginSubcomponent] for the backend card provider
 * @param depsFactory Factory function that generates the UI-scope dependencies for the
 *  [backendSubcomponent]
 */
fun <D> WalletLifetimeComponent(
        pluginContext: Context,
        sysuiContext: Context,
        backendSubcomponent: WalletPluginSubcomponent<
                Factory<D, WalletUiModelSubcomponent<TopLevelViewModel<Drawable>>>?>,
        depsFactory: (PanelComponentDeps) -> D
): WalletLifetimeComponent =
        WalletLifetimeComponentImpl(
                pluginContext,
                sysuiContext,
                backendSubcomponent,
                depsFactory
        )

private class WalletLifetimeComponentImpl<D>(
        private val pluginContext: Context,
        private val sysuiContext: Context,
        backendSubcomponent: WalletPluginSubcomponent<
                Factory<D, WalletUiModelSubcomponent<TopLevelViewModel<Drawable>>>?>,
        private val depsFactory: (PanelComponentDeps) -> D
) : WalletLifetimeComponent {

    override val pluginController = run {
        // Device settings
        val walletAvailableSetting =
                BooleanSetting(
                        Settings.Secure.GLOBAL_ACTIONS_PANEL_AVAILABLE,
                        sysuiContext.contentResolver
                ).provider().map { it ?: false }
        val walletEnabledSetting =
                BooleanSetting(
                        Settings.Secure.GLOBAL_ACTIONS_PANEL_ENABLED,
                        sysuiContext.contentResolver
                ).provider().map { it ?: false }
        val deviceProvisionedSettingProvider =
                BooleanSetting(
                        Settings.Secure.USER_SETUP_COMPLETE,
                        sysuiContext.contentResolver,
                        UserHandle.USER_CURRENT
                ).provider().map { it ?: false }
        val lockdownSetting = LockdownSetting(sysuiContext)

        val componentModel = WalletComponentModel(
                backendSubcomponent,
                walletAvailableSetting,
                walletEnabledSetting,
                deviceProvisionedSettingProvider,
                lockdownSetting,
                AndroidLogger("QAWallet", Log.DEBUG)
        )

        componentModel
                .mapUiScopedSubcomponent { factory ->
                    factory?.contraMap { deps: PanelComponentDeps -> UiDeps(deps) }
                }
                // wrap ui-scoped subcomponent in controller
                .uiComponentToPanelViewController(pluginContext)
                // wrap lifetime component in controller
                .toPluginController()
    }

    private inner class UiDeps(private val deps: PanelComponentDeps) :
            Combined<UiModelDependencies<TopLevelViewModel<Drawable>>, D> {

        val lockScreenNotifsEnabled =
                BooleanSetting(
                        Settings.Secure.LOCK_SCREEN_SHOW_NOTIFICATIONS,
                        sysuiContext.contentResolver
                ).provider().map { it ?: false }
        val lockScreenAllowPrivateNotifs =
                BooleanSetting(
                        Settings.Secure.LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS,
                        sysuiContext.contentResolver
                ).provider().map { it ?: false }
        val availableWhenLockedSetting = lockScreenNotifsEnabled.zip(
                lockScreenAllowPrivateNotifs,
                Boolean::and
        )
        val settingsIntent =
                PendingIntent.getActivity(sysuiContext, 0, Intent(SETTINGS_INTENT_ACTION), 0)

        override val first = object : UiModelDependencies<TopLevelViewModel<Drawable>> {
            override val uiAvailableWhenLockedSetting = availableWhenLockedSetting
            override val settingsLauncher = object : SettingsLauncher {
                override fun showSettings(): Boolean =
                        deps.pendingIntentSender.sendPendingIntent(settingsIntent)
            }
            override val uiDismissRequests = deps.uiDismissRequests
            override val callbacks = deps.callbacks
            override val lockEvents = deps.lockEvents
            override val selections = deps.uiCardSelections
            override val clicks = deps.uiCardClicks
            override val settingsClicks = deps.uiSettingsButtonClicks
            override val lockedErrorMessage = pluginContext.getText(R.string.error_user_locked)
        }
        override val second = depsFactory(deps)
    }
}
