package io.github.yuroyami.audiokodec.internal.container

import io.github.yuroyami.audiokodec.DecodeOptions

/**
 * Indexed FIFO lookup for RF64 and BW64 ds64 entries.
 *
 * A chunk ID can occur more than once. Its sizes must be consumed in their
 * table order, even when entries for other IDs are interleaved. Packing the
 * four bytes into an integer also avoids colliding four-character string
 * hashes. The tag writer keeps its separate list for original output order.
 */
internal class RiffSizeTable<T>(private val options: DecodeOptions) {
    private class Entry<T>(val value: T, var next: Entry<T>? = null)
    private class Queue<T> {
        var first: Entry<T>? = null
        var last: Entry<T>? = null
    }

    private val queues = HashMap<Int, Queue<T>>()
    private var entryCount = 0L

    fun add(id: String, value: T) {
        options.checkAllocation((entryCount + 1L) * BYTES_PER_ENTRY, "the RF64 ds64 size table")
        val queue = queues.getOrPut(key(id)) { Queue() }
        val entry = Entry(value)
        val previous = queue.last
        if (previous == null) queue.first = entry else previous.next = entry
        queue.last = entry
        entryCount++
    }

    fun take(id: String): T? {
        val key = key(id)
        val queue = queues[key] ?: return null
        val entry = queue.first ?: return null
        queue.first = entry.next
        if (queue.first == null) queues.remove(key)
        return entry.value
    }

    private fun key(id: String): Int =
        (id[0].code shl 24) or (id[1].code shl 16) or (id[2].code shl 8) or id[3].code

    companion object {
        // Includes retained values/IDs, writer list slots, queue links and
        // the map's keys, buckets and per-distinct-ID queue objects.
        const val BYTES_PER_ENTRY = 256L
    }
}
