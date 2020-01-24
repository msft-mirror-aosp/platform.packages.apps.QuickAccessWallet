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

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.ViewGroup
import com.android.systemui.plugin.globalactions.wallet.reactive.Completable
import com.android.systemui.plugin.globalactions.wallet.view.Bindable
import com.android.systemui.plugin.globalactions.wallet.view.carousel.ClickableScalingCardCarouselView
import com.android.systemui.plugin.globalactions.wallet.view.carousel.ViewSupplier
import com.android.systemui.plugin.globalactions.wallet.view.common.BitmapCardViewModel
import com.android.systemui.plugin.globalactions.wallet.view.common.EmergencyInfoCardViewModel
import com.android.systemui.plugin.globalactions.wallet.view.common.GlobalActionCardContainerViewModel
import com.android.systemui.plugin.globalactions.wallet.view.common.GlobalActionCardViewModel
import com.android.systemui.plugin.globalactions.wallet.view.common.WalletCarouselViewModel

/**
 * View for the global action wallet carousel. Responsible for taking a list of global action cards,
 * and rendering the appropriate view for them in the carousel.
 */
class WalletView<TViewModel, TBitmapCard, TEmergencyInfoCard>(
    context: Context,
    attributeSet: AttributeSet? = null
) : ClickableScalingCardCarouselView<WalletView.ViewHolder<TViewModel>>(context, attributeSet)
where
    TViewModel : WalletCarouselViewModel<Drawable, TBitmapCard, TEmergencyInfoCard>,
    TViewModel : GlobalActionCardContainerViewModel<Drawable>,
    TBitmapCard : GlobalActionCardViewModel,
    TBitmapCard: GlobalActionCardContainerViewModel<Drawable>,
    TBitmapCard : BitmapCardViewModel<Drawable>,
    TEmergencyInfoCard : GlobalActionCardViewModel,
    TEmergencyInfoCard: GlobalActionCardContainerViewModel<Drawable>,
    TEmergencyInfoCard : EmergencyInfoCardViewModel<Drawable>
{
    class ViewHolder<in TViewModel>(
        holder: ClickableScalingCardCarouselView.ViewHolder,
        private val bindable: Bindable<TViewModel>
    ) : ClickableScalingCardCarouselView.ViewHolder by holder {
        fun bind(viewModel: TViewModel) = bindable.bind(viewModel)
    }

    private val inflater = LayoutInflater.from(context)!!

    /**
     * Sets a list of cards and selected index for this carousel, triggering a relayout.
     */
    fun setCards(cards: List<TViewModel>, selectedIndex: Int) {
        setAdapter(Adapter(cards), selectedIndex)
    }

    private inner class Adapter(val cards: List<TViewModel>) :
            CardCarouselAdapter<ViewHolder<TViewModel>> {

        override val itemCount: Int = cards.size

        private val bitmapType = 0
        private val emergencyInfoType = 1
        private val typeVisitor =
            object : WalletCarouselViewModel.Visitor<TBitmapCard, TEmergencyInfoCard, Int> {
                override fun bitmapCard(card: TBitmapCard) = bitmapType
                override fun emergencyInfoCard(card: TEmergencyInfoCard) = emergencyInfoType
            }

        override fun getItemViewType(position: Int): Int =
            cards[position].visit(typeVisitor)

        override fun bindViewHolder(viewHolder: ViewHolder<TViewModel>, position: Int) =
            viewHolder.bind(cards[position])

        override fun createViewHolder(parent: ViewGroup, type: Int): ViewHolder<TViewModel> =
            when (type) {
                bitmapType -> bitmapCardViewHolder(parent)
                emergencyInfoType -> emergencyInfoCardViewHolder(parent)
                else ->
                    throw IllegalArgumentException("Can't create ViewHolder of unknown type $type")
            }

        private fun emergencyInfoCardViewHolder(parent: ViewGroup): ViewHolder<TViewModel> {
            fun innerFactory(innerParent: ViewGroup) = run {
                val eInfoHolder = EmergencyCardViewHolder(inflater, innerParent)
                object : Bindable<TEmergencyInfoCard> by eInfoHolder,
                        ViewSupplier by eInfoHolder {}
            }
            val cardHolder = GlobalActionCardViewHolder(
                    cardWidthPx, cardHeightPx, inflater, parent, ::innerFactory)
            val bindVisitor = object :
                    WalletCarouselViewModel.Visitor<TBitmapCard, TEmergencyInfoCard, Completable>
                            by errorVisitor() {
                override fun emergencyInfoCard(card: TEmergencyInfoCard): Completable =
                    cardHolder.bind(card)
            }
            val bindable = object : Bindable<TViewModel> {
                override fun bind(data: TViewModel) = data.visit(bindVisitor)
            }
            return ViewHolder(cardHolder, bindable)
        }

        private fun bitmapCardViewHolder(parent: ViewGroup): ViewHolder<TViewModel> {
            fun innerFactory(innerParent: ViewGroup) = run {
                val bitmapHolder = BitmapCardViewHolder(inflater, innerParent)
                object : Bindable<TBitmapCard> by bitmapHolder, ViewSupplier by bitmapHolder {}
            }
            val cardHolder = GlobalActionCardViewHolder(
                    cardWidthPx, cardHeightPx, inflater, parent, ::innerFactory)
            val bindVisitor = object :
                    WalletCarouselViewModel.Visitor<TBitmapCard, TEmergencyInfoCard, Completable>
                            by errorVisitor() {
                override fun bitmapCard(card: TBitmapCard) = cardHolder.bind(card)
            }
            val bindable = object : Bindable<TViewModel> {
                override fun bind(data: TViewModel) = data.visit(bindVisitor)
            }
            return ViewHolder(cardHolder, bindable)
        }

        private fun <R> errorVisitor() = object :
                WalletCarouselViewModel.Visitor<TBitmapCard, TEmergencyInfoCard, R> {
            override fun bitmapCard(card: TBitmapCard) = error("unexpected bitmap card")
            override fun emergencyInfoCard(card: TEmergencyInfoCard) =
                error("unexpected emergency info card")
        }
    }
}
