package com.android.systemui.plugin.globalactions.wallet.model

import com.android.systemui.plugin.globalactions.wallet.common.BackendAvailabilityChecker
import com.android.systemui.plugin.globalactions.wallet.common.PluginLifetimeProcess
import com.android.systemui.plugin.globalactions.wallet.common.Setting
import com.android.systemui.plugin.globalactions.wallet.reactive.BroadcastingEventSource
import com.android.systemui.plugin.globalactions.wallet.reactive.Completable
import com.android.systemui.plugin.globalactions.wallet.reactive.asEventStream
import com.android.systemui.plugin.globalactions.wallet.reactive.mapConsume
import com.android.systemui.plugin.globalactions.wallet.reactive.switchMap

/**
 * Asynchronously checks if the Wallet backend is available, and if so, enables Wallet for future
 * invocations of Global Actions.
 *
 * This is a [PluginLifetimeProcess]. [checkAvailable] will not perform any action until this
 * process has been started.
 */
internal class BackendAvailabilitySynchronizer(
        backendAvailabilityChecker: BackendAvailabilityChecker,
        walletAvailableSetting: Setting<Boolean?>,
        walletEnabledSetting: Setting<Boolean?>
) : PluginLifetimeProcess {

    private val checkRequests = BroadcastingEventSource<Unit>()

    // Expose a lifetime process to track the asynchronous queries, so we don't leak if the plugin
    // is destroyed mid-query.
    override val startProcess: Completable = checkRequests
            .switchMap { backendAvailabilityChecker.isAvailable.asEventStream() }
            .mapConsume { isAvailable ->
                walletAvailableSetting.value = isAvailable
                if (isAvailable && walletEnabledSetting.value == null) {
                    walletEnabledSetting.value = true
                }
            }

    /** Queries the backend, and updates wallet settings based on availability. */
    fun checkAvailable() {
        // Fire off an asynchronous query without blocking this thread, so there's no jank when
        // showing Global Actions
        checkRequests.emitEvent(Unit)
    }
}