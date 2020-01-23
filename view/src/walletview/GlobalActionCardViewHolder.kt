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

package com.android.systemui.plugin.globalactions.wallet.view.walletview

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.android.systemui.plugin.globalactions.wallet.reactive.Completable
import com.android.systemui.plugin.globalactions.wallet.reactive.EventStream
import com.android.systemui.plugin.globalactions.wallet.reactive.completableAction
import com.android.systemui.plugin.globalactions.wallet.reactive.events
import com.android.systemui.plugin.globalactions.wallet.reactive.mergeWith
import com.android.systemui.plugin.globalactions.wallet.reactive.setCancelAction
import com.android.systemui.plugin.globalactions.wallet.view.Bindable
import com.android.systemui.plugin.globalactions.wallet.view.R
import com.android.systemui.plugin.globalactions.wallet.view.RoundedCardViewButton
import com.android.systemui.plugin.globalactions.wallet.view.carousel.ClickableScalingCardCarouselView
import com.android.systemui.plugin.globalactions.wallet.view.carousel.ViewSupplier
import com.android.systemui.plugin.globalactions.wallet.view.common.GlobalActionCardContainerViewModel
import kotlin.math.abs
import kotlin.math.max

private const val TAG = "WalletCardViewHolder"
const val ICON_SIZE_DP = 32
private const val UNSELECTED_CARD_SCALE = .71f

/**
 * View holder for all global action cards. The actual card itself is handled via a separate view
 * holder that is passed in when constructed. When binding to a view model to this view holder, it
 * will pass that view model along to the inner view model.
 */
class GlobalActionCardViewHolder<TModel, TInner>(
    private val cardWidthPx: Int,
    cardHeightPx: Int,
    inflater: LayoutInflater,
    private val parent: ViewGroup,
    innerFactory: (ViewGroup) -> TInner
) : Bindable<TModel>, ClickableScalingCardCarouselView.ViewHolder
where TModel : GlobalActionCardContainerViewModel<Drawable>,
      TInner : Bindable<TModel>,
      TInner : ViewSupplier
{
    private var firstLayout = true

    override val view: View = inflater.inflate(R.layout.global_action_card, parent, false)

    private val label: TextView =
        view.requireViewById<TextView>(R.id.Label).apply { maxWidth = cardWidthPx }
    private val cardContainerView: View = view.requireViewById(R.id.CardContainer)
    private val cardView: RoundedCardViewButton =
        view.requireViewById<RoundedCardViewButton>(R.id.CardView).apply {
            layoutParams.width = cardWidthPx
            layoutParams.height = cardHeightPx
        }
    private val innerViewHolder: TInner = innerFactory(cardView).apply { cardView.addView(view) }
    private val settingsButton: ImageView = view.requireViewById(R.id.Settings)

    override val clickEvents: EventStream<Unit> = events {
        setCancelAction { cardView.setOnClickListener(null) }
        cardView.setOnClickListener { emitEvent(Unit) }
    }

    override val settingsEvents: EventStream<Unit> = events {
        setCancelAction { settingsButton.setOnClickListener(null) }
        settingsButton.setOnClickListener { emitEvent(Unit) }
    }

    init {
        parent.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateLayout()
            firstLayout = false
        }
    }

    override fun bind(data: TModel): Completable =
        innerViewHolder.bind(data)
            .mergeWith(completableAction {
                label.apply {
                    visibility = View.VISIBLE
                    text = data.cardLabel ?: ""
                    setCompoundDrawables(top = data.cardLabelImage)
                }
                settingsButton.visibility = if (data.showSettingsButton) View.VISIBLE else View.GONE
            })

    override var labelAlpha: Float
        get() = label.alpha
        set(value) {
            label.alpha = value
        }

    override var cardAlpha: Float
        get() = cardContainerView.alpha
        set(value) {
            cardContainerView.alpha = value
        }

    private fun updateLayout() {
        val viewCenter = (view.right + view.left) / 2f
        val viewWidth = view.width.toFloat()
        val parentCenter = (parent.right + parent.left) / 2f
        val position = abs(parentCenter - viewCenter) / viewWidth
        val scaleFactor = max(UNSELECTED_CARD_SCALE, 1f - abs(position))
        scaleCard(scaleFactor)
        if (!firstLayout) {
            val factor = (scaleFactor - UNSELECTED_CARD_SCALE) / (1 - UNSELECTED_CARD_SCALE)
            labelAlpha = factor
        }
    }

    override fun onScrolled() = updateLayout()

    private fun scaleCard(scaleFactor: Float) {
        cardContainerView.scaleX = scaleFactor
        cardContainerView.scaleY = scaleFactor
    }

    override fun setCardCornerRadiusRatio(ratio: Float) {
        cardView.cornerRadiusRatio = ratio
        cardView.radius = ratio * cardWidthPx
    }
}

@Suppress("EXTENSION_SHADOWED_BY_MEMBER")
private fun TextView.setCompoundDrawables(
        left: Drawable? = null,
        top: Drawable? = null,
        right: Drawable? = null,
        bottom: Drawable? = null
) = setCompoundDrawables(left, top, right, bottom)
