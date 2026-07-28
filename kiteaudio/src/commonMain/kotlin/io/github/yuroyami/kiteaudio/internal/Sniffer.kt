package io.github.yuroyami.kiteaudio.internal

import io.github.yuroyami.kiteaudio.AudioContainer
import io.github.yuroyami.kiteaudio.internal.container.Wave64Reader
import io.github.yuroyami.kiteaudio.internal.io.fourCcOf

/**
 * Names a format from the first bytes of a file.
 *
 * The sniffer never throws and never reads past what it was given. It names
 * more formats than KiteAudio can decode, which is the point: a caller that
 * gets [AudioContainer.MATROSKA] back knows to send the file somewhere else,
 * where a caller that got null would have to guess.
 */
internal object Sniffer {

    /** Bytes worth reading before calling [sniff]. */
    const val RECOMMENDED_HEADER_BYTES: Int = 64 * 1024

    /**
     * ID3v2 blocks sit in front of MP3, FLAC, AIFF and WAV files alike, and a
     * sniffer that stopped at one would call every tagged file an MP3. Every
     * valid block is at least ten bytes, so an iterative walk is both bounded
     * by [header] and safe from a deliberately deep tag stack.
     */
    fun sniff(header: ByteArray): AudioContainer? {
        var offset = 0
        while (true) {
            val available = header.size - offset
            if (available < 10 || !matches(header, offset, "ID3")) break

            val major = header[offset + 3].toInt() and 0xFF
            val revision = header[offset + 4].toInt() and 0xFF
            val flags = header[offset + 5].toInt() and 0xFF
            val reservedMask = when (major) {
                2 -> 0x3F
                3 -> 0x1F
                4 -> 0x0F
                else -> return AudioContainer.MPEG_AUDIO
            }
            if (revision == 0xFF || flags and reservedMask != 0) {
                return AudioContainer.MPEG_AUDIO
            }
            var size = 0
            for (i in 6..9) {
                val b = header[offset + i].toInt() and 0xFF
                if (b and 0x80 != 0) return AudioContainer.MPEG_AUDIO
                size = (size shl 7) or b
            }
            // A footer, when present, adds ten more bytes after the frames.
            val total = 10 + size + if (flags and 0x10 != 0) 10 else 0
            if (total >= available) {
                // The tag runs past what was read. Almost every file with a
                // tag that large is an MP3, and probe reads the real header
                // afterwards anyway.
                return AudioContainer.MPEG_AUDIO
            }
            offset += total
        }

        val available = header.size - offset
        if (available < 4) {
            return if (offset == 0) null else AudioContainer.MPEG_AUDIO
        }

        val id = fourCcOf(header, offset)
        val b0 = header[offset].toInt() and 0xFF
        val b1 = if (available > 1) header[offset + 1].toInt() and 0xFF else 0
        val b2 = if (available > 2) header[offset + 2].toInt() and 0xFF else 0
        val b3 = if (available > 3) header[offset + 3].toInt() and 0xFF else 0

        when (id) {
            "RIFF" -> {
                if (available >= 12 && fourCcOf(header, offset + 8) == "WAVE") return AudioContainer.WAV
                return null
            }
            "RF64", "BW64" -> {
                if (available >= 12 && fourCcOf(header, offset + 8) == "WAVE") return AudioContainer.RF64
                return null
            }
            "FORM" -> {
                if (available < 12) return null
                return when (fourCcOf(header, offset + 8)) {
                    "AIFF", "AIFC" -> AudioContainer.AIFF
                    else -> null
                }
            }
            ".snd" -> return AudioContainer.AU
            "caff" -> return AudioContainer.CAF
            "fLaC" -> return AudioContainer.FLAC
            "OggS" -> return AudioContainer.OGG
            "ADIF" -> return AudioContainer.ADIF
            "MAC " -> return AudioContainer.APE
            "wvpk" -> return AudioContainer.WAVPACK
            "MPCK" -> return AudioContainer.MUSEPACK
            "TTA1" -> return AudioContainer.TTA
            "DSD " -> return AudioContainer.DSF
            "FRM8" -> return AudioContainer.DFF
            "MThd" -> return AudioContainer.MIDI
            "ajkg" -> return AudioContainer.SHORTEN
            "OFR " -> return AudioContainer.OPTIMFROG
            "tBaK" -> return AudioContainer.TAK
        }

        // Musepack before SV8 used a three-character identifier.
        if (b0 == 'M'.code && b1 == 'P'.code && b2 == '+'.code) return AudioContainer.MUSEPACK

        // ISO base media, which puts its brand at offset four.
        if (available >= 12 && fourCcOf(header, offset + 4) == "ftyp") return AudioContainer.MP4

        // Matroska and WebM share the EBML header.
        if (b0 == 0x1A && b1 == 0x45 && b2 == 0xDF && b3 == 0xA3) return AudioContainer.MATROSKA

        // Wave64 names its outer chunk with a GUID.
        if (available >= 16 && Wave64Reader.isWave64Header(header, offset)) {
            return AudioContainer.WAVE64
        }

        // Advanced Systems Format, which is what a .wma file really is.
        if (available >= 8 &&
            b0 == 0x30 && b1 == 0x26 && b2 == 0xB2 && b3 == 0x75 &&
            (header[offset + 4].toInt() and 0xFF) == 0x8E &&
            (header[offset + 5].toInt() and 0xFF) == 0x66 &&
            (header[offset + 6].toInt() and 0xFF) == 0xCF &&
            (header[offset + 7].toInt() and 0xFF) == 0x11
        ) {
            return AudioContainer.ASF
        }

        // AMR, which states its own name in the header.
        if (available >= 6 && matches(header, offset, "#!AMR")) {
            return if (available >= 9 && matches(header, offset, "#!AMR-WB")) {
                AudioContainer.AMR_WB
            } else {
                AudioContainer.AMR_NB
            }
        }

        // AC-3 and its enhanced form share a sync word.
        if (b0 == 0x0B && b1 == 0x77) return AudioContainer.AC3

        // DTS, in the byte orders the format is found in.
        if (b0 == 0x7F && b1 == 0xFE && b2 == 0x80 && b3 == 0x01) return AudioContainer.DTS
        if (b0 == 0xFE && b1 == 0x7F && b2 == 0x01 && b3 == 0x80) return AudioContainer.DTS

        // A transport stream is 188-byte packets, each starting with 0x47.
        if (b0 == 0x47 && available > 188 && (header[offset + 188].toInt() and 0xFF) == 0x47) {
            return AudioContainer.MPEG_TS
        }

        // ADTS and MPEG audio share the top of their sync word, and the four
        // bits below it are what tell them apart.
        if (b0 == 0xFF) {
            val isAdts = (b1 and 0xF0) == 0xF0 && (b1 and 0x06) == 0x00
            if (isAdts) return AudioContainer.ADTS
            val hasSync = (b1 and 0xE0) == 0xE0
            val version = (b1 shr 3) and 0x03
            val layer = (b1 shr 1) and 0x03
            if (hasSync && version != 1 && layer != 0) return AudioContainer.MPEG_AUDIO
        }

        return if (offset == 0) null else AudioContainer.MPEG_AUDIO
    }

    private fun matches(header: ByteArray, offset: Int, text: String): Boolean {
        if (offset < 0 || offset > header.size - text.length) return false
        for (i in text.indices) {
            if ((header[offset + i].toInt() and 0xFF) != text[i].code) return false
        }
        return true
    }
}
