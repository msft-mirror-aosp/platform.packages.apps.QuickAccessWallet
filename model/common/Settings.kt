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

interface Setting<T> {
    var value: T

    interface Provider<T> {
        val value: T
    }
}

fun <T> Setting<T>.provider(): Setting.Provider<T> = object : Setting.Provider<T> {
    override val value: T get() = this@provider.value
}

fun <T, U> Setting.Provider<T>.map(mapper: (T) -> U): Setting.Provider<U> =
    object : Setting.Provider<U> {
        override val value: U get() = mapper.invoke(this@map.value)
    }

fun <T, U, R> Setting.Provider<T>.zip(
    other: Setting.Provider<U>,
    zipper: (T, U) -> R
): Setting.Provider<R> =
    object : Setting.Provider<R> {
        override val value: R get() = zipper(this@zip.value, other.value)
    }