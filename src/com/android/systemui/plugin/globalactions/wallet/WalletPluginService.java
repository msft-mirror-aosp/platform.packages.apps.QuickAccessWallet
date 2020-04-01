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

package com.android.systemui.plugin.globalactions.wallet;

import android.content.ContentResolver;
import android.content.Context;
import android.provider.Settings;
import android.service.quickaccesswallet.QuickAccessWalletClient;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.plugins.GlobalActionsPanelPlugin;
import com.android.systemui.plugins.annotations.Requirements;
import com.android.systemui.plugins.annotations.Requires;

@Requirements({
        @Requires(
                target = GlobalActionsPanelPlugin.class,
                version = GlobalActionsPanelPlugin.VERSION),
        @Requires(
                target = GlobalActionsPanelPlugin.Callbacks.class,
                version = GlobalActionsPanelPlugin.Callbacks.VERSION),
        @Requires(
                target = GlobalActionsPanelPlugin.PanelViewController.class,
                version = GlobalActionsPanelPlugin.PanelViewController.VERSION),
})
public class WalletPluginService implements GlobalActionsPanelPlugin {

    private Context mSysuiContext;
    private Context mPluginContext;

    @Override
    public void onCreate(Context sysuiContext, Context pluginContext) {
        mSysuiContext = sysuiContext;
        mPluginContext = pluginContext;
        enableFeatureInSettings(mSysuiContext);
    }

    /**
     * Invoked when the GlobalActions menu is shown.
     *
     * @param callbacks    {@link Callbacks} instance that can be used by the Panel to interact with
     *                     the Global Actions menu.
     * @param deviceLocked Indicates whether or not the device is currently locked.
     * @return A {@link PanelViewController} instance used to receive Global Actions events.
     */
    @Override
    public PanelViewController onPanelShown(
            GlobalActionsPanelPlugin.Callbacks callbacks, boolean deviceLocked) {
        return onPanelShown(callbacks, deviceLocked, QuickAccessWalletClient.create(mSysuiContext));
    }

    @VisibleForTesting
    GlobalActionsPanelPlugin.PanelViewController onPanelShown(
            GlobalActionsPanelPlugin.Callbacks callbacks,
            boolean isDeviceLocked,
            QuickAccessWalletClient client) {
        if (!client.isWalletServiceAvailable() || !client.isWalletFeatureAvailable()) {
            return null;
        }
        WalletPanelViewController panelViewController = new WalletPanelViewController(
                mSysuiContext, mPluginContext, client, callbacks, isDeviceLocked);
        panelViewController.queryWalletCards();
        return panelViewController;
    }


    @Override
    public void onDestroy() {
        mSysuiContext = null;
        mPluginContext = null;
    }

    /**
     * Enabling GLOBAL_ACTIONS_PANEL_AVAILABLE makes the settings screen visible. Enabling
     * GLOBAL_ACTIONS_PANEL_ENABLED when the settings is not set effectively turns the feature on by
     * default.
     */
    static void enableFeatureInSettings(Context context) {
        ContentResolver cr = context.getContentResolver();
        // Turning on the availability toggle lets users turn the feature on and off in Settings
        Settings.Secure.putInt(cr, Settings.Secure.GLOBAL_ACTIONS_PANEL_AVAILABLE, 1);
        // Enable the panel by default, but do not re-enable if the user has disabled it.
        if (Settings.Secure.getInt(cr, Settings.Secure.GLOBAL_ACTIONS_PANEL_ENABLED, -1) == -1) {
            Settings.Secure.putInt(cr, Settings.Secure.GLOBAL_ACTIONS_PANEL_ENABLED, 1);
        }
    }
}
