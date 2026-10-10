package io.github.yuroyami.audiokodec.internal.vorbis

import io.github.yuroyami.audiokodec.DecodeOptions
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * The most recent Vorbis setups, so that streams with byte-identical setup headers share one parse.
 * A SoundFont holds hundreds of streams from one encoder, and parsing codebooks can cost more than decoding.
 */
@OptIn(ExperimentalAtomicApi::class)
internal object VorbisSetupCache {

    private const val CAPACITY = 8

    /** A setup holding more than this is parsed every time instead of kept. */
    private const val MAX_RETAINED_BYTES = 1L shl 20

    private class Entry(val setup: ByteArray, val hash: Int, val config: VorbisConfig)

    /** Newest first. The array is replaced whole and never changed, so reading it needs no lock. */
    private val recent = AtomicReference(emptyArray<Entry>())

    fun configFor(
        setup: ByteArray,
        identification: VorbisIdentification,
        options: DecodeOptions,
        parse: () -> VorbisConfig,
    ): VorbisConfig {
        val hash = setup.contentHashCode()
        find(recent.load(), setup, hash, identification)
            ?.takeIf { it.guardedBytes <= options.maxAllocationBytes }
            ?.let { return it }
        val config = parse()
        if (config.retainedBytes <= MAX_RETAINED_BYTES) remember(Entry(setup, hash, config), identification)
        return config
    }

    private fun find(
        entries: Array<Entry>,
        setup: ByteArray,
        hash: Int,
        identification: VorbisIdentification,
    ): VorbisConfig? {
        for (entry in entries) {
            val config = entry.config
            if (entry.hash == hash &&
                config.channels == identification.channels &&
                config.shortBlock == identification.shortBlock &&
                config.longBlock == identification.longBlock &&
                entry.setup.contentEquals(setup)
            ) {
                return config
            }
        }
        return null
    }

    private fun remember(entry: Entry, identification: VorbisIdentification) {
        while (true) {
            val current = recent.load()
            // Another stream may have parsed the same setup at the same moment.
            if (find(current, entry.setup, entry.hash, identification) != null) return
            val next = Array(minOf(current.size + 1, CAPACITY)) { if (it == 0) entry else current[it - 1] }
            if (recent.compareAndSet(current, next)) return
        }
    }
}
