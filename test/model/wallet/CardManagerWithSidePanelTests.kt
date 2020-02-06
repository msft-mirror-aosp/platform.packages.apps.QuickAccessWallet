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

package com.android.systemui.plugin.globalactions.wallet.model

import com.android.systemui.plugin.globalactions.wallet.common.CardManager
import com.android.systemui.plugin.globalactions.wallet.common.CardManager.Result.Failure
import com.android.systemui.plugin.globalactions.wallet.common.CardManager.Result.Success
import com.android.systemui.plugin.globalactions.wallet.reactive.Eventual
import com.android.systemui.plugin.globalactions.wallet.reactive.eventualOf
import com.android.systemui.plugin.globalactions.wallet.reactive.getBlocking
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CardManagerWithSidePanelTests {

    @Test
    fun globalActionCards_failsWhenPrimaryFails() {
        val primaryResult = Failure<Int>("foo")
        val primaryManager = object : CardManager<Int> {
            override val globalActionCards: Eventual<CardManager.Result<Int>>
                get() = eventualOf(primaryResult)
        }

        val leftPanelResult = Success(0, sequenceOf(0), 1)
        val leftPanelManager = object : CardManager<Int> {
            override val globalActionCards: Eventual<CardManager.Result<Int>>
                get() = eventualOf(leftPanelResult)
        }

        val tlvmm = primaryManager.withSidePanel(leftPanelManager)
        val result = tlvmm.globalActionCards.getBlocking()
        assertThat(result).isSameAs(primaryResult)
    }

    @Test
    fun globalActionCards_returnPrimaryResult_whenPrimarySuccessful_andLeftPanelFails() {
        val primaryResult = Success(0, sequenceOf(0), 1)
        val primaryManager = object : CardManager<Int> {
            override val globalActionCards: Eventual<CardManager.Result<Int>>
                get() = eventualOf(primaryResult)
        }

        val leftPanelResult = Failure<Int>("foo")
        val leftPanelManager = object : CardManager<Int> {
            override val globalActionCards: Eventual<CardManager.Result<Int>>
                get() = eventualOf(leftPanelResult)
        }

        val tlvmm = primaryManager.withSidePanel(leftPanelManager)
        val result = tlvmm.globalActionCards.getBlocking()
        assertThat(result).isSameAs(primaryResult)
    }

    @Test
    fun globalActionCards_concatResults_whenBothSuccessful() {
        val primaryResult = Success(0, sequenceOf(1), 1)
        val primaryManager = object : CardManager<Int> {
            override val globalActionCards: Eventual<CardManager.Result<Int>>
                get() = eventualOf(primaryResult)
        }

        val leftPanelResult = Success(0, sequenceOf(0), 1)
        val leftPanelManager = object : CardManager<Int> {
            override val globalActionCards: Eventual<CardManager.Result<Int>>
                get() = eventualOf(leftPanelResult)
        }

        val tlvmm = primaryManager.withSidePanel(leftPanelManager)
        val result = tlvmm.globalActionCards.getBlocking()
        assertThat(result).isInstanceOf(Success::class.java)
        val success = result as Success<Int>
        assertThat(success.selectedIndex).isEqualTo(1)
        assertThat(success.numCards).isEqualTo(2)
        assertThat(success.cards.toList()).isEqualTo(listOf(0, 1))
    }
}