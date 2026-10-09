package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.internal.io.SourceReader
import io.github.yuroyami.audiokodec.internal.ogg.OggReader
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

/** Complete streams assembled from Vorbis I sections 3, 6 and 8 and its Ogg mapping. */
internal object VorbisFloor0Fixtures {
    class Bits {
        private val data = ArrayList<Byte>()
        private var partial = 0
        private var filled = 0

        fun write(value: Long, count: Int): Bits {
            for (index in 0 until count) {
                partial = partial or (((value ushr index).toInt() and 1) shl filled)
                if (++filled == 8) {
                    data.add(partial.toByte())
                    partial = 0
                    filled = 0
                }
            }
            return this
        }

        fun write(value: Int, count: Int): Bits = write(value.toLong(), count)
        fun bytes(): ByteArray = ByteArray(data.size + if (filled > 0) 1 else 0) {
            if (it < data.size) data[it] else partial.toByte()
        }
    }

    fun file(order: Int = 4, lookup: Int = 1, channels: Int = 1, block: Int = 64,
             floorRate: Int = 8000, barkSize: Int = 32, amplitudeBits: Int = 4,
             amplitudeOffset: Int = 30, packets: Int = 6, silent: Boolean = false,
             scalarFloorBook: Boolean = false): ByteArray {
        val bits = Bits().write(2, 8)
        writeFloorBook(bits, order, lookup, scalarFloorBook)
        writeBook(bits, 1, 1, 0)
        writeBook(bits, 2, 4, 1, -1.0, 2.0, intArrayOf(0, 1))
        bits.write(0, 6).write(0, 16) // One unused time transform.
        bits.write(0, 6).write(0, 16) // One floor, type zero.
        floorHeader(bits, order, floorRate, barkSize, amplitudeBits, amplitudeOffset)
        bits.write(0, 6).write(1, 16) // One residue, type one.
            .write(0, 24).write(block / 2, 24).write(7, 24)
            .write(0, 6).write(1, 8).write(1, 3).write(0, 1).write(2, 8)
        bits.write(0, 6).write(0, 16) // One mapping, without coupling.
            .write(0, 1).write(0, 1).write(0, 2)
        bits.write(0, 8).write(0, 8).write(0, 8)
        bits.write(0, 6).write(0, 1).write(0, 16).write(0, 16).write(0, 8).write(1, 1)
        val setup = byteArrayOf(5) + "vorbis".encodeToByteArray() + bits.bytes()
        val exponent = log2(block.toDouble()).toInt()
        val identification = byteArrayOf(1) + "vorbis".encodeToByteArray() + u32(0) +
            byteArrayOf(channels.toByte()) + u32(8000) + ByteArray(12) +
            byteArrayOf((exponent or (exponent shl 4)).toByte(), 1)
        val comments = byteArrayOf(3) + "vorbis".encodeToByteArray() + u32(7) +
            "Fixture".encodeToByteArray() + u32(0) + byteArrayOf(1)
        val ogg = OggBuilder().page(791, 0, 0, 2, listOf(identification))
            .page(791, 1, 0, 0, listOf(comments, setup))
        for (packet in 0 until packets) {
            val audio = Bits().write(0, 1)
            for (channel in 0 until channels) {
                audio.write(if (silent) 0 else 2 + (packet + channel) % 3, amplitudeBits)
                if (!silent) {
                    audio.write(0, 1) // ilog(one floor book), including the reserved selector.
                    repeat((order + 2) / 3) { audio.write(0, if (lookup == 1) 3 else 1) }
                }
            }
            if (!silent) repeat(block / 16) { partition ->
                repeat(channels) { audio.write(0, 1) }
                repeat(channels) { channel ->
                    repeat(4) { vector -> audio.write(((packet + channel + partition + vector) % 2) * 3, 2) }
                }
            }
            ogg.page(791, packet + 2, packet.toLong() * block / 2,
                if (packet == packets - 1) 4 else 0, listOf(audio.bytes()))
        }
        return ogg.build()
    }

    fun floorBook(order: Int, lookup: Int, delta: Double = PI / (order + 1)): ByteArray =
        Bits().also { writeFloorBook(it, order, lookup, false, delta) }.bytes()

    private fun writeFloorBook(bits: Bits, order: Int, lookup: Int, scalar: Boolean, delta: Double = PI / (order + 1)) {
        if (lookup == 1) writeBook(bits, 3, 8, if (scalar) 0 else 1, 0.0, delta, intArrayOf(1, 2), sequence = true)
        else writeBook(bits, 3, 2, if (scalar) 0 else 2, 0.0, delta, intArrayOf(1, 2, 3, 1, 2, 3))
    }

    fun floorHeader(bits: Bits, order: Int, rate: Int = 8000, bark: Int = 32, amplitudeBits: Int = 4, offset: Int = 30) {
        bits.write(order, 8).write(rate, 16).write(bark, 16).write(amplitudeBits, 6)
            .write(offset, 8).write(0, 4).write(0, 8)
    }

    fun audioPackets(bytes: ByteArray): List<ByteArray> {
        val options = DecodeOptions.DEFAULT
        val ogg = OggReader(SourceReader(bytes.asAudioSource(), options), options)
        val packets = ogg.packets(ogg.readStreamHeads().first().serialNumber, 0)
        return buildList {
            while (true) {
                val packet = packets.next() ?: break
                add(packet.bytes.copyOf(packet.length))
            }
        }.drop(3)
    }

    fun writeBook(bits: Bits, dimensions: Int, entries: Int, lookup: Int,
                          minimum: Double = 0.0, delta: Double = 1.0,
                          values: IntArray = intArrayOf(), sequence: Boolean = false) {
        val length = maxOf(1, log2(entries.toDouble()).toInt())
        bits.write(0x564342, 24).write(dimensions, 16).write(entries, 24).write(0, 1).write(0, 1)
        repeat(entries) { bits.write(length - 1, 5) }
        bits.write(lookup, 4)
        if (lookup != 0) {
            bits.write(packFloat(minimum), 32).write(packFloat(delta), 32).write(3, 4).write(if (sequence) 1 else 0, 1)
            for (value in values) bits.write(value, 4)
        }
    }

    /** Inverse of the specification's custom mantissa/exponent unpacking. */
    private fun packFloat(value: Double): Int {
        if (value == 0.0) return 0
        val exponent = floor(log2(abs(value))).toInt() + 768
        val mantissa = (abs(value) * 2.0.pow(788 - exponent)).roundToInt()
        return (if (value < 0.0) Int.MIN_VALUE else 0) or (exponent shl 21) or mantissa
    }

    private fun u32(value: Int): ByteArray = ByteArray(4) { (value ushr (8 * it)).toByte() }
}
