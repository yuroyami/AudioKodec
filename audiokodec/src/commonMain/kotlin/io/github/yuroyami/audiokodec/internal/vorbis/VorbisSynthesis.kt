package io.github.yuroyami.audiokodec.internal.vorbis

import io.github.yuroyami.audiokodec.CorruptStreamException
import io.github.yuroyami.audiokodec.DecodeOptions
import io.github.yuroyami.audiokodec.internal.dsp.InverseMdct

/**
 * Turns Vorbis audio packets into samples, whatever container carries them.
 *
 * Each packet is one overlapping block: this reads its floor and residue,
 * undoes the coupling, transforms and windows it, and adds it onto the
 * block before, handing back the samples between the two blocks' centres.
 * The container decides which packets come in what order and where they
 * sit in time; this keeps only the state one block carries to the next.
 */
internal class VorbisSynthesis(
    identification: VorbisIdentification,
    private val config: VorbisConfig,
    private val options: DecodeOptions,
    /** Where the stream's audio starts, for an error message. */
    private val offset: Long,
) {
    private val channels = identification.channels
    private val longBlock = identification.longBlock
    private val shortBlock = identification.shortBlock
    private val bits = VorbisBitReader(ByteArray(0), 0)
    private val shortMdct = InverseMdct(shortBlock)
    private val longMdct = InverseMdct(longBlock)

    /** One block of spectrum, then of time, per channel. */
    private val blocks = Array(channels) {
        // Three buffers of this size exist per channel, plus the interleaved
        // output. A file is free to declare eight channels of 8192-sample
        // blocks, so the ceiling is checked rather than assumed reasonable.
        options.checkAllocation(
            channels.toLong() * longBlock * 4 * 3,
            "decoding $channels channels of $longBlock-sample Vorbis blocks",
        )
        FloatArray(longBlock)
    }

    /** The part of the last block that reaches past its own centre. */
    private val tails = Array(channels) { FloatArray(longBlock / 2) }

    private val curve = FloatArray(longBlock / 2)
    private val floor0Order = config.floors.maxOf { if (it is VorbisFloor.Floor0) it.order else 0 }
    private val floorStates = Array(channels) {
        options.checkAllocation(channels.toLong() * floor0Order * (Float.SIZE_BYTES + Double.SIZE_BYTES),
            "decoding Vorbis floor 0 coefficients")
        VorbisFloor.FloorState(MAX_FLOOR_POINTS, floor0Order)
    }
    private val floorForChannel = arrayOfNulls<VorbisFloor>(channels)
    private val noResidue = BooleanArray(channels)

    /**
     * Whether each channel's floor said it had nothing this packet.
     *
     * This is kept apart from [noResidue] on purpose. Coupling clears
     * [noResidue] for both sides of a pair, because a coupled pair is decoded
     * together whatever either side said; but a channel whose floor was unused
     * still has no curve to multiply by, and its output is silence. Reading the
     * cleared flag instead would give it the curve left over from the packet
     * before, which is one channel of a stereo pair leaking into the other.
     */
    private val floorUnused = BooleanArray(channels)
    private val submapChannels = Array(channels) { blocks[it] }
    private val submapSkip = BooleanArray(channels)
    private val residueScratch = VorbisResidue.Scratch()

    private var previousBlockSize = 0
    private var hasPrevious = false

    /** Frames one channel's part of [output] can hold, which is its longest block. */
    val stride = longBlock / 2

    /** The last packet's samples, per channel, [stride] apart. */
    var output = FloatArray(0)
        private set

    /**
     * Decodes one packet and returns how many frames it produced.
     *
     * The frames land in [output], one channel after another with [stride]
     * between them, in the stream's own channel order.
     */
    fun decode(bytes: ByteArray, length: Int): Int {
        bits.reset(bytes, length)
        if (bits.readBits(1) != 0) return 0

        val modeNumber = bits.readBits(config.modeBits)
        if (bits.endOfPacket || modeNumber >= config.modes.size) {
            throw CorruptStreamException(
                "a Vorbis packet names mode $modeNumber of ${config.modes.size}",
                offset,
            )
        }
        val mode = config.modes[modeNumber]
        val n = if (mode.longBlock) longBlock else shortBlock
        var previousLong = true
        var nextLong = true
        if (mode.longBlock) {
            previousLong = bits.readBits(1) == 1
            nextLong = bits.readBits(1) == 1
        }
        val half = n / 2
        val mapping = config.mappings[mode.mapping]

        for (channel in 0 until channels) {
            val floor = config.floors[mapping.submapFloor[mapping.mux[channel]]]
            floorForChannel[channel] = floor
            val decoded = floor.decode(bits, config.codebooks, floorStates[channel])
            floorUnused[channel] = !decoded
            noResidue[channel] = !decoded
        }

        // A coupled pair is decoded together, so if either side has anything to
        // say then both are decoded.
        for (step in mapping.couplingMagnitude.indices) {
            val magnitude = mapping.couplingMagnitude[step]
            val angle = mapping.couplingAngle[step]
            if (!noResidue[magnitude] || !noResidue[angle]) {
                noResidue[magnitude] = false
                noResidue[angle] = false
            }
        }

        for (channel in 0 until channels) {
            val block = blocks[channel]
            for (i in 0 until half) block[i] = 0f
        }

        for (submap in 0 until mapping.submaps) {
            var used = 0
            for (channel in 0 until channels) {
                if (mapping.mux[channel] != submap) continue
                submapChannels[used] = blocks[channel]
                submapSkip[used] = noResidue[channel]
                used++
            }
            if (used == 0) continue
            config.residues[mapping.submapResidue[submap]].decode(
                reader = bits,
                codebooks = config.codebooks,
                channels = submapChannels,
                used = used,
                skip = submapSkip,
                half = half,
                scratch = residueScratch,
            )
        }

        // Undo the coupling, last step first, because each step was applied to
        // the result of the one before it.
        for (step in mapping.couplingMagnitude.indices.reversed()) {
            val magnitudes = blocks[mapping.couplingMagnitude[step]]
            val angles = blocks[mapping.couplingAngle[step]]
            for (i in 0 until half) {
                val magnitude = magnitudes[i]
                val angle = angles[i]
                val newMagnitude: Float
                val newAngle: Float
                if (magnitude > 0) {
                    if (angle > 0) {
                        newMagnitude = magnitude
                        newAngle = magnitude - angle
                    } else {
                        newAngle = magnitude
                        newMagnitude = magnitude + angle
                    }
                } else {
                    if (angle > 0) {
                        newMagnitude = magnitude
                        newAngle = magnitude + angle
                    } else {
                        newAngle = magnitude
                        newMagnitude = magnitude - angle
                    }
                }
                magnitudes[i] = newMagnitude
                angles[i] = newAngle
            }
        }

        val window = config.windowFor(mode.longBlock, previousLong, nextLong)
        val mdct = if (mode.longBlock) longMdct else shortMdct

        for (channel in 0 until channels) {
            val block = blocks[channel]
            if (floorUnused[channel]) {
                for (i in 0 until n) block[i] = 0f
                continue
            }
            floorForChannel[channel]!!.synthesise(floorStates[channel], curve, half)
            for (i in 0 until half) block[i] *= curve[i]
            mdct.transform(block, block, MDCT_SCALE)
            for (i in 0 until n) block[i] *= window[i]
        }

        return overlapAdd(n)
    }

    /**
     * Adds this block onto the tail of the one before and hands back what is
     * now complete.
     *
     * The samples that leave the decoder are the ones between the previous
     * block's centre and this one's. Everything after this block's centre is
     * kept, because its other half has not arrived yet.
     */
    private fun overlapAdd(n: Int): Int {
        if (!hasPrevious) {
            for (channel in 0 until channels) {
                val block = blocks[channel]
                val tail = tails[channel]
                for (i in 0 until n / 2) tail[i] = block[n / 2 + i]
                for (i in n / 2 until tail.size) tail[i] = 0f
            }
            previousBlockSize = n
            hasPrevious = true
            return 0
        }

        val previous = previousBlockSize
        val count = previous / 4 + n / 4
        val shift = previous / 4 - n / 4
        val needed = channels * stride
        if (output.size < needed) {
            options.checkAllocation(needed.toLong() * 4, "decoding a Vorbis block")
            output = FloatArray(needed)
        }

        for (channel in 0 until channels) {
            val block = blocks[channel]
            val tail = tails[channel]
            val base = channel * stride
            for (i in 0 until count) {
                var value = if (i < previous / 2) tail[i] else 0f
                val at = i - shift
                if (at in 0 until n) value += block[at]
                output[base + i] = value
            }
            // What is left of the previous block past this block's centre has
            // to be carried forward as well, which happens whenever a long
            // block is followed by a short one.
            for (i in 0 until n / 2) {
                var value = block[n / 2 + i]
                val old = count + i
                if (old < previous / 2) value += tail[old]
                tail[i] = value
            }
            for (i in n / 2 until tail.size) tail[i] = 0f
        }

        previousBlockSize = n
        return count
    }

    /** Forgets the block before, as at the start of a stream or after a seek. */
    fun reset() {
        hasPrevious = false
        previousBlockSize = 0
    }

    /** The block size of a packet, read from its mode, or 0 for one that is not audio. */
    fun blockSizeOf(bytes: ByteArray, length: Int): Int {
        if (length <= 0) return 0
        bits.reset(bytes, length)
        if (bits.readBits(1) != 0) return 0
        val mode = bits.readBits(config.modeBits)
        if (bits.endOfPacket || mode >= config.modes.size) return 0
        return if (config.modes[mode].longBlock) longBlock else shortBlock
    }

    private companion object {
        /**
         * What the inverse transform's output is multiplied by.
         *
         * A transform pair has to be normalised somewhere, and Vorbis puts the
         * whole of it in the encoder: the coefficients in the file are already
         * scaled so that the plain unnormalised inverse transform lands in the
         * range a sample lives in. Measured against the reference decoder, this
         * is exactly one for every block size, and a wrong value here shows up
         * as output quieter or louder by a power of two.
         */
        private const val MDCT_SCALE = 1f

        /** The most points a floor 1 curve may have, from the specification. */
        private const val MAX_FLOOR_POINTS = 65
    }
}
