/*
 * Translated from the Monkey's Audio SDK 13.27, which is Copyright 2000-2026
 * Matthew T. Ashland and licensed under the BSD 3-Clause licence, reproduced
 * in NOTICE. See reference/REFERENCES.md.
 */
package io.github.yuroyami.audiokodec.internal.ape

import io.github.yuroyami.audiokodec.CorruptStreamException
import kotlin.math.floor

/**
 * Thrown inside a frame where the reference throws, so that the frame can be
 * retried in interim mode before it is reported as damage.
 */
private class ApeFrameError : Exception()

/**
 * Reads a frame's bits the way `CUnBitArrayBase` does: 32-bit little-endian
 * words, each read from its most significant bit, with the word boundaries
 * counted from where the frame's reading started. [length] is the input the
 * reference reads for the frame, four bytes past its end; bytes of it past
 * the end of the file read as zero, as they do in the reference's buffer.
 */
private class ApeBits(private val data: ByteArray, private val available: Int, private val length: Int) {
    var bitIndex = 0

    /** Fails where the reference finds no more data: a read reaching the last bit of the input. */
    private fun ensure(bits: Int) {
        if (bitIndex.toLong() + bits >= length.toLong() * 8) throw ApeFrameError()
    }

    private fun byteAt(position: Int): Int {
        // Byte `position` of the stream, where each word's bytes are stored low first.
        val stored = (position and 3.inv()) + (3 - (position and 3))
        return if (stored < available) data[stored].toInt() and 0xFF else 0
    }

    fun readBits(count: Int): Int {
        ensure(count)
        var value = 0L
        for (i in 0 until count) {
            val position = bitIndex + i
            val bit = (byteAt(position ushr 3) ushr (7 - (position and 7))) and 1
            value = (value shl 1) or bit.toLong()
        }
        bitIndex += count
        return value.toInt()
    }

    fun readByte(): Int = readBits(8)

    fun alignToByte() {
        val rest = bitIndex and 7
        if (rest != 0) bitIndex += 8 - rest
    }
}

/**
 * The range decoder of files from version 3.99 on, `CUnBitArray`, with the
 * adaptive state each channel keeps, `UNBIT_ARRAY_STATE`.
 */
private class ApeRangeDecoder(private val bits: ApeBits) {
    private var low = 0
    private var range = 0
    private var buffer = 0

    class State {
        var k = 0
        var kSum = 0
    }

    fun flushState(state: State) {
        state.k = 10
        state.kSum = (1 shl state.k) * 16
    }

    /** `FlushBitArray`: the coder starts on a byte boundary, after one byte it ignores. */
    fun start() {
        bits.alignToByte()
        bits.readBits(8)
        buffer = bits.readBits(8)
        low = buffer ushr (8 - EXTRA_BITS)
        range = 1 shl EXTRA_BITS
    }

    /** `Finalize`: moves past the bytes the coder had loaded. */
    fun finish() {
        while (Integer32.compareUnsigned(range, BOTTOM_VALUE) <= 0) {
            bits.bitIndex += 8
            range = range shl 8
            if (range == 0) return
        }
    }

    private fun normalise() {
        while (Integer32.compareUnsigned(range, BOTTOM_VALUE) <= 0) {
            buffer = (buffer shl 8) or bits.readByte()
            low = (low shl 8) or ((buffer ushr 1) and 0xFF)
            range = range shl 8
        }
    }

    private fun decodeFast(shift: Int): Int {
        while (Integer32.compareUnsigned(range, BOTTOM_VALUE) <= 0) {
            buffer = (buffer shl 8) or bits.readByte()
            low = (low shl 8) or ((buffer ushr 1) and 0xFF)
            range = range shl 8
            if (range == 0) return 0
        }
        range = range ushr shift
        return Integer32.divideUnsigned(low, range)
    }

    private fun decodeFastWithUpdate(shift: Int): Int {
        while (Integer32.compareUnsigned(range, BOTTOM_VALUE) <= 0) {
            if (range == 0) throw ApeFrameError()
            buffer = (buffer shl 8) or bits.readByte()
            low = (low shl 8) or ((buffer ushr 1) and 0xFF)
            range = range shl 8
        }
        range = range ushr shift
        if (range == 0) throw ApeFrameError()
        val result = Integer32.divideUnsigned(low, range)
        low = Integer32.remainderUnsigned(low, range)
        return result
    }

    private fun decodeOverflow(pivot: IntArray): Int {
        val total = decodeFast(RANGE_OVERFLOW_SHIFT)
        if (Integer32.compareUnsigned(total, 65536) >= 0) throw ApeFrameError()
        var overflow = OVERFLOW_TABLE[total].toInt() and 0xFF
        low -= range * RANGE_TOTAL[overflow]
        range *= RANGE_WIDTH[overflow]
        if (overflow == MODEL_ELEMENTS - 1) {
            overflow = decodeFastWithUpdate(16) shl 16
            overflow = overflow or decodeFastWithUpdate(16)
            if (overflow == OVERFLOW_SIGNAL) {
                pivot[0] = OVERFLOW_PIVOT_VALUE
                return decodeOverflow(pivot)
            }
        }
        return overflow
    }

    /** `DecodeValueRange`: one residual, as a signed value. */
    fun decode(state: State): Long {
        // An unsigned quotient by 32 always fits in a positive Int.
        val pivot = intArrayOf(maxOf(Integer32.divideUnsigned(state.kSum, 32), 1))
        val overflow = decodeOverflow(pivot)
        val pivotValue = pivot[0]
        val base: Int
        if (Integer32.compareUnsigned(pivotValue, 1 shl 16) >= 0) {
            var pivotBits = 0
            while ((pivotValue ushr pivotBits) != 0) pivotBits++
            val shift = if (pivotBits >= 16) pivotBits - 16 else 0
            val splitFactor = 1 shl shift
            val pivotA = Integer32.divideUnsigned(pivotValue, splitFactor) + 1
            normalise()
            range = Integer32.divideUnsigned(range, pivotA)
            val baseA = Integer32.divideUnsigned(low, range)
            low = Integer32.remainderUnsigned(low, range)
            normalise()
            range = Integer32.divideUnsigned(range, splitFactor)
            val baseB = Integer32.divideUnsigned(low, range)
            low = Integer32.remainderUnsigned(low, range)
            base = baseA * splitFactor + baseB
        } else {
            while (Integer32.compareUnsigned(range, BOTTOM_VALUE) <= 0) {
                buffer = (buffer shl 8) or bits.readByte()
                low = (low shl 8) or ((buffer ushr 1) and 0xFF)
                range = range shl 8
                if (range == 0) return 0
            }
            range = Integer32.divideUnsigned(range, pivotValue)
            base = Integer32.divideUnsigned(low, range)
            low = Integer32.remainderUnsigned(low, range)
        }
        val value = (base.toLong() and 0xFFFF_FFFFL) + (overflow.toLong() and 0xFFFF_FFFFL) * (pivotValue.toLong() and 0xFFFF_FFFFL)
        state.kSum += ((value + 1) / 2).toInt() - ((state.kSum + 16) ushr 5)
        if (Integer32.compareUnsigned(state.kSum, K_SUM_MIN_BOUNDARY[state.k]) < 0) {
            state.k--
        } else if (K_SUM_MIN_BOUNDARY[state.k + 1] != 0 && Integer32.compareUnsigned(state.kSum, K_SUM_MIN_BOUNDARY[state.k + 1]) >= 0) {
            state.k++
        }
        if (state.k !in 0..30) throw ApeFrameError()
        return if (value and 1L != 0L) (value shr 1) + 1 else -(value shr 1)
    }

    private companion object {
        const val CODE_BITS = 32
        const val TOP_VALUE = 1 shl (CODE_BITS - 1)
        const val EXTRA_BITS = (CODE_BITS - 2) % 8 + 1
        const val BOTTOM_VALUE = TOP_VALUE ushr 8
        const val RANGE_OVERFLOW_SHIFT = 16
        const val OVERFLOW_SIGNAL = 1
        const val OVERFLOW_PIVOT_VALUE = 32768
        const val MODEL_ELEMENTS = 64

        /** `RANGE_TOTAL_2` and `RANGE_WIDTH_2`, the model of files from version 3.99 on. */
        val RANGE_TOTAL = intArrayOf(
            0, 19578, 36160, 48417, 56323, 60899, 63265, 64435, 64971, 65232, 65351, 65416, 65447, 65466, 65476, 65482,
            65485, 65488, 65490, 65491, 65492, 65493, 65494, 65495, 65496, 65497, 65498, 65499, 65500, 65501, 65502, 65503,
            65504, 65505, 65506, 65507, 65508, 65509, 65510, 65511, 65512, 65513, 65514, 65515, 65516, 65517, 65518, 65519,
            65520, 65521, 65522, 65523, 65524, 65525, 65526, 65527, 65528, 65529, 65530, 65531, 65532, 65533, 65534, 65535, 65536,
        )
        val RANGE_WIDTH = IntArray(64) { RANGE_TOTAL[it + 1] - RANGE_TOTAL[it] }

        /** Which symbol each 16-bit total falls in, `RangeOverflowTable`. */
        val OVERFLOW_TABLE: ByteArray = ByteArray(65536).also { table ->
            var overflow = 0
            for (z in 0 until 65536) {
                if (z >= RANGE_TOTAL[overflow + 1]) overflow++
                table[z] = overflow.toByte()
            }
        }

        val K_SUM_MIN_BOUNDARY = IntArray(32) { if (it == 0 || it > 27) 0 else 1 shl (it + 4) }
    }
}

/** `CNNFilter<int, short>`: the adaptive filter of audio below 32 bits, in 16-bit storage. */
private class ApeNnFilter16(private val order: Int, private val shift: Int, private val interim: Boolean) {
    private val one = 1 shl (shift - 1)
    private val coefficients = ShortArray(order)
    private val input = ShortArray(WINDOW + order)
    private val delta = ShortArray(WINDOW + order)
    private var position = order
    private var runningAverage = 0

    fun flush() {
        coefficients.fill(0)
        for (i in 0..order) {
            input[i] = 0
            delta[i] = 0
        }
        position = order
        runningAverage = 0
    }

    fun decompress(value: Int): Int {
        var dot = 0
        val base = position - order
        for (j in 0 until order) dot += input[base + j] * coefficients[j]
        val output = if (interim) (value + ((dot.toLong() + one) shr shift)).toInt() else value + ((dot + one) shr shift)
        if (value < 0) {
            for (j in 0 until order) coefficients[j] = (coefficients[j] + delta[base + j]).toShort()
        } else if (value > 0) {
            for (j in 0 until order) coefficients[j] = (coefficients[j] - delta[base + j]).toShort()
        }
        val magnitude = if (output < 0) -output else output
        delta[position] = when {
            magnitude > runningAverage * 3 -> ((output shr 25) and 64) - 32
            magnitude > (runningAverage * 4) / 3 -> ((output shr 26) and 32) - 16
            magnitude > 0 -> ((output shr 27) and 16) - 8
            else -> 0
        }.toShort()
        runningAverage += (magnitude - runningAverage) / 16
        delta[position - 1] = (delta[position - 1].toInt() shr 1).toShort()
        delta[position - 2] = (delta[position - 2].toInt() shr 1).toShort()
        delta[position - 8] = (delta[position - 8].toInt() shr 1).toShort()
        input[position] = saturate(output.toLong())
        position++
        if (position == WINDOW + order) {
            input.copyInto(input, 0, position - order, position)
            delta.copyInto(delta, 0, position - order, position)
            position = order
        }
        return output
    }
}

/** `CNNFilter<int64, int>`: the adaptive filter of 32-bit audio. */
private class ApeNnFilter32(private val order: Int, private val shift: Int) {
    private val one = 1 shl (shift - 1)
    private val coefficients = IntArray(order)
    private val input = IntArray(WINDOW + order)
    private val delta = IntArray(WINDOW + order)
    private var position = order
    private var runningAverage = 0L

    fun flush() {
        coefficients.fill(0)
        for (i in 0..order) {
            input[i] = 0
            delta[i] = 0
        }
        position = order
        runningAverage = 0
    }

    fun decompress(value: Long): Long {
        var dot = 0L
        val base = position - order
        // Each product is formed in 32 bits, as the reference does, and summed in 64.
        for (j in 0 until order) dot += input[base + j] * coefficients[j]
        val output = value + ((dot + one) shr shift)
        if (value < 0) {
            for (j in 0 until order) coefficients[j] += delta[base + j]
        } else if (value > 0) {
            for (j in 0 until order) coefficients[j] -= delta[base + j]
        }
        val magnitude = if (output < 0) -output else output
        delta[position] = when {
            magnitude > runningAverage * 3 -> (((output shr 25) and 64) - 32).toInt()
            magnitude > (runningAverage * 4) / 3 -> (((output shr 26) and 32) - 16).toInt()
            magnitude > 0 -> (((output shr 27) and 16) - 8).toInt()
            else -> 0
        }
        runningAverage += (magnitude - runningAverage) / 16
        delta[position - 1] = delta[position - 1] shr 1
        delta[position - 2] = delta[position - 2] shr 1
        delta[position - 8] = delta[position - 8] shr 1
        input[position] = saturate(output).toInt()
        position++
        if (position == WINDOW + order) {
            input.copyInto(input, 0, position - order, position)
            delta.copyInto(delta, 0, position - order, position)
            position = order
        }
        return output
    }
}

private const val WINDOW = 512

/** `GetSaturatedShortFromInt`. */
private fun saturate(value: Long): Short {
    val short = value.toShort()
    return if (short.toLong() != value) ((value shr 63).toInt() xor 0x7FFF).toShort() else short
}

/** The filter orders and shifts of each compression level, from `CPredictorDecompress3950toCurrent`. */
private fun filtersFor(level: Int): List<Pair<Int, Int>> = when (level) {
    1000 -> emptyList()
    2000 -> listOf(16 to 11)
    3000 -> listOf(64 to 11)
    4000 -> listOf(256 to 13, 32 to 10)
    5000 -> listOf(1280 to 15, 256 to 13, 16 to 11)
    else -> throw CorruptStreamException("Monkey's Audio compression level $level does not exist")
}

private const val PREDICTION_WINDOW = 256
private const val HISTORY = 8

/** `CPredictorDecompress3950toCurrent<int, short>`, and its interim variant, for audio below 32 bits. */
private class ApePredictor(level: Int, private val bitsPerSample: Int, private val interim: Boolean) {
    private val filters = filtersFor(level).map { (order, shift) -> ApeNnFilter16(order, shift, interim) }
    private val predictionA = IntArray(PREDICTION_WINDOW + HISTORY)
    private val predictionB = IntArray(PREDICTION_WINDOW + HISTORY)
    private val adaptA = IntArray(PREDICTION_WINDOW + HISTORY)
    private val adaptB = IntArray(PREDICTION_WINDOW + HISTORY)
    private val ma = IntArray(8)
    private val mb = IntArray(8)
    private var position = HISTORY
    private var lastValueA = 0
    private var stage1A = 0
    private var stage1B = 0
    private var index = 0

    fun flush() {
        for (filter in filters) filter.flush()
        ma.fill(0)
        mb.fill(0)
        for (buffer in listOf(predictionA, predictionB, adaptA, adaptB)) for (i in 0..HISTORY) buffer[i] = 0
        position = HISTORY
        ma[0] = 360
        ma[1] = 317
        ma[2] = -109
        ma[3] = 98
        stage1A = 0
        stage1B = 0
        lastValueA = 0
        index = 0
    }

    fun decompress(inputA: Long, inputB: Long): Int {
        if (index == PREDICTION_WINDOW) {
            for (buffer in listOf(predictionA, predictionB, adaptA, adaptB)) buffer.copyInto(buffer, 0, position - HISTORY, position)
            position = HISTORY
            index = 0
        }
        var a = inputA.toInt()
        val b = inputB.toInt()
        // The filters run from the last built to the first.
        for (f in filters.indices.reversed()) a = filters[f].decompress(a)
        val p = position
        predictionA[p] = lastValueA
        predictionA[p - 1] = predictionA[p] - predictionA[p - 1]
        // Stage one for B: a scaled first-order filter run forwards.
        val filteredB = b - ((stage1B * 31) shr 5)
        stage1B = b
        predictionB[p] = filteredB
        predictionB[p - 1] = predictionB[p] - predictionB[p - 1]
        val current: Int
        if (bitsPerSample <= 16) {
            val sumA = predictionA[p] * ma[0] + predictionA[p - 1] * ma[1] + predictionA[p - 2] * ma[2] + predictionA[p - 3] * ma[3]
            val sumB = predictionB[p] * mb[0] + predictionB[p - 1] * mb[1] + predictionB[p - 2] * mb[2] + predictionB[p - 3] * mb[3] + predictionB[p - 4] * mb[4]
            current = a + ((sumA + (sumB shr 1)) shr 10)
        } else {
            val sumA = predictionA[p].toLong() * ma[0] + predictionA[p - 1].toLong() * ma[1] + predictionA[p - 2].toLong() * ma[2] + predictionA[p - 3].toLong() * ma[3]
            val sumB = predictionB[p].toLong() * mb[0] + predictionB[p - 1].toLong() * mb[1] + predictionB[p - 2].toLong() * mb[2] + predictionB[p - 3].toLong() * mb[3] + predictionB[p - 4].toLong() * mb[4]
            current = if (interim) {
                a + ((sumA + (sumB shr 1)) shr 10).toInt()
            } else {
                a + ((sumA.toInt() + (sumB.toInt() shr 1)) shr 10)
            }
        }
        adaptA[p] = if (predictionA[p] != 0) ((predictionA[p] shr 30) and 2) - 1 else 0
        adaptA[p - 1] = if (predictionA[p - 1] != 0) ((predictionA[p - 1] shr 30) and 2) - 1 else 0
        adaptB[p] = if (predictionB[p] != 0) ((predictionB[p] shr 30) and 2) - 1 else 0
        adaptB[p - 1] = if (predictionB[p - 1] != 0) ((predictionB[p - 1] shr 30) and 2) - 1 else 0
        val direction = if (a < 0) 1 else if (a > 0) -1 else 0
        for (k in 0 until 4) ma[k] += adaptA[p - k] * direction
        for (k in 0 until 5) mb[k] += adaptB[p - k] * direction
        stage1A = current + ((stage1A * 31) shr 5)
        lastValueA = current
        position++
        index++
        return stage1A
    }
}

/** `CPredictorDecompress3950toCurrent<int64, int>`, for 32-bit audio. */
private class ApePredictor32(level: Int) {
    private val filters = filtersFor(level).map { (order, shift) -> ApeNnFilter32(order, shift) }
    private val predictionA = LongArray(PREDICTION_WINDOW + HISTORY)
    private val predictionB = LongArray(PREDICTION_WINDOW + HISTORY)
    private val adaptA = LongArray(PREDICTION_WINDOW + HISTORY)
    private val adaptB = LongArray(PREDICTION_WINDOW + HISTORY)
    private val ma = LongArray(8)
    private val mb = LongArray(8)
    private var position = HISTORY
    private var lastValueA = 0L
    private var stage1A = 0
    private var stage1B = 0
    private var index = 0

    fun flush() {
        for (filter in filters) filter.flush()
        ma.fill(0)
        mb.fill(0)
        for (buffer in listOf(predictionA, predictionB, adaptA, adaptB)) for (i in 0..HISTORY) buffer[i] = 0
        position = HISTORY
        ma[0] = 360
        ma[1] = 317
        ma[2] = -109
        ma[3] = 98
        stage1A = 0
        stage1B = 0
        lastValueA = 0
        index = 0
    }

    fun decompress(inputA: Long, inputB: Long): Int {
        if (index == PREDICTION_WINDOW) {
            for (buffer in listOf(predictionA, predictionB, adaptA, adaptB)) buffer.copyInto(buffer, 0, position - HISTORY, position)
            position = HISTORY
            index = 0
        }
        var a = inputA
        val b = inputB.toInt()
        for (f in filters.indices.reversed()) a = filters[f].decompress(a)
        val p = position
        predictionA[p] = lastValueA
        predictionA[p - 1] = predictionA[p] - predictionA[p - 1]
        val filteredB = b.toLong() - ((stage1B.toLong() * 31) shr 5)
        stage1B = b
        predictionB[p] = filteredB
        predictionB[p - 1] = predictionB[p] - predictionB[p - 1]
        val sumA = predictionA[p] * ma[0] + predictionA[p - 1] * ma[1] + predictionA[p - 2] * ma[2] + predictionA[p - 3] * ma[3]
        val sumB = predictionB[p] * mb[0] + predictionB[p - 1] * mb[1] + predictionB[p - 2] * mb[2] + predictionB[p - 3] * mb[3] + predictionB[p - 4] * mb[4]
        val current = a + ((sumA + (sumB shr 1)) shr 10)
        adaptA[p] = if (predictionA[p] != 0L) ((predictionA[p] shr 30) and 2) - 1 else 0
        adaptA[p - 1] = if (predictionA[p - 1] != 0L) ((predictionA[p - 1] shr 30) and 2) - 1 else 0
        adaptB[p] = if (predictionB[p] != 0L) ((predictionB[p] shr 30) and 2) - 1 else 0
        adaptB[p - 1] = if (predictionB[p - 1] != 0L) ((predictionB[p - 1] shr 30) and 2) - 1 else 0
        val direction = if (a < 0) 1L else if (a > 0) -1L else 0L
        for (k in 0 until 4) ma[k] += adaptA[p - k] * direction
        for (k in 0 until 5) mb[k] += adaptB[p - k] * direction
        stage1A = (current + ((stage1A.toLong() * 31) shr 5)).toInt()
        lastValueA = current
        position++
        index++
        return stage1A
    }
}

/**
 * Decodes Monkey's Audio frames of files from version 3.99 on into the
 * integers a WAV file of the same audio holds: `CAPEDecompressCore` and
 * `CPrepare::Unprepare`, with the frame checksum over those integers'
 * little-endian bytes.
 *
 * 24-bit files written between Monkey's Audio 5.01 and 8.51 used wider
 * arithmetic in two places. Like the reference, a 24-bit frame that fails
 * is decoded again that way, and the file is decoded that way from then on.
 */
internal class ApeFrameDecoder(
    private val level: Int,
    private val bitsPerSample: Int,
    private val channels: Int,
) {
    private var interim = false
    private var predictors16: Array<ApePredictor>? = null
    private var predictors32: Array<ApePredictor32>? = null
    private val values = IntArray(channels)

    init {
        filtersFor(level)
        build()
    }

    private fun build() {
        if (bitsPerSample < 32) {
            predictors16 = Array(channels) { ApePredictor(level, bitsPerSample, interim) }
        } else {
            predictors32 = Array(channels) { ApePredictor32(level) }
        }
    }

    private fun predict(channel: Int, a: Long, b: Long): Int =
        predictors16?.get(channel)?.decompress(a, b) ?: predictors32!![channel].decompress(a, b)

    /**
     * Decodes [blocks] blocks from the first [available] bytes of [frame],
     * whose first [skipBytes] bytes come before the frame, into [out],
     * interleaved WAV integers. [length] is what the reference reads, which
     * may run past the end of the file.
     */
    fun decode(frame: ByteArray, available: Int, length: Int, skipBytes: Int, blocks: Int, out: IntArray) {
        while (true) {
            val passed = try {
                decodeOnce(frame, available, length, skipBytes, blocks, out)
            } catch (_: ApeFrameError) {
                false
            }
            if (passed) return
            if (!interim && bitsPerSample == 24) {
                interim = true
                build()
                continue
            }
            throw CorruptStreamException("a Monkey's Audio frame fails its checksum")
        }
    }

    private fun decodeOnce(frame: ByteArray, available: Int, length: Int, skipBytes: Int, blocks: Int, out: IntArray): Boolean {
        val bits = ApeBits(frame, minOf(available, length), length)
        bits.bitIndex = skipBytes * 8
        val coder = ApeRangeDecoder(bits)
        var storedCrc = bits.readBits(32)
        var special = 0
        if (storedCrc and Int.MIN_VALUE != 0) special = bits.readBits(32)
        storedCrc = storedCrc and 0x7FFFFFFF
        predictors16?.forEach { it.flush() }
        predictors32?.forEach { it.flush() }
        val states = Array(channels) { ApeRangeDecoder.State().also { s -> coder.flushState(s) } }
        coder.start()
        var lastX = 0
        var crc = -1
        for (block in 0 until blocks) {
            when {
                channels > 2 -> for (c in 0 until channels) values[c] = predict(c, coder.decode(states[c]), 0)
                channels == 2 -> when {
                    special and SPECIAL_LEFT_SILENCE != 0 && special and SPECIAL_RIGHT_SILENCE != 0 -> {
                        values[0] = 0
                        values[1] = 0
                    }
                    special and SPECIAL_PSEUDO_STEREO != 0 -> {
                        values[0] = predict(0, coder.decode(states[0]), 0)
                        values[1] = 0
                    }
                    else -> {
                        val y = coder.decode(states[1])
                        val x = coder.decode(states[0])
                        val yValue = predict(1, y, lastX.toLong())
                        val xValue = predict(0, x, yValue.toLong())
                        lastX = xValue
                        values[0] = xValue
                        values[1] = yValue
                    }
                }
                else -> values[0] = if (special and SPECIAL_MONO_SILENCE != 0) 0 else predict(0, coder.decode(states[0]), 0)
            }
            crc = unprepare(out, block * channels, crc)
        }
        coder.finish()
        return (crc.inv() ushr 1) == storedCrc
    }

    /** `Unprepare`: the pairs coded as a difference and a mean become channels again, as WAV integers. */
    private fun unprepare(out: IntArray, at: Int, crcIn: Int): Int {
        var crc = crcIn
        fun emit(index: Int, value: Int) {
            val sample = when (bitsPerSample) {
                8 -> ((value + 128) and 0xFF) - 128
                16 -> value.toShort().toInt()
                24 -> (value shl 8) shr 8
                else -> value
            }
            out[at + index] = sample
            for (b in 0 until bitsPerSample / 8) {
                val byte = if (bitsPerSample == 8) (sample + 128) and 0xFF else (sample ushr (8 * b)) and 0xFF
                crc = (crc ushr 8) xor CRC_TABLE[(crc xor byte) and 0xFF]
            }
        }
        fun pair(first: Int, checkOverflow: Boolean) {
            val a = values[first] - values[first + 1] / 2
            val b = a + values[first + 1]
            if (checkOverflow && (a < -32768 || a > 32767 || b < -32768 || b > 32767)) throw ApeFrameError()
            emit(first, a)
            emit(first + 1, b)
        }
        when {
            bitsPerSample == 32 -> if (channels == 2) pair(0, false) else for (c in 0 until channels) emit(c, values[c])
            channels > 2 && bitsPerSample != 8 && (channels == 4 || channels >= 6) -> {
                val check = bitsPerSample == 16
                pair(0, check)
                if (channels == 4) {
                    pair(2, check)
                } else {
                    for (c in 2..3) {
                        if (check && (values[c] < -32768 || values[c] > 32767)) throw ApeFrameError()
                        emit(c, values[c])
                    }
                    pair(4, check)
                    if (channels >= 8) pair(6, check)
                    val from = if (channels == 7) 7 else 8
                    for (c in from until channels) emit(c, values[c])
                }
            }
            channels == 2 -> pair(0, bitsPerSample == 16)
            else -> for (c in 0 until channels) emit(c, values[c])
        }
        return crc
    }

    companion object {
        private const val SPECIAL_MONO_SILENCE = 1
        private const val SPECIAL_LEFT_SILENCE = 1
        private const val SPECIAL_RIGHT_SILENCE = 2
        private const val SPECIAL_PSEUDO_STEREO = 4

        /** The reflected CRC-32 table, polynomial 0xEDB88320, as `CRC.cpp` builds it. */
        private val CRC_TABLE = IntArray(256) { n ->
            var c = n
            repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }
            c
        }

        /** `CFloatTransform::Process`: the stored integer of a float sample back to its bits. */
        fun floatBits(sample: Int): Int {
            var out = sample and 0xC3FFFFFF.toInt()
            out = out or ((sample and 0x3C000000).inv() xor 0xC3FFFFFF.toInt())
            if (out and Int.MIN_VALUE != 0) out = out.inv() or Int.MIN_VALUE
            return out
        }
    }
}

/**
 * Unsigned 32-bit helpers that every target has, without 64-bit integers,
 * which JavaScript emulates slowly. A quotient of two 32-bit integers is
 * exact as the floor of their correctly rounded double quotient: when it is
 * not a whole number it lies at least 1/b from one, and the rounding error
 * is at most 2^-53 of a quotient no larger than 2^32/b.
 */
private object Integer32 {
    private const val TWO_32 = 4294967296.0

    fun compareUnsigned(a: Int, b: Int): Int = (a xor Int.MIN_VALUE).compareTo(b xor Int.MIN_VALUE)

    private fun unsigned(a: Int): Double = if (a < 0) a + TWO_32 else a.toDouble()

    /** The reference divides by a range it has already checked; damage can still make it zero. */
    fun divideUnsigned(a: Int, b: Int): Int {
        if (b == 0) throw ApeFrameError()
        val quotient = floor(unsigned(a) / unsigned(b))
        return if (quotient >= 2147483648.0) (quotient - TWO_32).toInt() else quotient.toInt()
    }

    fun remainderUnsigned(a: Int, b: Int): Int = a - divideUnsigned(a, b) * b
}
