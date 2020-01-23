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

@file:Suppress("DEPRECATION")

package com.android.systemui.plugin.globalactions.wallet.reactive

import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.Uri
import android.os.AsyncTask
import android.os.Handler
import android.os.PatternMatcher
import android.util.Log
import java.util.concurrent.CountDownLatch

private const val TAG = "WalletReactive"

/**
 * Subscribes to the Eventual via an [AsyncTask].
 *
 * Specifically, subscription occurs in [AsyncTask.doInBackground], and the result is dispatched
 * back to the main thread via [AsyncTask.onPostExecute]. The background thread is blocked until the
 * result is available.
 *
 * @param mayInterruptWhenCancelled If true, interrupts execution of the async task when this
 *                                  eventual value is cancelled. If false, the background work will
 *                                  be executed to completion, but no value will be emitted
 *                                  downstream.
 */
fun <T> Eventual<T>.asyncTask(mayInterruptWhenCancelled: Boolean = true): Eventual<T> =
    eventual {
        val task = object : AsyncTask<Unit, Unit, T>() {
            override fun doInBackground(vararg params: Unit?): T = getBlocking()
            override fun onPostExecute(result: T) = complete(result)
        }
        setCancelAction { task.cancel(mayInterruptWhenCancelled) }
        task.execute()
    }

/**
 * Subscribes to the EventStream via an [AsyncTask].
 *
 * Specifically, subscription occurs in [AsyncTask.doInBackground], and events are dispatched back
 * to the main thread via [AsyncTask.onProgressUpdate]. The background thread is blocked until the
 * source stream completes.
 *
 * @param mayInterruptWhenCancelled If true, interrupts execution of the async task when this
 *                                  stream is cancelled. If false, the background work will be
 *                                  executed to completion, but no value will be emitted downstream.
 */
fun <T> EventStream<T>.asyncTask(mayInterruptWhenCancelled: Boolean = true): EventStream<T> =
    events {
        val subRef = SubscriptionHolder()
        val task = object : AsyncTask<Unit, T, Unit>() {
            override fun doInBackground(vararg params: Unit?) {
                val latch = CountDownLatch(1)
                connect { sub ->
                    subRef.replace(sub)
                    object : EventStream.Sink<T> {
                        override fun onEvent(event: T) {
                            if (mayInterruptWhenCancelled && isCancelled) {
                                throw InterruptedException()
                            }
                            publishProgress(event)
                        }
                        override fun onComplete() = latch.countDown()
                    }
                }
                latch.await()
            }
            override fun onPostExecute(result: Unit?) = complete()
            override fun onProgressUpdate(vararg values: T) {
                for (v in values) {
                    if (this@events.isCancelled) break
                    emitEvent(v)
                }
            }
        }
        val taskSubscription = Subscription.create { task.cancel(mayInterruptWhenCancelled) }
        setSubscription(object : Subscription {
            override val isCancelled: Boolean
                get() = subRef.isCancelled && taskSubscription.isCancelled

            override fun cancel() {
                subRef.cancel()
                taskSubscription.cancel()
            }
        })
        task.execute()
    }

fun AndroidLogger(tag: String, priority: Int): Logger = { Log.println(priority, tag, it) }

/** Logs each event emission, leaving the underlying stream untouched. */
fun <T> EventStream<T>.logEach(
    tag: String = TAG,
    priority: Int = Log.DEBUG,
    block: (T) -> String = { it.toString() }
): EventStream<T> =
    logEach(AndroidLogger(tag, priority), block)

/** Logs when the [EventStream] has completed, leaving the underlying stream untouched. */
fun <T> EventStream<T>.logComplete(
    tag: String = TAG,
    priority: Int = Log.DEBUG,
    block: () -> String = { "EventStream complete" }
): EventStream<T> =
    logComplete(AndroidLogger(tag, priority), block)

/** Logs the result of an eventual value, leaves the value itself untouched. */
fun <T> Eventual<T>.logResult(
    tag: String = TAG,
    priority: Int = Log.DEBUG,
    block: (T) -> String = { it.toString() }
): Eventual<T> =
    logResult(AndroidLogger(tag, priority), block)

/** Logs when an EventStream is canceled. */
fun <T> EventStream<T>.logCancellation(
    tag: String = TAG,
    priority: Int = Log.DEBUG,
    block: () -> String = { "EventStream canceled" }
): EventStream<T> =
    logCancellation(AndroidLogger(tag, priority), block)

/** Logs when an Eventual is canceled. */
fun <T> Eventual<T>.logCancellation(
    tag: String = TAG,
    priority: Int = Log.DEBUG,
    block: () -> String = { "Eventual canceled" }
): Eventual<T> =
    logCancellation(AndroidLogger(tag, priority), block)


/** Logs when the EventStream is subscribed to. */
fun <T> EventStream<T>.logSubscription(
    tag: String = TAG,
    priority: Int = Log.DEBUG,
    block: () -> String = { "EventStream started" }
): EventStream<T> =
    logSubscription(AndroidLogger(tag, priority), block)

/** Logs when the Eventual is subscribed to. */
fun <T> Eventual<T>.logSubscription(
    tag: String = TAG,
    priority: Int = Log.DEBUG,
    block: () -> String = { "Eventual started" }
): Eventual<T> =
    logSubscription(AndroidLogger(tag, priority), block)

fun ContentResolver.observeContent(
        uri: Uri,
        notifyForDescendants: Boolean = false,
        handler: Handler? = null
): EventStream<ContentChangeInfo> =
        events {
            val observer = object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean) = onChange(selfChange, null)
                override fun onChange(selfChange: Boolean, uri: Uri?) =
                        emitEvent(ContentChangeInfo(selfChange, uri))
            }
            setCancelAction { unregisterContentObserver(observer) }
            try {
                registerContentObserver(uri, notifyForDescendants, observer)
            } catch (e: SecurityException) {
                Log.e(TAG, "Couldn't register content observer")
                complete()
            }
        }

fun Context.observeBroadcasts(
        filter: IntentFilter,
        permission: String? = null,
        handler: Handler? = null,
        flags: Int = 0
): EventStream<Intent> =
        events {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) = emitEvent(intent)
            }
            setCancelAction { unregisterReceiver(receiver) }
            registerReceiver(receiver, filter, permission, handler, flags)
        }

fun Context.packageEvents(packageName: String): EventStream<Pair<PackageEvent, Intent>> {
    val filter = IntentFilter().apply {
        addAction(Intent.ACTION_PACKAGE_ADDED)
        addAction(Intent.ACTION_PACKAGE_CHANGED)
        addAction(Intent.ACTION_PACKAGE_REPLACED)
        addAction(Intent.ACTION_PACKAGE_REMOVED)
        addDataScheme("package")
        addDataSchemeSpecificPart(packageName, PatternMatcher.PATTERN_LITERAL)
    }
    fun getPackageEvent(intent: Intent): Pair<PackageEvent, Intent>? =
            when (intent.action) {
                Intent.ACTION_PACKAGE_ADDED ->
                    intent.extras
                            ?.getBoolean(Intent.EXTRA_REPLACING, false)
                            ?.takeUnless { it }
                            ?.let { PackageEvent.PackageAdded }
                Intent.ACTION_PACKAGE_CHANGED -> PackageEvent.PackageChanged
                Intent.ACTION_PACKAGE_REPLACED -> PackageEvent.PackageReplaced
                Intent.ACTION_PACKAGE_REMOVED ->
                    intent.extras
                            ?.getBoolean(Intent.EXTRA_REPLACING, false)
                            ?.takeUnless { it }
                            ?.let { PackageEvent.PackageRemoved }
                else -> null
            }?.let { Pair(it, intent) }
    return observeBroadcasts(filter).filterMap(::getPackageEvent)
}

sealed class PackageEvent {
    object PackageAdded : PackageEvent()
    object PackageReplaced : PackageEvent()
    object PackageRemoved : PackageEvent()
    object PackageChanged : PackageEvent()
}

data class ContentChangeInfo(val selfChange: Boolean, val uri: Uri?)