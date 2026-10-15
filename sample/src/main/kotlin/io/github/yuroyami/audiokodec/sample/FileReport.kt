package io.github.yuroyami.audiokodec.sample

import io.github.yuroyami.audiokodec.AllocationGuardException
import io.github.yuroyami.audiokodec.AudioChannel
import io.github.yuroyami.audiokodec.AudioContainer
import io.github.yuroyami.audiokodec.AudioInfo
import io.github.yuroyami.audiokodec.AudioKodec
import io.github.yuroyami.audiokodec.AudioKodecException
import io.github.yuroyami.audiokodec.AudioSource
import io.github.yuroyami.audiokodec.ChannelLayout
import io.github.yuroyami.audiokodec.CorruptStreamException
import io.github.yuroyami.audiokodec.TruncatedStreamException
import io.github.yuroyami.audiokodec.UnsupportedFeatureException
import io.github.yuroyami.audiokodec.UnsupportedFormatException
import io.github.yuroyami.audiokodec.tag.TagBundle
import io.github.yuroyami.audiokodec.tag.TagField
import io.github.yuroyami.audiokodec.tag.TagPicture
import java.math.BigDecimal
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * What the viewer shows about a file, worked out without decoding any audio.
 *
 * Everything here is plain data with no Swing in it, so the console fallback
 * and the tests read the same thing the window shows.
 */
internal class FileReport(
    /** The file name, for the title. */
    val name: String,
    /** Size of the file in bytes, or -1 when the source does not know it. */
    val sizeBytes: Long,
    /** The container as the sniffer or the header names it, or null when nothing matched. */
    val container: AudioContainer?,
    /** What the header says, or null when it could not be read. */
    val info: AudioInfo?,
    /** The tags, which are empty when the file has none or they could not be read. */
    val tags: TagBundle,
    /** Why the audio cannot be decoded, in words, or null when nothing has said so yet. */
    val problem: String?,
    /** Why the tags could not be read, in words, or null when they were. */
    val tagProblem: String?,
) {
    /** True when the header was read and nothing has ruled decoding out. */
    val looksDecodable: Boolean get() = info != null && problem == null

    /** A one-line description, such as "FLAC / FLAC, 44.1 kHz, stereo". */
    val headline: String
        get() {
            val info = info ?: return container?.let { "${it.displayName}, which AudioKodec cannot read" } ?: "Not an audio file AudioKodec recognises"
            return "${info.container.displayName} / ${info.codec.displayName}, ${sampleRateText(info.format.sampleRate)}, " +
                layoutName(info.format.channelLayout)
        }

    /**
     * Label and value pairs for the format panel, in display order. Once the
     * audio has been decoded, [decodedFrames] gives its actual length, which
     * replaces the header's when the header did not state one or stated a
     * different one.
     */
    fun facts(decodedFrames: Long? = null): List<Pair<String, String>> = buildList {
        add("Container" to (container?.displayName ?: "not recognised"))
        val info = info
        if (info != null) {
            add("Coding" to info.codec.displayName + if (info.codec.isLossless) " (lossless)" else " (lossy)")
            add("Sample rate" to sampleRateText(info.format.sampleRate))
            add("Channels" to "${info.format.channels}")
            add("Layout" to layoutText(info.format.channelLayout))
            add("Bit depth" to (info.bitDepth?.let { "$it-bit" } ?: "not stored"))
            add("Bitrate" to bitrateText(info))
            val stated = info.durationFrames
            val rate = info.format.sampleRate.toDouble()
            add(
                "Duration" to when {
                    decodedFrames != null && decodedFrames != stated ->
                        "${durationText(decodedFrames / rate)} ($decodedFrames frames; " +
                            (stated?.let { "header: $it)" } ?: "decoded)")
                    stated != null -> "${durationText(stated / rate)} ($stated frames)"
                    else -> "not stated"
                },
            )
        }
        if (sizeBytes >= 0) add("File size" to sizeText(sizeBytes))
        val formats = tags.raw.formats
        add("Tag blocks" to if (formats.isEmpty()) "none" else formats.joinToString(", ") { it.displayName })
    }

    /** Label and value pairs for the tag table, one row to a value. */
    fun tagRows(): List<Pair<String, String>> = tagRowsOf(tags)

    /** The picture to show beside the format, which is the front cover if there is one. */
    val cover: TagPicture? get() = tags.cover

    /** The report as text, for the console. */
    fun asText(): String = buildString {
        appendLine(name)
        appendLine(headline)
        problem?.let { appendLine("Cannot decode: $it") }
        tagProblem?.let { appendLine("Cannot read tags: $it") }
        appendLine()
        val facts = facts()
        val width = facts.maxOf { it.first.length }
        for ((label, value) in facts) appendLine("${label.padEnd(width)}  $value")
        val rows = tagRows()
        if (rows.isNotEmpty()) {
            appendLine()
            val tagWidth = rows.maxOf { it.first.length }
            for ((label, value) in rows) appendLine("${label.padEnd(tagWidth)}  ${value.replace('\n', ' ')}")
        }
    }
}

/**
 * Reads what the viewer shows about the file in [source]: its container,
 * header and tags. No audio is decoded and the source is not closed.
 *
 * Nothing the file contains makes this throw. A file AudioKodec names but
 * cannot decode, or one whose header is damaged, comes back with [FileReport.problem]
 * saying so in words; an exception from the source itself, such as a read
 * error, does propagate.
 */
internal fun describe(name: String, source: AudioSource): FileReport {
    val header = readHeader(source)
    val sniffed = AudioKodec.sniff(header)
    var problem: String? = null
    val info = try {
        AudioKodec.probe(source)
    } catch (failure: AudioKodecException) {
        problem = readableProblem(failure, sniffed)
        null
    }
    val container = info?.container ?: sniffed
    if (problem == null && container != null && !container.isDecodable) {
        problem = "AudioKodec recognises this file as ${container.displayName} but has no decoder for it."
    }
    var tagProblem: String? = null
    val tags = try {
        AudioKodec.readTags(source)
    } catch (failure: AudioKodecException) {
        // A file with no tags reads as an empty bundle, so this is a damaged block.
        tagProblem = readableProblem(failure, container)
        TagBundle.EMPTY
    }
    return FileReport(name, source.size, container, info, tags, problem, tagProblem)
}

/** The first bytes of [source], up to the window the sniffer recommends. */
private fun readHeader(source: AudioSource): ByteArray {
    val wanted = if (source.size in 0 until AudioKodec.SNIFF_HEADER_BYTES) source.size.toInt() else AudioKodec.SNIFF_HEADER_BYTES
    val header = ByteArray(wanted)
    var filled = 0
    while (filled < wanted) {
        val read = source.readAt(filled.toLong(), header, filled, wanted - filled)
        if (read <= 0) break
        filled += read
    }
    return if (filled == wanted) header else header.copyOf(filled)
}

/**
 * A sentence a person can read about why [failure] happened, for a file the
 * sniffer named [container].
 */
internal fun readableProblem(failure: Throwable, container: AudioContainer?): String {
    val what = container?.displayName?.let { "this $it file" } ?: "this file"
    val detail = failure.message?.trim()?.removeSuffix(".").orEmpty()
    return when (failure) {
        is UnsupportedFormatException -> {
            val named = failure.format ?: container
            when {
                named == null -> "AudioKodec does not recognise this file as audio."
                // The library's own message already names the format and says what is missing.
                detail.isNotEmpty() -> "${detail.replaceFirstChar { it.uppercase() }}."
                else -> "AudioKodec recognises this file as ${named.displayName} but has no decoder for it."
            }
        }
        is UnsupportedFeatureException ->
            if (detail.isEmpty() || detail == "Unsupported feature: ${failure.feature}") {
                "AudioKodec does not support ${failure.feature}."
            } else {
                "AudioKodec does not support ${failure.feature}: $detail."
            }
        is TruncatedStreamException -> "${what.replaceFirstChar { it.uppercase() }} ends early: $detail."
        is CorruptStreamException -> "${what.replaceFirstChar { it.uppercase() }} is damaged: $detail."
        is AllocationGuardException -> "${what.replaceFirstChar { it.uppercase() }} asks for more memory than the viewer allows: $detail."
        is AudioKodecException -> "AudioKodec could not read ${what}: $detail."
        else -> "The file could not be read: ${failure.javaClass.simpleName}${if (detail.isEmpty()) "" else ", $detail"}."
    }
}

/** Every value of every field the bundle exposes, then its pictures, chapters and loudness values. */
internal fun tagRowsOf(tags: TagBundle): List<Pair<String, String>> = buildList {
    for ((field, values) in tags.fields) {
        for (value in values) add(fieldLabel(field) to value)
    }
    for (picture in tags.pictures) add("Picture" to pictureText(picture))
    for (chapter in tags.chapters) {
        val start = durationText(chapter.startMillis / 1000.0)
        add("Chapter" to listOfNotNull(start, chapter.title).joinToString("  "))
    }
    tags.replayGain?.let { gain ->
        val scheme = if (gain.isR128) "R128, against -23 LUFS" else "ReplayGain, against -18 LUFS"
        gain.trackGainDb?.let { add("Track gain" to "${decibels(it)} ($scheme)") }
        gain.trackPeak?.let { add("Track peak" to String.format(Locale.ROOT, "%.6f", it)) }
        gain.albumGainDb?.let { add("Album gain" to "${decibels(it)} ($scheme)") }
        gain.albumPeak?.let { add("Album peak" to String.format(Locale.ROOT, "%.6f", it)) }
    }
}

/** "ALBUM_ARTIST" as "Album artist", and the identifiers with their capitals kept. */
internal fun fieldLabel(field: TagField): String = when (field) {
    TagField.BPM -> "BPM"
    TagField.ISRC -> "ISRC"
    TagField.URL -> "URL"
    TagField.ACOUSTID_ID -> "AcoustID"
    else -> {
        val words = field.name.lowercase().split('_')
        val label = words.joinToString(" ").replaceFirstChar { it.uppercase() }
        label.replace("musicbrainz", "MusicBrainz").replace(" id", " ID")
    }
}

private fun pictureText(picture: TagPicture): String {
    val role = picture.type.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
    val size = if (picture.width > 0 && picture.height > 0) ", ${picture.width} x ${picture.height}" else ""
    val caption = if (picture.description.isNotEmpty()) ", \"${picture.description}\"" else ""
    return "$role, ${picture.mimeType}$size, ${sizeText(picture.bytes.size.toLong())}$caption"
}

/** The short name of a layout: mono, stereo, 5.1 and so on, or a count when it has none. */
internal fun layoutName(layout: ChannelLayout): String = when (layout) {
    ChannelLayout.MONO -> "mono"
    ChannelLayout.STEREO -> "stereo"
    ChannelLayout.SURROUND_3_0 -> "3.0"
    ChannelLayout.QUAD -> "quad"
    ChannelLayout.SURROUND_5_0 -> "5.0"
    ChannelLayout.SURROUND_5_1 -> "5.1"
    ChannelLayout.SURROUND_7_1 -> "7.1"
    else -> "${layout.count} channels"
}

/** The layout's name and its speakers in interleaving order. */
internal fun layoutText(layout: ChannelLayout): String {
    val speakers = layout.channels.joinToString(", ") { speakerName(it) }
    return if (layout.isPositional) "${layoutName(layout)}: $speakers" else "speakers not stated"
}

private fun speakerName(channel: AudioChannel): String = when (channel) {
    AudioChannel.FRONT_LEFT -> "L"
    AudioChannel.FRONT_RIGHT -> "R"
    AudioChannel.FRONT_CENTER -> "C"
    AudioChannel.LOW_FREQUENCY -> "LFE"
    AudioChannel.BACK_LEFT -> "Lb"
    AudioChannel.BACK_RIGHT -> "Rb"
    AudioChannel.SIDE_LEFT -> "Ls"
    AudioChannel.SIDE_RIGHT -> "Rs"
    AudioChannel.BACK_CENTER -> "Cb"
    else -> channel.name.lowercase().replace('_', ' ')
}

/** 44100 as "44.1 kHz", with every digit the rate has and no more. */
internal fun sampleRateText(rate: Int): String =
    "${BigDecimal(rate).movePointLeft(3).stripTrailingZeros().toPlainString()} kHz"

private fun bitrateText(info: AudioInfo): String {
    val rate = info.bitrate ?: return "not stated"
    val kbps = (rate / 1000.0).roundToLong()
    return "$kbps kbit/s" + if (info.isVariableBitrate) ", variable" else ""
}

/** Seconds as m:ss.mmm, or h:mm:ss.mmm from an hour. */
internal fun durationText(seconds: Double): String {
    val millis = (seconds * 1000).roundToLong().coerceAtLeast(0)
    val h = millis / 3_600_000
    val m = millis / 60_000 % 60
    val s = millis / 1000 % 60
    val ms = millis % 1000
    return if (h > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d.%03d", h, m, s, ms)
    } else {
        String.format(Locale.ROOT, "%d:%02d.%03d", m, s, ms)
    }
}

internal fun sizeText(bytes: Long): String = when {
    bytes < 1024 -> "$bytes bytes"
    bytes < 1024 * 1024 -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
    else -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))
}

private fun decibels(value: Double): String =
    String.format(Locale.ROOT, "%s%.2f dB", if (value > 0 && abs(value) >= 0.005) "+" else "", value)
