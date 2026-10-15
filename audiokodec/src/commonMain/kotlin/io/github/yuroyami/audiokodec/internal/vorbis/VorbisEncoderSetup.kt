package io.github.yuroyami.audiokodec.internal.vorbis

import io.github.yuroyami.audiokodec.DecodeOptions
import io.github.yuroyami.audiokodec.internal.bits.BitWriterLsb
import kotlin.math.abs
import kotlin.math.exp

/**
 * The encoder's fixed configuration: its codebooks, floors, residues,
 * channel coupling and modes, and the three header packets that describe
 * them to a decoder.
 *
 * Vorbis leaves all of this to the encoder and ships it in the setup header,
 * so none of it has to match any other encoder. The design is deliberately
 * plain:
 *
 * - Two modes, one per block size, each with its own mapping, floor and
 *   residue, because a floor's points and a residue's extent are measured in
 *   bins of one block size.
 * - Floor 1 with points spread evenly on the Bark scale, coded with one book.
 * - Residue type 2 for stereo, so the coupled pair shares a classification,
 *   and type 1 otherwise. Ten classes by the largest value in a partition,
 *   from all zero up to a three-pass cascade.
 * - Square polar coupling of each left and right pair, which is lossless on
 *   the integers the residue codes.
 *
 * The codeword lengths come from simple models of how the values fall, not
 * from training on any corpus: Laplacian distributions for the residue
 * values, and a falling distribution for the floor corrections and the
 * classes. A model that is a little off costs a few percent in size and
 * nothing in correctness, since the decoder reads whatever lengths the header
 * gives.
 */
internal class VorbisEncoderSetup(
    val channels: Int,
    val sampleRate: Int,
) {
    val shortBlock: Int = if (sampleRate > 50_000) 512 else 256
    val longBlock: Int = if (sampleRate > 50_000) 4096 else 2048

    val books: List<VorbisEncodeBook>

    /** The book every floor correction is coded with. */
    val floorBook: VorbisEncodeBook

    val shortFloor = VorbisFloorEncoder(shortBlock / 2, sampleRate, interiorPoints = 15, classDimensions = 1, minimumSpacing = 1)
    val longFloor = VorbisFloorEncoder(longBlock / 2, sampleRate, interiorPoints = 32, classDimensions = 2, minimumSpacing = 2)

    val residueType: Int = if (channels == 2) 2 else 1

    val shortResidue: VorbisResidueEncoder
    val longResidue: VorbisResidueEncoder

    /** Pairs of channels coupled as magnitude and angle, in the stream's channel order. */
    val couplings: List<Pair<Int, Int>> = couplingsFor(channels)

    init {
        val list = ArrayList<VorbisEncodeBook>()
        floorBook = VorbisEncodeBook.scalar(DoubleArray(VorbisFloorEncoder.RANGE) { floorWeight(it) })
        list.add(floorBook)

        val classCount = CLASS_MAXIMA.size
        list.add(
            VorbisEncodeBook.scalar(
                DoubleArray(classCount * classCount) { classPairWeight(it / classCount, it % classCount) },
                dimensions = 2,
            ),
        )
        val classbook = 1

        fun add(book: VorbisEncodeBook): Int {
            list.add(book)
            return list.size - 1
        }

        fun laplace(scale: Double): (Int) -> Double = { v -> exp(-abs(v) / scale) }
        val flat: (Int) -> Double = { 1.0 }

        val ones = add(VorbisEncodeBook.lattice(4, 1, 1, laplace(0.55)))
        val twos = add(VorbisEncodeBook.lattice(2, 2, 1, laplace(0.9)))
        val threes = add(VorbisEncodeBook.lattice(2, 3, 1, laplace(1.3)))
        val fives = add(VorbisEncodeBook.lattice(2, 5, 1, laplace(2.0)))
        val eights = add(VorbisEncodeBook.lattice(2, 8, 1, laplace(3.0)))
        val thirteens = add(VorbisEncodeBook.lattice(1, 13, 1, laplace(5.0)))
        val twentytwos = add(VorbisEncodeBook.lattice(1, 22, 1, laplace(8.0)))
        val coarse17 = add(VorbisEncodeBook.lattice(2, 8, 17, laplace(1.5)))
        val fine = add(VorbisEncodeBook.lattice(2, 8, 1, flat))
        val coarse289 = add(VorbisEncodeBook.lattice(1, 8, 289, laplace(1.0)))
        val middle17 = add(VorbisEncodeBook.lattice(2, 8, 17, flat))

        val classes = arrayOf(
            VorbisResidueEncoder.ResidueClass(0, intArrayOf()),
            VorbisResidueEncoder.ResidueClass(1, intArrayOf(ones)),
            VorbisResidueEncoder.ResidueClass(2, intArrayOf(twos)),
            VorbisResidueEncoder.ResidueClass(3, intArrayOf(threes)),
            VorbisResidueEncoder.ResidueClass(5, intArrayOf(fives)),
            VorbisResidueEncoder.ResidueClass(8, intArrayOf(eights)),
            VorbisResidueEncoder.ResidueClass(13, intArrayOf(thirteens)),
            VorbisResidueEncoder.ResidueClass(22, intArrayOf(twentytwos)),
            VorbisResidueEncoder.ResidueClass(8 * 17 + 8, intArrayOf(coarse17, fine)),
            VorbisResidueEncoder.ResidueClass(MAX_RESIDUE, intArrayOf(coarse289, middle17, fine)),
        )
        check(classes.size == classCount && classes.indices.all { classes[it].maximum == CLASS_MAXIMA[it] })
        books = list

        val partition = if (residueType == 2) 32 else 16
        fun residueFor(block: Int): VorbisResidueEncoder {
            val size = if (residueType == 2) block / 2 * channels else block / 2
            return VorbisResidueEncoder(residueType, 0, size - size % partition, partition, classes, classbook, list)
        }
        shortResidue = residueFor(shortBlock)
        longResidue = residueFor(longBlock)
    }

    fun identificationHeader(): ByteArray {
        val writer = BitWriterLsb(64)
        writeSignature(writer, 1)
        writer.writeBits(0, 32)
        writer.writeBits(channels, 8)
        writer.writeBits(sampleRate, 32)
        writer.writeBits(0, 32) // no stated maximum,
        writer.writeBits(0, 32) // nominal
        writer.writeBits(0, 32) // or minimum bitrate
        writer.writeBits(shortBlock.countTrailingZeroBits(), 4)
        writer.writeBits(longBlock.countTrailingZeroBits(), 4)
        writer.writeBits(1, 1)
        return writer.toByteArray()
    }

    fun setupHeader(): ByteArray {
        val writer = BitWriterLsb(16 * 1024)
        writeSignature(writer, 5)
        writer.writeBits(books.size - 1, 8)
        for (book in books) book.writeHeader(writer)
        writer.writeBits(0, 6) // one time domain transform,
        writer.writeBits(0, 16) // of the only type there is

        writer.writeBits(2 - 1, 6)
        for (floor in listOf(shortFloor, longFloor)) {
            writer.writeBits(1, 16)
            floor.writeHeader(writer, book = 0)
        }

        writer.writeBits(2 - 1, 6)
        for (residue in listOf(shortResidue, longResidue)) {
            writer.writeBits(residue.type, 16)
            residue.writeHeader(writer)
        }

        // Two mappings, the same apart from which floor and residue they use.
        writer.writeBits(2 - 1, 6)
        for (index in 0 until 2) {
            writer.writeBits(0, 16)
            writer.writeBits(0, 1) // one submap
            if (couplings.isEmpty()) {
                writer.writeBits(0, 1)
            } else {
                writer.writeBits(1, 1)
                writer.writeBits(couplings.size - 1, 8)
                val bits = VorbisCodebook.ilog(channels - 1)
                for ((magnitude, angle) in couplings) {
                    writer.writeBits(magnitude, bits)
                    writer.writeBits(angle, bits)
                }
            }
            writer.writeBits(0, 2)
            writer.writeBits(0, 8)
            writer.writeBits(index, 8)
            writer.writeBits(index, 8)
        }

        writer.writeBits(2 - 1, 6)
        for (index in 0 until 2) {
            writer.writeBits(index, 1)
            writer.writeBits(0, 16)
            writer.writeBits(0, 16)
            writer.writeBits(index, 8)
        }
        writer.writeBits(1, 1)
        return writer.toByteArray()
    }

    /**
     * The decoder's reading of this setup, which the encoder draws its
     * windows and floor curves from, so that what it assumes the decoder will
     * do is what the decoder does.
     */
    val decoderConfig: VorbisConfig by lazy {
        val identification = VorbisConfig.parseIdentification(identificationHeader(), DecodeOptions.DEFAULT, 0)
        VorbisConfig.parseSetup(setupHeader(), identification, DecodeOptions.DEFAULT, 0)
    }

    private fun writeSignature(writer: BitWriterLsb, type: Int) {
        writer.writeBits(type, 8)
        for (character in "vorbis") writer.writeBits(character.code, 8)
    }

    companion object {
        /** The largest residue magnitude the classes can code. */
        const val MAX_RESIDUE = 8 * 289 + 8 * 17 + 8

        /** The largest value each residue class codes, smallest first. */
        val CLASS_MAXIMA = intArrayOf(0, 1, 2, 3, 5, 8, 13, 22, 8 * 17 + 8, MAX_RESIDUE)

        /** How often each class is expected, before the pairing below. */
        private val CLASS_SHARE = doubleArrayOf(0.25, 0.2, 0.14, 0.1, 0.1, 0.08, 0.05, 0.04, 0.03, 0.01)

        /** Neighbouring partitions tend to share a class, so pairs that match are favoured. */
        private fun classPairWeight(first: Int, second: Int): Double =
            CLASS_SHARE[first] * CLASS_SHARE[second] * exp(-abs(first - second) / 2.0)

        /**
         * Floor corrections: zero, a point left on its predicted line, is the
         * most common, then corrections falling slowly with size. One and
         * two, a single step either way, are not written, because the fit
         * leaves a point that close on the line, so they only happen at the
         * edges of the range.
         */
        private fun floorWeight(value: Int): Double = when {
            value == 0 -> 0.4
            value < 3 -> 0.001
            else -> 0.6 * (1.0 - exp(-1.0 / 9.0)) * exp(-(value - 3) / 9.0) + 0.0005
        }

        /** The coupled pairs for each channel count, in Vorbis channel order. */
        fun couplingsFor(channels: Int): List<Pair<Int, Int>> = when (channels) {
            2 -> listOf(0 to 1)
            3 -> listOf(0 to 2)
            4 -> listOf(0 to 1, 2 to 3)
            5, 6, 7 -> listOf(0 to 2, 3 to 4)
            8 -> listOf(0 to 2, 3 to 4, 5 to 6)
            else -> emptyList()
        }
    }
}
