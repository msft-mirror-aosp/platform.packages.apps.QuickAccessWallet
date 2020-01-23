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

package com.android.systemui.plugin.globalactions.wallet.reactive

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.fail
import org.junit.Test
import kotlin.NoSuchElementException

class ReactiveTests {

    @Test
    fun activeSubscription_isCancelled() {
        val s = ActiveSubscription()
        assertThat(s.isCancelled).isFalse()
        s.cancel()
        assertThat(s.isCancelled).isTrue()
    }

    @Test
    fun subscriptionHolder_replacesNothing() {
        val holder = SubscriptionHolder()
        assertThat(holder.isCancelled).isFalse()
        holder.replace(CancelledSubscription)
        assertThat(holder.isCancelled).isTrue()
    }

    @Test
    fun subscriptionHolder_replaceCancelled_cancelsNew() {
        val holder = SubscriptionHolder()
        holder.replace(CancelledSubscription)
        val s = ActiveSubscription()
        holder.replace(s)
        assertThat(s.isCancelled).isTrue()
    }

    @Test
    fun subscriptionHolder_replace_cancelsOld() {
        val holder = SubscriptionHolder()
        val s = ActiveSubscription()
        val s2 = ActiveSubscription()
        holder.replace(s)
        assertThat(s.isCancelled).isFalse()
        holder.replace(s2)
        assertThat(s.isCancelled).isTrue()
        assertThat(s2.isCancelled).isFalse()
    }

    @Test
    fun events_sourceSubscription_cancelledWhenComplete() {
        val fakeSubscription = FakeSubscription()

        val eventStream = events<Any> {
            setSubscription(fakeSubscription)
            complete()
        }

        assertThat(fakeSubscription.isCancelled).isFalse()

        val sink = TestEventStreamSink<Any>()
        val sub = eventStream.subscribe(sink)

        assertThat(sink.completed).isTrue()
        assertThat(fakeSubscription.isCancelled).isTrue()
        assertThat(sub.isCancelled).isTrue()
    }

    @Test
    fun events_sourceSubscription_cancelledWhenEventStreamSubscriptionCancelled() {
        val fakeSubscription = FakeSubscription()

        val eventStream = events<Int> { setSubscription(fakeSubscription) }

        assertThat(fakeSubscription.isCancelled).isFalse()

        val sink = TestEventStreamSink<Int>()
        val sub = eventStream.subscribe(sink)

        assertThat(sink.completed).isFalse()
        assertThat(sub.isCancelled).isFalse()
        assertThat(fakeSubscription.isCancelled).isFalse()

        sub.cancel()

        assertThat(sink.completed).isFalse()
        assertThat(sub.isCancelled).isTrue()
        assertThat(fakeSubscription.isCancelled).isTrue()
    }

    @Test
    fun eventStreamSubscribe_callbacksInvoked() {
        val expected = listOf(1, 2, 3)
        val eventStream = events<Int> {
            emitEvent(1)
            emitEvent(2)
            emitEvent(3)
            complete()
        }
        val sink = TestEventStreamSink<Int>()
        eventStream.subscribe(sink)
        assertThat(sink.completed).isTrue()
        assertThat(sink.seenEvents).isEqualTo(expected)
    }

    @Test
    fun eventStreamConnect_propagatesSubscriptionDownstream() {
        var cancelCount = 0
        val fakeSubSource = object : Subscription {
            override val isCancelled: Boolean get() = cancelCount > 0
            override fun cancel() {
                cancelCount++
            }
        }
        val stream = object : EventStream<Any> {
            override fun connect(getSink: (Subscription) -> EventStream.Sink<Any>) {
                val sink = getSink(fakeSubSource)
                sink.onEvent(Unit)
                assertThat(fakeSubSource.isCancelled).isTrue()
            }
        }
        var seenEvents = 0
        stream.connect { sub ->
            object : EventStream.Sink<Any> {
                override fun onEvent(event: Any) {
                    seenEvents++
                    sub.cancel()
                }
            }
        }
        assertThat(cancelCount).isEqualTo(1)
        assertThat(seenEvents).isEqualTo(1)
    }

    @Test
    fun events_ifCancelled_doesNotInvokeCallbacksOnEmit() {
        val stream = events<Int> {
            emitEvent(1)
            setSubscription(CancelledSubscription)
            emitEvent(2)
            complete()
        }
        var lastSeen: Int? = null
        var completed = false
        stream.subscribe(object : EventStream.Sink<Int> {
            override fun onEvent(event: Int) {
                lastSeen = event
            }

            override fun onComplete() {
                completed = true
            }
        })
        assertThat(lastSeen).isEqualTo(1)
        assertThat(completed).isFalse()
    }

    @Test
    fun eventsOf_emitsAll_completesImmediately() {
        val expected = listOf(1, 2, 3)
        val sink = TestEventStreamSink<Int>()
        eventsOf(1, 2, 3).subscribe(sink)
        assertThat(sink.completed).isTrue()
        assertThat(sink.seenEvents).isEqualTo(expected)
    }

    @Test
    fun emptyEventStream_completesImmediately() {
        var eventCount = 0
        var completed = false
        var sub: Subscription? = null
        emptyEvents<Any>().connect { s ->
            assertThat(s.isCancelled).isFalse()
            sub = s
            object : EventStream.Sink<Any> {
                override fun onEvent(event: Any) {
                    eventCount++
                }

                override fun onComplete() {
                    completed = true
                }
            }
        }
        assertThat(completed).isTrue()
        assertThat(eventCount).isEqualTo(0)
        assertThat(sub?.isCancelled).isEqualTo(true)
    }

    @Test
    fun neverEventStream_neverCompletes() {
        val sink = TestEventStreamSink<Any>()
        val sub = neverEvents<Any>().subscribe(sink)
        assertThat(sub.isCancelled).isFalse()
        assertThat(sink.completed).isFalse()
        assertThat(sink.seenEvents).isEmpty()
    }

    @Test
    fun sequence_toEvents_emitsAll() {
        val expected = (0..10).toList()
        val sink = TestEventStreamSink<Int>()
        (0..10).asSequence().toEvents().subscribe(sink)
        assertThat(sink.completed).isTrue()
        assertThat(sink.seenEvents).isEqualTo(expected)
    }

    @Test
    fun sequence_toEvents_stopsWhenCancelledDownstream() {
        val expected = listOf(1)
        val seq = sequence {
            yield(1)
            fail("iteration should not continue")
        }
        val seen = mutableListOf<Int>()
        seq.toEvents().connect { sub ->
            object : EventStream.Sink<Int> {
                override fun onEvent(event: Int) {
                    seen.add(event)
                    sub.cancel()
                }

                override fun onComplete() {
                    fail("unexpected onComplete")
                }
            }
        }
        assertThat(seen).isEqualTo(expected)
    }

    @Test
    fun eventStreamMap_transformsAllEventStream() {
        val expected = listOf(2, 4, 6)
        val sink = TestEventStreamSink<Int>()
        eventsOf(1, 2, 3).map { it * 2 }.subscribe(sink)
        assertThat(sink.completed).isTrue()
        assertThat(sink.seenEvents).isEqualTo(expected)
    }

    @Test
    fun eventStreamMap_cancelsUpstream() {
        val fakeSubscription = FakeSubscription()
        val eventStream = events<Int> { setSubscription(fakeSubscription) }
        val sub = eventStream.map { it * 2 }.subscribe()

        assertThat(fakeSubscription.isCancelled).isFalse()

        sub.cancel()

        assertThat(fakeSubscription.isCancelled).isTrue()
    }

    @Test
    fun eventStreamFilter_filtersAgainstPredicate() {
        val expected = listOf(1, 2, 3)
        val sink = TestEventStreamSink<Int>()
        eventsOf(4, 1, 5, 6, 2, 7, 3).filter { it <= 3 }.subscribe(sink)
        assertThat(sink.completed).isTrue()
        assertThat(sink.seenEvents).isEqualTo(expected)
    }

    @Test
    fun eventStreamFilter_cancelsUpstream() {
        val fakeSubscription = FakeSubscription()
        val eventStream = events<Int> { setSubscription(fakeSubscription) }
        val sub = eventStream.filter { true }.subscribe()

        assertThat(fakeSubscription.isCancelled).isFalse()

        sub.cancel()

        assertThat(fakeSubscription.isCancelled).isTrue()
    }

    @Test
    fun eventStreamFilterMap_filtersOutNulls() {
        val expected = listOf(0, 2, 4, 6, 8)
        val sink = TestEventStreamSink<Int>()
        (0 until 10).asSequence().toEvents().filterMap { it.takeIf { it % 2 == 0 } }.subscribe(sink)
        assertThat(sink.completed).isTrue()
        assertThat(sink.seenEvents).isEqualTo(expected)
    }

    @Test
    fun eventStreamFilterMap_cancelsUpstream() {
        val fakeSubscription = FakeSubscription()
        val eventStream = events<Int> { setSubscription(fakeSubscription) }
        val sub = eventStream.filterMap { it }.subscribe()

        assertThat(fakeSubscription.isCancelled).isFalse()

        sub.cancel()

        assertThat(fakeSubscription.isCancelled).isTrue()
    }

    @Test
    fun eventStreamAndThen_emitsAllEvents() {
        val expected = listOf(1, 2, 3, 4)
        val stream1 = eventsOf(1, 2)
        val stream2 = eventsOf(3, 4)
        val sink = TestEventStreamSink<Int>()
        stream1.andThen(stream2).subscribe(sink)
        assertThat(sink.completed).isTrue()
        assertThat(sink.seenEvents).isEqualTo(expected)
    }

    @Test
    fun eventStreamAndThen_doesNotSubscribeSecondUntilFirstCompletes() {
        val expected = listOf(1, 2, 3)
        val stream1 = BroadcastingEventSource<Int>()
        var subscribed2 = false
        val stream2 = events<Int> {
            subscribed2 = true
            emitEvent(3)
            complete()
        }
        val sink = TestEventStreamSink<Int>()
        stream1.andThen(stream2).subscribe(sink)
        assertThat(sink.completed).isFalse()
        assertThat(sink.seenEvents).isEmpty()
        assertThat(subscribed2).isFalse()
        stream1.emitEvent(1)
        stream1.emitEvent(2)
        assertThat(subscribed2).isFalse()
        stream1.complete()
        assertThat(subscribed2).isTrue()
        assertThat(sink.completed).isTrue()
        assertThat(sink.seenEvents).isEqualTo(expected)
    }

    @Test
    fun eventStreamAndThen_cancelsUpstream_first() {
        val fakeSubscription1 = FakeSubscription()
        val eventStream1 = events<Int> { setSubscription(fakeSubscription1) }
        val fakeSubscription2 = FakeSubscription()
        val eventStream2 = events<Int> { setSubscription(fakeSubscription2) }
        val sub = eventStream1.andThen(eventStream2).subscribe()

        assertThat(fakeSubscription1.isCancelled).isFalse()
        assertThat(fakeSubscription2.isCancelled).isFalse()

        sub.cancel()

        assertThat(fakeSubscription1.isCancelled).isTrue()
        assertThat(fakeSubscription2.isCancelled).isFalse()
    }

    @Test
    fun eventStreamAndThen_cancelsUpstream_second() {
        val fakeSubscription2 = FakeSubscription()
        val eventStream2 = events<Int> { setSubscription(fakeSubscription2) }
        val sub = emptyEvents<Int>().andThen(eventStream2).subscribe()

        assertThat(fakeSubscription2.isCancelled).isFalse()

        sub.cancel()

        assertThat(fakeSubscription2.isCancelled).isTrue()
    }

    @Test
    fun eventStreamEnumerate_emitsIndices() {
        val expected = listOf(0 to 1, 1 to 2, 2 to 3)
        val sink = TestEventStreamSink<Pair<Int, Int>>()
        eventsOf(1, 2, 3).enumerate().subscribe(sink)
        assertThat(sink.completed).isTrue()
        assertThat(sink.seenEvents).isEqualTo(expected)
    }

    @Test
    fun eventStreamEnumerate_cancelsUpstream() {
        val fakeSubscription = FakeSubscription()
        val eventStream = events<Int> { setSubscription(fakeSubscription) }
        val sub = eventStream.enumerate().subscribe()

        assertThat(fakeSubscription.isCancelled).isFalse()

        sub.cancel()

        assertThat(fakeSubscription.isCancelled).isTrue()
    }

    @Test
    fun eventStreamBroadcast_emitsAllEvents() {
        val expected = listOf(1, 2, 3)
        val sink = TestEventStreamSink<Int>()
        eventsOf(0, 1, 2).broadcast { es -> es.map { it + 1 } }.subscribe(sink)
        assertThat(sink.completed).isTrue()
        assertThat(sink.seenEvents).isEqualTo(expected)
    }

    @Test
    fun eventStreamBroadcast_sharesSubscriptionSideEffects() {
        var subscribeCount = 0
        var eventCount = 0
        val stream = events<Any> {
            subscribeCount++
            emitEvent(Unit)
        }
        stream.broadcast { s -> s.mergeWith(s) }.subscribe { eventCount++ }
        assertThat(subscribeCount).isEqualTo(1)
        assertThat(eventCount).isEqualTo(2)
    }

    @Test
    fun eventStreamBroadcast_cancelsUpstream() {
        val fakeSubscription = FakeSubscription()
        val eventStream = events<Int> { setSubscription(fakeSubscription) }
        val sub = eventStream.broadcast { s -> s.mergeWith(s) }.subscribe()

        assertThat(fakeSubscription.isCancelled).isFalse()

        sub.cancel()

        assertThat(fakeSubscription.isCancelled).isTrue()
    }

    @Test
    fun eventStreamFlatMap_transformAllEventStream() {
        val expected = listOf(1, 2, 3, 4, 5, 6)
        val sink = TestEventStreamSink<Int>()
        eventsOf(eventsOf(1, 2), eventsOf(3, 4), eventsOf(5, 6))
                .flatMap { it }
                .subscribe(sink)
        assertThat(sink.completed).isTrue()
        assertThat(sink.seenEvents).isEqualTo(expected)
    }

    @Test
    fun eventStreamFlatMap_cancelsUpstream() {
        val fakeSubscription = FakeSubscription()
        val eventStream = events<Int> { setSubscription(fakeSubscription) }
        val sub = eventStream.flatMap { neverEvents<Any>() }.subscribe()

        assertThat(fakeSubscription.isCancelled).isFalse()

        sub.cancel()

        assertThat(fakeSubscription.isCancelled).isTrue()
    }

    @Test
    fun eventStreamFlatMap_cancelsOnceAllCompleted() {
        val sourceEventStream = BroadcastingEventSource<EventStream<Unit>>()
        val subEventStream1 = BroadcastingEventSource<Unit>()
        val subEventStream2 = BroadcastingEventSource<Unit>()
        val eventStream = sourceEventStream.flatMap { it }
        var sub = eventStream.subscribe()

        assertThat(sub.isCancelled).isFalse()
        sourceEventStream.complete()
        assertThat(sub.isCancelled).isTrue()

        sub = eventStream.subscribe()
        assertThat(sub.isCancelled).isFalse()
        sourceEventStream.emitEvent(subEventStream1)
        sourceEventStream.complete()
        assertThat(sub.isCancelled).isFalse()
        subEventStream1.complete()
        assertThat(sub.isCancelled).isTrue()

        sub = eventStream.subscribe()
        sourceEventStream.emitEvent(subEventStream1)
        subEventStream1.complete()
        assertThat(sub.isCancelled).isFalse()
        sourceEventStream.complete()
        assertThat(sub.isCancelled).isTrue()

        sub = eventStream.subscribe()
        sourceEventStream.emitEvent(subEventStream1)
        sourceEventStream.emitEvent(subEventStream2)
        subEventStream1.complete()
        assertThat(sub.isCancelled).isFalse()
        sourceEventStream.complete()
        assertThat(sub.isCancelled).isFalse()
        subEventStream2.complete()
        assertThat(sub.isCancelled).isTrue()
    }

    @Test
    fun eventStreamSwitchMap_transformAllEventStream() {
        val expected = listOf(1, 2, 3, 4, 5, 6)
        val sink = TestEventStreamSink<Int>()
        eventsOf(eventsOf(1, 2), eventsOf(3, 4), eventsOf(5, 6))
                .switchMap { it }
                .subscribe(sink)
        assertThat(sink.completed).isTrue()
        assertThat(sink.seenEvents).isEqualTo(expected)
    }

    @Test
    fun eventStreamSwitchMap_cancelsUpstream() {
        val fakeSubscription2 = FakeSubscription()
        val eventStream2 = events<Any> { setSubscription(fakeSubscription2) }
        val fakeSubscription1 = FakeSubscription()
        val eventStream1 = events<Any> {
            setSubscription(fakeSubscription1)
            emitEvent(Unit)
        }
        val sub = eventStream1.switchMap { eventStream2 }.subscribe()

        assertThat(fakeSubscription1.isCancelled).isFalse()
        assertThat(fakeSubscription2.isCancelled).isFalse()

        sub.cancel()

        assertThat(fakeSubscription1.isCancelled).isTrue()
        assertThat(fakeSubscription2.isCancelled).isTrue()
    }

    @Test
    fun eventStreamSwitchMap_cancelsOnNewSourceEvent() {
        val sourceEventStream = BroadcastingEventSource<EventStream<Int>>()
        val subEventStream1 = BroadcastingEventSource<Int>()
        val subEventStream2 = BroadcastingEventSource<Int>()
        val eventStream = sourceEventStream.switchMap { it }
        var sub = eventStream.subscribe()

        assertThat(sub.isCancelled).isFalse()
        sourceEventStream.complete()
        assertThat(sub.isCancelled).isTrue()

        sub = eventStream.subscribe()
        assertThat(sub.isCancelled).isFalse()
        sourceEventStream.emitEvent(subEventStream1)
        sourceEventStream.complete()
        assertThat(sub.isCancelled).isFalse()
        subEventStream1.complete()
        assertThat(sub.isCancelled).isTrue()

        sub = eventStream.subscribe()
        sourceEventStream.emitEvent(subEventStream1)
        subEventStream1.complete()
        assertThat(sub.isCancelled).isFalse()
        sourceEventStream.complete()
        assertThat(sub.isCancelled).isTrue()

        val seenEventStream = mutableListOf<Int>()
        val expected = listOf(1, 2)
        sub = eventStream.subscribe { seenEventStream.add(it) }
        sourceEventStream.emitEvent(subEventStream1)
        subEventStream1.emitEvent(1)
        sourceEventStream.emitEvent(subEventStream2)
        subEventStream1.emitEvent(5)
        subEventStream2.emitEvent(2)
        assertThat(sub.isCancelled).isFalse()
        sourceEventStream.complete()
        assertThat(sub.isCancelled).isFalse()
        subEventStream2.complete()
        assertThat(sub.isCancelled).isTrue()
        assertThat(seenEventStream).isEqualTo(expected)
    }

    @Test
    fun eventStreamMerge_emitsAllEventStream() {
        val expected = listOf(1, 2, 3, 4)
        val sink = TestEventStreamSink<Int>()
        mergeEvents(eventsOf(1, 2), eventsOf(3, 4)).subscribe(sink)
        assertThat(sink.completed).isTrue()
        assertThat(sink.seenEvents).isEqualTo(expected)
    }

    @Test
    fun eventStreamMerge_doesNotReorderEventStream() {
        val eventStream1 = BroadcastingEventSource<Int>()
        val eventStream2 = BroadcastingEventSource<Int>()
        val expected = listOf(1, 2, 3, 4, 5)
        val sink = TestEventStreamSink<Int>()
        mergeEvents(eventStream1, eventStream2).subscribe(sink)

        eventStream2.emitEvent(1)
        eventStream1.emitEvent(2)
        eventStream1.emitEvent(3)
        eventStream2.emitEvent(4)
        eventStream2.emitEvent(5)

        assertThat(sink.seenEvents).isEqualTo(expected)
    }

    @Test
    fun eventStreamMerge_completesOnceBothSourcesComplete() {
        val eventStream1 = BroadcastingEventSource<Int>()
        val eventStream2 = BroadcastingEventSource<Int>()
        val expected = listOf(1, 2, 3, 4, 5)
        val sink = TestEventStreamSink<Int>()
        eventStream1.mergeWith(eventStream2).subscribe(sink)
        assertThat(sink.completed).isFalse()

        eventStream1.emitEvent(1)
        eventStream2.emitEvent(2)
        eventStream1.complete()
        assertThat(sink.completed).isFalse()
        eventStream2.emitEvent(3)
        eventStream2.emitEvent(4)
        eventStream2.emitEvent(5)
        eventStream2.complete()
        assertThat(sink.completed).isTrue()
        assertThat(sink.seenEvents).isEqualTo(expected)
    }

    @Test
    fun eventStreamFirstOrError_emitsFirstEventAndCompletes() {
        val sink = TestEventualSink<Int>()
        eventsOf(1).firstOrError().subscribe(sink)
        assertThat(sink.result).isEqualTo(Complete(1))
    }

    @Test
    fun eventStreamFirstOrError_cancelsUpstreamWhenComplete() {
        var cancelled = false
        val source = events<Int> {
            setCancelAction { cancelled = true }
            emitEvent(1)
        }
        source.firstOrError().subscribe()
        assertThat(cancelled).isTrue()
    }

    @Test
    fun eventStreamFirstOrError_cancelsUpstream() {
        val fakeSubscription = FakeSubscription()
        val eventStream1 = events<Int> { setSubscription(fakeSubscription) }
        val sub = eventStream1.firstOrError().subscribe()

        assertThat(fakeSubscription.isCancelled).isFalse()

        sub.cancel()

        assertThat(fakeSubscription.isCancelled).isTrue()
    }

    @Test
    fun eventStreamFirstOrError_whenSourceEmpty_throwsError() {
        assertThrows(NoSuchElementException::class.java) {
            emptyEvents<Any>().firstOrError().subscribe()
        }
    }

    @Test
    fun eventStreamFirst_emitsFirstEventAndCompletes() {
        val sink = TestPotentialSink<Int>()
        eventsOf(1).first().subscribe(sink)
        assertThat(sink.result).isEqualTo(Complete(1))
    }

    @Test
    fun eventStreamFirst_whenEmpty_emitsNull() {
        val sink = TestPotentialSink<Int>()
        emptyEvents<Int>().first().subscribe(sink)
        assertThat(sink.result).isEqualTo(Complete(null))
    }

    @Test
    fun eventStreamFirst_cancelsUpstreamWhenComplete() {
        var cancelled = false
        val source = events<Int> {
            setCancelAction { cancelled = true }
            emitEvent(1)
        }
        source.first().subscribe()
        assertThat(cancelled).isTrue()
    }

    @Test
    fun eventStreamFirst_cancelsUpstream() {
        val fakeSubscription = FakeSubscription()
        val eventStream1 = events<Int> { setSubscription(fakeSubscription) }
        val sub = eventStream1.first().subscribe()

        assertThat(fakeSubscription.isCancelled).isFalse()

        sub.cancel()

        assertThat(fakeSubscription.isCancelled).isTrue()
    }

    @Test
    fun eventStreamScan_runningTotal() {
        val expected = listOf(0, 1, 3, 6, 10, 15)
        val sink = TestEventStreamSink<Int>()
        (0..5).asSequence().toEvents().scan(0, Int::plus).subscribe(sink)
        assertThat(sink.completed).isTrue()
        assertThat(sink.seenEvents).isEqualTo(expected)
    }

    @Test
    fun eventStreamScan_cancelsUpstream() {
        val fakeSubscription = FakeSubscription()
        val eventStream = events<Int> { setSubscription(fakeSubscription) }
        val sub = eventStream.scan(0) { a, b -> a + b }.subscribe()

        assertThat(fakeSubscription.isCancelled).isFalse()

        sub.cancel()

        assertThat(fakeSubscription.isCancelled).isTrue()
    }

    @Test
    fun eventStreamPaired_emitsAdjacentEvents() {
        val expected = listOf(0 to 1, 1 to 2, 2 to 3, 3 to 4, 4 to 5)
        val sink = TestEventStreamSink<Pair<Int, Int>>()
        (0..5).asSequence().toEvents().paired().subscribe(sink)
        assertThat(sink.completed).isTrue()
        assertThat(sink.seenEvents).isEqualTo(expected)
    }

    @Test
    fun eventStreamPaired_cancelsUpstream() {
        val fakeSubscription = FakeSubscription()
        val eventStream = events<Int> { setSubscription(fakeSubscription) }
        val sub = eventStream.paired().subscribe()

        assertThat(fakeSubscription.isCancelled).isFalse()

        sub.cancel()

        assertThat(fakeSubscription.isCancelled).isTrue()
    }

    @Test
    fun eventStreamChanges_skipsRepeatedEvents() {
        val expected = listOf(1, 2, 3, 4, 5)
        val sink = TestEventStreamSink<Int>()
        eventsOf(1, 2, 2, 3, 3, 3, 4, 5, 5).changes().subscribe(sink)
        assertThat(sink.completed).isTrue()
        assertThat(sink.seenEvents).isEqualTo(expected)
    }

    @Test
    fun eventStreamChanges_cancelsUpstream() {
        val fakeSubscription = FakeSubscription()
        val eventStream = events<Int> { setSubscription(fakeSubscription) }
        val sub = eventStream.changes().subscribe()

        assertThat(fakeSubscription.isCancelled).isFalse()

        sub.cancel()

        assertThat(fakeSubscription.isCancelled).isTrue()
    }

    @Test
    fun eventStreamWithLatestFrom_emitsAllFromSourceAndLatestFromOther() {
        val expected = listOf(1 to 1, 2 to 1, 3 to 4)
        val stream1 = BroadcastingEventSource<Int>()
        val stream2 = BroadcastingEventSource<Int>()
        val sink = TestEventStreamSink<Pair<Int, Int>>()
        stream1.withLatestFrom(stream2).subscribe(sink)

        stream1.emitEvent(0)
        stream2.emitEvent(0)
        stream2.emitEvent(1)
        stream1.emitEvent(1)
        stream1.emitEvent(2)
        stream2.emitEvent(2)
        stream2.emitEvent(3)
        stream2.emitEvent(4)
        stream1.emitEvent(3)

        assertThat(sink.seenEvents).isEqualTo(expected)
    }

    @Test
    fun eventStreamWithLatestFrom_doesNotCompleteWithOtherStreamCompletes() {
        val stream1 = BroadcastingEventSource<Int>()
        val stream2 = BroadcastingEventSource<Int>()
        val sink = TestEventStreamSink<Pair<Int, Int>>()
        stream1.withLatestFrom(stream2).subscribe(sink)

        stream2.complete()
        assertThat(sink.completed).isFalse()
        stream1.complete()
        assertThat(sink.completed).isTrue()
    }

    @Test
    fun eventStreamWithLatestFrom_completesOnceSourceCompletes() {
        val stream1 = BroadcastingEventSource<Int>()
        val stream2 = BroadcastingEventSource<Int>()
        val sink = TestEventStreamSink<Pair<Int, Int>>()
        stream1.withLatestFrom(stream2).subscribe(sink)

        stream1.complete()
        assertThat(sink.completed).isTrue()
    }

    @Test
    fun eventStreamWithLatestFrom_cancelsUpstream() {
        val fakeSubscription1 = FakeSubscription()
        val fakeSubscription2 = FakeSubscription()
        val eventStream1 = events<Int> { setSubscription(fakeSubscription1) }
        val eventStream2 = events<Int> { setSubscription(fakeSubscription2) }
        val sub = eventStream1.withLatestFrom(eventStream2).subscribe()

        assertThat(fakeSubscription1.isCancelled).isFalse()
        assertThat(fakeSubscription2.isCancelled).isFalse()

        sub.cancel()

        assertThat(fakeSubscription1.isCancelled).isTrue()
        assertThat(fakeSubscription2.isCancelled).isTrue()
    }

    @Test
    fun eventStreamCompleteWhen_emitsAndCompletesUponPassingPredicate() {
        val expected = listOf(1, 2, 3)
        val sink = TestEventStreamSink<Int>()
        eventsOf(1, 2, 3, 4, 5).completeWhen { it > 2 }.subscribe(sink)
        assertThat(sink.completed).isTrue()
        assertThat(sink.seenEvents).isEqualTo(expected)
    }

    @Test
    fun eventStreamCompleteWhen_cancelsUpstreamWhenComplete() {
        var cancelled = false
        val source = events<Int> {
            setCancelAction { cancelled = true }
            emitEvent(1)
        }
        source.completeWhen { true }.subscribe()
        assertThat(cancelled).isTrue()
    }

    @Test
    fun eventStreamCompleteWhen_cancelsUpstream() {
        val fakeSubscription = FakeSubscription()
        val eventStream = events<Int> { setSubscription(fakeSubscription) }
        val sub = eventStream.completeWhen { false }.subscribe()

        assertThat(fakeSubscription.isCancelled).isFalse()

        sub.cancel()

        assertThat(fakeSubscription.isCancelled).isTrue()
    }

    @Test
    fun eventStreamUntil_completesUponPassingPredicate() {
        val expected = listOf(1, 2)
        val sink = TestEventStreamSink<Int>()
        eventsOf(1, 2, 3, 4, 5).until { it > 2 }.subscribe(sink)
        assertThat(sink.completed).isTrue()
        assertThat(sink.seenEvents).isEqualTo(expected)
    }

    @Test
    fun eventStreamUntil_cancelsUpstreamWhenComplete() {
        var cancelled = false
        val source = events<Int> {
            setCancelAction { cancelled = true }
            emitEvent(1)
        }
        source.until { true }.subscribe()
        assertThat(cancelled).isTrue()
    }

    @Test
    fun eventStreamUntil_cancelsUpstream() {
        val fakeSubscription = FakeSubscription()
        val eventStream = events<Int> { setSubscription(fakeSubscription) }
        val sub = eventStream.until { false }.subscribe()

        assertThat(fakeSubscription.isCancelled).isFalse()

        sub.cancel()

        assertThat(fakeSubscription.isCancelled).isTrue()
    }

    @Test
    fun eventual_sourceSubscription_cancelledWhenComplete() {
        val fakeSubscription = FakeSubscription()

        val eventual = eventual<Int> {
            setSubscription(fakeSubscription)
            complete(0)
        }

        assertThat(fakeSubscription.isCancelled).isFalse()

        val sink = TestEventualSink<Int>()
        val sub = eventual.subscribe(sink)

        assertThat(sink.completed).isTrue()
        assertThat(fakeSubscription.isCancelled).isTrue()
        assertThat(sub.isCancelled).isTrue()
    }

    @Test
    fun eventual_sourceSubscription_cancelledWhenEventStreamSubscriptionCancelled() {
        val fakeSubscription = FakeSubscription()

        val eventual = eventual<Int> { setSubscription(fakeSubscription) }

        assertThat(fakeSubscription.isCancelled).isFalse()

        val sink = TestEventualSink<Int>()
        val sub = eventual.subscribe(sink)

        assertThat(sink.completed).isFalse()
        assertThat(sub.isCancelled).isFalse()
        assertThat(fakeSubscription.isCancelled).isFalse()

        sub.cancel()

        assertThat(sink.completed).isFalse()
        assertThat(sub.isCancelled).isTrue()
        assertThat(fakeSubscription.isCancelled).isTrue()
    }

    @Test
    fun eventualSubscribe_callbackInvoked() {
        val sink = TestEventualSink<Int>()
        val eventual = eventual<Int> { complete(1) }
        eventual.subscribe(sink)
        assertThat(sink.completed).isTrue()
    }

    @Test
    fun eventualOf_completesImmediately() {
        val sink = TestEventualSink<Int>()
        eventualOf(1).subscribe(sink)
        assertThat(sink.result).isEqualTo(Complete(1))
    }

    @Test
    fun emptyPotential_completesImmediatelyWithNullResult() {
        val sink = TestPotentialSink<Int>()
        emptyPotential<Int>().subscribe(sink)
        assertThat(sink.result).isEqualTo(Complete(null))
    }

    @Test
    fun neverEventual_neverCompletes() {
        val sink = TestEventualSink<Any>()
        neverEventual<Any>().subscribe(sink)
        assertThat(sink.completed).isFalse()
    }

    @Test
    fun eventualMap_transformsAllEventStream() {
        val sink = TestEventualSink<Any>()
        eventualOf(1).map { it * 2 }.subscribe(sink)
        assertThat(sink.result).isEqualTo(Complete(2))
    }

    @Test
    fun eventualMap_cancelsUpstream() {
        val fakeSubscription = FakeSubscription()
        val eventual = eventual<Int> { setSubscription(fakeSubscription) }
        val sub = eventual.map { it * 2 }.subscribe()

        assertThat(fakeSubscription.isCancelled).isFalse()

        sub.cancel()

        assertThat(fakeSubscription.isCancelled).isTrue()
    }

    @Test
    fun eventualAndThen_producesInnerResult() {
        val sink = TestEventualSink<Int>()
        eventualOf(eventualOf(1)).andThen { it }.subscribe(sink)
        assertThat(sink.result).isEqualTo(Complete(1))
    }

    @Test
    fun eventualAndThen_cancelsUpstream() {
        val fakeSubscription = FakeSubscription()
        val eventual = eventual<Int> { setSubscription(fakeSubscription) }
        val sub = eventual.andThen { neverEventual<Int>() }.subscribe()

        assertThat(fakeSubscription.isCancelled).isFalse()

        sub.cancel()

        assertThat(fakeSubscription.isCancelled).isTrue()
    }

    @Test
    fun eventualAndThen_cancelsOnceAllCompleted() {
        val sourceEventStream = BroadcastingEventSource<Eventual<Unit>>()
        val subEventStream = BroadcastingEventSource<Unit>()
        val eventual = sourceEventStream.firstOrError().andThen { it }

        val sub = eventual.subscribe()
        assertThat(sub.isCancelled).isFalse()
        sourceEventStream.emitEvent(subEventStream.firstOrError())
        sourceEventStream.complete()
        assertThat(sub.isCancelled).isFalse()
        subEventStream.emitEvent(Unit)
        subEventStream.complete()
        assertThat(sub.isCancelled).isTrue()
    }

    @Test
    fun eventualAndThen_subscribeInner() {
        val eventual1 = BroadcastingEventSource<Eventual<Unit>>()
        var subscribed2 = false
        val eventual2 = eventual<Unit> { subscribed2 = true }
        val merged = eventual1.firstOrError().andThen { it }
        merged.subscribe()

        assertThat(subscribed2).isFalse()
        eventual1.emitEvent(eventual2)
        eventual1.complete()
        assertThat(subscribed2).isTrue()
    }

    @Test
    fun eventualAndThen_completeWhenInnerCompletes() {
        val eventual1 = BroadcastingEventSource<Eventual<Unit>>()
        val eventual2 = BroadcastingEventSource<Unit>()
        val merged = eventual1.firstOrError().andThen { it }
        val sink = TestEventualSink<Any>()
        merged.subscribe(sink)

        assertThat(sink.completed).isFalse()
        eventual1.emitEvent(eventual2.firstOrError())
        eventual1.complete()
        assertThat(sink.completed).isFalse()
        eventual2.emitEvent(Unit)
        eventual2.complete()
        assertThat(sink.completed).isTrue()
    }

    @Test
    fun eventualAndThen_cancelsUpstream_whenWaitingForFirst() {
        val fakeSubscription1 = FakeSubscription()
        val eventual = eventual<Unit> { setSubscription(fakeSubscription1) }
        val sub = eventual.andThen { neverEventual<Unit>() }.subscribe()

        assertThat(fakeSubscription1.isCancelled).isFalse()

        sub.cancel()

        assertThat(fakeSubscription1.isCancelled).isTrue()
    }

    @Test
    fun eventualAndThen_cancelsUpstream_whenWaitingForSecond() {
        val fakeSubscription2 = FakeSubscription()
        val eventual2 = eventual<Any> { setSubscription(fakeSubscription2) }
        val sub = completed().andThen(eventual2).subscribe()

        assertThat(fakeSubscription2.isCancelled).isFalse()

        sub.cancel()

        assertThat(fakeSubscription2.isCancelled).isTrue()
    }

    @Test
    fun eventualRace_emitWinner() {
        val eventStream1 = BroadcastingEventSource<Int>()
        var subscribed = false
        val fakeSubscription = FakeSubscription()
        val eventual2 = eventual<Int> {
            subscribed = true
            setSubscription(fakeSubscription)
        }
        val eventual = Eventual.race(eventStream1.firstOrError(), eventual2)
        val sink = TestEventualSink<Int>()
        val sub = eventual.subscribe(sink)

        assertThat(subscribed).isTrue()
        assertThat(sub.isCancelled).isFalse()
        assertThat(fakeSubscription.isCancelled).isFalse()

        eventStream1.emitEvent(1)
        eventStream1.complete()

        assertThat(sink.result).isEqualTo(Complete(1))
        assertThat(sub.isCancelled).isTrue()
        assertThat(fakeSubscription.isCancelled).isTrue()
    }

    @Test
    fun eventualRace_cancelIfImmediateWinner() {
        var subscribed1 = false
        val fakeSubscription = FakeSubscription()
        val eventual1 = eventual<Int> {
            subscribed1 = true
            setSubscription(fakeSubscription)
        }
        var subscribed3 = false
        val eventual3 = eventual<Int> { subscribed3 = true }
        val eventual = Eventual.race(eventual1, eventualOf(1), eventual3)

        val sub = eventual.subscribe()

        assertThat(subscribed1).isTrue()
        assertThat(subscribed3).isFalse()
        assertThat(sub.isCancelled).isTrue()
        assertThat(fakeSubscription.isCancelled).isTrue()
    }

    @Test
    fun eventualRace_cancelsUpstream() {
        val fakeSubscription = FakeSubscription()
        val eventual1 = eventual<Unit> { setSubscription(fakeSubscription) }
        val sub = Eventual.race(eventual1, neverEventual()).subscribe()

        assertThat(fakeSubscription.isCancelled).isFalse()

        sub.cancel()

        assertThat(fakeSubscription.isCancelled).isTrue()
    }

    @Test
    fun eventualZip_combineAllResults() {
        var zipperInvoked = false
        val sink = TestEventualSink<Int>()
        Eventual
                .zip((1..3).map { eventualOf(it) }) { results ->
                    zipperInvoked = true
                    results.sum()
                }
                .subscribe(sink)
        assertThat(zipperInvoked).isTrue()
        assertThat(sink.result).isEqualTo(Complete(6))
    }

    @Test
    fun eventualZip_cancelsUpstream() {
        val fakeSubscription = FakeSubscription()
        val eventual1 = eventual<Unit> { setSubscription(fakeSubscription) }
        val sub = Eventual.zip(eventual1, neverEventual()).subscribe()

        assertThat(fakeSubscription.isCancelled).isFalse()

        sub.cancel()

        assertThat(fakeSubscription.isCancelled).isTrue()
    }

    @Test
    fun cachingEventSource_emitsCacheToNewSubscribers() {
        val cacheSource = CachingEventSource(1)
        var value1: Int? = null
        cacheSource.subscribe { value1 = it }
        assertThat(value1).isEqualTo(1)

        cacheSource.emitEvent(2)
        assertThat(value1).isEqualTo(2)

        var value2: Int? = null
        cacheSource.subscribe { value2 = it }
        assertThat(value2).isEqualTo(2)
    }

    @Test
    fun possibleEventStream_completesIfNoValue() {
        val sink = TestEventStreamSink<Any>()
        emptyPotential<Unit>().possibleEvents().subscribe(sink)
        assertThat(sink.seenEvents).isEmpty()
        assertThat(sink.completed).isTrue()
    }

    @Test
    fun possibleEventStream_whenPresent_emitsThenCompletes() {
        val sink = TestEventStreamSink<Any>()
        potentialOf(1).possibleEvents().subscribe(sink)
        assertThat(sink.seenEvents).isEqualTo(listOf(1))
        assertThat(sink.completed).isTrue()
    }

    @Test
    fun eventualFilter_passesPredicate_emitsValue() {
        val sink = TestPotentialSink<Int>()
        eventualOf(1).filter { true }.subscribe(sink)
        assertThat(sink.result).isEqualTo(Complete(1))
    }

    @Test
    fun eventualFilter_failsPredicate_emitsNull() {
        val sink = TestPotentialSink<Int>()
        eventualOf(1).filter { false }.subscribe(sink)
        assertThat(sink.result).isEqualTo(Complete(null))
    }

    @Test
    fun eventual_asEventStream_emitsResultThenCompletes() {
        val sink = TestEventStreamSink<Int>()
        eventualOf(1).asEventStream().subscribe(sink)
        assertThat(sink.seenEvents).isEqualTo(listOf(1))
        assertThat(sink.completed).isTrue()
    }

    @Test
    fun completable_toPotential_emitsWhenComplete() {
        val sink = TestPotentialSink<Unit>()
        completed().toPotential().subscribe(sink)
        assertThat(sink.completed).isTrue()
    }

    @Test
    fun completable_toEventual_emitsWhenComplete() {
        val sink = TestPotentialSink<Unit>()
        completed().toEventual().subscribe(sink)
        assertThat(sink.completed).isTrue()
    }

    @Test
    fun potential_andThenCompletablePotentially_whenNoValue_completesImmediately() {
        var result: Int? = null
        var complete = false
        emptyPotential<Int>()
                .andThenCompletablePotentially { completableAction { result = it } }
                .subscribe { complete = true }
        assertThat(result).isNull()
        assertThat(complete).isTrue()
    }

    @Test
    fun potential_andThenCompletablePotentially_whenValuePresent_invokesCompletable() {
        var result: Int? = null
        var complete = false
        potentialOf(1)
                .andThenCompletablePotentially { completableAction { result = it } }
                .subscribe { complete = true }
        assertThat(result).isEqualTo(1)
        assertThat(complete).isTrue()
    }

    @Test
    fun potential_andThenCompletablePotentially_whenValuePresent_completesWhenCompletableCompletes()
    {
        val innerSource = BroadcastingEventSource<Unit>()
        var complete = false
        potentialOf(Unit)
                .andThenCompletablePotentially { innerSource.asCompletable() }
                .subscribe { complete = true }
        assertThat(complete).isFalse()
        innerSource.complete()
        assertThat(complete).isTrue()
    }

    @Test
    fun eventual_andThenCompletable_invokesCompletable() {
        var result: Int? = null
        var complete = false
        eventualOf(1)
                .andThenCompletable { completableAction { result = it } }
                .subscribe { complete = true }
        assertThat(result).isEqualTo(1)
        assertThat(complete).isTrue()
    }

    @Test
    fun eventual_andThenCompletable_completesWhenCompletableCompletes() {
        var complete = false
        val innerSource = BroadcastingEventSource<Unit>()
        eventualOf(Unit)
                .andThenCompletable { innerSource.asCompletable() }
                .subscribe { complete = true }
        assertThat(complete).isFalse()
        innerSource.complete()
        assertThat(complete).isTrue()
    }

    @Test
    fun potential_toEventual_whenValuePresent_emitsValue() {
        var result: Int? = null
        potentialOf(1).toEventual(2).subscribe { result = it }
        assertThat(result).isEqualTo(1)
    }

    @Test
    fun potential_toEventual_whenNoValuePresent_emitsDefaultValue() {
        var result: Int? = null
        emptyPotential<Int>().toEventual(2).subscribe { result = it }
        assertThat(result).isEqualTo(2)
    }

    @Test
    fun mapPotentially_skipsNull() {
        var value: Int? = null
        emptyPotential<Int>().mapPotentially { it * 2 }.subscribe { value = it }
        assertThat(value).isNull()
    }

    @Test
    fun mapPotentially_mapsValueIfPresent() {
        var value: Int? = null
        potentialOf(1).mapPotentially { it * 2 }.subscribe { value = it }
        assertThat(value).isEqualTo(2)
    }

    @Test
    fun completableMergeWith_completeWhenBothComplete() {
        val completable1 = BroadcastingEventSource<Unit>()
        val completable2 = BroadcastingEventSource<Unit>()
        val merged = completable1.asCompletable().mergeWith(completable2.asCompletable())
        var completed = false
        merged.subscribe { completed = true }

        assertThat(completed).isFalse()
        completable1.complete()
        assertThat(completed).isFalse()
        completable2.complete()
        assertThat(completed).isTrue()

        completed = false
        merged.subscribe { completed = true }

        assertThat(completed).isFalse()
        completable2.complete()
        assertThat(completed).isFalse()
        completable1.complete()
        assertThat(completed).isTrue()
    }

    @Test
    fun completableMergeWith_cancelsUpstream() {
        val fakeSubscription1 = FakeSubscription()
        val completable1 = completable { setSubscription(fakeSubscription1) }
        val fakeSubscription2 = FakeSubscription()
        val completable2 = completable { setSubscription(fakeSubscription2) }
        val sub = completable1.mergeWith(completable2).subscribe()

        assertThat(fakeSubscription1.isCancelled).isFalse()
        assertThat(fakeSubscription2.isCancelled).isFalse()

        sub.cancel()

        assertThat(fakeSubscription1.isCancelled).isTrue()
        assertThat(fakeSubscription2.isCancelled).isTrue()
    }

    @Test
    fun eventStream_changes_skipsDuplicates() {
        val expected = listOf(1, 2, 1)
        val source = BroadcastingEventSource<Int>()
        val seen = mutableListOf<Int>()
        source.changes().subscribe { seen.add(it) }

        source.emitEvent(1)
        source.emitEvent(2)
        source.emitEvent(2)
        source.emitEvent(2)
        source.emitEvent(1)
        source.emitEvent(1)

        assertThat(seen).isEqualTo(expected)
    }

    @Test
    fun eventStream_withLatestFrom_skipsUntilOtherValueExists() {
        val eventStream1 = BroadcastingEventSource<Int>()
        val eventStream2 = BroadcastingEventSource<Int>()
        val expected = listOf(Pair(1, 1))
        val seen = mutableListOf<Pair<Int, Int>>()
        eventStream1.withLatestFrom(eventStream2) { e1, e2 -> Pair(e1, e2) }
                .subscribe { seen.add(it) }

        eventStream1.emitEvent(0)
        eventStream2.emitEvent(1)
        eventStream1.emitEvent(1)

        assertThat(seen).isEqualTo(expected)
    }

    @Test
    fun eventStream_withLatestFrom_emitsOnlyWhenFirstStreamEmits() {
        val eventStream1 = BroadcastingEventSource<Int>()
        val eventStream2 = BroadcastingEventSource<Int>()
        val expected = listOf(Pair(1, 1), Pair(2, 1), Pair(3, 2))
        val seen = mutableListOf<Pair<Int, Int>>()
        eventStream1.withLatestFrom(eventStream2) { e1, e2 -> Pair(e1, e2) }
                .subscribe { seen.add(it) }

        eventStream2.emitEvent(1)
        eventStream1.emitEvent(1)
        eventStream1.emitEvent(2)
        eventStream2.emitEvent(2)
        eventStream1.emitEvent(3)

        assertThat(seen).isEqualTo(expected)
    }

    @Test
    fun eventStream_withLatestFrom_completesWhenFirstStreamCompletes() {
        val eventStream1 = BroadcastingEventSource<Int>()
        val eventStream2 = BroadcastingEventSource<Int>()
        var complete = false
        val withLatest = eventStream1.withLatestFrom(eventStream2) { _, _ -> Unit }

        withLatest.subscribe({ complete = true }) {}
        eventStream2.complete()
        assertThat(complete).isFalse()
        eventStream1.complete()
        assertThat(complete).isTrue()

        complete = false
        withLatest.subscribe({ complete = true }) {}

        eventStream1.complete()
        assertThat(complete).isTrue()
    }
}

private sealed class EventualResult<T>
private data class Complete<T>(val data: T) : EventualResult<T>()
private class Pending<T> : EventualResult<T>()

private class FakeSubscription : Subscription {
    override fun cancel() {
        isCancelled = true
    }

    override var isCancelled = false
}

private class TestEventStreamSink<T> : EventStream.Sink<T> {
    val _seenEvents = mutableListOf<T>()
    val seenEvents: List<T> get() = _seenEvents
    var completed: Boolean = false
        private set

    override fun onEvent(event: T) {
        _seenEvents.add(event)
    }

    override fun onComplete() {
        completed = true
    }
}

private typealias TestPotentialSink<T> = TestEventualSink<T?>
private typealias TestCompletableSink = TestEventualSink<Any>

private class TestEventualSink<T> : Eventual.Sink<T> {
    var result: EventualResult<T> = Pending()
        private set

    val completed: Boolean
        get() = when (result) {
            is Complete -> true
            else -> false
        }

    override fun onComplete(result: T) {
        this.result = Complete(result)
    }
}

private fun <T : Exception> assertThrows(clazz: Class<T>, action: () -> Any) {
    try {
        action()
        fail("Expected $clazz to be thrown")
    } catch (e: Exception) {
        if (!clazz.isAssignableFrom(e.javaClass)) {
            throw e
        }
    }
}