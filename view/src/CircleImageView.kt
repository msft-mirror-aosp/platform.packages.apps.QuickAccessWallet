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

import android.content.Context
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.widget.ImageView
import kotlin.math.min

class CircleImageView(context: Context, attrs: AttributeSet?, defStyleAttr: Int, defStyleRes: Int) :
        ImageView(context, attrs, defStyleAttr, defStyleRes) {

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) :
            this(context, attrs, defStyleAttr, 0)
    constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, 0, 0)
    constructor(context: Context) : this(context, null)

    private val strokeWidth = context.resources.displayMetrics.density

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).also { p ->
        p.color = context.getColor(R.color.emergency_info_photo_border)
        p.isDither = true
        p.strokeWidth = strokeWidth * 2
        p.style = Paint.Style.STROKE
        p.blendMode = BlendMode.SRC
        p.strokeJoin = Paint.Join.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        val centerX = width / 2f
        val centerY = height / 2f
        val radius = min(width, height) / 2f
        val outerEdge = Path().apply {
            addCircle(centerX, centerY, radius, Path.Direction.CCW)
        }
        canvas.clipPath(outerEdge)
        super.onDraw(canvas)
        canvas.drawPath(outerEdge, strokePaint)
    }
}