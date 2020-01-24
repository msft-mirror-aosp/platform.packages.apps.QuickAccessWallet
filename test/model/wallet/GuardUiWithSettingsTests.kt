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

import com.android.systemui.plugin.globalactions.wallet.common.WalletPluginSubcomponent
import com.android.systemui.plugin.globalactions.wallet.reactive.eventualOf
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GuardUiWithSettingsTests {
    @Test
    fun happyCase() {
        val wps = object : WalletPluginSubcomponent<String> {
            override fun getUiScopedSubcomponent() = "foo"
        }
        val guarded = wps.guardUiWithSettings(
                deviceProvisionedSetting = settingProviderOf(true),
                lockdownSetting = settingProviderOf(false),
                walletAvailableSetting = FakeSetting<Boolean?>(true),
                walletEnabledSetting = FakeSetting<Boolean?>(true),
                backendAvailabilityChecker = FakeBackendAvailabilityChecker(eventualOf(true))
        )
        assertThat(guarded.getUiScopedSubcomponent()).isNotNull()
    }

    @Test
    fun deviceInLockdown() {
        val wps = object : WalletPluginSubcomponent<String> {
            override fun getUiScopedSubcomponent() = "foo"
        }
        val guarded = wps.guardUiWithSettings(
                deviceProvisionedSetting = settingProviderOf(true),
                lockdownSetting = settingProviderOf(true),
                walletAvailableSetting = FakeSetting<Boolean?>(true),
                walletEnabledSetting = FakeSetting<Boolean?>(true),
                backendAvailabilityChecker = FakeBackendAvailabilityChecker(eventualOf(true))
        )
        assertThat(guarded.getUiScopedSubcomponent()).isNull()
    }
}
