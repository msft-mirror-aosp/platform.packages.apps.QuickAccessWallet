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
import com.android.systemui.plugin.globalactions.wallet.reactive.Completable
import com.android.systemui.plugin.globalactions.wallet.reactive.completableAction
import com.android.systemui.plugin.globalactions.wallet.view.Bindable
import com.android.systemui.plugin.globalactions.wallet.view.R
import com.android.systemui.plugin.globalactions.wallet.view.carousel.ViewSupplier
import com.android.systemui.plugin.globalactions.wallet.view.common.BitmapCardViewModel

/** View Holder for a card that displays a Bitmap in an ImageView. */
class BitmapCardViewHolder(inflater: LayoutInflater, parent: ViewGroup) :
        Bindable<BitmapCardViewModel<Drawable>>, ViewSupplier {

    override val view: View = inflater.inflate(R.layout.picture_card_layout, parent, false)
    private val cardImageView: ImageView = view.requireViewById(R.id.CardImageView)

    override fun bind(data: BitmapCardViewModel<Drawable>): Completable = completableAction {
        cardImageView.apply {
            setImageDrawable(data.cardImage)
            contentDescription = data.contentDescription
        }
    }
}
