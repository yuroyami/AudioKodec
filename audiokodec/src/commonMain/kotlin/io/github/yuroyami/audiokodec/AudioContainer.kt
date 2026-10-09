package io.github.yuroyami.audiokodec

/**
 * A container or bare codec stream that the sniffer can name.
 *
 * Sniffing is not decoding. This list is longer than the list of formats
 * AudioKodec can read, on purpose: naming a file AudioKodec cannot decode lets a
 * caller route it somewhere else instead of guessing from the extension.
 * [AudioContainer.isDecodable] answers the other question.
 */
public enum class AudioContainer(
    /** A short name suitable for an error message or a UI label. */
    public val displayName: String,
    /** The extension most commonly used, without a dot. */
    public val extension: String,
) {
    /** RIFF WAVE, including WAVE_FORMAT_EXTENSIBLE. */
    WAV("WAV", "wav"),

    /** RF64 and BW64: RIFF with 64-bit sizes, for files past 4 GiB. */
    RF64("RF64", "wav"),

    /** Sony Wave64. */
    WAVE64("Wave64", "w64"),

    /** Apple AIFF and AIFF-C. */
    AIFF("AIFF", "aiff"),

    /** Sun and NeXT AU. */
    AU("AU", "au"),

    /** Apple Core Audio Format. */
    CAF("CAF", "caf"),

    /** Native FLAC, meaning the `fLaC` stream, not FLAC inside Ogg. */
    FLAC("FLAC", "flac"),

    /** An Ogg bitstream. The codec inside is reported separately. */
    OGG("Ogg", "ogg"),

    /** An MPEG audio stream: Layer I, II or III. */
    MPEG_AUDIO("MPEG audio", "mp3"),

    /** Raw AAC in ADTS framing. */
    ADTS("AAC (ADTS)", "aac"),

    /** Raw AAC in ADIF framing. */
    ADIF("AAC (ADIF)", "aac"),

    /** ISO base media: MP4, M4A, M4B, 3GP. */
    MP4("MP4", "m4a"),

    /** Matroska and WebM. */
    MATROSKA("Matroska", "mka"),

    /** Monkey's Audio. */
    APE("Monkey's Audio", "ape"),

    /** WavPack. */
    WAVPACK("WavPack", "wv"),

    /** Musepack, both SV7 and SV8. */
    MUSEPACK("Musepack", "mpc"),

    /** True Audio. */
    TTA("True Audio", "tta"),

    /** Sony DSF, one of the two DSD containers. */
    DSF("DSF", "dsf"),

    /** Philips DSDIFF, the other DSD container. */
    DFF("DSDIFF", "dff"),

    /** AMR narrowband. */
    AMR_NB("AMR-NB", "amr"),

    /** AMR wideband. */
    AMR_WB("AMR-WB", "awb"),

    /** iLBC in the storage format of RFC 3952. */
    ILBC("iLBC", "lbc"),

    /** Raw AC-3 or Enhanced AC-3. */
    AC3("AC-3", "ac3"),

    /** Raw DTS. */
    DTS("DTS", "dts"),

    /** Advanced Systems Format, which is what carries WMA. */
    ASF("ASF/WMA", "wma"),

    /** MPEG transport stream. */
    MPEG_TS("MPEG-TS", "ts"),

    /** Standard MIDI file. Not audio, but often in the same folder. */
    MIDI("MIDI", "mid"),

    /** Shorten. */
    SHORTEN("Shorten", "shn"),

    /** OptimFROG. */
    OPTIMFROG("OptimFROG", "ofr"),

    /** TAK. */
    TAK("TAK", "tak"),
    ;

    /** True when AudioKodec can decode this format today. */
    public val isDecodable: Boolean
        get() = this in DECODABLE

    private companion object {
        /**
         * The formats with a decoder in this build.
         *
         * A README table and this set are checked against each other by a
         * unit test, so the documentation cannot claim a decoder that does
         * not exist.
         */
        val DECODABLE = setOf(WAV, RF64, WAVE64, AIFF, AU, FLAC, CAF, OGG, MPEG_AUDIO, MP4, ADTS, WAVPACK, APE, TTA, MUSEPACK, AMR_NB, AMR_WB, ILBC, DSF, DFF, MATROSKA, AC3)
    }
}

/**
 * The coding of the samples inside a container.
 *
 * A container names the wrapper, this names what is inside it. WAV can hold
 * integer PCM, float PCM, A-law, mu-law or ADPCM, and the difference is what
 * decides which decoder runs.
 */
public enum class AudioCodec(
    /** A short name suitable for an error message or a UI label. */
    public val displayName: String,
    /** True when decoding gives back the exact samples that were encoded. */
    public val isLossless: Boolean,
) {
    /** Integer PCM up to 32 bits, signed or unsigned per container. */
    PCM_INTEGER("PCM", true),

    /** IEEE 754 float PCM, 32 or 64 bits. */
    PCM_FLOAT("PCM float", true),

    /** ITU-T G.711 mu-law. */
    MU_LAW("mu-law", false),

    /** ITU-T G.711 A-law. */
    A_LAW("A-law", false),

    /** IMA and DVI ADPCM, in the block layout the container defines. */
    IMA_ADPCM("IMA ADPCM", false),

    /** Microsoft ADPCM. */
    MS_ADPCM("Microsoft ADPCM", false),

    /** MPEG-1, 2 or 2.5 Layer I. */
    MP1("MP1", false),

    /** MPEG-1, 2 or 2.5 Layer II. */
    MP2("MP2", false),

    /** MPEG-1, 2 or 2.5 Layer III. */
    MP3("MP3", false),

    /** Advanced Audio Coding. */
    AAC("AAC", false),

    /** Xiph Vorbis. */
    VORBIS("Vorbis", false),

    /** Opus. */
    OPUS("Opus", false),

    /** Xiph Speex. */
    SPEEX("Speex", false),

    /** Free Lossless Audio Codec. */
    FLAC("FLAC", true),

    /** Apple Lossless. */
    ALAC("ALAC", true),

    /** WavPack, whose hybrid mode is lossy. */
    WAVPACK("WavPack", true),

    /** Monkey's Audio. */
    APE("Monkey's Audio", true),

    /** True Audio. */
    TTA("True Audio", true),

    /** Musepack. */
    MUSEPACK("Musepack", false),

    /** Dolby AC-3. */
    AC3("AC-3", false),

    /** AMR narrowband. */
    AMR_NB("AMR-NB", false),

    /** AMR wideband. */
    AMR_WB("AMR-WB", false),

    /** GSM 06.10 full rate. */
    GSM("GSM 06.10", false),

    /** Internet Low Bitrate Codec speech, in 20 or 30 ms frames. */
    ILBC("iLBC", false),

    /** ITU-T G.722 sub-band ADPCM, 7 kHz audio in 64 kbit/s. */
    G722("G.722", false),

    /** ITU-T G.726 ADPCM, at 16, 24, 32 or 40 kbit/s. */
    G726("G.726", false),

    /** Direct Stream Digital, the 1-bit coding used by SACD. */
    DSD("DSD", true),

    /** Recognised by the container, but not by this library. */
    UNKNOWN("unknown", false),
}
