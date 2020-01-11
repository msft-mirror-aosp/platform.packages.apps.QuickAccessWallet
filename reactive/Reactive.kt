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

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Represents an acquired resource. Cancelling will release the resource. Implementations should be
 * thread-safe.
 */
interface Subscription {

    /** Releases the resource bound by this [Subscription]. */
    fun cancel()

    /**
     * Determines whether this resource is still bound. After invoking [cancel], this should
     * return false.
     */
    val isCancelled: Boolean

    companion object {
        /**
         * Creates a new [Subscription] from an action used to release a resource. The Subscription
         * returned is guaranteed to have an idempotent [cancel] method, will properly set
         * [isCancelled] upon invoking [cancel], and, once cancelled, will remove all references to
         * the action supplied.
         */
        fun create(cancelAction: () -> Unit) = object : Subscription {
            val cancelActionRef: AtomicReference<(() -> Unit)?> = AtomicReference(cancelAction)
            override fun cancel() {
                cancelActionRef.getAndSet(null)?.invoke()
            }

            override val isCancelled get() = cancelActionRef.get() == null
        }
    }
}

/** Trivial subscription that is already cancelled, and isn't bound to any resource */
object CancelledSubscription : Subscription {
    override val isCancelled: Boolean = true
    override fun cancel() {}
}

/**
 * Basic subscription that just tracks if it's running or cancelled, and performs no additional
 * cancellation logic.
 */
class ActiveSubscription : Subscription {
    private val done = AtomicBoolean(false)
    override fun cancel() = done.set(true)
    override val isCancelled: Boolean get() = done.get()
}

/** Holds a reference to an underlying subscription, and allows for replacement. */
class SubscriptionHolder : Subscription {
    private val ref = AtomicReference<Subscription?>()
    override fun cancel() {
        ref.getAndSet(CancelledSubscription)?.cancel()
    }

    override val isCancelled: Boolean get() = ref.get()?.isCancelled == true

    /** Replace the held subscription, cancelling the old one. */
    fun replace(new: Subscription) {
        val old = ref.getAndSet(new)
        if (old?.isCancelled == true) {
            cancel()
        } else {
            old?.cancel()
        }
    }
}

/** Represents a stream of events that can be subscribed to. */
interface EventStream<out T> {

    /** Subscribes to the event stream. */
    fun connect(getSink: (Subscription) -> Sink<T>)

    /** Receives event and completion notifications */
    interface Sink<in T> {

        /** Invoked when a new event occurs on the stream. */
        fun onEvent(event: T) {}

        /**
         * Invoked when the stream has completed.
         *
         * Well-formed streams will invoke this no more than once, and once invoked, will never
         * again invoke [onEvent].
         */
        fun onComplete() {}
    }

    /** Pushes event and completion notifications */
    interface Source<in T> {

        /**
         * Sets a [Subscription] that, when cancelled, releases any resources associated with this
         * source. This Subscription will be automatically cancelled when [complete] is invoked.
         *
         * Any previously set Subscription will be cancelled automatically when a new one is set.
         */
        fun setSubscription(subscription: Subscription)

        /** Pushes an event notification downstream */
        fun emitEvent(event: T)

        /**
         * Pushes a completion notification downstream, and automatically invokes
         * [Subscription.cancel] on the [Subscription] set via [setSubscription].
         *
         * Well-formed streams should invoke this when no more events will occur, so that downstream
         * subscribers can unsubscribe and free up resources. Afterwards, they should never invoke
         * [emitEvent] again.
         */
        fun complete()

        /** Whether or not the downstream listener has cancelled their [Subscription]. */
        val isCancelled: Boolean
    }
}

/**
 * Creates a new [EventStream] via a generator function. This function receives a
 * [EventStream.Source] which can be used to post events to the stream.
 *
 * If any resources are allocated that need to be released upon cancellation or completion,
 * a [Subscription] can be supplied via [EventStream.Source.setSubscription].
 *
 * Well-formed streams should signal that the stream is complete via [EventStream.Source.complete].
 * Doing so will allow downstream subscribers to release any resources, and will also
 * automatically cancel the Subscription registered on the Source.
 */
fun <T> events(creator: EventStream.Source<T>.() -> Unit): EventStream<T> =
    object : EventStream<T> {
        override fun connect(getSink: (Subscription) -> EventStream.Sink<T>) {
            val subscriptionRef = SubscriptionHolder()
            val sink = getSink(subscriptionRef)
            val source = object : EventStream.Source<T> {
                override fun setSubscription(subscription: Subscription) =
                    subscriptionRef.replace(subscription)

                override val isCancelled: Boolean get() = subscriptionRef.isCancelled

                override fun emitEvent(event: T) {
                    if (!subscriptionRef.isCancelled) {
                        sink.onEvent(event)
                    }
                }

                override fun complete() {
                    if (!subscriptionRef.isCancelled) {
                        sink.onComplete()
                        subscriptionRef.cancel()
                    }
                }
            }
            creator(source)
        }
    }

/** [EventStream] that emits the given items, then completes. */
fun <T> eventsOf(vararg values: T): EventStream<T> =
    if (values.isEmpty()) emptyEvents() else values.asSequence().toEvents()

/** [EventStream] that completes immediately. */
fun <T> emptyEvents(): EventStream<T> = object : EventStream<T> {
    override fun connect(getSink: (Subscription) -> EventStream.Sink<T>) {
        val subscription = ActiveSubscription()
        val sink = getSink(subscription)
        if (!subscription.isCancelled) {
            sink.onComplete()
            subscription.cancel()
        }
    }
}

/** [EventStream] that never emits a value or completes. */
fun <T> neverEvents(): EventStream<T> = object : EventStream<T> {
    override fun connect(getSink: (Subscription) -> EventStream.Sink<T>) {
        getSink(ActiveSubscription())
    }
}

/** [EventStream] that emits each event in the given sequence, and then completes. */
fun <T> Sequence<T>.toEvents(): EventStream<T> = events {
    for (value in this@toEvents) {
        if (isCancelled) return@events
        emitEvent(value)
        if (isCancelled) return@events
    }
    complete()
}

/**
 * Creates a new [EventStream] that subscribes to and emits from all streams produced by this
 * [Sequence].
 */
fun <T> Sequence<EventStream<T>>.mergeEvents(): EventStream<T> = toEvents().flatMap { it }

/** [EventStream] that subscribes to and emits from all given streams. */
fun <T> mergeEvents(vararg events: EventStream<T>): EventStream<T> =
    events.asSequence().mergeEvents()

/**
 * [EventStream] that subscribes to and emits from each stream in the order produced by this
 * [Sequence], switching to the next once the current completes.
 */
fun <T> Sequence<EventStream<T>>.concatEvents(): EventStream<T> =
    fold(emptyEvents(), EventStream<T>::andThen)

/**
 * [EventStream] that subscribes to and emits from each stream in the order provided, switching
 * to the next once the current completes.
 */
fun <T> concatEvents(vararg events: EventStream<T>): EventStream<T> =
    events.asSequence().concatEvents()

/** Subscribes to the event stream with the given callback. */
@Suppress("NOTHING_TO_INLINE")
inline fun <T> EventStream<T>.subscribe() = subscribe {}

inline fun <T> EventStream<T>.subscribe(
        crossinline onComplete: () -> Unit = {},
        crossinline onEvent: (data: T) -> Unit
) = subscribe(object : EventStream.Sink<T> {
    override fun onEvent(event: T) = onEvent.invoke(event)
    override fun onComplete() = onComplete.invoke()
})

fun <T> EventStream<T>.subscribe(sink: EventStream.Sink<T>): Subscription {
    val subHolder = SubscriptionHolder()
    connect { sub ->
        subHolder.replace(sub)
        object : EventStream.Sink<T> {
            override fun onEvent(event: T) {
                if (!subHolder.isCancelled) {
                    sink.onEvent(event)
                }
            }

            override fun onComplete() {
                if (!subHolder.isCancelled) {
                    sink.onComplete()
                    subHolder.cancel()
                }
            }
        }
    }
    return subHolder
}


/**
 * Sets an action to be performed when this [Eventual] completes or is unsubscribed from. Typically,
 * this is used to cleanup resources which were acquired at subscription time.
 */
@Suppress("NOTHING_TO_INLINE")
inline fun EventStream.Source<*>.setCancelAction(noinline doOnCancel: () -> Unit) =
    setSubscription(Subscription.create(doOnCancel))

/** Subscribes to the given [EventStream] immediately after this one completes. */
fun <T> EventStream<T>.andThen(next: EventStream<T>): EventStream<T> = events {
    connect { sub ->
        setSubscription(sub)
        object : EventStream.Sink<T> {
            override fun onEvent(event: T) = emitEvent(event)
            override fun onComplete() {
                if (isCancelled) return
                next.connect { sub ->
                    setSubscription(sub)
                    object : EventStream.Sink<T> {
                        override fun onEvent(event: T) = emitEvent(event)
                        override fun onComplete() = complete()
                    }
                }
            }
        }
    }
}

fun <T> EventStream<T>.andThen(next: Eventual<T>): EventStream<T> = andThen(next.asEventStream())
fun <T> EventStream<T>.andThenPotentially(next: Potential<T>): EventStream<T> =
    andThen(next.possibleEvents())

/** Tags each event emission with an index. */
fun <T> EventStream<T>.enumerate(): EventStream<Pair<Int, T>> = object : EventStream<Pair<Int, T>> {
    override fun connect(getSink: (Subscription) -> EventStream.Sink<Pair<Int, T>>) {
        val counter = AtomicInteger()
        this@enumerate.connect { sub ->
            val sink = getSink(sub)
            object : EventStream.Sink<T> {
                override fun onEvent(event: T) = sink.onEvent(counter.getAndIncrement() to event)
                override fun onComplete() = sink.onComplete()
            }
        }
    }
}

/** Prepends the stream with the given values, which are emitted immediately when subscribed. */
fun <T> EventStream<T>.startWith(vararg values: T): EventStream<T> =
    eventsOf(*values).andThen(this)

/**
 * Creates an [EventStream] that, when subscribed to, invokes the given [block] with a new event
 * stream that shares a single subscription to this [EventStream].
 *
 * This combinator is useful when you want to share a single subscription with multiple downstream
 * consumers, due to side-effects or performance.
 */
fun <T, R> EventStream<T>.broadcast(block: (EventStream<T>) -> EventStream<R>): EventStream<R> =
    events {
        val broadcaster = BroadcastingEventSource<T>()
        val downstreamSub = SubscriptionHolder()
        val upstreamSub = SubscriptionHolder()
        setSubscription(object : Subscription {
            override val isCancelled: Boolean
                get() =
                    downstreamSub.isCancelled && upstreamSub.isCancelled

            override fun cancel() {
                upstreamSub.cancel()
                downstreamSub.cancel()
            }
        })
        val downstream = block(broadcaster)
        downstream.connect { sub ->
            downstreamSub.replace(sub)
            object : EventStream.Sink<R> {
                override fun onEvent(event: R) = emitEvent(event)
                override fun onComplete() = complete()
            }
        }
        if (isCancelled) return@events
        connect { sub ->
            upstreamSub.replace(sub)
            object : EventStream.Sink<T> {
                override fun onEvent(event: T) = broadcaster.emitEvent(event)
                override fun onComplete() = broadcaster.complete()
            }
        }
    }

/** Creates a new [EventStream] that subscribes to and emits from both given streams. */
fun <T> EventStream<T>.mergeWith(events2: EventStream<T>): EventStream<T> =
    events {
        val firstSub = SubscriptionHolder()
        val secondSub = SubscriptionHolder()
        setSubscription(object : Subscription {
            override val isCancelled: Boolean get() = firstSub.isCancelled && secondSub.isCancelled
            override fun cancel() {
                firstSub.cancel()
                secondSub.cancel()
            }
        })
        fun sink(sub: Subscription): EventStream.Sink<T> = object : EventStream.Sink<T> {
            override fun onEvent(event: T) = emitEvent(event)
            override fun onComplete() {
                if (sub.isCancelled) {
                    complete()
                }
            }
        }
        connect { sub ->
            firstSub.replace(sub)
            sink(secondSub)
        }
        if (isCancelled) return@events
        events2.connect { sub ->
            secondSub.replace(sub)
            sink(firstSub)
        }
    }

/**
 * Creates a new [EventStream] that applies a function to each event emitted from this stream and
 * emits the result.
 */
fun <T, S> EventStream<T>.map(mapper: (T) -> S): EventStream<S> = object : EventStream<S> {
    override fun connect(getSink: (Subscription) -> EventStream.Sink<S>) {
        this@map.connect { sub ->
            val sink = getSink(sub)
            object : EventStream.Sink<T> {
                override fun onEvent(event: T) = sink.onEvent(mapper(event))
                override fun onComplete() = sink.onComplete()
            }
        }
    }
}

/** Maps each value to a new nullable value, and drops all nulls. */
fun <T, S> EventStream<T>.filterMap(mapper: (T) -> S?): EventStream<S> =
    map(mapper).filter { it != null }.map { it!! }

/** Pairs each event emission with the previous emission. */
fun <T> EventStream<T>.paired(): EventStream<Pair<T, T>> =
    scan(Pair<Result<T>?, Result<T>?>(null, null)) { curr, (_, prev) ->
        Pair(prev, Result(curr))
    }.filterMap { (prev, curr) ->
        prev?.let { curr?.let { Pair(prev.data, curr.data) } }
    }

fun <T> EventStream<T>.paired(init: T): EventStream<Pair<T, T>> = startWith(init).paired()

/**
 * Creates a new [EventStream] that applies a function to each event emitted from this stream,
 * subscribes to each result stream, and emits those events.
 */
fun <T, S> EventStream<T>.flatMap(mapper: (T) -> EventStream<S>): EventStream<S> = events {
    // Set of inner subscriptions
    val subscriptions = ConcurrentLinkedQueue<Subscription>()
    val childCount = AtomicInteger()
    connect { outerSub ->
        subscriptions.add(outerSub)
        setSubscription(object : Subscription {
            override val isCancelled: Boolean get() = subscriptions.all { it.isCancelled }
            override fun cancel() {
                for (s in subscriptions) {
                    s.cancel()
                }
                subscriptions.clear()
            }
        })
        object : EventStream.Sink<T> {
            override fun onEvent(event: T) {
                if (isCancelled) return
                mapper(event).connect { innerSub ->
                    subscriptions.add(innerSub)
                    childCount.incrementAndGet()
                    object : EventStream.Sink<S> {
                        override fun onEvent(event: S) = emitEvent(event)
                        override fun onComplete() {
                            if (childCount.decrementAndGet() == 0 && outerSub.isCancelled) {
                                complete()
                            } else {
                                subscriptions.remove(innerSub)
                            }
                        }
                    }
                }
            }

            override fun onComplete() {
                if (childCount.get() == 0) {
                    complete()
                }
            }
        }
    }
}

/**
 * Applies [mapper] to each event emitted, and subscribes to the result [Eventual], emitting the
 * result when available.
 */
fun <T, S> EventStream<T>.flatMapEventual(mapper: (T) -> Eventual<S>): EventStream<S> =
    flatMap { mapper(it).asEventStream() }

/**
 * Applies [mapper] to each event emitted, and subscribes to the result [Potential], emitting the
 * result when available if present.
 */
fun <T, S> EventStream<T>.flatMapPotential(mapper: (T) -> Potential<S>): EventStream<S> =
    flatMapEventual(mapper).filterMap { it }

/**
 * Creates a new [EventStream] that applies a function to each event emitted from this stream,
 * and subscribes to the result, cancelling the previous subscription if present.
 */
fun <T, S> EventStream<T>.switchMap(mapper: (T) -> EventStream<S>): EventStream<S> = events {
    val outerSub = SubscriptionHolder()
    val innerSub = AtomicReference<Subscription?>()
    setSubscription(object : Subscription {
        override val isCancelled: Boolean
            get() = outerSub.isCancelled && innerSub.get()?.isCancelled != false

        override fun cancel() {
            outerSub.cancel()
            innerSub.getAndSet(CancelledSubscription)?.cancel()
        }
    })
    connect { sub ->
        outerSub.replace(sub)
        object : EventStream.Sink<T> {
            override fun onEvent(event: T) {
                if (isCancelled) return
                mapper(event).connect { sub ->
                    innerSub.getAndSet(sub)?.cancel()
                    object : EventStream.Sink<S> {
                        override fun onEvent(event: S) = emitEvent(event)
                        override fun onComplete() {
                            if (outerSub.isCancelled) {
                                complete()
                            }
                        }
                    }
                }
            }

            override fun onComplete() {
                if (innerSub.get()?.isCancelled != false) {
                    complete()
                }
            }
        }
    }
}

/**
 * Creates a new eventual value that produces the first event emitted from this [EventStream]. If
 * no event is produced, then [NoSuchElementException] is thrown.
 */
fun <T> EventStream<T>.firstOrError(): Eventual<T> = object : Eventual<T> {
    override fun connect(getSink: (Subscription) -> Eventual.Sink<T>) {
        val done = AtomicBoolean(false)
        this@firstOrError.connect { sub ->
            val sink = getSink(sub)
            object : EventStream.Sink<T> {
                override fun onEvent(event: T) {
                    if (!done.getAndSet(true)) {
                        sink.onComplete(event)
                        sub.cancel()
                    }
                }

                override fun onComplete() {
                    if (!done.get()) {
                        throw NoSuchElementException("source EventStream did not emit an event")
                    }
                }
            }
        }
    }
}

/**  Creates a new potential value that produces the first event emitted from this [EventStream]. */
fun <T> EventStream<T>.first(): Potential<T> = firstOrDefault(null)

/** Creates a new eventual value that produces the first event emitted from this [EventStream]. */
fun <T> EventStream<T>.firstOrDefault(default: T): Eventual<T> = object : Eventual<T> {
    override fun connect(getSink: (Subscription) -> Eventual.Sink<T>) {
        val done = AtomicBoolean(false)
        this@firstOrDefault.connect { sub ->
            val sink = getSink(sub)
            object : EventStream.Sink<T> {
                override fun onEvent(event: T) {
                    if (!done.getAndSet(true)) {
                        sink.onComplete(event)
                        sub.cancel()
                    }
                }

                override fun onComplete() {
                    if (!done.get()) {
                        sink.onComplete(default)
                        sub.cancel()
                    }
                }
            }
        }
    }
}

/**
 * Creates a new [EventStream] that subscribes to this [EventStream], emitting the first event and
 * then all subsequent events that are not equal to the preceding event.
 */
fun <T> EventStream<T>.changes(): EventStream<T> = object : EventStream<T> {
    override fun connect(getSink: (Subscription) -> EventStream.Sink<T>) {
        val lastSeen = AtomicReference<T?>()
        this@changes.connect { sub ->
            val sink = getSink(sub)
            object : EventStream.Sink<T> by sink {
                override fun onEvent(event: T) {
                    val previous = lastSeen.getAndSet(event)
                    if (!(event?.equals(previous) ?: (previous == null))) {
                        sink.onEvent(event)
                    }
                }
            }
        }
    }
}

/**
 * Creates a new Completable that applies a function to each event emitted from this stream, and
 * subscribes to the result, unsubscribing from the previous result, if one exists.
 */
fun <T> EventStream<T>.switchCompletable(mapper: (T) -> Completable): Completable =
    switchMap { mapper(it).asEventStream<Any?>() }.asCompletable()

/**
 * Creates a new Completable that applies a function to each event emitted from this stream, and
 * subscribes to each result, completing once this [EventStream] and all returned Completables
 * have completed.
 */
fun <T> EventStream<T>.flatMapCompletable(mapper: (T) -> Completable): Completable =
    flatMap { mapper(it).asEventStream<Any?>() }.asCompletable()

/** Shorthand for flatMapCompletable { Completable.fromAction { ... } } */
fun <T> EventStream<T>.mapConsume(mapper: (T) -> Unit): Completable =
    flatMapCompletable { completableAction { mapper(it) } }

/**
 * Creates a new Completable that subscribes to this [EventStream] and notifies when it has
 * completed.
 */
fun <T> EventStream<T>.asCompletable(): Completable = object : Completable {
    override fun connect(getSink: (Subscription) -> Eventual.Sink<Any?>) {
        this@asCompletable.connect { sub ->
            val sink = getSink(sub)
            object : EventStream.Sink<T> {
                override fun onComplete() = sink.onComplete(Unit)
            }
        }
    }
}

/**
 * Creates a new [EventStream] that, when subscribed to, subscribes to this [EventStream] and the
 * given [EventStream], and emits events when this stream emits events, but combines the emitted
 * value with the last emitted value from the given stream, using the given [combiner] function.
 */
fun <T, U, R> EventStream<T>.withLatestFrom(
        other: EventStream<U>,
        combiner: (T, U) -> R
): EventStream<R> =
    events {
        val latest: AtomicReference<Result<U>> = AtomicReference()
        val firstSub = SubscriptionHolder()
        val secondSub = SubscriptionHolder()
        setSubscription(object : Subscription {
            override val isCancelled: Boolean get() = firstSub.isCancelled && secondSub.isCancelled
            override fun cancel() {
                firstSub.cancel()
                secondSub.cancel()
            }
        })

        connect { sub ->
            firstSub.replace(sub)
            object : EventStream.Sink<T> {
                override fun onEvent(event: T) {
                    latest.get()?.let { lastSeen -> emitEvent(combiner(event, lastSeen.data)) }
                }

                override fun onComplete() = complete()
            }
        }
        if (isCancelled) return@events
        other.connect { sub ->
            secondSub.replace(sub)
            object : EventStream.Sink<U> {
                override fun onEvent(event: U) = latest.set(Result(event))
            }
        }
    }

fun <T, U> EventStream<T>.withLatestFrom(other: EventStream<U>): EventStream<Pair<T, U>> =
    withLatestFrom(other) { a, b -> a to b }

/**
 * Creates a new [EventStream] that, when subscribed to, subscribes to this [EventStream], emitting
 * all events that pass the given predicate.
 */
fun <T> EventStream<T>.filter(predicate: (T) -> Boolean): EventStream<T> = object : EventStream<T> {
    override fun connect(getSink: (Subscription) -> EventStream.Sink<T>) {
        this@filter.connect { sub ->
            val sink = getSink(sub)
            object : EventStream.Sink<T> by sink {
                override fun onEvent(event: T) {
                    if (predicate(event)) {
                        sink.onEvent(event)
                    }
                }
            }
        }
    }
}

/**
 * Emits values from the [EventStream] until one passes the given predicate, at which point that
 * value is emitted, and then the stream immediately completes.
 */
fun <T> EventStream<T>.completeWhen(predicate: (T) -> Boolean): EventStream<T> =
    object : EventStream<T> {
        override fun connect(getSink: (Subscription) -> EventStream.Sink<T>) {
            val completed = AtomicBoolean(false)
            this@completeWhen.connect { sub ->
                val sink = getSink(sub)
                object : EventStream.Sink<T> {
                    override fun onEvent(event: T) {
                        sink.onEvent(event)
                        if (predicate(event)) {
                            completed.set(true)
                            sink.onComplete()
                            sub.cancel()
                        }
                    }

                    override fun onComplete() {
                        if (!completed.getAndSet(true)) {
                            sink.onComplete()
                        }
                    }
                }
            }
        }
    }

fun <T> EventStream<T>.completeWhen(value: T): EventStream<T> = completeWhen { it == value }

/**
 * Emits values from the [EventStream] until one passes the given predicate, at which point the
 * stream immediately completes without emitting that value.
 */
fun <T> EventStream<T>.until(predicate: (T) -> Boolean): EventStream<T> = object : EventStream<T> {
    override fun connect(getSink: (Subscription) -> EventStream.Sink<T>) {
        val completed = AtomicBoolean(false)
        this@until.connect { sub ->
            val sink = getSink(sub)
            object : EventStream.Sink<T> {
                override fun onEvent(event: T) {
                    if (predicate(event)) {
                        completed.set(true)
                        sink.onComplete()
                        sub.cancel()
                    } else {
                        sink.onEvent(event)
                    }
                }

                override fun onComplete() {
                    if (!completed.getAndSet(true)) {
                        sink.onComplete()
                    }
                }
            }
        }
    }
}

fun <T> EventStream<T>.until(value: T): EventStream<T> = until { it == value }

/**
 * Creates an [EventStream] that applies the specified [update] function to the first item emitted
 * and the given [init] value, then feeds the result along with the second item emitted into
 * [update], and so on until completion.
 */
fun <T, S> EventStream<T>.scan(init: S, update: (T, S) -> S): EventStream<S> =
    object : EventStream<S> {
        override fun connect(getSink: (Subscription) -> EventStream.Sink<S>) {
            val state = AtomicReference<S>(init)
            this@scan.connect { sub ->
                val sink = getSink(sub)
                object : EventStream.Sink<T> {
                    override fun onEvent(event: T) =
                        sink.onEvent(state.updateAndGet { state -> update(event, state) })

                    override fun onComplete() = sink.onComplete()
                }
            }
        }
    }

/**
 * Represents a single asynchronous value, that will be available at some point in the future.
 */
interface Eventual<out T> {

    /** Subscribes to the eventual value. */
    fun connect(getSink: (Subscription) -> Sink<T>)

    /**
     * Receives a notification when the value becomes available.
     *
     * Well-formed Eventual instances will only ever invoke this once.
     */
    interface Sink<in T> {

        /** Invoked once with the eventual result, when available. */
        fun onComplete(result: T)
    }

    /** Pushes a notification when a value becomes available. */
    interface Source<in T> {

        /**
         * Sets a [Subscription] that, when cancelled, releases any resources associated with this
         * source. This Subscription will be automatically cancelled when [complete] is invoked.
         *
         * Any previously set Subscription will be cancelled automatically when a new one is set.
         */
        fun setSubscription(subscription: Subscription)

        /**
         * Notifies downstream subscribers that the value is available. Should only ever be invoked
         * once.
         */
        fun complete(result: T)

        /** Whether or not the downstream listener has cancelled their [Subscription]. */
        val isCancelled: Boolean
    }

    companion object {

        /**
         * Creates an eventual value that, when subscribed to, will connect to all the given
         * eventual values, and forwards the first value that is produced. All losers will be
         * unsubscribed from immediately after the winner is determined.
         *
         * If the winner completes immediately after it is subscribed to, all contenders that appear
         * afterward in the given [Sequence] will *not* be subscribed to.
         */
        fun <T> race(contenders: List<Eventual<T>>): Eventual<T> =
            if (contenders.size == 1)
                contenders[0]
            else
                eventual {
                    val start = Empty<Subscription>()
                    val subscriptionsRef =
                        AtomicReference<ImmutableLinkedList<Subscription>?>(start)
                    setSubscription(object : Subscription {
                        override val isCancelled: Boolean get() = subscriptionsRef.get() == null
                        override fun cancel() {
                            subscriptionsRef.getAndSet(null)?.forEach { it.cancel() }
                        }
                    })
                    val sink = object : Eventual.Sink<T> {
                        override fun onComplete(result: T) = complete(result)
                    }
                    for (eventual in contenders) {
                        val subscriptions = subscriptionsRef.get() ?: return@eventual
                        eventual.connect { sub ->
                            val success = subscriptionsRef
                                    .compareAndSet(subscriptions, Cons(sub, subscriptions))
                            if (!success) {
                                sub.cancel()
                            }
                            sink
                        }
                    }
                }

        fun <T> race(vararg contenders: Eventual<T>): Eventual<T> = race(contenders.toList())

        /**
         * Creates an eventual value that, when subscribed to, will connect to all the given
         * eventual values, and combines their results with the given zipper function.
         *
         * Note that this will not produce a value until all given eventual values have produced a
         * value.
         */
        fun <T, S> zip(sources: Collection<Eventual<T>>, zipper: (List<T>) -> S): Eventual<S> =
            if (sources.size == 1)
                sources.first().map { r -> zipper(listOf(r)) }
            else
                eventual {
                    val results = arrayOfNulls<Any>(sources.size)
                    val counter = AtomicInteger(sources.size)
                    val subscriptions = ConcurrentLinkedQueue<Subscription>()
                    var completing = false
                    setSubscription(object : Subscription {
                        override val isCancelled: Boolean get() = !completing && counter.get() <= 0
                        override fun cancel() {
                            completing = false
                            if (counter.getAndSet(0) > 0) {
                                for (s in subscriptions) {
                                    s.cancel()
                                }
                                subscriptions.clear()
                            }
                        }
                    })
                    for ((index, eventual) in sources.withIndex()) {
                        if (isCancelled) return@eventual
                        eventual.connect { sub ->
                            subscriptions.add(sub)
                            object : Eventual.Sink<T> {
                                override fun onComplete(result: T) {
                                    results[index] = result
                                    if (counter.decrementAndGet() == 0) {
                                        completing = true
                                        @Suppress("UNCHECKED_CAST")
                                        val resultList = results.asSequence()
                                                .map { it!! as T }
                                                .toList()
                                        subscriptions.clear()
                                        complete(zipper(resultList))
                                    }
                                }
                            }
                        }
                    }
                }

        fun <T, S> zip(zipper: (List<T>) -> S, vararg sources: Eventual<T>): Eventual<S> =
            zip(sources.toList(), zipper)
        fun <T> zip(vararg sources: Eventual<T>): Eventual<List<T>> = zip(sources.toList()) { it }
        fun <T> zip(sources: Collection<Eventual<T>>): Eventual<List<T>> = zip(sources) { it }
    }
}

/**
 * Creates a new eventual value via a generator function. This function receives a Source which can
 * be used to post an event when the value becomes available.
 *
 * If any resources are allocated that need to be released upon cancellation or completion, a
 * [Subscription] can be supplied via [Eventual.Source.setSubscription].
 *
 * Well-formed eventual values should signal that the stream is complete via
 * [Eventual.Source.complete]. Doing so will allow downstream subscribers to release any resources,
 * and will also automatically cancel the Subscription registered on the Source.
 */
fun <T> eventual(creator: Eventual.Source<T>.() -> Unit): Eventual<T> = object : Eventual<T> {
    override fun connect(getSink: (Subscription) -> Eventual.Sink<T>) {
        val subscriptionRef = SubscriptionHolder()
        val sink = getSink(subscriptionRef)
        val source = object : Eventual.Source<T> {
            override fun setSubscription(subscription: Subscription) =
                subscriptionRef.replace(subscription)

            override val isCancelled: Boolean get() = subscriptionRef.isCancelled

            override fun complete(result: T) {
                if (!subscriptionRef.isCancelled) {
                    sink.onComplete(result)
                    subscriptionRef.cancel()
                }
            }
        }
        creator(source)
    }
}

/** Creates an eventual value that completes immediately with the given result. */
fun <T> eventualOf(result: T): Eventual<T> = object : Eventual<T> {
    override fun connect(getSink: (Subscription) -> Eventual.Sink<T>) {
        val subscription = ActiveSubscription()
        val sink = getSink(subscription)
        if (!subscription.isCancelled) {
            sink.onComplete(result)
            subscription.cancel()
        }
    }
}

/** Creates an eventual value that is supplied lazily when subscribed to. */
inline fun <T> eventualLazy(crossinline supplier: () -> T): Eventual<T> = object : Eventual<T> {
    override fun connect(getSink: (Subscription) -> Eventual.Sink<T>) {
        val sub = ActiveSubscription()
        val sink = getSink(sub)
        if (!sub.isCancelled) {
            sink.onComplete(supplier())
            sub.cancel()
        }
    }
}

/** Creates a potential value via a generator function. See: [eventual] */
fun <T> potential(creator: Eventual.Source<T?>.() -> Unit): Potential<T> = eventual(creator)

/** Creates a potential value that completes immediately with the given result. */
fun <T> potentialOf(result: T? = null): Potential<T> = eventualOf(result)

/** Creates a potential value that completes immediately with no result. */
fun <T> emptyPotential(): Potential<T> = eventualOf(null)

/** Creates a potential value that is supplies lazily when subscribed to. */
fun <T> potentialLazy(supplier: () -> T?): Potential<T> = eventualLazy(supplier)

/** Creates a [Completable] via a generator function. See: [eventual] */
fun completable(creator: Eventual.Source<Unit>.() -> Unit): Completable = eventual(creator)

/** Creates a [Completable] that invokes the given [action] when subscribed to. */
fun completableAction(action: () -> Unit): Completable = eventualLazy(action)

/** [Completable] that completes immediately. */
fun completed(): Completable = eventualOf(Unit)

/** Creates an eventual value that will never complete. */
fun <T> neverEventual(): Eventual<T> = object : Eventual<T> {
    override fun connect(getSink: (Subscription) -> Eventual.Sink<T>) {
        getSink(ActiveSubscription())
    }
}

/** Creates a potential value that will never complete. */
fun <T> neverPotential(): Potential<T> = neverEventual()

/** Creates a [Completable] that will never complete. */
fun neverComplete(): Completable = neverEventual()

/**
 * Sets an action to be performed when this eventual completes or is unsubscribed from. Typically,
 * this is used to cleanup resources which were acquired at subscription time.
 */
@Suppress("NOTHING_TO_INLINE")
inline fun Eventual.Source<*>.setCancelAction(noinline doOnCancel: () -> Unit) =
    setSubscription(Subscription.create(doOnCancel))

/** Subscribes to the eventual value with the given callback. */
inline fun <T> Eventual<T>.subscribe(crossinline subscriber: (data: T) -> Unit = {}): Subscription {
    val subHolder = SubscriptionHolder()
    connect { sub ->
        subHolder.replace(sub)
        object : Eventual.Sink<T> {
            override fun onComplete(result: T) {
                if (!subHolder.isCancelled) {
                    subscriber(result)
                    subHolder.cancel()
                }
            }
        }
    }
    return subHolder
}

@Suppress("NOTHING_TO_INLINE")
inline fun <T> Eventual<T>.subscribe(sink: Eventual.Sink<T>): Subscription =
    subscribe(sink::onComplete)

/**
 * Creates an eventual value that, when subscribed to, will connect to all the given
 * eventual values, and combines their results into a [List].
 *
 * Note that this will not produce a value until all given eventual values have produced a
 * value.
 */
fun <T> Collection<Eventual<T>>.zip(): Eventual<List<T>> = Eventual.zip(this)

fun <T, U> Collection<Eventual<T>>.zip(zipper: (List<T>) -> U) = Eventual.zip(this, zipper)
fun Collection<Completable>.merge(): Completable = zip()

/**
 * Creates an eventual value that, when subscribed to, will connect to both eventual values, and
 * combines their results with the given [zipper].
 *
 * Note that this will not produce a value until all given eventual values have produced a
 * value.
 */
fun <T, U, V> Eventual<T>.zipWith(other: Eventual<U>, zipper: (T, U) -> V): Eventual<V> =
    eventual {
        val firstSub = SubscriptionHolder()
        val secondSub = SubscriptionHolder()
        setSubscription(object : Subscription {
            override val isCancelled: Boolean get() = firstSub.isCancelled && secondSub.isCancelled
            override fun cancel() {
                firstSub.cancel()
                secondSub.cancel()
            }
        })
        val results = AtomicReference<Pair<Result<T>?, Result<U>?>>(Pair(null, null))
        connect { sink ->
            firstSub.replace(sink)
            object : Eventual.Sink<T> {
                override fun onComplete(result: T) {
                    val (_, otherResult) =
                        results.getAndUpdate { old -> Pair(Result(result), old.second) }
                    if (otherResult != null) {
                        complete(zipper(result, otherResult.data))
                    }
                }
            }
        }
        if (isCancelled) return@eventual
        other.connect { sink ->
            secondSub.replace(sink)
            object : Eventual.Sink<U> {
                override fun onComplete(result: U) {
                    val (otherResult, _) =
                        results.getAndUpdate { old -> Pair(old.first, Result(result)) }
                    if (otherResult != null) {
                        complete(zipper(otherResult.data, result))
                    }
                }
            }
        }
    }

fun <T, U> Eventual<T>.zipWith(other: Eventual<U>): Eventual<Pair<T, U>> =
    zipWith(other) { a, b -> Pair(a, b) }

/**
 * Creates a potential value that, when subscribed to, will connect to both potential values, and
 * combine their results (if present) with the given [zipper]. Produces a result only if both
 * potential values produce a result.
 */
fun <T, U, V> Potential<T>.zipWithPotentially(
        other: Potential<U>,
        zipper: (T, U) -> V
): Potential<V> =
    zipWith(other) { a, b -> a?.let { b?.let { zipper(a, b) } } }

fun <T, U> Potential<T>.zipWithPotentially(other: Potential<U>): Potential<Pair<T, U>> =
    zipWithPotentially(other) { a, b -> Pair(a, b) }

/**
 * Creates a new eventual value that applies a function to the this value when available, and
 * produces the result.
 */
fun <T, S> Eventual<T>.map(mapper: (T) -> S): Eventual<S> = object : Eventual<S> {
    override fun connect(getSink: (Subscription) -> Eventual.Sink<S>) {
        this@map.connect { sub ->
            val sink = getSink(sub)
            object : Eventual.Sink<T> {
                override fun onComplete(result: T) {
                    sink.onComplete(mapper(result))
                }
            }
        }
    }
}

/**
 * Creates a new eventual value that applies a function to this value, and subscribes to the
 * result.
 */
fun <T, S> Eventual<T>.andThen(mapper: (T) -> Eventual<S>): Eventual<S> = eventual {
    connect { sub ->
        setSubscription(sub)
        object : Eventual.Sink<T> {
            override fun onComplete(result: T) {
                if (isCancelled) return
                mapper(result).connect { sub ->
                    setSubscription(sub)
                    object : Eventual.Sink<S> {
                        override fun onComplete(result: S) = complete(result)
                    }
                }
            }
        }
    }
}

fun <T, S> Eventual<T>.andThen(next: Eventual<S>): Eventual<S> = andThen { next }

/**
 * Invokes the given [mapper] with this eventual result, and then subscribes to the resulting event
 * stream.
 */
fun <T, S> Eventual<T>.andThenEventStream(mapper: (T) -> EventStream<S>): EventStream<S> =
    map(mapper).asEventStream().flatMap { it }

fun <T, S> Eventual<T>.andThenEventStream(next: EventStream<S>): EventStream<S> =
    andThenEventStream { next }

/** Represents a value that may potentially become available. */
typealias Potential<T> = Eventual<T?>

/** Represents an asynchronous process that indicates when it has completed. */
typealias Completable = Eventual<Any?>

/**
 * Creates a new Completable that subscribes to both given Completables, and signals when they have
 * both completed.
 */
fun Completable.mergeWith(other: Completable): Completable = zipWith(other) { _, _ -> Unit }

/**
 * Creates a new potential value that applies a function to this value if it comes available, and
 * produces the result.
 */
fun <T, S> Potential<T>.mapPotentially(mapper: (T) -> S): Potential<S> = object : Potential<S> {
    override fun connect(getSink: (Subscription) -> Eventual.Sink<S?>) {
        this@mapPotentially.connect { sub ->
            val sink = getSink(sub)
            object : Eventual.Sink<T?> {
                override fun onComplete(result: T?) = sink.onComplete(result?.let(mapper))
            }
        }
    }
}

/**
 * Creates a new potential value that applies a function to this value if present, and then
 * subscribes to the result.
 */
fun <T, S> Potential<T>.andThenPotentially(mapper: (T) -> Potential<S>): Potential<S> =
    andThen { it?.let { mapper(it) } ?: emptyPotential() }

/**
 * Creates a new eventual value that produces this potential value if it exists, or the given value
 * otherwise.
 */
fun <T> Potential<T>.toEventual(default: T): Eventual<T> = map { it ?: default }

/**
 * Creates a new Completable that applies a function to this potential value, and subscribes to
 * the result.
 */
fun <T> Eventual<T>.andThenCompletable(mapper: (T) -> Completable): Completable = andThen(mapper)

fun <T> Eventual<T>.andThenCompletable(next: Completable): Completable = andThenCompletable { next }

/** Shorthand for `flatMapCompletable { Completable.fromAction { ... } }` */
fun <T> Eventual<T>.mapConsume(mapper: (T) -> Unit): Completable =
    andThenCompletable { completableAction { mapper(it) } }

/**
 * Creates a new Completable that applies a function to this potential value, and subscribes to
 * the result. If no value exists, complete immediately.
 */
fun <T> Potential<T>.andThenCompletablePotentially(mapper: (T) -> Completable): Completable =
    andThenCompletable { it?.let(mapper) ?: completed() }

/**
 * Creates a new eventual value that subscribes to this Completable, and produces a Unit value when
 * complete.
 */
fun Completable.toEventual(): Eventual<Unit> = map { Unit }

/**
 * Creates a new potential value that subscribes to this Completable, and produces a Unit value when
 * complete.
 */
fun Completable.toPotential(): Potential<Unit> = toEventual()

/**
 * Creates a new [EventStream] that subscribes to this eventual value and emits its result when it
 * becomes available, immediately completing afterwards.
 */
fun <T> Eventual<T>.asEventStream(): EventStream<T> = object : EventStream<T> {
    override fun connect(getSink: (Subscription) -> EventStream.Sink<T>) {
        this@asEventStream.connect { sub ->
            val sink = getSink(sub)
            object : Eventual.Sink<T> {
                override fun onComplete(result: T) {
                    sink.onEvent(result)
                    sink.onComplete()
                }
            }
        }
    }
}

/** Subscribes to the given eventual value if this potential value does not emit a result. */
fun <T> Potential<T>.elseThen(fallback: Eventual<T>): Eventual<T> =
    andThen { it?.let { eventualOf(it) } ?: fallback }

/**
 * Creates a new [EventStream] that drops all events from this stream. [EventStream.Sink.onComplete]
 * is still invoked.
 */
fun <T, S> EventStream<T>.ignoreAll(): EventStream<S> = flatMap { emptyEvents<S>() }

/** Returns the eventual result, blocking the current thread if necessary. */
@Throws(InterruptedException::class)
fun <T> Eventual<T>.getBlocking(): T {
    val resultRef = AtomicReference<T>()
    val latch = CountDownLatch(1)
    val sub = subscribe {
        resultRef.set(it)
        latch.countDown()
    }
    try {
        latch.await()
        return resultRef.get()
    } finally {
        sub.cancel()
    }
}

/**
 * Creates a new potential value that subscribes to this eventual value and tests its result with
 * the given predicate. If the predicate returns true, the result is emitted, otherwise the
 * potential value completes with no result.
 */
fun <T> Eventual<T>.filter(predicate: (T) -> Boolean): Potential<T> = object : Potential<T> {
    override fun connect(getSink: (Subscription) -> Eventual.Sink<T?>) {
        this@filter.connect { sub ->
            val sink = getSink(sub)
            object : Eventual.Sink<T> {
                override fun onComplete(result: T) = sink.onComplete(result.takeIf(predicate))
            }
        }
    }
}

/**
 * Creates a new [EventStream] that subscribes to this potential value, and emits it (should it
 * exist) before completing. If no value exists, the [EventStream] will just complete.
 */
fun <T> Potential<T>.possibleEvents(): EventStream<T> = object : EventStream<T> {
    override fun connect(getSink: (Subscription) -> EventStream.Sink<T>) {
        this@possibleEvents.connect { sub ->
            val sink = getSink(sub)
            object : Eventual.Sink<T?> {
                override fun onComplete(result: T?) {
                    result?.let(sink::onEvent)
                    sink.onComplete()
                }
            }
        }
    }
}

/**
 * Imperative event source that can have multiple subscribers, and will push events to all of them.
 */
class BroadcastingEventSource<T> : EventStream<T> {

    private val subscribers = ConcurrentLinkedQueue<Pair<EventStream.Sink<T>, Subscription>>()

    override fun connect(getSink: (Subscription) -> EventStream.Sink<T>) {
        lateinit var pair: Pair<EventStream.Sink<T>, Subscription>
        val subscription = Subscription.create { subscribers.remove(pair) }
        val sink = getSink(subscription)
        pair = Pair(sink, subscription)
        subscribers.add(pair)
        if (subscription.isCancelled) {
            subscribers.remove(pair)
        }
    }

    /** Emits an event to all downstream subscribers. */
    fun emitEvent(t: T) = subscribers.asSequence().toList().forEach { it.first.onEvent(t) }

    /** Notifies each subscriber that this stream has completed, and then clears all subscribers. */
    fun complete() {
        val copy = subscribers.asSequence().toList()
        subscribers.clear()
        copy.forEach { (sink, sub) ->
            sink.onComplete()
            sub.cancel()
        }
    }
}

/**
 * Imperative event source that can have multiple subscribers, and caches the most recently emitted
 * event, to be emitted immediately for new subscribers.
 */
class CachingEventSource<T>(default: T) : EventStream<T> {

    @Volatile
    private var cache = default

    private val subscribers = ConcurrentLinkedQueue<Pair<EventStream.Sink<T>, Subscription>>()

    override fun connect(getSink: (Subscription) -> EventStream.Sink<T>) {
        lateinit var pair: Pair<EventStream.Sink<T>, Subscription>
        val subscription = Subscription.create { subscribers.remove(pair) }
        val sink = getSink(subscription)
        pair = Pair(sink, subscription)
        subscribers.add(pair)
        if (subscription.isCancelled) {
            subscribers.remove(pair)
        } else {
            sink.onEvent(cache)
        }
    }

    /**
     * Emits an event to all downstream subscribers, and updates the cached value for all future
     * ones.
     */
    fun emitEvent(t: T) {
        cache = t
        subscribers.asSequence().toList().forEach { it.first.onEvent(t) }
    }

    /**
     * Notifies each subscriber that this stream has completed, and then clears all subscribers.
     */
    fun complete() {
        val copy = subscribers.asSequence().toList()
        subscribers.clear()
        copy.forEach { (sink, sub) ->
            sink.onComplete()
            sub.cancel()
        }
    }
}


typealias Logger = (String) -> Unit

/** Logs each event emission, leaving the underlying stream untouched. */
fun <T> EventStream<T>.logEach(
        logger: Logger,
        block: (T) -> String = { it.toString() }
): EventStream<T> =
    object : EventStream<T> {
        override fun connect(getSink: (Subscription) -> EventStream.Sink<T>) {
            this@logEach.connect { sub ->
                val sink = getSink(sub)
                object : EventStream.Sink<T> by sink {
                    override fun onEvent(event: T) {
                        logger(block(event))
                        sink.onEvent(event)
                    }
                }
            }
        }
    }

/** Logs when the [EventStream] has completed, leaving the underlying stream untouched. */
fun <T> EventStream<T>.logComplete(
        logger: Logger,
        block: () -> String = { "EventStream complete" }
): EventStream<T> =
    object : EventStream<T> {
        override fun connect(getSink: (Subscription) -> EventStream.Sink<T>) {
            this@logComplete.connect { sub ->
                val sink = getSink(sub)
                object : EventStream.Sink<T> by sink {
                    override fun onComplete() {
                        logger(block())
                        sink.onComplete()
                    }
                }
            }
        }
    }

/** Logs the result of this eventual value, leaves the value itself untouched. */
fun <T> Eventual<T>.logResult(
        logger: Logger,
        block: (T) -> String = { it.toString() }
): Eventual<T> =
    object : Eventual<T> {
        override fun connect(getSink: (Subscription) -> Eventual.Sink<T>) {
            this@logResult.connect { sub ->
                val sink = getSink(sub)
                object : Eventual.Sink<T> {
                    override fun onComplete(result: T) {
                        logger(block(result))
                        sink.onComplete(result)
                    }
                }
            }
        }
    }

/** Logs when the EventStream is canceled. */
fun <T> EventStream<T>.logCancellation(
        logger: Logger,
        block: () -> String = { "EventStream canceled" }
): EventStream<T> =
    doOnCancel { logger(block()) }

/** Executes the given block when the EventStream is canceled. */
fun <T> EventStream<T>.doOnCancel(block: () -> Unit): EventStream<T> =
    object : EventStream<T> {
        override fun connect(getSink: (Subscription) -> EventStream.Sink<T>) {
            this@doOnCancel.connect { sub ->
                getSink(object : Subscription by sub {
                    override fun cancel() {
                        block()
                        sub.cancel()
                    }
                })
            }
        }
    }

/** Logs when the Eventual is canceled. */
fun <T> Eventual<T>.logCancellation(
        logger: Logger,
        block: () -> String = { "Eventual canceled" }
): Eventual<T> =
    doOnCancel { logger(block()) }

/** Executes the given block when the Eventual is canceled. */
fun <T> Eventual<T>.doOnCancel(block: () -> Unit): Eventual<T> =
    object : Eventual<T> {
        override fun connect(getSink: (Subscription) -> Eventual.Sink<T>) {
            this@doOnCancel.connect { sub ->
                getSink(object : Subscription by sub {
                    override fun cancel() {
                        block()
                        sub.cancel()
                    }
                })
            }
        }
    }

/** Logs when the EventStream is subscribed to. */
fun <T> EventStream<T>.logSubscription(
        logger: Logger,
        block: () -> String = { "EventStream started" }
): EventStream<T> =
    object : EventStream<T> {
        override fun connect(getSink: (Subscription) -> EventStream.Sink<T>) {
            this@logSubscription.connect { sub ->
                logger(block())
                getSink(sub)
            }
        }
    }

/** Logs when the Eventual is subscribed to. */
fun <T> Eventual<T>.logSubscription(
        logger: Logger,
        block: () -> String = { "Eventual started" }
): Eventual<T> =
    object : Eventual<T> {
        override fun connect(getSink: (Subscription) -> Eventual.Sink<T>) {
            this@logSubscription.connect { sub ->
                logger(block())
                getSink(sub)
            }
        }
    }

private data class Result<T>(val data: T)

private sealed class ImmutableLinkedList<T>
private data class Cons<T>(val head: T, val tail: ImmutableLinkedList<T>) : ImmutableLinkedList<T>()
private class Empty<T> : ImmutableLinkedList<T>()

private tailrec fun <T> ImmutableLinkedList<T>.forEach(action: (element: T) -> Unit) {
    when (this) {
        is Empty<T> -> Unit
        is Cons<T> -> {
            action(this.head)
            this.tail.forEach(action)
        }
    }
}

private fun <T> ImmutableLinkedList<T>.all(predicate: (element: T) -> Boolean): Boolean =
    when (this) {
        is Empty<T> -> true
        is Cons<T> -> predicate(head) && tail.all(predicate)
    }
