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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.android.systemui.plugin.globalactions.wallet.settings.BooleanSetting

private const val EXTRA_IS_FEATURE_ENABLED = "enabled"

class EnableWalletBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        intent?.getBooleanExtra(EXTRA_IS_FEATURE_ENABLED, false)?.let { enabled ->
            context?.contentResolver?.let { resolver ->
                val walletEnabledSetting = BooleanSetting(
                        Settings.Secure.GLOBAL_ACTIONS_PANEL_ENABLED, resolver)
                val walletAvailableSetting = BooleanSetting(
                        Settings.Secure.GLOBAL_ACTIONS_PANEL_AVAILABLE, resolver)

                // Show the setting in Settings > System > Gestures
                walletAvailableSetting.value = enabled

                // Set enabled by default
                if (enabled && walletEnabledSetting.value == null) {
                    walletEnabledSetting.value = true
                }
            }
        }
    }
}