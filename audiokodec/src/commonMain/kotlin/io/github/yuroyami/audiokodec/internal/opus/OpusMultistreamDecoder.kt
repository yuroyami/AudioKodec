package io.github.yuroyami.audiokodec.internal.opus

import io.github.yuroyami.audiokodec.CorruptStreamException

/**
 * Decodes Opus with any number of channels.
 *
 * One Opus stream carries one or two channels, so anything wider is several
 * streams side by side in each packet: some coupled, carrying two channels
 * coded jointly, the rest carrying one. A table then says which output channel
 * each coded channel feeds, and may feed one coded channel to several outputs
 * or leave an output silent. Every stream but the last is packed in the
 * self-delimited form, which states its own length.
 *
 * For ambisonics coded by projection there is no table: the coded channels are
 * mixed into the outputs through a matrix the header carries.
 *
 * Translated from libopus `opus_multistream_decoder.c`, `opus_multistream.c`,
 * `opus_projection_decoder.c` and `mapping_matrix.c`, float build.
 */
internal class OpusMultistreamDecoder private constructor(
    val sampleRate: Int,
    val channels: Int,
    val streams: Int,
    val coupledStreams: Int,
    private val mapping: IntArray,
    /** Column-major, `channels` rows by `streams + coupledStreams` columns, in Q15. */
    private val demixing: IntArray?,
) {
    private val decoders = Array(streams) { OpusDecoder(sampleRate, if (it < coupledStreams) 2 else 1) }
    private val buffer = FloatArray(2 * MAX_FRAME_MS * sampleRate / 1000)

    /** Output gain in Q8 decibels, from an Ogg Opus header, applied by every stream. */
    var gain: Int = 0
        set(value) {
            field = value
            for (decoder in decoders) decoder.gain = value
        }

    /** The final range states of all the streams for the last packet, combined as the encoder combines them. */
    val finalRange: Long
        get() {
            var combined = 0L
            for (decoder in decoders) combined = combined xor decoder.finalRange
            return combined
        }

    /** Samples per channel the last call decoded or concealed. */
    val lastPacketDuration: Int get() = decoders[0].lastPacketDuration

    fun reset() {
        for (decoder in decoders) decoder.reset()
    }

    /**
     * `opus_multistream_decode_native`: decodes the packet of [length] bytes
     * at [offset], or conceals one when [data] is null or [length] is zero,
     * into [pcm] as interleaved samples. [frameSize] is the room in [pcm] per
     * channel, and for concealment the duration to conceal. Returns the
     * samples written per channel.
     */
    fun decode(
        data: ByteArray?,
        offset: Int,
        length: Int,
        pcm: FloatArray,
        frameSize: Int,
        decodeFec: Boolean = false,
        softClip: Boolean = false,
    ): Int {
        require(frameSize > 0) { "a frame size must be positive, was $frameSize" }
        var size = minOf(frameSize, MAX_FRAME_MS * sampleRate / 1000)
        val conceal = data == null || length == 0
        if (!conceal) {
            if (length < 2 * streams - 1) {
                throw CorruptStreamException("a $length-byte packet cannot hold $streams Opus streams", offset.toLong())
            }
            val samples = validate(data!!, offset, length)
            require(samples <= size) { "a $size-sample buffer cannot hold this $samples-sample packet" }
        }
        var at = offset
        var left = length
        for (s in 0 until streams) {
            val last = s == streams - 1
            val packetLength = if (conceal) {
                0
            } else {
                // Only the last stream is not self-delimited, and it runs to
                // the end of the packet.
                if (last) left else OpusPacket.parse(data!!, at, left, selfDelimited = true).length
            }
            val decoded = decoders[s].decode(
                if (conceal) null else data, at, packetLength, buffer, size, decodeFec, softClip,
                selfDelimited = !last,
            )
            if (!conceal) {
                at += packetLength
                left -= packetLength
            }
            size = decoded
            if (demixing != null) {
                if (s < coupledStreams) {
                    demix(pcm, 2 * s, buffer, 0, 2, size)
                    demix(pcm, 2 * s + 1, buffer, 1, 2, size)
                } else {
                    demix(pcm, s + coupledStreams, buffer, 0, 1, size)
                }
            } else if (s < coupledStreams) {
                copyTo(pcm, 2 * s, buffer, 0, 2, size)
                copyTo(pcm, 2 * s + 1, buffer, 1, 2, size)
            } else {
                copyTo(pcm, s + coupledStreams, buffer, 0, 1, size)
            }
        }
        if (demixing == null) {
            for (c in 0 until channels) {
                if (mapping[c] == SILENT) {
                    for (i in 0 until size) pcm[i * channels + c] = 0f
                }
            }
        }
        return size
    }

    /**
     * `opus_multistream_packet_validate`: every stream's packet must parse
     * and all must last equally long, which is checked before any stream
     * decodes so that a bad packet changes no state. Returns that length.
     */
    private fun validate(data: ByteArray, offset: Int, length: Int): Int {
        var at = offset
        var left = length
        var samples = 0
        for (s in 0 until streams) {
            if (left <= 0) throw CorruptStreamException("an Opus multistream packet ends before stream $s", offset.toLong())
            val packet = OpusPacket.parse(data, at, left, selfDelimited = s != streams - 1)
            val streamSamples = packet.samples * sampleRate / 48_000
            if (s != 0 && streamSamples != samples) {
                throw CorruptStreamException("the streams of an Opus packet disagree on its length", offset.toLong())
            }
            samples = streamSamples
            at += packet.length
            left -= packet.length
        }
        return samples
    }

    /** Copies coded channel [coded] to every output channel the table sends it to. */
    private fun copyTo(pcm: FloatArray, coded: Int, source: FloatArray, from: Int, stride: Int, size: Int) {
        for (c in 0 until channels) {
            if (mapping[c] != coded) continue
            for (i in 0 until size) pcm[i * channels + c] = source[i * stride + from]
        }
    }

    /**
     * `mapping_matrix_multiply_channel_out_float`: adds coded channel [coded]
     * into every output through its column of the matrix. The output is
     * cleared first, when the first coded channel arrives.
     */
    private fun demix(pcm: FloatArray, coded: Int, source: FloatArray, from: Int, stride: Int, size: Int) {
        val matrix = demixing!!
        // The reference feeds the matrix through the channel table, which
        // for projection is the identity over the output channels, so a coded
        // channel beyond them is never mixed in.
        if (coded >= channels) return
        if (coded == 0) pcm.fill(0f, 0, size * channels)
        for (i in 0 until size) {
            val sample = source[i * stride + from]
            for (row in 0 until channels) {
                val term = (1 / 32768f) * matrix[channels * coded + row] * sample
                pcm[channels * i + row] += term
            }
        }
    }

    companion object {
        /** A table entry for an output channel that no coded channel feeds. */
        const val SILENT: Int = 255

        private const val MAX_FRAME_MS = 120

        /**
         * A decoder whose [mapping] gives, for each output channel, the coded
         * channel feeding it or [SILENT]. Coded channels number the coupled
         * streams' pairs first and the single streams after them.
         */
        fun withMapping(
            sampleRate: Int,
            channels: Int,
            streams: Int,
            coupledStreams: Int,
            mapping: IntArray,
        ): OpusMultistreamDecoder {
            checkCounts(channels, streams, coupledStreams)
            require(mapping.size == channels) { "the channel table has ${mapping.size} entries for $channels channels" }
            for (entry in mapping) {
                require(entry == SILENT || entry in 0 until streams + coupledStreams) {
                    "the channel table names coded channel $entry of ${streams + coupledStreams}"
                }
            }
            return OpusMultistreamDecoder(sampleRate, channels, streams, coupledStreams, mapping.copyOf(), null)
        }

        /**
         * A decoder for ambisonics coded by projection, which mixes the coded
         * channels into the outputs through [matrix]: `channels` rows by
         * `streams + coupledStreams` columns of signed 16-bit Q15 values,
         * column after column, as the header stores them.
         */
        fun withDemixingMatrix(
            sampleRate: Int,
            channels: Int,
            streams: Int,
            coupledStreams: Int,
            matrix: IntArray,
        ): OpusMultistreamDecoder {
            checkCounts(channels, streams, coupledStreams)
            require(matrix.size == channels * (streams + coupledStreams)) {
                "a demixing matrix for $channels channels from ${streams + coupledStreams} needs " +
                    "${channels * (streams + coupledStreams)} entries, not ${matrix.size}"
            }
            // The reference routes coded channels to the matrix through an
            // identity table over the outputs, which it validates like any
            // other table.
            require(channels <= streams + coupledStreams) {
                "projection cannot make $channels channels from ${streams + coupledStreams} coded ones"
            }
            return OpusMultistreamDecoder(
                sampleRate, channels, streams, coupledStreams, IntArray(channels) { it }, matrix.copyOf(),
            )
        }

        private fun checkCounts(channels: Int, streams: Int, coupledStreams: Int) {
            require(channels in 1..255) { "Opus carries 1 to 255 channels, not $channels" }
            require(streams >= 1 && coupledStreams in 0..streams && streams + coupledStreams <= 255) {
                "$streams streams of which $coupledStreams coupled is not a valid Opus layout"
            }
        }
    }
}
