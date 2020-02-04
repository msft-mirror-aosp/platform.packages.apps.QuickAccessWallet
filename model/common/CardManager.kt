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

package com.android.systemui.plugin.globalactions.wallet.common

import com.android.systemui.plugin.globalactions.wallet.common.CardManager.Result
import com.android.systemui.plugin.globalactions.wallet.reactive.Completable
import com.android.systemui.plugin.globalactions.wallet.reactive.Eventual
import com.android.systemui.plugin.globalactions.wallet.reactive.andThen
import com.android.systemui.plugin.globalactions.wallet.reactive.map
import com.android.systemui.plugin.globalactions.wallet.reactive.neverComplete

/**
 * Quick Access Wallet backend data source. This interface represents all interaction between a
 * backend and the Wallet UI.
 */
interface CardManager<out T> {

    /**
     * A potential value that, when subscribed to, asynchronously fetches data to be displayed in
     * the Wallet.
     */
    val globalActionCards: Eventual<Result<T>>

    /**
     * A completable that, when complete, signifies that the backend would like to dismiss the
     * Wallet.
     */
    val dismissRequest: Completable get() = neverComplete()

    /** Result of [CardManager.globalActionCards] */
    sealed class Result<out T> {
        /** A successful result, containing the data queried from the [CardManager] */
        data class Success<T>(val selectedIndex: Int, val cards: Sequence<T>, val numCards: Int) :
                Result<T>()
        /** Indicates that an error occurred while querying the [CardManager] */
        data class Failure<T>(val message: CharSequence?) : Result<T>()
    }
}

/**
 * Converts the type of data contained in a [Result.Success], and leaves other [Result] types
 * unchanged.
 */
fun <T, U> Result<T>.map(mapper: (T) -> U): Result<U> =
    when (this) {
        is Result.Success<T> -> Result.Success(selectedIndex, cards.map(mapper), numCards)
        is Result.Failure<T> -> Result.Failure(message)
    }

/**
 * Returns a new [CardManager] that wraps this one, but converts all cards emitted by applying the
 * given [converter].
 */
fun <T, U> CardManager<U>.convert(converter: (U) -> T): CardManager<T> = object : CardManager<T> {
    override val globalActionCards get() = this@convert.globalActionCards.map { it.map(converter) }
    override val dismissRequest = this@convert.dismissRequest
}

/**
 * Returns a new [CardManager] that wraps this one, but performs an additional asynchronous action
 * after fetching cards by invoking the given [converter].
 */
fun <T, U> CardManager<U>.andThen(converter: (Result<U>) -> Eventual<Result<T>>): CardManager<T> =
    object : CardManager<T> {
        override val globalActionCards
            get() = this@andThen.globalActionCards.andThen { converter(it) }
        override val dismissRequest = this@andThen.dismissRequest
    }
