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

import com.android.systemui.plugin.globalactions.wallet.common.CardManager.Result.Failure
import com.android.systemui.plugin.globalactions.wallet.common.CardManager.Result.Success
import com.android.systemui.plugin.globalactions.wallet.reactive.BroadcastingEventSource
import com.android.systemui.plugin.globalactions.wallet.reactive.Completable
import com.android.systemui.plugin.globalactions.wallet.reactive.Eventual
import com.android.systemui.plugin.globalactions.wallet.reactive.asCompletable
import com.android.systemui.plugin.globalactions.wallet.reactive.eventualOf
import com.android.systemui.plugin.globalactions.wallet.reactive.getBlocking
import com.android.systemui.plugin.globalactions.wallet.reactive.neverEventual
import com.android.systemui.plugin.globalactions.wallet.reactive.subscribe
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CardManagerTests {

    @Test
    fun cardManagerResultMap_transformsCards() {
        val fakeResult = Success(0, sequenceOf(0, 1, 2, 3), 4)
        val mappedResult = fakeResult.map { it + 1 }
        assertThat(mappedResult).isInstanceOf(Success::class.java)
        val success = mappedResult as Success<Int>
        assertThat(success.selectedIndex).isEqualTo(0)
        assertThat(success.numCards).isEqualTo(4)
        assertThat(success.cards.toList()).isEqualTo(listOf(1,2,3,4))
    }

    @Test
    fun cardManagerResultMap_doesNotAffectFailure() {
        val fakeResult = Failure<Int>("foo")
        val mappedResult = fakeResult.map { it + 1 }
        assertThat(mappedResult).isInstanceOf(Failure::class.java)
        val failure = mappedResult as Failure<Int>
        assertThat(failure.message).isEqualTo("foo")
    }

    @Test
    fun cardManagerConvert_doesNotAffectDismissRequest() {
        val dismissSource = BroadcastingEventSource<Any>()
        val fakeManager = object : CardManager<Any> {
            override val globalActionCards: Eventual<CardManager.Result<Any>>
                get() = neverEventual()
            override val dismissRequest: Completable
                get() = dismissSource.asCompletable()
        }
        val converted = fakeManager.convert { it }
        var completed = false
        converted.dismissRequest.subscribe { completed = true }
        assertThat(completed).isFalse()
        dismissSource.complete()
        assertThat(completed).isTrue()
    }

    @Test
    fun cardManagerConvert_convertsSuccessfulResultCards() {
        val fakeResult = Success(0, sequenceOf(0, 1, 2, 3), 4)
        val fakeManager = object : CardManager<Int> {
            override val globalActionCards: Eventual<CardManager.Result<Int>>
                get() = eventualOf(fakeResult)
        }
        val converted = fakeManager.convert { it + 1 }
        val result = converted.globalActionCards.getBlocking()
        assertThat(result).isInstanceOf(Success::class.java)
        val success = result as Success<Int>
        assertThat(success.selectedIndex).isEqualTo(0)
        assertThat(success.numCards).isEqualTo(4)
        assertThat(success.cards.toList()).isEqualTo(listOf(1,2,3,4))
    }

    @Test
    fun cardManagerConvert_doesNotAffectFailureResult() {
        val fakeResult = Failure<Int>("foo")
        val fakeManager = object : CardManager<Int> {
            override val globalActionCards: Eventual<CardManager.Result<Int>>
                get() = eventualOf(fakeResult)
        }
        val converted = fakeManager.convert { it + 1 }
        val result = converted.globalActionCards.getBlocking()
        assertThat(result).isInstanceOf(Failure::class.java)
        val failure = result as Failure<Int>
        assertThat(failure.message).isEqualTo("foo")
    }

    @Test
    fun cardManagerAndThen_invokesCallback() {
        val fakeResult = Failure<Int>("fnord")
        val fakeResult2 = Failure<Int>("foo")
        val fakeManager = object : CardManager<Int> {
            override val globalActionCards: Eventual<CardManager.Result<Int>>
                get() = eventualOf(fakeResult)
        }
        val chained = fakeManager.andThen {
            assertThat(it).isSameAs(fakeResult)
            eventualOf(fakeResult2)
        }
        val result = chained.globalActionCards.getBlocking()
        assertThat(result).isSameAs(fakeResult2)
    }

    @Test
    fun cardManagerAndThen_doesNotAffectDismissRequest() {
        val dismissSource = BroadcastingEventSource<Any>()
        val fakeManager = object : CardManager<Any> {
            override val globalActionCards: Eventual<CardManager.Result<Any>>
                get() = neverEventual()
            override val dismissRequest: Completable
                get() = dismissSource.asCompletable()
        }
        val chained = fakeManager.andThen { eventualOf(Failure<Any>("foo")) }
        var completed = false
        chained.dismissRequest.subscribe { completed = true }
        assertThat(completed).isFalse()
        dismissSource.complete()
        assertThat(completed).isTrue()
    }
}