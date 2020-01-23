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

import android.content.Context
import android.util.AttributeSet
import android.util.DisplayMetrics
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerViewAccessibilityDelegate
import com.android.systemui.plugin.globalactions.wallet.reactive.BroadcastingEventSource
import com.android.systemui.plugin.globalactions.wallet.reactive.CachingEventSource
import com.android.systemui.plugin.globalactions.wallet.reactive.Completable
import com.android.systemui.plugin.globalactions.wallet.reactive.EventStream
import com.android.systemui.plugin.globalactions.wallet.reactive.Subscription
import com.android.systemui.plugin.globalactions.wallet.reactive.changes
import com.android.systemui.plugin.globalactions.wallet.reactive.subscribe
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val TAG = "WalletCardCarouselView"

private const val CARD_WIDTH = 700f
private const val CARD_HEIGHT = 440f
private const val CARD_ASPECT_RATIO = CARD_WIDTH / CARD_HEIGHT
private const val BACKGROUND_CORNER_RADIUS = 25f
private const val CORNER_RADIUS_RATIO = BACKGROUND_CORNER_RADIUS / CARD_WIDTH

private const val CARD_SCREEN_WIDTH_RATIO = .82f
private const val CARD_MARGIN_RATIO = -.082f

// Fling parameters
private const val MILLISECONDS_PER_INCH = 200.0f
private const val MAX_SCROLL_ON_FLING_DURATION = 80 // ms

/**
 * Card Carousel based on Recycler view.
 */
open class CardCarouselView<VH : CardCarouselView.CarouselViewHolder>(
    context: Context,
    attrSet: AttributeSet? = null
) : RecyclerView(context, attrSet) {

    interface CarouselViewHolder : ViewSupplier {
        fun setCardCornerRadiusRatio(ratio: Float)
    }

    interface CardCarouselAdapter<VH : CarouselViewHolder> {
        val itemCount: Int
        fun getItemViewType(position: Int): Int
        fun bindViewHolder(viewHolder: VH, position: Int): Completable
        fun createViewHolder(parent: ViewGroup, type: Int): VH
    }

    /** Width of cards to be shown in carousel, which depends on phone width */
    val cardWidthPx: Int

    /** Height of cards to be shown in carousel, which depends on phone width */
    val cardHeightPx: Int

    private val selections = CachingEventSource(0)

    /** Event stream that emits the index of the card that was just selected. */
    val selectEvents: EventStream<Int> = selections.changes()

    private val dismissals = BroadcastingEventSource<Unit>()

    /** Event stream that emits events when the user dismisses global actions. */
    val dismissEvents = dismissals

    private val myAdapter = MyAdapter<VH>()
    private val cardMarginPx: Int
    private val linearLayoutManager =
        LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
    private val scrollListener = object : OnScrollListener() {
        private var oldState = -1
        override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
            if (newState == SCROLL_STATE_IDLE && newState != oldState) {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            }
            oldState = newState
        }
    }

    private val accessibilityDelegate = object : RecyclerViewAccessibilityDelegate(this) {
        override fun onRequestSendAccessibilityEvent(
            host: ViewGroup?,
            child: View?,
            event: AccessibilityEvent?
        ): Boolean {
            if (child != null &&
                    event?.eventType == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED) {
                scrollToPosition(getChildAdapterPosition(child))
            }
            return super.onRequestSendAccessibilityEvent(host, child, event)
        }
    }

    private val gestureDetector = GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapUp(e: MotionEvent?): Boolean = true
                override fun onSingleTapConfirmed(e: MotionEvent?): Boolean = true
            })

    init {
        layoutManager = linearLayoutManager
        clipToPadding = false

        // View always covers entire width of screen
        val metrics = resources.displayMetrics
        val rotationIndependentWidth: Int = min(metrics.widthPixels, metrics.heightPixels)
        cardWidthPx = (rotationIndependentWidth * CARD_SCREEN_WIDTH_RATIO).roundToInt()
        cardHeightPx = (cardWidthPx / CARD_ASPECT_RATIO).roundToInt()
        cardMarginPx = (rotationIndependentWidth * CARD_MARGIN_RATIO).roundToInt()

        ViewCompat.setAccessibilityDelegate(this, accessibilityDelegate)

        addOnScrollListener(scrollListener)
        CarouselSnapHelper().attachToRecyclerView(this)

        val totalCardWidth = cardWidthPx + (32 * metrics.density).roundToInt()
        val padding = (rotationIndependentWidth - totalCardWidth) / 2 - cardMarginPx
        setPadding(padding, getPaddingTop(), padding, getPaddingBottom())
    }

    override fun scrollToPosition(position: Int) {
        super.scrollToPosition(position)
        selections.emitEvent(position)
    }

    override fun smoothScrollToPosition(position: Int) {
        super.smoothScrollToPosition(position)
        selections.emitEvent(position)
    }

    override fun onTouchEvent(e: MotionEvent?): Boolean {
        if (gestureDetector.onTouchEvent(e)) {
            dismissals.emitEvent(Unit)
        }
        return super.onTouchEvent(e)
    }

    private data class MyViewHolder<VH : CarouselViewHolder>(val inner: VH) :
            ViewHolder(inner.view) {

        var subscription: Subscription? = null
    }

    open fun setAdapter(adapter: CardCarouselAdapter<VH>, selectedIndex: Int) {
        myAdapter.setCardCarouselAdapter(adapter)
        if (getAdapter() == null) {
            setAdapter(myAdapter)
            scrollToPosition(selectedIndex)
        }
    }

    private inner class MyAdapter<VH : CarouselViewHolder> : Adapter<MyViewHolder<VH>>() {

        private var adapter: CardCarouselAdapter<VH>? = null

        fun setCardCarouselAdapter(adapter: CardCarouselAdapter<VH>) {
            this.adapter = adapter
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, type: Int): MyViewHolder<VH> {
            val viewHolder = adapter?.createViewHolder(parent, type)
                    ?: throw IllegalStateException("onCreateViewHolder invoked with no adapter set")
            viewHolder.setCardCornerRadiusRatio(CORNER_RADIUS_RATIO)
            (viewHolder.view.layoutParams as LayoutParams).run {
                leftMargin = cardMarginPx
                rightMargin = cardMarginPx
            }
            return MyViewHolder(viewHolder)
        }

        override fun getItemViewType(position: Int): Int = adapter?.getItemViewType(position)
                ?: error("getItemViewType invoked for unknown position $position")

        override fun getItemCount(): Int = adapter?.itemCount ?: 0

        override fun onBindViewHolder(viewHolder: MyViewHolder<VH>, position: Int) {
            viewHolder.subscription?.cancel()
            viewHolder.subscription =
                    adapter?.bindViewHolder(viewHolder.inner, position)?.subscribe()
                            ?: error("onBindViewHolder invoked with no adapter set")
        }
    }

    private inner class CarouselSnapHelper : PagerSnapHelper() {
        override fun findSnapView(layoutManager: LayoutManager): View? =
            super.findSnapView(layoutManager)?.also {
                selections.emitEvent(getChildAdapterPosition(it))
            }

        // The default SnapScroller is a little sluggish
        override fun createSnapScroller(layoutManager: LayoutManager): LinearSmoothScroller {
            return object : LinearSmoothScroller(context) {
                override fun onTargetFound(targetView: View, state: State, action: Action) {
                    val snapDistances =
                            getLayoutManager()?.let { calculateDistanceToFinalSnap(it, targetView) }
                                    ?: return
                    val dx = snapDistances[0]
                    val dy = snapDistances[1]
                    val time = calculateTimeForDeceleration(max(abs(dx), abs(dy)))
                    if (time > 0) {
                        action.update(dx, dy, time, mDecelerateInterpolator)
                    }
                }

                override fun calculateSpeedPerPixel(displayMetrics: DisplayMetrics): Float =
                        MILLISECONDS_PER_INCH / displayMetrics.densityDpi

                override fun calculateTimeForScrolling(dx: Int): Int =
                    min(MAX_SCROLL_ON_FLING_DURATION, super.calculateTimeForScrolling(dx))
            }
        }
    }
}
