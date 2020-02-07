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

/**
 * A "subcomponent" that exists for the lifetime of the Wallet plugin.
 *
 * This definition encapsulates how a Wallet "backend" is connected to the greater Wallet
 * application.
 */
interface WalletPluginSubcomponent<out F> {

    /**
     * Process that is started when the Wallet plugin is started, and stopped when the plugin is
     * destroyed.
     */
    val pluginLifetimeProcess: PluginLifetimeProcess
        get() = PluginLifetimeProcess.EmptyProcess

    /**
     * An [F] that corresponds to the Wallet UI-scoped "subcomponent" associated with this Wallet
     * Plugin "subcomponent". This is usually a [Factory] that requires dependencies only available
     * at the time the Wallet UI is shown in order to instantiate the UI subcomponent.
     */
    fun getUiScopedSubcomponent(): F
}

inline fun <A, B> WalletPluginSubcomponent<A>.mapUiScopedSubcomponent(
        crossinline f: (A) -> B
): WalletPluginSubcomponent<B> =
        object : WalletPluginSubcomponent<B> {

            override val pluginLifetimeProcess: PluginLifetimeProcess
                get() = this@mapUiScopedSubcomponent.pluginLifetimeProcess

            override fun getUiScopedSubcomponent() =
                    f(this@mapUiScopedSubcomponent.getUiScopedSubcomponent())
        }

inline fun <A, B, C> WalletPluginSubcomponent<Factory<B, C>>.contraMapUiFactory(
        crossinline f: (A) -> B
) = mapUiScopedSubcomponent { it.contraMap(f) }

inline fun <A, B, C> WalletPluginSubcomponent<Factory<A, B>>.mapUiFactory(
        crossinline f: (B) -> C
) = mapUiScopedSubcomponent { it.map(f) }

/**
 * A "subcomponent" that exists for the lifetime of the Wallet UI (that being a single invocation
 * of Global Actions).
 */
interface WalletUiModelSubcomponent<out VM> {

    /** [CardManager] used to query for cards. */
    val uiCardManager: CardManager<VM>

    /** Listener to be invoked when the UI is dismissed. */
    val onWalletDismissedListener: OnWalletDismissedListener
        get() = OnWalletDismissedListener.EmptyListener
}

/** A unary factory, which accepts some input and instantiates some output. */
typealias Factory<A, B> = (A) -> B

fun <A, B> Factory<A, B>.injectDeps(deps: A) = invoke(deps)
inline fun <A, B, C> Factory<B, C>.contraMap(crossinline f: (A) -> B) = { a: A -> this(f(a)) }
inline fun <A, B, C> Factory<A, B>.map(crossinline f: (B) -> C) = { a: A -> f(this(a)) }

/** Subcomponent for Wallet, as implemented by the backend. */
typealias WalletBackendSubcomponent<D, VM> =
        WalletPluginSubcomponent<Factory<D, WalletUiModelSubcomponent<VM>>?>
