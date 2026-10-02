package io.github.yuroyami.audiokodecs.internal.vorbis

import io.github.yuroyami.audiokodecs.CorruptStreamException
import io.github.yuroyami.audiokodecs.UnsupportedFeatureException
import kotlin.math.abs

/**
 * A Vorbis floor: the coarse shape of the spectrum.
 *
 * Vorbis splits a block's spectrum into a smooth curve and a residual. The
 * curve is the floor, and it costs a handful of bits because it is described by
 * a few points with straight lines between them. The residual is what is left
 * after dividing by it, which is close to flat and therefore cheap to code. The
 * decoder multiplies the two back together.
 *
 * Two kinds are defined. Floor 1, the line-segment kind, is what every encoder
 * written since 2001 emits. Floor 0 describes the curve as a set of line
 * spectral pairs instead; it is parsed here so a file using it is named
 * precisely, and refused rather than guessed at.
 *
 * Written from the Vorbis I specification, sections 6 and 7.
 */
internal sealed class VorbisFloor {

    /**
     * Reads this floor's part of an audio packet.
     *
     * Returns false when the floor is flagged unused, which means the channel
     * is silent in this block and no residual will be coded for it.
     */
    abstract fun decode(reader: VorbisBitReader, books: Array<VorbisCodebook>, state: FloorState): Boolean

    /** Writes the curve into [into] over `0 until half`. */
    abstract fun synthesise(state: FloorState, into: FloatArray, half: Int)

    /**
     * The per-channel scratch a floor decode fills.
     *
     * One of these exists per channel for the life of the stream, so decoding a
     * packet allocates nothing.
     */
    class FloorState(values: Int) {
        val y = IntArray(values)
        val finalY = IntArray(values)
        val step2 = BooleanArray(values)
    }

    class Floor1(
        private val partitionClasses: IntArray,
        private val classDimensions: IntArray,
        private val classSubclasses: IntArray,
        private val classMasterbooks: IntArray,
        private val subclassBooks: Array<IntArray>,
        private val multiplier: Int,
        /** The x coordinates of the curve's points, in the order they are coded. */
        private val xList: IntArray,
        /** The order that sorts [xList] ascending. */
        private val sortedOrder: IntArray,
        private val lowNeighbour: IntArray,
        private val highNeighbour: IntArray,
    ) : VorbisFloor() {

        val values: Int get() = xList.size

        private val range = RANGES[multiplier - 1]

        override fun decode(
            reader: VorbisBitReader,
            books: Array<VorbisCodebook>,
            state: FloorState,
        ): Boolean {
            if (reader.readBits(1) == 0) return false
            val bits = VorbisCodebook.ilog(range - 1)
            state.y[0] = reader.readBits(bits)
            state.y[1] = reader.readBits(bits)
            var offset = 2
            for (partition in partitionClasses) {
                val dimension = classDimensions[partition]
                val subclassBits = classSubclasses[partition]
                val mask = (1 shl subclassBits) - 1
                var value = 0
                if (subclassBits > 0) {
                    val book = books.getOrNull(classMasterbooks[partition])
                        ?: throw CorruptStreamException(
                            "a Vorbis floor names codebook ${classMasterbooks[partition]}, " +
                                "which the file does not have",
                            0,
                        )
                    value = book.decodeScalar(reader)
                    if (value < 0) return false
                }
                for (i in 0 until dimension) {
                    val bookNumber = subclassBooks[partition][value and mask]
                    value = value ushr subclassBits
                    state.y[offset + i] = if (bookNumber >= 0) {
                        val book = books.getOrNull(bookNumber)
                            ?: throw CorruptStreamException(
                                "a Vorbis floor names codebook $bookNumber, which the file does not have",
                                0,
                            )
                        val decoded = book.decodeScalar(reader)
                        if (decoded < 0) return false
                        decoded
                    } else {
                        0
                    }
                }
                offset += dimension
            }
            return !reader.endOfPacket
        }

        override fun synthesise(state: FloorState, into: FloatArray, half: Int) {
            val count = xList.size
            val y = state.y
            val finalY = state.finalY
            val step2 = state.step2

            // The points after the first two are coded as a correction to what
            // a straight line between their neighbours already predicts, so
            // most of them cost almost nothing. Undoing that is the first half
            // of the synthesis.
            finalY[0] = y[0].coerceIn(0, range - 1)
            finalY[1] = y[1].coerceIn(0, range - 1)
            step2[0] = true
            step2[1] = true
            for (i in 2 until count) {
                val low = lowNeighbour[i]
                val high = highNeighbour[i]
                val predicted = renderPoint(
                    xList[low], finalY[low], xList[high], finalY[high], xList[i],
                )
                val correction = y[i]
                val highRoom = range - predicted
                val lowRoom = predicted
                val room = if (highRoom < lowRoom) highRoom * 2 else lowRoom * 2
                if (correction != 0) {
                    step2[low] = true
                    step2[high] = true
                    step2[i] = true
                    finalY[i] = if (correction >= room) {
                        if (highRoom > lowRoom) {
                            correction - lowRoom + predicted
                        } else {
                            -correction + highRoom + predicted - 1
                        }
                    } else {
                        if (correction and 1 != 0) {
                            predicted - (correction + 1) / 2
                        } else {
                            predicted + correction / 2
                        }
                    }
                } else {
                    step2[i] = false
                    finalY[i] = predicted
                }
                // A height outside the range cannot be drawn, and it would go
                // on to skew the prediction for every point that leans on this
                // one. The format says to hold each height inside the range
                // here rather than to fix up the drawn curve afterwards.
                if (finalY[i] < 0) {
                    finalY[i] = 0
                } else if (finalY[i] >= range) {
                    finalY[i] = range - 1
                }
            }

            // The second half draws the lines. Points whose correction was zero
            // and that no neighbour leaned on are skipped, which is how the
            // format spends nothing on a stretch of curve that is already
            // straight.
            var lastX = 0
            var lastY = finalY[sortedOrder[0]] * multiplier
            var highX = 0
            var highY = lastY
            for (index in 1 until count) {
                val i = sortedOrder[index]
                if (!step2[i]) continue
                highY = finalY[i] * multiplier
                highX = xList[i]
                renderLine(lastX, lastY, highX, highY, into, half)
                lastX = highX
                lastY = highY
            }
            if (highX < half) renderLine(highX, highY, half, highY, into, half)

            for (i in 0 until half) {
                val index = into[i].toInt().coerceIn(0, 255)
                into[i] = INVERSE_DB[index]
            }
        }

        private fun renderPoint(x0: Int, y0: Int, x1: Int, y1: Int, x: Int): Int {
            val dy = y1 - y0
            val adx = x1 - x0
            if (adx == 0) return y0
            val ady = abs(dy)
            val error = ady * (x - x0)
            val offset = error / adx
            return if (dy < 0) y0 - offset else y0 + offset
        }

        /**
         * Draws a straight line into the curve using integers only.
         *
         * The format defines this with integer arithmetic on purpose: two
         * decoders have to agree on every sample, and a line drawn in floating
         * point would not give the same answer everywhere.
         */
        private fun renderLine(x0: Int, y0: Int, x1: Int, y1: Int, into: FloatArray, half: Int) {
            if (x1 <= x0) return
            val dy = y1 - y0
            val adx = x1 - x0
            var ady = abs(dy)
            val base = dy / adx
            var x = x0
            var y = y0
            var error = 0
            val stepUp = if (dy < 0) base - 1 else base + 1
            ady -= abs(base) * adx
            if (x < half) into[x] = y.toFloat()
            x++
            while (x < x1) {
                error += ady
                if (error >= adx) {
                    error -= adx
                    y += stepUp
                } else {
                    y += base
                }
                if (x >= half) break
                into[x] = y.toFloat()
                x++
            }
        }
    }

    /**
     * The line spectral pair floor, parsed and refused.
     *
     * No encoder has produced this since the format's beta releases in 2001,
     * and none exists today to produce a file to test a decoder against. Its
     * header is read so that a file using it is named exactly rather than
     * failing somewhere further in, and it is refused rather than implemented
     * from a formula nothing could check.
     */
    class Floor0(
        val order: Int,
        val rate: Int,
        val barkMapSize: Int,
        val amplitudeBits: Int,
        val amplitudeOffset: Int,
        val bookList: IntArray,
    ) : VorbisFloor() {

        override fun decode(
            reader: VorbisBitReader,
            books: Array<VorbisCodebook>,
            state: FloorState,
        ): Boolean = throw UnsupportedFeatureException(
            "Vorbis floor 0",
            "this Vorbis stream uses the line spectral pair floor, which only the " +
                "format's 2001 beta encoders ever produced",
        )

        override fun synthesise(state: FloorState, into: FloatArray, half: Int): Unit =
            throw UnsupportedFeatureException(
                "Vorbis floor 0",
                "this Vorbis stream uses the line spectral pair floor",
            )
    }

    companion object {

        private val RANGES = intArrayOf(256, 128, 86, 64)

        /**
         * Turns a floor's quantised height into a linear amplitude.
         *
         * Floor 1 codes heights in decibels, and this is the table that undoes
         * that: 256 entries about 0.547 dB apart, ending at unity, printed in
         * full in the specification.
         *
         * The values are copied rather than computed. They are very nearly
         * `10^((index - 255) * 0.546875 / 20)`, but not exactly: the published
         * constants differ from that expression by up to seven parts in ten
         * million, which no arrangement of the arithmetic reproduces. That is
         * far too small to hear, and it is still the difference between
         * agreeing with every other decoder and not.
         */
        private val INVERSE_DB: FloatArray = floatArrayOf(
            1.0649863e-07f, 1.1341951e-07f, 1.2079015e-07f, 1.2863978e-07f, 1.3699951e-07f,
            1.4590251e-07f, 1.5538408e-07f, 1.6548181e-07f, 1.7623575e-07f, 1.8768855e-07f,
            1.9988561e-07f, 2.1287530e-07f, 2.2670913e-07f, 2.4144197e-07f, 2.5713223e-07f,
            2.7384213e-07f, 2.9163793e-07f, 3.1059021e-07f, 3.3077411e-07f, 3.5226968e-07f,
            3.7516214e-07f, 3.9954229e-07f, 4.2550680e-07f, 4.5315863e-07f, 4.8260743e-07f,
            5.1396998e-07f, 5.4737065e-07f, 5.8294187e-07f, 6.2082472e-07f, 6.6116941e-07f,
            7.0413592e-07f, 7.4989464e-07f, 7.9862701e-07f, 8.5052630e-07f, 9.0579828e-07f,
            9.6466216e-07f, 1.0273513e-06f, 1.0941144e-06f, 1.1652161e-06f, 1.2409384e-06f,
            1.3215816e-06f, 1.4074654e-06f, 1.4989305e-06f, 1.5963394e-06f, 1.7000785e-06f,
            1.8105592e-06f, 1.9282195e-06f, 2.0535261e-06f, 2.1869758e-06f, 2.3290978e-06f,
            2.4804557e-06f, 2.6416497e-06f, 2.8133190e-06f, 2.9961443e-06f, 3.1908506e-06f,
            3.3982101e-06f, 3.6190449e-06f, 3.8542308e-06f, 4.1047004e-06f, 4.3714470e-06f,
            4.6555282e-06f, 4.9580707e-06f, 5.2802740e-06f, 5.6234160e-06f, 5.9888572e-06f,
            6.3780469e-06f, 6.7925283e-06f, 7.2339451e-06f, 7.7040476e-06f, 8.2047000e-06f,
            8.7378876e-06f, 9.3057248e-06f, 9.9104632e-06f, 1.0554501e-05f, 1.1240392e-05f,
            1.1970856e-05f, 1.2748789e-05f, 1.3577278e-05f, 1.4459606e-05f, 1.5399272e-05f,
            1.6400004e-05f, 1.7465768e-05f, 1.8600792e-05f, 1.9809576e-05f, 2.1096914e-05f,
            2.2467911e-05f, 2.3928002e-05f, 2.5482978e-05f, 2.7139006e-05f, 2.8902651e-05f,
            3.0780908e-05f, 3.2781225e-05f, 3.4911534e-05f, 3.7180282e-05f, 3.9596466e-05f,
            4.2169667e-05f, 4.4910090e-05f, 4.7828601e-05f, 5.0936773e-05f, 5.4246931e-05f,
            5.7772202e-05f, 6.1526565e-05f, 6.5524908e-05f, 6.9783085e-05f, 7.4317983e-05f,
            7.9147585e-05f, 8.4291040e-05f, 8.9768747e-05f, 9.5602426e-05f, 0.00010181521f,
            0.00010843174f, 0.00011547824f, 0.00012298267f, 0.00013097477f, 0.00013948625f,
            0.00014855085f, 0.00015820453f, 0.00016848555f, 0.00017943469f, 0.00019109536f,
            0.00020351382f, 0.00021673929f, 0.00023082423f, 0.00024582449f, 0.00026179955f,
            0.00027881276f, 0.00029693158f, 0.00031622787f, 0.00033677814f, 0.00035866388f,
            0.00038197188f, 0.00040679456f, 0.00043323036f, 0.00046138411f, 0.00049136745f,
            0.00052329927f, 0.00055730621f, 0.00059352311f, 0.00063209358f, 0.00067317058f,
            0.00071691700f, 0.00076350630f, 0.00081312324f, 0.00086596457f, 0.00092223983f,
            0.00098217216f, 0.0010459992f, 0.0011139742f, 0.0011863665f, 0.0012634633f,
            0.0013455702f, 0.0014330129f, 0.0015261382f, 0.0016253153f, 0.0017309374f,
            0.0018434235f, 0.0019632195f, 0.0020908006f, 0.0022266726f, 0.0023713743f,
            0.0025254795f, 0.0026895994f, 0.0028643847f, 0.0030505286f, 0.0032487691f,
            0.0034598925f, 0.0036847358f, 0.0039241906f, 0.0041792066f, 0.0044507950f,
            0.0047400328f, 0.0050480668f, 0.0053761186f, 0.0057254891f, 0.0060975636f,
            0.0064938176f, 0.0069158225f, 0.0073652516f, 0.0078438871f, 0.0083536271f,
            0.0088964928f, 0.009474637f, 0.010090352f, 0.010746080f, 0.011444421f,
            0.012188144f, 0.012980198f, 0.013823725f, 0.014722068f, 0.015678791f, 0.016697687f,
            0.017782797f, 0.018938423f, 0.020169149f, 0.021479854f, 0.022875735f, 0.024362330f,
            0.025945531f, 0.027631618f, 0.029427276f, 0.031339626f, 0.033376252f, 0.035545228f,
            0.037855157f, 0.040315199f, 0.042935108f, 0.045725273f, 0.048696758f, 0.051861348f,
            0.055231591f, 0.058820850f, 0.062643361f, 0.066714279f, 0.071049749f, 0.075666962f,
            0.080584227f, 0.085821044f, 0.091398179f, 0.097337747f, 0.10366330f, 0.11039993f,
            0.11757434f, 0.12521498f, 0.13335215f, 0.14201813f, 0.15124727f, 0.16107617f,
            0.17154380f, 0.18269168f, 0.19456402f, 0.20720788f, 0.22067342f, 0.23501402f,
            0.25028656f, 0.26655159f, 0.28387361f, 0.30232132f, 0.32196786f, 0.34289114f,
            0.36517414f, 0.38890521f, 0.41417847f, 0.44109412f, 0.46975890f, 0.50028648f,
            0.53279791f, 0.56742212f, 0.60429640f, 0.64356699f, 0.68538959f, 0.72993007f,
            0.77736504f, 0.82788260f, 0.88168307f, 0.9389798f, 1.0f
        )

        fun parse(
            type: Int,
            reader: VorbisBitReader,
            codebookCount: Int,
            at: Long,
        ): VorbisFloor = when (type) {
            0 -> parseFloor0(reader, codebookCount, at)
            1 -> parseFloor1(reader, codebookCount, at)
            else -> throw CorruptStreamException(
                "this Vorbis file declares floor type $type, which the format does not define",
                at,
            )
        }

        private fun parseFloor0(reader: VorbisBitReader, codebookCount: Int, at: Long): Floor0 {
            val order = reader.readBits(8)
            val rate = reader.readBits(16)
            val barkMapSize = reader.readBits(16)
            val amplitudeBits = reader.readBits(6)
            val amplitudeOffset = reader.readBits(8)
            val bookCount = reader.readBits(4) + 1
            val books = IntArray(bookCount) {
                val book = reader.readBits(8)
                if (book >= codebookCount) {
                    throw CorruptStreamException(
                        "a Vorbis floor names codebook $book of $codebookCount",
                        at,
                    )
                }
                book
            }
            return Floor0(order, rate, barkMapSize, amplitudeBits, amplitudeOffset, books)
        }

        private fun parseFloor1(reader: VorbisBitReader, codebookCount: Int, at: Long): Floor1 {
            val partitions = reader.readBits(5)
            val partitionClasses = IntArray(partitions) { reader.readBits(4) }
            var maximumClass = -1
            for (partition in partitionClasses) {
                if (partition > maximumClass) maximumClass = partition
            }

            val classDimensions = IntArray(maximumClass + 1)
            val classSubclasses = IntArray(maximumClass + 1)
            val classMasterbooks = IntArray(maximumClass + 1) { -1 }
            val subclassBooks = Array(maximumClass + 1) { IntArray(0) }
            for (i in 0..maximumClass) {
                classDimensions[i] = reader.readBits(3) + 1
                classSubclasses[i] = reader.readBits(2)
                if (classSubclasses[i] != 0) {
                    classMasterbooks[i] = reader.readBits(8)
                    if (classMasterbooks[i] >= codebookCount) {
                        throw CorruptStreamException(
                            "a Vorbis floor names codebook ${classMasterbooks[i]} of $codebookCount",
                            at,
                        )
                    }
                }
                subclassBooks[i] = IntArray(1 shl classSubclasses[i]) {
                    val book = reader.readBits(8) - 1
                    if (book >= codebookCount) {
                        throw CorruptStreamException(
                            "a Vorbis floor names codebook $book of $codebookCount",
                            at,
                        )
                    }
                    book
                }
            }

            val multiplier = reader.readBits(2) + 1
            val rangeBits = reader.readBits(4)

            var count = 2
            for (partition in partitionClasses) count += classDimensions[partition]
            if (count > 65) {
                throw CorruptStreamException(
                    "a Vorbis floor declares $count points, and the format allows 65",
                    at,
                )
            }
            val xList = IntArray(count)
            xList[0] = 0
            xList[1] = 1 shl rangeBits
            var write = 2
            for (partition in partitionClasses) {
                repeat(classDimensions[partition]) {
                    xList[write++] = reader.readBits(rangeBits)
                }
            }
            if (reader.endOfPacket) {
                throw CorruptStreamException("a Vorbis floor ends inside its point list", at)
            }
            for (i in 0 until count) {
                for (j in i + 1 until count) {
                    if (xList[i] == xList[j]) {
                        throw CorruptStreamException(
                            "a Vorbis floor puts two points at x = ${xList[i]}",
                            at,
                        )
                    }
                }
            }

            // The points are drawn in ascending x order but coded in another,
            // and each one is predicted from the nearest coded point on either
            // side of it. Both orders are worked out once here rather than on
            // every packet.
            val sortedOrder = (0 until count).sortedBy { xList[it] }.toIntArray()
            val lowNeighbour = IntArray(count)
            val highNeighbour = IntArray(count)
            for (i in 2 until count) {
                var low = 0
                var high = 1
                for (j in 0 until i) {
                    if (xList[j] < xList[i] && xList[j] > xList[low]) low = j
                    if (xList[j] > xList[i] && xList[j] < xList[high]) high = j
                }
                lowNeighbour[i] = low
                highNeighbour[i] = high
            }

            return Floor1(
                partitionClasses = partitionClasses,
                classDimensions = classDimensions,
                classSubclasses = classSubclasses,
                classMasterbooks = classMasterbooks,
                subclassBooks = subclassBooks,
                multiplier = multiplier,
                xList = xList,
                sortedOrder = sortedOrder,
                lowNeighbour = lowNeighbour,
                highNeighbour = highNeighbour,
            )
        }
    }
}
