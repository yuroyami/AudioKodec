package io.github.yuroyami.audiokodec

/**
 * What to write, and how.
 *
 * A target names the container and the coding together, because the two are
 * not independent: WAV can carry ADPCM and AIFF cannot, AU has its own short
 * list of codings, and each container spells the same coding differently. The
 * factory functions are the supported combinations, so a target that compiles
 * is one AudioKodec can write.
 */
public class EncodeTarget private constructor(
    /** The container to write. */
    public val container: AudioContainer,
    /** The coding of the samples inside it. */
    public val codec: AudioCodec,
    /** Bits per stored sample, or 0 where the coding fixes it. */
    public val bitDepth: Int,
    /**
     * Block size in bytes for the block-based codings, or 0 otherwise.
     *
     * For FLAC this carries the effort level instead, because FLAC's block size
     * is measured in samples and is not something a caller needs to choose.
     */
    public val blockAlign: Int,
    /** Noise to add when reducing the sample depth. */
    public val dither: Dither,
) {
    override fun toString(): String =
        "EncodeTarget(${container.displayName}, ${codec.displayName}, ${bitDepth}-bit)"

    public companion object {

        /**
         * WAV holding integer PCM at [bitDepth] bits.
         *
         * 8-bit is written unsigned and every wider depth signed, which is
         * what the format requires. [dither] is worth setting when the source
         * is float or a deeper integer than the target.
         */
        public fun wav(bitDepth: Int = 16, dither: Dither = Dither.None): EncodeTarget {
            require(bitDepth == 8 || bitDepth == 16 || bitDepth == 24 || bitDepth == 32) {
                "PCM in WAV is 8, 16, 24 or 32 bits, not $bitDepth"
            }
            return EncodeTarget(AudioContainer.WAV, AudioCodec.PCM_INTEGER, bitDepth, 0, dither)
        }

        /** WAV holding IEEE float samples, 32 or 64 bits. */
        public fun wavFloat(bitDepth: Int = 32): EncodeTarget {
            require(bitDepth == 32 || bitDepth == 64) {
                "float in WAV is 32 or 64 bits, not $bitDepth"
            }
            return EncodeTarget(AudioContainer.WAV, AudioCodec.PCM_FLOAT, bitDepth, 0, Dither.None)
        }

        /** WAV holding G.711 mu-law, the coding North American telephony uses. */
        public fun wavMuLaw(): EncodeTarget =
            EncodeTarget(AudioContainer.WAV, AudioCodec.MU_LAW, 8, 0, Dither.None)

        /** WAV holding G.711 A-law, the coding the rest of the world uses. */
        public fun wavALaw(): EncodeTarget =
            EncodeTarget(AudioContainer.WAV, AudioCodec.A_LAW, 8, 0, Dither.None)

        /**
         * WAV holding IMA ADPCM, at roughly a quarter the size of 16-bit PCM.
         *
         * [blockAlign] is the size of one block in bytes. It must leave room
         * for the four-byte header per channel and then a whole number of
         * four-byte groups per channel. Zero, the default, chooses a valid
         * size close to 1024 bytes after the channel count is known.
         */
        public fun wavImaAdpcm(blockAlign: Int = 0): EncodeTarget {
            require(blockAlign == 0 || blockAlign in 8..0xFFFF) {
                "an IMA ADPCM block is 8..65535 bytes, or 0 for automatic; not $blockAlign"
            }
            return EncodeTarget(
                AudioContainer.WAV, AudioCodec.IMA_ADPCM, 4, blockAlign, Dither.None,
            )
        }

        /**
         * WAV holding Microsoft ADPCM, at the same size as IMA with a
         * slightly better predictor.
         */
        public fun wavMsAdpcm(blockAlign: Int = 1024): EncodeTarget {
            require(blockAlign in 15..0xFFFF) {
                "a Microsoft ADPCM block is 15..65535 bytes, not $blockAlign"
            }
            return EncodeTarget(
                AudioContainer.WAV, AudioCodec.MS_ADPCM, 4, blockAlign, Dither.None,
            )
        }

        /** AIFF holding big-endian integer PCM at [bitDepth] bits. */
        public fun aiff(bitDepth: Int = 16, dither: Dither = Dither.None): EncodeTarget {
            require(bitDepth == 8 || bitDepth == 16 || bitDepth == 24 || bitDepth == 32) {
                "PCM in AIFF is 8, 16, 24 or 32 bits, not $bitDepth"
            }
            return EncodeTarget(AudioContainer.AIFF, AudioCodec.PCM_INTEGER, bitDepth, 0, dither)
        }

        /**
         * AIFF-C holding IEEE float samples.
         *
         * Float samples need the AIFF-C form, because plain AIFF has no way
         * to say the samples are not integers.
         */
        public fun aiffFloat(bitDepth: Int = 32): EncodeTarget {
            require(bitDepth == 32 || bitDepth == 64) {
                "float in AIFF-C is 32 or 64 bits, not $bitDepth"
            }
            return EncodeTarget(AudioContainer.AIFF, AudioCodec.PCM_FLOAT, bitDepth, 0, Dither.None)
        }

        /** AIFF-C holding G.711 mu-law. */
        public fun aiffMuLaw(): EncodeTarget =
            EncodeTarget(AudioContainer.AIFF, AudioCodec.MU_LAW, 8, 0, Dither.None)

        /** AIFF-C holding G.711 A-law. */
        public fun aiffALaw(): EncodeTarget =
            EncodeTarget(AudioContainer.AIFF, AudioCodec.A_LAW, 8, 0, Dither.None)

        /** AU holding big-endian integer PCM at [bitDepth] bits. */
        public fun au(bitDepth: Int = 16, dither: Dither = Dither.None): EncodeTarget {
            require(bitDepth == 8 || bitDepth == 16 || bitDepth == 24 || bitDepth == 32) {
                "PCM in AU is 8, 16, 24 or 32 bits, not $bitDepth"
            }
            return EncodeTarget(AudioContainer.AU, AudioCodec.PCM_INTEGER, bitDepth, 0, dither)
        }

        /** AU holding IEEE float samples. */
        public fun auFloat(bitDepth: Int = 32): EncodeTarget {
            require(bitDepth == 32 || bitDepth == 64) {
                "float in AU is 32 or 64 bits, not $bitDepth"
            }
            return EncodeTarget(AudioContainer.AU, AudioCodec.PCM_FLOAT, bitDepth, 0, Dither.None)
        }

        /** AU holding G.711 mu-law, which is what the format was built for. */
        public fun auMuLaw(): EncodeTarget =
            EncodeTarget(AudioContainer.AU, AudioCodec.MU_LAW, 8, 0, Dither.None)

        /** AU holding G.711 A-law. */
        public fun auALaw(): EncodeTarget =
            EncodeTarget(AudioContainer.AU, AudioCodec.A_LAW, 8, 0, Dither.None)

        /**
         * FLAC, at [bitDepth] bits and the given effort.
         *
         * [effort] runs from 0 to 8 and trades encoding time for file size, in
         * the same way the reference encoder's numbered levels do. It changes
         * how hard the encoder looks for a good predictor and how finely it
         * partitions the residual; it never changes what comes back out,
         * because the format is lossless at every setting.
         *
         * [dither] applies when the source is float or deeper than [bitDepth].
         * Reducing depth is the only lossy step in writing a FLAC file, and it
         * happens before the coding rather than inside it.
         */
        public fun flac(
            bitDepth: Int = 16,
            effort: Int = 5,
            dither: Dither = Dither.None,
        ): EncodeTarget {
            require(bitDepth == 8 || bitDepth == 16 || bitDepth == 24) {
                "AudioKodec writes 8, 16 or 24-bit FLAC, not $bitDepth"
            }
            require(effort in 0..8) { "the FLAC effort is 0 to 8, not $effort" }
            return EncodeTarget(AudioContainer.FLAC, AudioCodec.FLAC, bitDepth, effort, dither)
        }

        /**
         * Apple Lossless in an `.m4a` file, at [bitDepth] bits.
         *
         * The encoder is a translation of Apple's reference encoder in its
         * default mode, and writes the same packets for the same samples.
         * ALAC keeps up to eight channels in its own speaker order, which
         * the encoder takes from the source's layout: one, two, three, five
         * and six channels in their canonical layouts map onto it, and four,
         * seven and eight channels need a layout naming ALAC's speakers.
         *
         * The `moov` box goes after the audio, whose size is only known at the
         * end, so the sink must support patching. Tags go into the iTunes item
         * list and chapters into a Nero `chpl` list. [dither] applies when the
         * source is float or deeper than [bitDepth].
         */
        public fun alac(bitDepth: Int = 16, dither: Dither = Dither.None): EncodeTarget {
            require(bitDepth == 16 || bitDepth == 20 || bitDepth == 24 || bitDepth == 32) {
                "AudioKodec writes 16, 20, 24 or 32-bit ALAC, not $bitDepth"
            }
            return EncodeTarget(AudioContainer.MP4, AudioCodec.ALAC, bitDepth, 0, dither)
        }
    }
}
