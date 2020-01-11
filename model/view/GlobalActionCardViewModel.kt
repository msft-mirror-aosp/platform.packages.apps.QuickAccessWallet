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

import com.android.systemui.plugin.globalactions.wallet.reactive.Completable
import com.android.systemui.plugin.globalactions.wallet.reactive.Potential

/**  Base view model for all global action cards. */
interface GlobalActionCardViewModel {

    /**
     * [Completable] that, when subscribed to, "selects" this card. The card should remiain selected
     * for the lifetime of the subscription.
     */
    val select: Completable

    /**
     * A potential value that, when subscribed to, "clicks" the card. If a value is emitted, the
     * click is considered successful, and the Global Actions menu will be dismissed.
     */
    val click: Potential<Unit>
}
