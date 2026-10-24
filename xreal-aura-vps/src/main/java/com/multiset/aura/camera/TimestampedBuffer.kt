/*
Copyright (c) 2026 MultiSet AI. All rights reserved.
Licensed under the MultiSet License. You may not use this file except in compliance with the License. and you can't re-distribute this file without a prior notice
For license details, visit www.multiset.ai.
Redistribution in source or binary forms must retain this notice.
*/
package com.multiset.aura.camera

import kotlinx.coroutines.CompletableDeferred

/** Holds recent items by timestamp so a still can be paired with its CaptureResult. Thread-safe. */
class TimestampedBuffer<T : AutoCloseable>(private val capacity: Int) : AutoCloseable {

    private val lock = Any()
    private val held = LinkedHashMap<Long, T>()
    private var waiter: Pair<Long?, CompletableDeferred<T>>? = null
    private var closed = false

    fun offer(timestamp: Long, item: T): Unit = synchronized(lock) {
        val current = waiter
        when {
            closed -> item.close()
            current != null && (current.first == null || current.first == timestamp) -> {
                waiter = null
                if (!current.second.complete(item)) item.close()
            }
            else -> {
                held[timestamp] = item
                while (held.size > capacity) {
                    held.remove(held.keys.first())?.close()
                }
            }
        }
    }

    /** Waits for the item with [timestamp], or for any item when it is null. */
    suspend fun await(timestamp: Long?): T {
        val deferred = CompletableDeferred<T>()
        synchronized(lock) {
            val ready = if (timestamp == null) held.keys.firstOrNull() else timestamp.takeIf { it in held }
            ready?.let { return held.remove(it)!! }
            waiter = timestamp to deferred
        }
        try {
            return deferred.await()
        } finally {
            synchronized(lock) { if (waiter?.second === deferred) waiter = null }
        }
    }

    override fun close() = synchronized(lock) {
        closed = true
        held.values.forEach(AutoCloseable::close)
        held.clear()
        waiter?.second?.cancel()
        waiter = null
    }
}
