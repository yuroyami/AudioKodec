package io.github.yuroyami.kiteaudio

/**
 * One speaker position.
 *
 * The order of the entries is the canonical KiteAudio order, which is the
 * order WAV and SMPTE use. Every decoder remaps to it before emitting samples,
 * so a caller never has to ask which codec put the centre channel where.
 */
public enum class AudioChannel {
    FRONT_LEFT,
    FRONT_RIGHT,
    FRONT_CENTER,
    LOW_FREQUENCY,
    BACK_LEFT,
    BACK_RIGHT,
    FRONT_LEFT_OF_CENTER,
    FRONT_RIGHT_OF_CENTER,
    BACK_CENTER,
    SIDE_LEFT,
    SIDE_RIGHT,
    TOP_CENTER,
    TOP_FRONT_LEFT,
    TOP_FRONT_CENTER,
    TOP_FRONT_RIGHT,
    TOP_BACK_LEFT,
    TOP_BACK_CENTER,
    TOP_BACK_RIGHT,

    /** A channel whose position the file did not state. */
    UNKNOWN,
    ;

    /**
     * The bit this channel occupies in a WAV `dwChannelMask`, or 0 for
     * [UNKNOWN].
     */
    public val waveMaskBit: Int
        get() = if (this == UNKNOWN) 0 else 1 shl ordinal
}

/**
 * The speaker positions of a stream, in the order the samples are interleaved.
 *
 * Two layouts with the same channels in a different order are different
 * layouts. That distinction is what makes channel remapping checkable.
 */
public class ChannelLayout private constructor(
    channels: List<AudioChannel>,
) {
    /** Positions in interleaving order. */
    public val channels: List<AudioChannel> = channels.toList()

    /** Number of channels. */
    public val count: Int get() = channels.size

    /** True when no position is [AudioChannel.UNKNOWN]. */
    public val isPositional: Boolean get() = channels.none { it == AudioChannel.UNKNOWN }

    /** The WAV `dwChannelMask` for this layout, or 0 when it is not positional. */
    public val waveMask: Int
        get() = if (!isPositional) 0 else channels.fold(0) { acc, c -> acc or c.waveMaskBit }

    override fun equals(other: Any?): Boolean = other is ChannelLayout && other.channels == channels

    override fun hashCode(): Int = channels.hashCode()

    override fun toString(): String = "ChannelLayout(${channels.joinToString(", ")})"

    public companion object {
        /** A layout with the given positions, in interleaving order. */
        public fun of(vararg channels: AudioChannel): ChannelLayout = of(channels.toList())

        /** A layout with the given positions, in interleaving order. */
        public fun of(channels: List<AudioChannel>): ChannelLayout {
            require(channels.isNotEmpty()) { "a channel layout must not be empty" }
            require(channels.size <= MAX_CHANNELS) {
                "a channel layout cannot exceed $MAX_CHANNELS channels, was ${channels.size}"
            }
            val known = channels.filter { it != AudioChannel.UNKNOWN }
            require(known.distinct().size == known.size) {
                "a positional channel may appear only once: $channels"
            }
            return ChannelLayout(channels)
        }

        /** [count] channels whose positions the file did not state. */
        public fun unknown(count: Int): ChannelLayout {
            require(count in 1..MAX_CHANNELS) {
                "channel count must be 1..$MAX_CHANNELS, was $count"
            }
            return ChannelLayout(List(count) { AudioChannel.UNKNOWN })
        }

        public val MONO: ChannelLayout = of(AudioChannel.FRONT_CENTER)

        public val STEREO: ChannelLayout = of(AudioChannel.FRONT_LEFT, AudioChannel.FRONT_RIGHT)

        public val SURROUND_3_0: ChannelLayout = of(
            AudioChannel.FRONT_LEFT, AudioChannel.FRONT_RIGHT, AudioChannel.FRONT_CENTER,
        )

        public val QUAD: ChannelLayout = of(
            AudioChannel.FRONT_LEFT, AudioChannel.FRONT_RIGHT,
            AudioChannel.BACK_LEFT, AudioChannel.BACK_RIGHT,
        )

        public val SURROUND_5_0: ChannelLayout = of(
            AudioChannel.FRONT_LEFT, AudioChannel.FRONT_RIGHT, AudioChannel.FRONT_CENTER,
            AudioChannel.BACK_LEFT, AudioChannel.BACK_RIGHT,
        )

        public val SURROUND_5_1: ChannelLayout = of(
            AudioChannel.FRONT_LEFT, AudioChannel.FRONT_RIGHT, AudioChannel.FRONT_CENTER,
            AudioChannel.LOW_FREQUENCY, AudioChannel.BACK_LEFT, AudioChannel.BACK_RIGHT,
        )

        public val SURROUND_7_1: ChannelLayout = of(
            AudioChannel.FRONT_LEFT, AudioChannel.FRONT_RIGHT, AudioChannel.FRONT_CENTER,
            AudioChannel.LOW_FREQUENCY, AudioChannel.BACK_LEFT, AudioChannel.BACK_RIGHT,
            AudioChannel.SIDE_LEFT, AudioChannel.SIDE_RIGHT,
        )

        /**
         * The layout a file with [count] channels and no explicit mask is
         * assumed to have.
         *
         * This is the WAV default assignment, which the other containers in
         * this library follow as well. Above eight channels there is no
         * convention, so the result is [unknown].
         */
        public fun canonical(count: Int): ChannelLayout = when (count) {
            1 -> MONO
            2 -> STEREO
            3 -> SURROUND_3_0
            4 -> QUAD
            5 -> SURROUND_5_0
            6 -> SURROUND_5_1
            8 -> SURROUND_7_1
            else -> unknown(count)
        }

        /**
         * The layout a WAV `dwChannelMask` describes.
         *
         * Bits are read from the low bit upwards, which is the order the
         * samples are interleaved in. When the mask names fewer channels than
         * [count], the rest are [AudioChannel.UNKNOWN]; when it names more,
         * the extra bits are ignored, because the channel count is what
         * decides how many samples a frame has.
         */
        public fun fromWaveMask(mask: Int, count: Int): ChannelLayout {
            require(count in 1..MAX_CHANNELS) {
                "channel count must be 1..$MAX_CHANNELS, was $count"
            }
            if (mask == 0) return canonical(count)
            val named = ArrayList<AudioChannel>(count)
            for (channel in AudioChannel.entries) {
                if (channel == AudioChannel.UNKNOWN) continue
                if (named.size == count) break
                if (mask and channel.waveMaskBit != 0) named.add(channel)
            }
            while (named.size < count) named.add(AudioChannel.UNKNOWN)
            return ChannelLayout(named)
        }

        private const val MAX_CHANNELS: Int = 4096
    }
}

/**
 * The shape of a PCM stream: how many samples per second, how many channels,
 * and which speaker each channel drives.
 *
 * This does not describe how the samples are stored. KiteAudio decodes to
 * Float32 whatever the file held, and [AudioInfo.bitDepth] reports the storage
 * depth of the original.
 */
public class AudioFormat(
    /** Samples per second per channel. */
    public val sampleRate: Int,
    /** Speaker positions, in interleaving order. */
    public val channelLayout: ChannelLayout,
) {
    /** Number of channels. */
    public val channels: Int get() = channelLayout.count

    public constructor(sampleRate: Int, channels: Int) :
        this(sampleRate, ChannelLayout.canonical(channels))

    init {
        require(sampleRate > 0) { "sampleRate must be positive, was $sampleRate" }
        require(channels > 0) { "channels must be positive, was ${channelLayout.count}" }
    }

    /** Bytes one frame of Float32 interleaved PCM occupies. */
    public val floatFrameBytes: Int get() = channels * 4

    override fun equals(other: Any?): Boolean =
        other is AudioFormat && other.sampleRate == sampleRate && other.channelLayout == channelLayout

    override fun hashCode(): Int = sampleRate * 31 + channelLayout.hashCode()

    override fun toString(): String = "AudioFormat(${sampleRate}Hz, $channelLayout)"
}
