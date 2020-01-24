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

package com.android.systemui.plugin.globalactions.wallet.common

import com.android.systemui.plugin.globalactions.wallet.reactive.Completable
import com.android.systemui.plugin.globalactions.wallet.reactive.Subscription
import com.android.systemui.plugin.globalactions.wallet.reactive.completed
import com.android.systemui.plugin.globalactions.wallet.reactive.mergeWith

/**
 * An async process which is bound to the lifetime of the plugin. These are guaranteed to be stopped
 * when the plugin is destroyed.
 */
interface PluginLifetimeProcess {

    /**
     * [Completable] representing the process. Subscribing initializes the process, and returns a
     * [Subscription] that, when cancelled, stops the process and frees any acquired resources.
     */
    val startProcess: Completable

    object EmptyProcess : PluginLifetimeProcess {
        override val startProcess = completed()
    }
}

/** Merges two [PluginLifetimeProcess]es into one. */
fun PluginLifetimeProcess.mergeWith(other: PluginLifetimeProcess) = object : PluginLifetimeProcess {
    override val startProcess: Completable
        get() = this@mergeWith.startProcess.mergeWith(other.startProcess)
}
