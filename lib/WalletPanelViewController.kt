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

import android.animation.TimeInterpolator
import android.app.PendingIntent
import android.content.Context
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.TextView
import com.android.systemui.plugin.globalactions.wallet.common.CardDimens
import com.android.systemui.plugin.globalactions.wallet.reactive.CachingEventSource
import com.android.systemui.plugin.globalactions.wallet.reactive.EventStream
import com.android.systemui.plugin.globalactions.wallet.reactive.Subscription
import com.android.systemui.plugin.globalactions.wallet.reactive.events
import com.android.systemui.plugin.globalactions.wallet.reactive.mergeEvents
import com.android.systemui.plugin.globalactions.wallet.reactive.setCancelAction
import com.android.systemui.plugin.globalactions.wallet.view.R
import com.android.systemui.plugin.globalactions.wallet.view.WalletBackgroundDrawable
import com.android.systemui.plugin.globalactions.wallet.view.common.TopLevelViewModel
import com.android.systemui.plugin.globalactions.wallet.view.walletview.ICON_SIZE_DP
import com.android.systemui.plugin.globalactions.wallet.view.walletview.TopLevelWalletView
import com.android.systemui.plugins.GlobalActionsPanelPlugin
import kotlin.math.roundToInt

private const val TAG = "WalletPanelViewCtrlr"

private const val SCROLL_X_ANIMATION_OFFSET_PX = 120f
private const val ANIMATION_DURATION = 300L

private val outInterpolator: TimeInterpolator = AccelerateInterpolator()

/** View controller for the Quick Access Wallet. */
class WalletPanelViewController(
        context: Context,
        callbacks: GlobalActionsPanelPlugin.Callbacks,
        deviceLocked: Boolean,
        panelComponentFactory: WalletPanelComponentFactory
) : GlobalActionsPanelPlugin.PanelViewController {

    private val panelView: View =
        LayoutInflater.from(context).inflate(R.layout.global_actions_activity, null)
    private val panelClicks: EventStream<Unit> = events {
        setCancelAction { panelView.setOnClickListener(null) }
        panelView.setOnClickListener { emitEvent(Unit) }
    }
    private val walletView: TopLevelWalletView<Drawable> =
            panelView.requireViewById(R.id.view_pager)
    private val errorView: TextView = panelView.requireViewById(R.id.error_view)
    private val backgroundDrawable = WalletBackgroundDrawable(context)
    private val lockEvents = CachingEventSource(deviceLocked)
    private val panelComponent: WalletUiComponent = run {
        val pendingIntentSender = object : PendingIntentSender {
            override fun sendPendingIntent(pendingIntent: PendingIntent): Boolean {
                callbacks.startPendingIntentDismissingKeyguard(pendingIntent)
                return true
            }
        }
        val cardDimens = CardDimens(
                walletView.cardWidthPx,
                walletView.cardHeightPx,
                (ICON_SIZE_DP * context.resources.displayMetrics.density).roundToInt()
        )
        val panelCallbacks = object : PanelCallbacks {
            override fun dismissGlobalActionsMenu() {
                callbacks.dismissGlobalActionsMenu()
            }

            override fun showErrorMessage(error: CharSequence?) {
                showError(error ?: context.resources.getString(R.string.error_generic))
            }

            override fun populateUi(cards: List<TopLevelViewModel<Drawable>>, selectedIndex: Int) {
                walletView.visibility = View.VISIBLE
                walletView.setCards(cards, selectedIndex)
                backgroundDrawable.darken()
                errorView.visibility = View.GONE
            }
        }
        val dismissRequests = mergeEvents(panelClicks, walletView.dismissEvents)

        panelComponentFactory.createPanelComponent(
                callbacks = panelCallbacks,
                cardDimens = cardDimens,
                pendingIntentSender = pendingIntentSender,
                uiDismissRequests = dismissRequests,
                lockEvents = lockEvents,
                uiCardSelections = walletView.selectEvents,
                uiCardClicks = walletView.clickEvents,
                uiSettingsButtonClicks = walletView.settingsEvents
        )
    }

    private val uiSubscription: Subscription

    init {
        // Connect UI to backend, and kick-off backend queries
        uiSubscription = panelComponent.uiController.connect()
    }

    override fun onDismissed() {
        uiSubscription.cancel()
        if (walletView.visibility == View.VISIBLE) {
            for (i in 0 until walletView.childCount) {
                val view = walletView.getChildAt(i)
                view.animate().apply {
                    duration = ANIMATION_DURATION
                    interpolator = outInterpolator
                    alpha(0f)
                    translationX(SCROLL_X_ANIMATION_OFFSET_PX)
                    start()
                }
            }
        }
        if (errorView.visibility == View.VISIBLE) {
            errorView.animate().apply {
                alpha(0f)
                duration = ANIMATION_DURATION
                interpolator = outInterpolator
                start()
            }
        }
    }

    override fun getPanelContent(): View = panelView

    override fun onDeviceLockStateChanged(locked: Boolean) = lockEvents.emitEvent(locked)

    override fun getBackgroundDrawable(): Drawable = backgroundDrawable

    private fun showError(errorText: CharSequence) {
        errorView.text = errorText
        if (errorView.visibility != View.VISIBLE) {
            errorView.visibility = View.VISIBLE
            errorView.alpha = 0f
            errorView.animate().apply {
                alpha(1f)
                duration = ANIMATION_DURATION
                interpolator = LinearInterpolator()
                start()
            }
        }
        walletView.visibility = View.INVISIBLE
    }
}
