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
     * For FLAC and WavPack this carries the effort level instead, because
     * their blocks are measured in samples and are not something a caller
     * needs to choose. For Opus it carries the frame length in samples at
     * 48 kHz.
     */
    public val blockAlign: Int,
    /** Noise to add when reducing the sample depth. */
    public val dither: Dither,
    /**
     * The quality setting of a lossy coding that takes one, or null.
     *
     * For Vorbis this is on oggenc's scale, 0 to 10.
     */
    public val quality: Float? = null,
    /**
     * The target bit rate in bits per second for the codings that aim at
     * one, or 0 for the others.
     */
    public val bitrate: Int = 0,
    /** How a lossy coding holds to [bitrate], or null for the codings without one. */
    public val bitrateMode: BitrateMode? = null,
) {
    override fun toString(): String = if (bitrateMode != null) {
        "EncodeTarget(${container.displayName}, ${codec.displayName}, $bitrate bit/s $bitrateMode)"
    } else if (quality != null) {
        "EncodeTarget(${container.displayName}, ${codec.displayName}, quality $quality)"
    } else {
        "EncodeTarget(${container.displayName}, ${codec.displayName}, ${bitDepth}-bit)"
    }

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
         * Opus in an Ogg file, at [bitrate] bits per second.
         *
         * The encoder is a translation of libopus 1.5.2's CELT encoder, the
         * transform half of Opus, which libopus uses for music, at its
         * highest complexity. Driven as libopus's CELT-only mode drives it,
         * minus the tonality analysis libopus adds above complexity 6, it
         * writes the same packets byte for byte. It codes mono and stereo,
         * always at full bandwidth (20 kHz), in frames of [frameMillis], 20
         * or 10 ms, with transient detection and short blocks, the pitch
         * prefilter, dynamic allocation, and mid/side and intensity stereo
         * decided as libopus decides them. Opus works at 48 kHz, so input at
         * any other rate is first converted with [Resampler], and the file
         * records the original rate for players that convert back.
         *
         * With [vbr], the default, each packet's size follows the signal and
         * the long-run average follows [bitrate], moving bits freely between
         * loud and quiet passages, as `opusenc` and ffmpeg do by default.
         * With [constrainedVbr] as well, libopus's own library default, the
         * encoder never runs more than one frame's worth of bits ahead of the
         * rate, which suits streaming over a link of that rate. Without [vbr]
         * every packet is the same size. [bitrate] is 6 to 510 kbit/s, and
         * with [vbr] CELT spends at most 260 kbit/s per channel.
         *
         * The identification header's pre-skip is the encoder's delay, 120
         * samples, and the last page's granule position marks where the
         * input ended, so a decoder that applies both, as RFC 7845 requires,
         * returns exactly as many samples as went in, aligned with them, or
         * the input's length at 48 kHz when it was resampled. Pages are
         * written as they fill, so any sink will do. Tags go into the
         * OpusTags header, and pictures into it as `METADATA_BLOCK_PICTURE`.
         *
         * Not implemented: the speech (SILK) and hybrid modes, more than two
         * channels, which needs the multistream mapping and is refused with
         * [UnsupportedFeatureException], and the analysis with which libopus
         * narrows the bandwidth and the stereo image at low rates. Integer
         * sources are refused; pass float PCM. Like any lossy coding it does
         * not return the input exactly.
         */
        public fun opus(
            bitrate: Int = 128_000,
            vbr: Boolean = true,
            constrainedVbr: Boolean = false,
            frameMillis: Int = 20,
        ): EncodeTarget {
            require(bitrate in 6_000..510_000) { "the Opus bit rate is 6000 to 510000 bit/s, not $bitrate" }
            require(frameMillis == 10 || frameMillis == 20) { "Opus frames are 10 or 20 ms here, not $frameMillis" }
            val mode = when {
                !vbr -> BitrateMode.CONSTANT
                constrainedVbr -> BitrateMode.CONSTRAINED_VARIABLE
                else -> BitrateMode.VARIABLE
            }
            return EncodeTarget(
                AudioContainer.OGG, AudioCodec.OPUS, 0, 48 * frameMillis, Dither.None,
                bitrate = bitrate, bitrateMode = mode,
            )
        }

        /**
         * Lossless WavPack, at [bitDepth] bits and the given effort.
         *
         * [effort] runs from 0 to 3 and picks the reference encoder's fast,
         * normal, high and very high modes, which `wavpack` selects with
         * `-f`, no option, `-h` and `-hh`. Each mode uses a longer
         * decorrelation filter than the one before, and the two high modes
         * use longer blocks; none changes what comes back out. The encoder
         * is a translation of the reference library and writes the same
         * blocks it does in those modes. Hybrid lossy mode is not offered.
         *
         * Up to eight channels are written. WavPack keeps the speakers of a
         * WAV channel mask first, in the mask's order, and any unassigned
         * channels after them, so a layout in any other order is refused
         * rather than written to the wrong speakers.
         *
         * The first block holds a RIFF header, so `wvunpack` restores a WAV
         * file, and the last the MD5 sum of the audio. When the source's
         * frame count is unknown, the length is stated as unknown and
         * corrected at the end if the sink can be patched. Tags go into an
         * APEv2 tag after the audio. [dither] applies when the source is
         * float or deeper than [bitDepth].
         */
        public fun wavPack(
            bitDepth: Int = 16,
            effort: Int = 1,
            dither: Dither = Dither.None,
        ): EncodeTarget {
            require(bitDepth == 8 || bitDepth == 16 || bitDepth == 24 || bitDepth == 32) {
                "AudioKodec writes 8, 16, 24 or 32-bit WavPack, not $bitDepth"
            }
            require(effort in 0..3) { "the WavPack effort is 0 to 3, not $effort" }
            return EncodeTarget(AudioContainer.WAVPACK, AudioCodec.WAVPACK, bitDepth, effort, dither)
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

        /**
         * Ogg Vorbis at [quality], on oggenc's scale from 0 to 10.
         *
         * Vorbis is lossy: what comes back is close to what went in, not the
         * same, and a higher quality buys closeness with size. The encoder
         * aims at a quality rather than a bitrate, so the size follows the
         * material. See `docs/encoding.md` for what each setting measured.
         *
         * One to eight channels in Vorbis's speaker layouts, at 8 to 192 kHz.
         * The encoder reads the source once from start to end and never
         * patches what it has written, so any sink will do, and the decoded
         * length is exactly the source's. Tags go into the Vorbis comment
         * header, pictures included; chapters are refused.
         */
        public fun vorbis(quality: Float = 4f): EncodeTarget {
            require(quality in 0f..10f) { "the Vorbis quality is 0 to 10, not $quality" }
            return EncodeTarget(AudioContainer.OGG, AudioCodec.VORBIS, 0, 0, Dither.None, quality)
        }
    }
}

/** How a lossy encoder holds to its target bit rate. */
public enum class BitrateMode {
    /** Every packet the same size, whatever the signal. */
    CONSTANT,

    /**
     * Packet sizes follow the signal, but the encoder never runs more than
     * about one frame's worth of bits ahead of the rate, so a stream at the
     * target rate never needs a larger buffer.
     */
    CONSTRAINED_VARIABLE,

    /** Packet sizes follow the signal, and only the long-run average follows the rate. */
    VARIABLE,
}
