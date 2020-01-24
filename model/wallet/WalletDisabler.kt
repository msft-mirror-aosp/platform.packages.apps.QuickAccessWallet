package com.android.systemui.plugin.globalactions.wallet.model

import com.android.systemui.plugin.globalactions.wallet.common.PluginLifetimeProcess
import com.android.systemui.plugin.globalactions.wallet.common.Setting
import com.android.systemui.plugin.globalactions.wallet.reactive.BroadcastingEventSource
import com.android.systemui.plugin.globalactions.wallet.reactive.Completable
import com.android.systemui.plugin.globalactions.wallet.reactive.Eventual
import com.android.systemui.plugin.globalactions.wallet.reactive.completableAction
import com.android.systemui.plugin.globalactions.wallet.reactive.eventual
import com.android.systemui.plugin.globalactions.wallet.reactive.flatMapCompletable
import com.android.systemui.plugin.globalactions.wallet.reactive.mapConsume

/** Disables Wallet from appearing on subsequent invocations of Global Actions. */
interface WalletDisabler {
    val disableWallet: Completable
}

internal class WalletDisablerImpl(walletAvailableSetting: Setting<Boolean?>) :
        WalletDisabler, PluginLifetimeProcess {

    private val disabler = completableAction { walletAvailableSetting.value = false }
    private val entangledPair = disabler.entangle()

    override val disableWallet: Completable = entangledPair.first

    // Expose a lifetime process to handle disabling, so that the actual process of disabling will
    // not be cancelled when dismissing the Wallet UI
    override val startProcess: Completable = entangledPair.second
}

/**
 * "Entangles" an [Eventual] with a [Completable], executing subscriptions to the former within the
 * lifetime of the latter.
 *
 * @return An [Eventual] and [Completable] pair. When the former is subscribed to
 */
private fun <T> Eventual<T>.entangle(): Pair<Eventual<T>, Completable> {
    val onSubscribe = BroadcastingEventSource<Eventual.Source<T>>()
    val entangledSource = eventual<T> { onSubscribe.emitEvent(this) }
    val entangledEnabler = onSubscribe.flatMapCompletable { source ->
        mapConsume { source.complete(it) }
    }
    return Pair(entangledSource, entangledEnabler)
}