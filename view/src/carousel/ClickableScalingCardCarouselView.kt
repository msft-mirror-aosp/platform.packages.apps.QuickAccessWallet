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

package com.android.systemui.plugin.globalactions.wallet.view.carousel

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.util.AttributeSet
import android.util.Property
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.recyclerview.widget.RecyclerView
import com.android.systemui.plugin.globalactions.wallet.reactive.BroadcastingEventSource
import com.android.systemui.plugin.globalactions.wallet.reactive.Completable
import com.android.systemui.plugin.globalactions.wallet.reactive.EventStream
import com.android.systemui.plugin.globalactions.wallet.reactive.completableAction
import com.android.systemui.plugin.globalactions.wallet.reactive.mapConsume
import com.android.systemui.plugin.globalactions.wallet.reactive.mergeWith
import com.android.systemui.plugin.globalactions.wallet.view.R

private const val TAG = "WalletScalingCarousel"

private const val SCROLL_X_ANIMATION_OFFSET_PX = 120

/**
 * Card Carousel that scales cards as the user scrolls, forwards click events from its cards, and
 * animates the initial layout.
 */
open class ClickableScalingCardCarouselView<VH : ClickableScalingCardCarouselView.ViewHolder>(
    context: Context,
    attrSet: AttributeSet? = null
) : CardCarouselView<VH>(context, attrSet) {

    /** View holder requirements for this carousel. */
    interface ViewHolder : CarouselViewHolder {

        /**
         * Property used to set the alpha value of the card label, used during entry animation.
         */
        var labelAlpha: Float

        /**
         * Property used to set the alpha value of the card view, used for the entry animation.
         */
        var cardAlpha: Float

        /** Invoked when the recycler view has scrolled, allowing the card to adjust its view. */
        fun onScrolled()

        /**
         * An event stream that, when subscribed to, emits click events from this card. These are
         * then forwarded through [ClickableScalingCardCarouselView.clickEvents].
         */
        val clickEvents: EventStream<Unit>

        /**
         * An event stream that, when subscribed to, emits events from this card when the user
         * wants to view Wallet settings.
         */
        val settingsEvents: EventStream<Unit>
    }

    private val clicks = BroadcastingEventSource<Int>()
    private val settingsClicks = BroadcastingEventSource<Unit>()
    private var animate = true

    /**
     * Event stream that, when subscribed to, emits the index of the card that was just clicked.
     */
    val clickEvents: EventStream<Int> = clicks

    /**
     * Event stream that, when subscribed to, emits whenever the user wants to launch settings.
     */
    val settingsEvents: EventStream<Unit> = settingsClicks

    /**
     * Sets a [CardCarouselView.CardCarouselAdapter] and selected index for this carousel,
     * triggering a relayout.
     */
    override fun setAdapter(adapter: CardCarouselAdapter<VH>, selectedIndex: Int) {
        val myAdapter = MyAdapter(adapter, selectedIndex)
        val zoomOutScrollListener = object : OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                for (i in 0 until childCount) {
                    myAdapter.getViewModelForView(getChildAt(i)).onScrolled()
                }
            }
        }

        if (animate) {
            // Wait until adapter content has been laid out before setting the transformer.
            // Otherwise, the transformer will override our nice animations.
            addOnLayoutChangeListener(
                    object : View.OnLayoutChangeListener {
                        override fun onLayoutChange(
                            v: View,
                            left: Int,
                            top: Int,
                            right: Int,
                            bottom: Int,
                            oldLeft: Int,
                            oldTop: Int,
                            oldRight: Int,
                            oldBottom: Int
                        ) {
                            animate = false
                            addOnScrollListener(zoomOutScrollListener)
                            removeOnLayoutChangeListener(this)
                        }
                    })
        } else {
            addOnScrollListener(zoomOutScrollListener)
        }

        // Do this after setting the transformer, otherwise animate will always be false.
        super.setAdapter(myAdapter, selectedIndex)
    }

    // Wrapper around the adapter given in setAdapter(), that sets up animations and click events.
    private inner class MyAdapter<VH : ViewHolder>(
            val inner: CardCarouselAdapter<VH>,
            val animationSelectedIndex: Int
    ) : CardCarouselAdapter<VH> by inner {

        val linearInterpolator = LinearInterpolator()
        val fastOutSlowInInterpolator = FastOutSlowInInterpolator()

        // Used by the page transformer to animate the view as it scrolls into/out of view.
        @Suppress("UNCHECKED_CAST")
        fun getViewModelForView(view: View) = view.getTag(R.id.tag_view_holder) as VH

        override fun bindViewHolder(viewHolder: VH, position: Int): Completable {
            val clickEvents = viewHolder.clickEvents.mapConsume { clicks.emitEvent(position) }
            val settingsEvents = viewHolder.settingsEvents
                    .mapConsume { settingsClicks.emitEvent(Unit) }

            val prepAnimations = completableAction {
                viewHolder.view.setTag(R.id.tag_view_holder, viewHolder)

                if (!animate) {
                    return@completableAction
                }

                val animatorSet = AnimatorSet()
                val animators = ArrayList<Animator>()

                viewHolder.labelAlpha = 0f
                if (position == animationSelectedIndex) {
                    animators.add(getLabelAnimator())
                }

                getStartDelay(position)?.let { startDelay ->
                    viewHolder.cardAlpha = 0f

                    viewHolder.view.apply {
                        translationX = SCROLL_X_ANIMATION_OFFSET_PX.toFloat()
                        animate()
                                .translationX(0f)
                                .setDuration(300)
                                .setInterpolator(fastOutSlowInInterpolator)
                                .start()
                    }

                    animators.add(getCardAnimator(startDelay))
                }

                animatorSet.apply {
                    playTogether(animators)
                    setTarget(viewHolder)
                    start()
                }
            }

            return inner.bindViewHolder(viewHolder, position)
                    .mergeWith(clickEvents)
                    .mergeWith(settingsEvents)
                    .mergeWith(prepAnimations)
        }

        private fun getStartDelay(position: Int): Long? = when (position) {
            animationSelectedIndex - 1 -> 0L
            animationSelectedIndex -> 50L
            animationSelectedIndex + 1 -> 100L
            else -> null
        }

        private fun getLabelAnimator(): ObjectAnimator =
            ObjectAnimator.ofFloat(
                    null,
                    object : Property<VH, Float>(Float::class.java, "labelAlpha") {
                        override fun get(vh: VH): Float? = vh.labelAlpha

                        override fun set(vh: VH, value: Float?) {
                            vh.labelAlpha = value!!
                        }
                    },
                    1f
            ).apply {
                startDelay = 133L
                duration = 100L
                interpolator = linearInterpolator
            }

        private fun getCardAnimator(startDelay: Long): Animator =
            ObjectAnimator.ofFloat(
                    null,
                    object : Property<VH, Float>(Float::class.java, "cardAlpha") {
                        override fun get(vh: VH): Float? = vh.cardAlpha

                        override fun set(vh: VH, value: Float?) {
                            vh.cardAlpha = value!!
                        }
                    },
                    1f
            ).apply {
                this.startDelay = startDelay
                duration = 100L
                interpolator = linearInterpolator
            }
    }
}
