package io.github.yuroyami.audiokodecs.internal.vorbis

import io.github.yuroyami.audiokodecs.CorruptStreamException
import io.github.yuroyami.audiokodecs.DecodeOptions
import kotlin.math.PI
import kotlin.math.sin

/** What a Vorbis stream's first header says about the audio. */
internal class VorbisIdentification(
    val channels: Int,
    val sampleRate: Int,
    val bitrateMaximum: Int,
    val bitrateNominal: Int,
    val bitrateMinimum: Int,
    val shortBlock: Int,
    val longBlock: Int,
)

/**
 * How a block's channels are put back together.
 *
 * A mapping says which floor and which residue each channel uses, and which
 * pairs of channels were coupled. Coupling is Vorbis's stereo trick: instead of
 * coding left and right, it codes one channel's magnitude and the difference in
 * angle, which costs far less when the two are similar.
 */
internal class VorbisMapping(
    val couplingMagnitude: IntArray,
    val couplingAngle: IntArray,
    /** Which submap each channel belongs to. */
    val mux: IntArray,
    val submapFloor: IntArray,
    val submapResidue: IntArray,
) {
    val submaps: Int get() = submapFloor.size
}

/** One of a stream's coding modes: a block size and a mapping. */
internal class VorbisMode(
    val longBlock: Boolean,
    val mapping: Int,
)

/**
 * Everything a Vorbis stream's three headers set up.
 *
 * Vorbis puts almost all of its configuration in the file rather than in the
 * specification: the codebooks, the floor and residue layouts, the channel
 * coupling and the window sizes are all per-stream. That is why a Vorbis
 * decoder is mostly a header parser, and why a stream cannot be decoded from
 * the middle without its headers.
 *
 * Written from the Vorbis I specification, section 4.
 */
internal class VorbisConfig(
    val identification: VorbisIdentification,
    val codebooks: Array<VorbisCodebook>,
    val floors: Array<VorbisFloor>,
    val residues: Array<VorbisResidue>,
    val mappings: Array<VorbisMapping>,
    val modes: Array<VorbisMode>,
) {

    /** Bits needed to name a mode, which is how every audio packet starts. */
    val modeBits: Int = VorbisCodebook.ilog(modes.size - 1)

    private val shortWindow = window(identification.shortBlock, identification.shortBlock / 2, identification.shortBlock / 2)

    /**
     * The four long-block windows, by whether the block before and after are
     * long.
     *
     * A long block next to a short one has to narrow its slope to match, or the
     * two would not add back up to one. The four combinations are built once
     * here because they never change for a stream.
     */
    private val longWindows: Array<FloatArray> = Array(4) { index ->
        val previousLong = index and 1 != 0
        val nextLong = index and 2 != 0
        val n = identification.longBlock
        val left = if (previousLong) n / 2 else identification.shortBlock / 2
        val right = if (nextLong) n / 2 else identification.shortBlock / 2
        window(n, left, right)
    }

    fun windowFor(longBlock: Boolean, previousLong: Boolean, nextLong: Boolean): FloatArray =
        if (!longBlock) {
            shortWindow
        } else {
            longWindows[(if (previousLong) 1 else 0) or (if (nextLong) 2 else 0)]
        }

    /**
     * Builds one window.
     *
     * The shape is a sine of a squared sine, which is what makes the two halves
     * of an overlap add to exactly one. [left] and [right] are how many samples
     * each slope takes; the slopes sit centred on the quarter and three-quarter
     * points, with silence outside and unity between.
     */
    private fun window(n: Int, left: Int, right: Int): FloatArray {
        val result = FloatArray(n)
        val leftStart = n / 4 - left / 2
        val rightStart = 3 * n / 4 - right / 2
        for (i in 0 until left) {
            val inner = sin((i + 0.5) / left * (PI / 2))
            result[leftStart + i] = sin(PI / 2 * inner * inner).toFloat()
        }
        for (i in leftStart + left until rightStart) result[i] = 1f
        for (i in 0 until right) {
            val inner = sin((i + 0.5) / right * (PI / 2) + PI / 2)
            result[rightStart + i] = sin(PI / 2 * inner * inner).toFloat()
        }
        return result
    }

    companion object {

        /** The signature every Vorbis header packet carries after its type byte. */
        private const val SIGNATURE = "vorbis"

        fun parseIdentification(
            packet: ByteArray,
            options: DecodeOptions,
            at: Long,
        ): VorbisIdentification {
            val reader = VorbisBitReader(packet)
            expectHeader(reader, 1, at)
            val version = reader.readBits(32)
            if (version != 0) {
                throw CorruptStreamException(
                    "this file declares Vorbis version $version, and only version 0 exists",
                    at,
                )
            }
            val channels = reader.readBits(8)
            val sampleRate = reader.readBits(32)
            val bitrateMaximum = reader.readBits(32)
            val bitrateNominal = reader.readBits(32)
            val bitrateMinimum = reader.readBits(32)
            val shortBlock = 1 shl reader.readBits(4)
            val longBlock = 1 shl reader.readBits(4)
            val framing = reader.readBits(1)
            if (reader.endOfPacket) {
                throw CorruptStreamException("the Vorbis identification header is too short", at)
            }
            options.checkStreamShape(sampleRate, channels, at)
            if (shortBlock < 64 || longBlock > 8192 || shortBlock > longBlock) {
                throw CorruptStreamException(
                    "this Vorbis stream declares block sizes of $shortBlock and $longBlock",
                    at,
                )
            }
            if (framing == 0) {
                throw CorruptStreamException(
                    "the Vorbis identification header has no framing bit",
                    at,
                )
            }
            return VorbisIdentification(
                channels = channels,
                sampleRate = sampleRate,
                bitrateMaximum = bitrateMaximum,
                bitrateNominal = bitrateNominal,
                bitrateMinimum = bitrateMinimum,
                shortBlock = shortBlock,
                longBlock = longBlock,
            )
        }

        fun parseSetup(
            packet: ByteArray,
            identification: VorbisIdentification,
            options: DecodeOptions,
            at: Long,
        ): VorbisConfig {
            val reader = VorbisBitReader(packet)
            expectHeader(reader, 5, at)

            val codebookCount = reader.readBits(8) + 1
            // A file may declare 256 codebooks, and each one on its own can be
            // small enough to pass an allocation check that the set of them
            // would not. So the running total is what gets checked.
            var codebookBytes = 0L
            val codebooks = Array(codebookCount) {
                val book = VorbisCodebook.parse(reader, options, at, codebookBytes)
                codebookBytes += VorbisCodebook.codewordBytes(book.entries)
                options.checkAllocation(codebookBytes, "the codebooks of a Vorbis stream")
                book
            }

            // The time domain transform list exists only because the format
            // left room for transforms it never gained. Every entry must be
            // zero, and nothing is done with them.
            val timeCount = reader.readBits(6) + 1
            repeat(timeCount) {
                if (reader.readBits(16) != 0) {
                    throw CorruptStreamException(
                        "this Vorbis file declares a time domain transform, which the format does not define",
                        at,
                    )
                }
            }

            val floorCount = reader.readBits(6) + 1
            val floors = Array(floorCount) {
                VorbisFloor.parse(reader.readBits(16), reader, codebookCount, at)
            }

            val residueCount = reader.readBits(6) + 1
            val residues = Array(residueCount) {
                VorbisResidue.parse(reader.readBits(16), reader, codebooks, at)
            }

            val mappingCount = reader.readBits(6) + 1
            val mappings = Array(mappingCount) {
                parseMapping(reader, identification.channels, floorCount, residueCount, at)
            }

            val modeCount = reader.readBits(6) + 1
            val modes = Array(modeCount) {
                val longBlock = reader.readBits(1) == 1
                val windowType = reader.readBits(16)
                val transformType = reader.readBits(16)
                val mapping = reader.readBits(8)
                if (windowType != 0 || transformType != 0) {
                    throw CorruptStreamException(
                        "this Vorbis file declares window type $windowType and transform type " +
                            "$transformType, and the format defines only zero for both",
                        at,
                    )
                }
                if (mapping >= mappingCount) {
                    throw CorruptStreamException(
                        "a Vorbis mode names mapping $mapping of $mappingCount",
                        at,
                    )
                }
                VorbisMode(longBlock, mapping)
            }

            if (reader.readBits(1) == 0 || reader.endOfPacket) {
                throw CorruptStreamException("the Vorbis setup header has no framing bit", at)
            }

            return VorbisConfig(
                identification = identification,
                codebooks = codebooks,
                floors = floors,
                residues = residues,
                mappings = mappings,
                modes = modes,
            )
        }

        private fun parseMapping(
            reader: VorbisBitReader,
            channels: Int,
            floorCount: Int,
            residueCount: Int,
            at: Long,
        ): VorbisMapping {
            val type = reader.readBits(16)
            if (type != 0) {
                throw CorruptStreamException(
                    "this Vorbis file declares mapping type $type, and the format defines only zero",
                    at,
                )
            }
            val submaps = if (reader.readBits(1) == 1) reader.readBits(4) + 1 else 1
            var magnitude = IntArray(0)
            var angle = IntArray(0)
            if (reader.readBits(1) == 1) {
                val steps = reader.readBits(8) + 1
                val bits = VorbisCodebook.ilog(channels - 1)
                magnitude = IntArray(steps)
                angle = IntArray(steps)
                for (i in 0 until steps) {
                    magnitude[i] = reader.readBits(bits)
                    angle[i] = reader.readBits(bits)
                    if (magnitude[i] == angle[i] ||
                        magnitude[i] >= channels ||
                        angle[i] >= channels
                    ) {
                        throw CorruptStreamException(
                            "a Vorbis mapping couples channel ${magnitude[i]} with ${angle[i]} " +
                                "in a stream of $channels",
                            at,
                        )
                    }
                }
            }
            if (reader.readBits(2) != 0) {
                throw CorruptStreamException(
                    "a Vorbis mapping sets bits the format reserves",
                    at,
                )
            }
            val mux = IntArray(channels)
            if (submaps > 1) {
                for (i in 0 until channels) {
                    mux[i] = reader.readBits(4)
                    if (mux[i] >= submaps) {
                        throw CorruptStreamException(
                            "a Vorbis mapping puts channel $i in submap ${mux[i]} of $submaps",
                            at,
                        )
                    }
                }
            }
            val submapFloor = IntArray(submaps)
            val submapResidue = IntArray(submaps)
            for (i in 0 until submaps) {
                reader.readBits(8)
                submapFloor[i] = reader.readBits(8)
                submapResidue[i] = reader.readBits(8)
                if (submapFloor[i] >= floorCount || submapResidue[i] >= residueCount) {
                    throw CorruptStreamException(
                        "a Vorbis mapping names floor ${submapFloor[i]} and residue " +
                            "${submapResidue[i]}, of $floorCount and $residueCount",
                        at,
                    )
                }
            }
            if (reader.endOfPacket) {
                throw CorruptStreamException("a Vorbis mapping ends before it is complete", at)
            }
            return VorbisMapping(magnitude, angle, mux, submapFloor, submapResidue)
        }

        private fun expectHeader(reader: VorbisBitReader, type: Int, at: Long) {
            val packetType = reader.readBits(8)
            if (packetType != type) {
                throw CorruptStreamException(
                    "expected Vorbis header type $type, found $packetType",
                    at,
                )
            }
            for (character in SIGNATURE) {
                if (reader.readBits(8) != character.code) {
                    throw CorruptStreamException("a Vorbis header lacks its signature", at)
                }
            }
        }
    }
}
