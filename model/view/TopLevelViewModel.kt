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

package com.android.systemui.plugin.globalactions.wallet.view.common

import com.android.systemui.plugin.globalactions.wallet.view.common.TopLevelViewModel.BitmapViewModel
import com.android.systemui.plugin.globalactions.wallet.view.common.TopLevelViewModel.EmergencyInfoViewModel

/** Top-level ViewModel interface for all global action cards shown in Wallet. */
interface TopLevelViewModel<TBitmap> :
        WalletCarouselViewModel<TBitmap, BitmapViewModel<TBitmap>, EmergencyInfoViewModel<TBitmap>>,
        GlobalActionCardViewModel,
        GlobalActionCardContainerViewModel<TBitmap> {

    interface BitmapViewModel<TBitmap> : TopLevelViewModel<TBitmap>, BitmapCardViewModel<TBitmap>
    interface EmergencyInfoViewModel<TBitmap> :
            TopLevelViewModel<TBitmap>, EmergencyInfoCardViewModel<TBitmap>

    companion object {
        fun <T, TBitmap> bitmapViewModel(viewModel: T): TopLevelViewModel<TBitmap>
                where T : BitmapCardViewModel<TBitmap>,
                      T : GlobalActionCardViewModel,
                      T : GlobalActionCardContainerViewModel<TBitmap> =
            BitmapViewModelImpl(viewModel)

        fun <T, TBitmap> emergencyInfoViewModel(viewModel: T): TopLevelViewModel<TBitmap>
                where T : EmergencyInfoCardViewModel<TBitmap>,
                      T : GlobalActionCardViewModel,
                      T : GlobalActionCardContainerViewModel<TBitmap> =
            EmergencyInfoViewModelImpl(viewModel)

        private class BitmapViewModelImpl<T, TBitmap>(viewModel: T) :
                BitmapViewModel<TBitmap>,
                BitmapCardViewModel<TBitmap> by viewModel,
                GlobalActionCardViewModel by viewModel,
                GlobalActionCardContainerViewModel<TBitmap> by viewModel
        where T : BitmapCardViewModel<TBitmap>,
              T : GlobalActionCardViewModel,
              T : GlobalActionCardContainerViewModel<TBitmap>
        {
            override fun <T> visit(visitor: Visitor<TBitmap, T>): T = visitor.bitmapCard(this)
        }

        private class EmergencyInfoViewModelImpl<T, TBitmap>(viewModel: T) :
                EmergencyInfoViewModel<TBitmap>,
                EmergencyInfoCardViewModel<TBitmap> by viewModel,
                GlobalActionCardViewModel by viewModel,
                GlobalActionCardContainerViewModel<TBitmap> by viewModel
        where T : EmergencyInfoCardViewModel<TBitmap>,
              T : GlobalActionCardViewModel,
              T : GlobalActionCardContainerViewModel<TBitmap>
        {
            override fun <T> visit(visitor: Visitor<TBitmap, T>): T =
                visitor.emergencyInfoCard(this)
        }
    }
}

private typealias Visitor<TBitmap, T> =
        WalletCarouselViewModel.Visitor<
                BitmapViewModel<TBitmap>, EmergencyInfoViewModel<TBitmap>, T>