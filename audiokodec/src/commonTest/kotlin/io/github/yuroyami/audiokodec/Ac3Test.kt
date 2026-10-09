package io.github.yuroyami.audiokodec

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Checks AC-3 decoding on every target, against three small streams ffmpeg's
 * encoder wrote and what ffmpeg's decoder gives for them, and the properties
 * of the decoder that need no reference: repeatable dither, exact seeking, and
 * damage reported as damage.
 *
 * The standard defines decoding in real arithmetic and ffmpeg's decoder holds
 * each coefficient as a 24-bit fixed-point integer, so the two differ by that
 * rounding: up to 1.5e-5 on the files the JVM oracle suite compares, whose
 * bound is derived in `Ac3DecodeOracleTest`. The reference here is ffmpeg's
 * samples rounded to steps of 1/8192, which keeps the test signal's peaks
 * above full scale, so a sample may differ from it by half a step plus the
 * oracle's bound, [TOLERANCE] steps in all. Samples are also
 * compared as an energy per channel and syncframe, so that the samples not in
 * the digest cannot go wrong unseen.
 */
class Ac3Test {

    @Test
    fun everyStreamDecodesToWhatFfmpegDecodesItTo() {
        for (fixture in Ac3Fixtures.ALL) {
            val what = fixture.name
            val info = AudioKodec.probe(fixture.bytes)
            assertEquals(AudioContainer.AC3, info.container, what)
            assertEquals(AudioCodec.AC3, info.codec, what)
            assertEquals(fixture.rate, info.format.sampleRate, what)
            assertEquals(fixture.channels, info.format.channels, what)
            assertEquals(4608L, info.durationFrames, what)

            val decoded = AudioKodec.decode(fixture.bytes)
            assertEquals(4608, decoded.frames, what)
            val channels = fixture.channels
            for (i in fixture.digest.indices) {
                val f = (i / channels) * 16
                val c = i % channels
                val difference = abs(decoded.samples[f * channels + c] * 8192f - fixture.digest[i])
                assertTrue(difference <= TOLERANCE, "$what: frame $f channel $c differs by $difference steps")
            }
            for (syncframe in 0 until 3) {
                for (c in 0 until channels) {
                    var energy = 0.0
                    for (f in syncframe * 1536 until (syncframe + 1) * 1536) {
                        energy += decoded.samples[f * channels + c].toDouble().let { it * it }
                    }
                    val rms = sqrt(energy / 1536).toFloat()
                    val expected = fixture.rms[syncframe * channels + c]
                    assertTrue(abs(rms - expected) <= 2e-5f + 1e-4f * expected, "$what: syncframe $syncframe channel $c has RMS $rms, ffmpeg's is $expected")
                }
            }
        }
    }

    @Test
    fun channelsComeOutInTheOrderOfTheLayout() {
        val surround = AudioKodec.decode(Ac3Fixtures.SURROUND.bytes)
        assertEquals(
            listOf(
                AudioChannel.FRONT_LEFT, AudioChannel.FRONT_RIGHT, AudioChannel.FRONT_CENTER,
                AudioChannel.LOW_FREQUENCY, AudioChannel.SIDE_LEFT, AudioChannel.SIDE_RIGHT,
            ),
            surround.format.channelLayout.channels,
        )
        // The test signal puts a different mix of tones in each channel, so the channels are told apart by their energy.
        val energies = (0 until 6).map { c ->
            (0 until 4608).sumOf { f -> surround.samples[f * 6 + c].toDouble().let { it * it } }
        }
        assertEquals(6, energies.map { (it * 1000).toLong() }.toSet().size, "every channel carries its own signal")
    }

    @Test
    fun ditherIsAppliedAndRepeatsExactly() {
        val dithered = AudioKodec.decode(Ac3Fixtures.MONO_DITHERED)
        assertEquals(4 * 1536, dithered.frames)
        // The same flags cleared change the samples: the dither is there.
        val cleared = AudioKodec.decode(Ac3Fixtures.MONO.bytes)
        assertTrue((0 until 3 * 1536).any { dithered.samples[it] != cleared.samples[it] }, "the dither flags change nothing")
        assertTrue((0 until 3 * 1536).all { abs(dithered.samples[it] - cleared.samples[it]) < 0.02f }, "the dither is no louder than the signal's own noise floor")
        // The noise restarts with every syncframe, so a decode does not depend on what was decoded before it.
        assertEquals(dithered.samples.toList(), AudioKodec.decode(Ac3Fixtures.MONO_DITHERED).samples.toList())
        AudioKodec.open(Ac3Fixtures.MONO_DITHERED).use { decoder ->
            assertEquals(4000L, decoder.seek(4000))
            val frame = decoder.nextFrame()!!
            for (i in 0 until minOf(frame.frameCount, 500)) {
                assertEquals(dithered.samples[4000 + i], frame.samples[i], "seek to 4000, sample $i")
            }
        }
    }

    @Test
    fun seekingLandsOnTheContinuousDecode() {
        for (fixture in Ac3Fixtures.ALL) {
            val channels = fixture.channels
            val whole = AudioKodec.decode(fixture.bytes).samples
            for (target in listOf(0L, 1L, 1535L, 1536L, 3000L, 4607L)) {
                AudioKodec.open(fixture.bytes).use { decoder ->
                    assertEquals(target, decoder.seek(target), fixture.name)
                    val frame = decoder.nextFrame()!!
                    for (i in 0 until minOf(frame.frameCount, 300) * channels) {
                        assertEquals(whole[(target * channels).toInt() + i], frame.samples[i], "${fixture.name}: seek to $target, sample $i")
                    }
                }
            }
        }
    }

    @Test
    fun anId3TagAndJunkBetweenSyncframesAreCrossed() {
        val fixture = Ac3Fixtures.STEREO
        val frames = fixture.frames()
        val plain = AudioKodec.decode(fixture.bytes).samples.toList()

        // An ID3v2 tag in front, as MPEG audio and ADTS may have.
        val tag = byteArrayOf(0x49, 0x44, 0x33, 4, 0, 0, 0, 0, 0, 20) + ByteArray(20)
        val tagged = tag + fixture.bytes
        val info = AudioKodec.probe(tagged)
        assertEquals(4608L, info.durationFrames)
        assertEquals(tag.size.toLong(), info.dataOffset)
        assertEquals(plain, AudioKodec.decode(tagged).samples.toList())

        // Bytes between syncframes are skipped, and the stream is found again behind them. A stream is
        // only believed once the frames after a sync word agree with it, so the gap needs frames on both sides.
        val many = List(5) { frames }.flatten()
        val junk = ByteArray(7) { (it * 3 + 1).toByte() }
        val solid = many.fold(ByteArray(0)) { a, b -> a + b }
        val gapped = many.take(7).fold(ByteArray(0)) { a, b -> a + b } + junk + many.drop(7).fold(ByteArray(0)) { a, b -> a + b }
        assertEquals(15 * 1536L, AudioKodec.probe(gapped).durationFrames)
        assertEquals(AudioKodec.decode(solid).samples.toList(), AudioKodec.decode(gapped).samples.toList())
    }

    @Test
    fun damageIsReportedAsDamage() {
        val surround = Ac3Fixtures.SURROUND.bytes
        // A bit flipped inside the second syncframe fails its CRC.
        val flipped = surround.copyOf()
        flipped[800] = (flipped[800].toInt() xor 0x10).toByte()
        assertFailsWith<CorruptStreamException> { AudioKodec.decode(flipped) }
        // A tolerant decode stops short of a syncframe it cannot make sense of, and never throws.
        val partial = AudioKodec.decode(flipped, DecodeOptions(tolerant = true)).frames
        assertTrue(partial % 1536 == 0 && partial in 1536..4608, "$partial frames")

        // The CRC itself damaged, with every bit it checks intact: strict mode refuses, tolerant takes the frame as stored.
        val checksum = surround.copyOf()
        checksum[2 * 768 - 1] = (checksum[2 * 768 - 1].toInt() xor 1).toByte()
        assertFailsWith<CorruptStreamException> { AudioKodec.decode(checksum) }
        assertEquals(AudioKodec.decode(surround).samples.toList(), AudioKodec.decode(checksum, DecodeOptions(tolerant = true)).samples.toList())

        // A final syncframe cut short is truncation, or left out when tolerant.
        val cut = surround.copyOf(surround.size - 100)
        assertFailsWith<TruncatedStreamException> { AudioKodec.decode(cut) }
        assertEquals(3072, AudioKodec.decode(cut, DecodeOptions(tolerant = true)).frames)
    }

    @Test
    fun enhancedAc3IsRefusedByName() {
        val failure = assertFailsWith<UnsupportedFormatException> { AudioKodec.open(Ac3Fixtures.ENHANCED) }
        assertTrue("Enhanced AC-3" in failure.message.orEmpty(), failure.message)
        assertFailsWith<UnsupportedFormatException> { AudioKodec.probe(Ac3Fixtures.ENHANCED) }
    }

    /** Two syncframes of the mono stream, for the hostile input sweeps. */
    fun hostileFixture(): ByteArray = Ac3Fixtures.MONO.frames().take(2).fold(ByteArray(0)) { a, b -> a + b }

    private companion object {
        /** Half a step of the digest, and the 5e-5 the oracle suite allows between the two decoders' roundings, with a little for float arithmetic. */
        const val TOLERANCE = 0.5f + 5e-5f * 8192f + 0.1f
    }
}
