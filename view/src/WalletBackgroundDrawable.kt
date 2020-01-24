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

package com.android.systemui.plugin.globalactions.wallet.view

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import com.android.internal.colorextraction.drawable.ScrimDrawable
import com.android.internal.graphics.ColorUtils
import kotlin.math.roundToInt

private const val TAG = "WalletBackground"

private const val GRADIENT_SCRIM_ALPHA = .2f
private const val DARKEN_ANIMATION_DURATION = 150L
private val DARKEN_ANIMATION_INTERPOLATOR = LinearInterpolator()
private const val COLOR_ANIMATION_DURATION = 2000L
private val COLOR_ANIMATION_INTERPOLATOR = DecelerateInterpolator()

/** Gradient used as the background for the Wallet UI */
class WalletBackgroundDrawable(context: Context) : ScrimDrawable() {

    private val gradientPaint = Paint()
    // Array of colors used for the "dark mode" gradient
    private val targetColors = context.resources.getIntArray(R.array.global_actions_bg_gradient)
    // Current array of colors being painted as a gradient
    private val colors = IntArray(targetColors.size)
    // Array of positions used for the gradient, corresponding to the colors and targetColors arrays
    // by index.
    private val positions =
        context.resources.obtainTypedArray(R.array.global_actions_bg_gradient_positions)
                .run { (0 until length()).map { getFloat(it, 0f) }.toFloatArray() }
    // Current raw alpha value, updated via setAlpha(). This is used by GlobalActionsDialog when
    // the scrim is first animated in.
    private var storedAlpha = 255
    // Whether or not dark mode is enabled
    private var darkMode = false
    // Animation currently in progress
    private var colorAnimation: ValueAnimator? = null
    // The current color being animated to, used by setColor when darkMode is false.
    private var colorTo: Int = 0
    // Listener used to null out colorAnimation when the animation has finished
    private val animationEndListener = object : AnimatorListenerAdapter() {
        override fun onAnimationEnd(animator: Animator?) {
            if (animator === colorAnimation) {
                colorAnimation = null
            }
        }
    }

    /** Animates to a darker gradient */
    fun darken() {
        if (darkMode) {
            return
        }
        darkMode = true

        // Store where we're coming from, so we can reference it inside the animation
        val colorsFrom = colors.clone()
        colorAnimation?.cancel()
        colorAnimation = ValueAnimator.ofFloat(0f, 1f)
                .apply {
                    duration = DARKEN_ANIMATION_DURATION
                    interpolator = DARKEN_ANIMATION_INTERPOLATOR
                    addUpdateListener { animation ->
                        val ratio = animation.animatedValue as Float
                        // For each color in the gradient, blend it towards the dark mode target
                        // color, based on how far along the animation is
                        for (i in 0 until colors.size) {
                            colors[i] = ColorUtils.blendARGB(colorsFrom[i], targetColors[i], ratio)
                        }
                        updateGradient()
                        invalidateSelf()
                    }
                    addListener(animationEndListener)
                    start()
                }
    }

    private fun updateGradient() {
        val shader = LinearGradient(
                0f, 0f, 0f, bounds.bottom.toFloat(), colors, positions, Shader.TileMode.MIRROR)
        gradientPaint.shader = shader
        gradientPaint.isAntiAlias = true
    }

    override fun setBounds(left: Int, top: Int, right: Int, bottom: Int) {
        super.setBounds(left, top, right, bottom)
        updateGradient()
    }

    override fun setBounds(bounds: Rect) {
        super.setBounds(bounds)
        updateGradient()
    }

    override fun draw(canvas: Canvas) {
        gradientPaint.alpha = getAlpha()
        canvas.drawPaint(gradientPaint)
    }

    override fun setAlpha(alpha: Int) {
        if (alpha != storedAlpha) {
            storedAlpha = alpha
            invalidateSelf()
        }
    }

    override fun getAlpha(): Int = storedAlpha

    override fun setColor(mainColor: Int, animated: Boolean) {
        if (colorTo == mainColor || darkMode) {
            return
        }
        colorTo = mainColor

        colorAnimation?.cancel()

        if (animated) {
            val colorsFrom = colors.clone()
            colorAnimation = ValueAnimator.ofFloat(0f, 1f)
                    .apply {
                        duration = COLOR_ANIMATION_DURATION
                        interpolator = COLOR_ANIMATION_INTERPOLATOR
                        addUpdateListener { animation ->
                            val ratio = animation.animatedValue as Float
                            // Animate towards the target color
                            setColors(ColorUtils.blendARGB(colorsFrom[0], mainColor, ratio))
                        }
                        addListener(animationEndListener)
                        start()
                    }
        } else {
            setColors(mainColor)
        }
    }

    private fun setColors(color: Int) {
        // Set each gradient color, ensuring that the alpha component of the color does not exceed
        // the alpha component of the dark mode color. If this is not done, then if dark mode is
        // enabled in the middle of this drawable being animated in by GlobalActionsDialog, the user
        // will see a flicker caused by the alpha increasing by the entry animation, and then
        // decreasing by the dark mode animation.
        for (i in 0 until colors.size) {
            val targetAlpha = Color.alpha(targetColors[i])
            val colorAlpha = (Color.alpha(color) * GRADIENT_SCRIM_ALPHA).roundToInt()
            if (targetAlpha < colorAlpha) {
                colors[i] = ColorUtils.setAlphaComponent(color, targetAlpha)
            } else {
                colors[i] = ColorUtils.setAlphaComponent(color, colorAlpha)
            }
        }
        updateGradient()
        invalidateSelf()
    }
}