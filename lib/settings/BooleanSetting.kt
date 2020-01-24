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

package com.android.systemui.plugin.globalactions.wallet.settings

import android.content.ContentResolver
import android.provider.Settings
import com.android.systemui.plugin.globalactions.wallet.common.Setting

class BooleanSetting(
    private val name: String,
    private val contentResolver: ContentResolver,
    private val user: Int? = null
) : Setting<Boolean?> {
    override var value: Boolean?
        get() {
            val setting =
                    if (user == null)
                        Settings.Secure.getInt(contentResolver, name, -1)
                    else
                        Settings.Secure.getIntForUser(contentResolver, name, -1, user)
            return when (setting) {
                0 -> false
                -1 -> null
                else -> true
            }
        }
        set(value) {
            value?.let {
                Settings.Secure.putInt(contentResolver, name, if (value) 1 else 0)
            } ?: Settings.Secure.putString(contentResolver, name, null)
        }
}