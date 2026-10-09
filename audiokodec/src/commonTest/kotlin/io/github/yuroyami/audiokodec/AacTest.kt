package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.internal.aac.AacTables
import io.github.yuroyami.audiokodec.internal.tag.Base64
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Checks AAC-LC decoding on every target: a real stream against what
 * ffmpeg's decoder makes of it, blocks built here bit by bit against the
 * transform's definition in ISO/IEC 14496-3, and the codings and tools the
 * decoder refuses by name. The JVM oracle suite compares whole files with
 * ffmpeg.
 */
class AacTest {

    @Test
    fun aRealStreamDecodesAsFfmpegDecodesIt() {
        val decoded = AudioKodec.decode(fixture())
        assertEquals(22_050, decoded.format.sampleRate)
        assertEquals(ChannelLayout.STEREO, decoded.format.channelLayout)
        assertEquals(8 * 1024, decoded.frames.toInt())
        val samples = decoded.samples
        for (block in 0 until 8) {
            for (c in 0 until 2) {
                var sum = 0.0
                for (f in block * 1024 until block * 1024 + 1024) sum += samples[f * 2 + c].toDouble().let { it * it }
                val rms = sqrt(sum / 1024)
                val expected = FFMPEG_RMS[block * 2 + c].toDouble()
                assertTrue(abs(rms - expected) < 1e-5, "block $block, channel $c: rms $rms where ffmpeg's is $expected")
            }
        }
        for ((frame, expected) in FFMPEG_SAMPLES) {
            for (c in 0 until 2) {
                assertTrue(abs(samples[frame * 2 + c] - expected[c]) < 1e-5, "frame $frame, channel $c: ${samples[frame * 2 + c]} where ffmpeg has ${expected[c]}")
            }
        }
    }

    @Test
    fun oneCoefficientBecomesTheWindowedCosineTheStandardDefines() {
        // ONLY_LONG_SEQUENCE: coefficient 0 of 1024 at unit gain, then a silent block to flush the overlap.
        val long = adts(listOf(singleCoefficient(short = false), silentSce()), rateIndex = 4, configuration = 1)
        val longOut = AudioKodec.decode(long).samples
        assertEquals(2048, longOut.size)
        for (n in 0 until 2048) {
            val expected = imdct(2048, n) * sineWindow(2048, n) / 32768
            assertTrue(abs(longOut[n] - expected) < 1e-5 * 2 / 2048 / 32768, "long, sample $n: ${longOut[n]} where the definition gives $expected")
        }
        // EIGHT_SHORT_SEQUENCE: coefficient 0 of the first short window, placed 448 samples in.
        val short = adts(listOf(singleCoefficient(short = true), silentSce()), rateIndex = 4, configuration = 1)
        val shortOut = AudioKodec.decode(short).samples
        for (n in 0 until 2048) {
            val inside = n - 448
            val expected = if (inside in 0 until 256) imdct(256, inside) * sineWindow(256, inside) / 32768 else 0.0
            assertTrue(abs(shortOut[n] - expected) < 1e-5 * 2 / 256 / 32768, "short, sample $n: ${shortOut[n]} where the definition gives $expected")
        }
    }

    @Test
    fun framesOfSeveralProtectedBlocksDecodeEveryBlock() {
        val block = silentCpe()
        for (crc in listOf(false, true)) {
            val file = adts(listOf(block, block, block), rateIndex = 12, configuration = 2, blocksPerFrame = 3, crc = crc)
            val decoded = AudioKodec.decode(file)
            assertEquals(7_350, decoded.format.sampleRate)
            assertEquals(3 * 1024, decoded.frames.toInt(), "crc $crc")
            assertTrue(decoded.samples.all { it == 0f })
        }
    }

    @Test
    fun spectralBandReplicationIsRefusedByName() {
        // Implicitly, by a fill element carrying SBR data in the first block.
        for (type in listOf(13, 14)) {
            val block = bits(silentSceFields() + listOf(6 to 3, 1 to 4, type to 4, 0 to 4, 7 to 3))
            val failure = assertFailsWith<UnsupportedFeatureException> { AudioKodec.open(adts(listOf(block), 4, 1)) }
            assertTrue("spectral band replication" in failure.feature, failure.feature)
        }
        // Explicitly, by the configuration, in both of its forms; probing
        // reports the rate a full decoder would give.
        val hierarchical = byteArrayOf(0x2C, 0x12, 0x88.toByte(), 0x00)
        val backwardCompatible = byteArrayOf(0x12, 0x10, 0x56, 0xE5.toByte(), 0x88.toByte())
        for ((config, rate) in listOf(hierarchical to 32_000, backwardCompatible to 88_200)) {
            val file = mp4(config)
            assertEquals(rate, AudioKodec.probe(file).format.sampleRate)
            val failure = assertFailsWith<UnsupportedFeatureException> { AudioKodec.open(file) }
            assertTrue("spectral band replication" in failure.feature, failure.feature)
        }
    }

    @Test
    fun couplingChannelsAndOtherProfilesAreRefusedByName() {
        val coupling = bits(listOf(2 to 3, 0 to 4) + List(10) { 0 to 8 })
        val failure = assertFailsWith<UnsupportedFeatureException> { AudioKodec.open(adts(listOf(coupling), 4, 1)) }
        assertEquals("AAC coupling channel elements", failure.feature)
        for ((profile, name) in listOf(0 to "AAC Main", 2 to "AAC SSR", 3 to "AAC LTP")) {
            val file = adts(listOf(silentSce()), 4, 1, profile = profile)
            assertEquals(name, assertFailsWith<UnsupportedFeatureException> { AudioKodec.open(file) }.feature)
        }
        // The same in MP4, where the configuration names the object type.
        val main = byteArrayOf(0x0A, 0x10)
        assertEquals("AAC Main", assertFailsWith<UnsupportedFeatureException> { AudioKodec.open(mp4(main)) }.feature)
    }

    @Test
    fun aBlockWithTheWrongChannelsIsDamage() {
        // A stereo stream whose block carries one channel, and a mono one whose block carries two.
        for ((block, configuration) in listOf(silentSce() to 2, silentCpe() to 1)) {
            val file = adts(listOf(block, block), 4, configuration)
            AudioKodec.open(file).use { decoder ->
                assertFailsWith<CorruptStreamException> { decoder.nextFrame() }
            }
        }
    }

    @Test
    fun truncatedAndMutatedStreamsFailOnlyWithTheLibrarysOwnExceptions() {
        val file = fixture()
        for (keep in 0..file.size step 3) {
            val cut = file.copyOf(keep)
            for (tolerant in listOf(false, true)) {
                expectOnlyOwnFailures("cut to $keep") { AudioKodec.decode(cut, DecodeOptions(tolerant = tolerant)) }
            }
        }
        for (at in file.indices) {
            for (bit in listOf(at % 8, (at * 5 + 3) % 8)) {
                val mutated = file.copyOf().also { it[at] = (it[at].toInt() xor (1 shl bit)).toByte() }
                expectOnlyOwnFailures("byte $at bit $bit") { requireFinite(AudioKodec.decode(mutated), "byte $at bit $bit") }
                expectOnlyOwnFailures("tolerant byte $at bit $bit") {
                    requireFinite(AudioKodec.decode(mutated, DecodeOptions(tolerant = true)), "tolerant byte $at bit $bit")
                }
            }
        }
    }

    // The definitions the hand-built blocks are checked against.

    /** 4.6.11.3.1 for a spectrum whose only coefficient is the first, at one. */
    private fun imdct(n: Int, i: Int): Double = 2.0 / n * cos(2 * PI / n * (i + (n / 2.0 + 1) / 2) * 0.5)

    /** 4.6.11.3.2, the sine window over both halves. */
    private fun sineWindow(n: Int, i: Int): Double = sin(PI / n * (i + 0.5))

    // Building blocks.

    private fun silentSceFields(): List<Pair<Int, Int>> = listOf(
        0 to 3, 0 to 4, // SCE, tag
        100 to 8, // global_gain
        0 to 1, 0 to 2, 0 to 1, 0 to 6, 0 to 1, // ics_info: long, sine, no bands, no prediction
        0 to 1, 0 to 1, 0 to 1, // no pulses, noise shaping or gain control
    )

    private fun silentSce(): ByteArray = bits(silentSceFields() + (7 to 3))

    private fun silentCpe(): ByteArray = bits(
        listOf(
            1 to 3, 0 to 4, 1 to 1, // CPE, tag, common window
            0 to 1, 0 to 2, 0 to 1, 0 to 6, 0 to 1, // ics_info
            0 to 2, // no mid/side
            100 to 8, 0 to 1, 0 to 1, 0 to 1,
            100 to 8, 0 to 1, 0 to 1, 0 to 1,
            7 to 3,
        ),
    )

    /** Coefficient 0 at one: escape codebook pairs (1, 0) and (0, 0) at a scalefactor of 100. */
    private fun singleCoefficient(short: Boolean): ByteArray {
        val fields = mutableListOf(0 to 3, 0 to 4, 100 to 8, 0 to 1)
        if (short) {
            fields += listOf(2 to 2, 0 to 1, 1 to 4, 0x7F to 7) // eight short windows in one group
            fields += listOf(11 to 4, 1 to 3)
        } else {
            fields += listOf(0 to 2, 0 to 1, 1 to 6, 0 to 1)
            fields += listOf(11 to 4, 1 to 5)
        }
        fields += code(0, 60) // a scalefactor difference of zero
        fields += listOf(0 to 1, 0 to 1, 0 to 1)
        fields += code(11, 17) // (1, 0)
        fields += 0 to 1 // its sign: positive
        fields += code(11, 0) // (0, 0)
        if (short) repeat(7) { fields += code(11, 0) + code(11, 0) }
        fields += 7 to 3
        return bits(fields)
    }

    private fun code(codebook: Int, index: Int): List<Pair<Int, Int>> =
        listOf(AacTables.CODES[codebook][index] to AacTables.LENGTHS[codebook][index])

    /** ADTS frames of [blocksPerFrame] blocks each, from [blocks] in order. */
    private fun adts(
        blocks: List<ByteArray>,
        rateIndex: Int,
        configuration: Int,
        blocksPerFrame: Int = 1,
        crc: Boolean = false,
        profile: Int = 1,
    ): ByteArray {
        var out = ByteArray(0)
        for (frame in blocks.chunked(blocksPerFrame)) {
            val multiple = crc && frame.size > 1
            val headerBytes = if (crc) 7 + 2 * (frame.size - 1) + 2 else 7
            val payload = frame.fold(ByteArray(0)) { acc, block -> acc + block + if (multiple) ByteArray(2) else ByteArray(0) }
            val length = headerBytes + payload.size
            val header = bits(
                listOf(
                    0xFFF to 12, 0 to 1, 0 to 2, (if (crc) 0 else 1) to 1,
                    profile to 2, rateIndex to 4, 0 to 1, configuration to 3, 0 to 1, 0 to 1,
                    0 to 1, 0 to 1, length to 13, 0x7FF to 11, (frame.size - 1) to 2,
                ) + if (crc) List(frame.size) { 0 to 16 } else emptyList(),
            )
            out += header + payload
        }
        return out
    }

    /** An MP4 file whose AAC track has [config] as its AudioSpecificConfig. */
    private fun mp4(config: ByteArray): ByteArray {
        val esds = Mp4TagFixtures.fullBox(
            "esds",
            byteArrayOf(3, (23 + config.size).toByte(), 0, 1, 0, 4, (15 + config.size).toByte(), 0x40, 0x15),
            ByteArray(11),
            byteArrayOf(5, config.size.toByte()) + config,
            byteArrayOf(6, 1, 2),
        )
        return Mp4Test().build(1_024, 2, 1_024, format = "mp4a", sampleEntryBoxes = esds)
    }

    /** Packs fields most significant bit first, padded to a whole byte. */
    private fun bits(fields: List<Pair<Int, Int>>): ByteArray {
        val bits = ArrayList<Int>()
        for ((value, count) in fields) for (k in count - 1 downTo 0) bits += (value ushr k) and 1
        while (bits.size % 8 != 0) bits += 0
        return ByteArray(bits.size / 8) { i -> (0 until 8).fold(0) { acc, k -> (acc shl 1) or bits[i * 8 + k] }.toByte() }
    }

    /** Damage may decode to wrong samples, but never to ones that are not numbers. */
    private fun requireFinite(decoded: DecodedAudio, what: String) {
        assertTrue(decoded.samples.all { it.isFinite() }, "$what decodes to samples that are not finite")
    }

    private inline fun expectOnlyOwnFailures(what: String, block: () -> Unit) {
        try {
            block()
        } catch (_: AudioKodecException) {
            // Every failure has to be one of the library's own.
        } catch (e: Throwable) {
            throw AssertionError("$what threw ${e::class.simpleName}: ${e.message}", e)
        }
    }

    private fun fixture(): ByteArray = Base64.decodeOrNull(FIXTURE)!!

    private companion object {
        /**
         * 0.3 seconds of stereo at 22.05 kHz from ffmpeg 6.1's AAC encoder at
         * 40 kb/s, without noise substitution: tones, noise and clicks, so
         * that it switches blocks and shapes noise. Its first block carries a
         * data stream element naming the encoder.
         */
        const val FIXTURE: String =
            "//FcgCDf/N4CAExhdmM2MC4zMS4xMDIAQlGQABwEI1kKsKYCCpeXHB3MiPPq01zJ0KrFtrWu4q+sr7SfMbYbAMzVT+DpL7HXbb/p" +
                "n0z6Z/q+jfBgYG0A6RI2GFq969XYqNpIAy8WHA9ijFsy4IIC0F6VxDpP0nFBwzS+Lxn9QzBmZbMsDvYBhJ6YMDP2wNKgjDLyMsbA" +
                "OHgnWZYyig269S93u61mcQbnGSry1JKsY7qeWOpBMUI3ZzdnZ5q9G55br6zL9/jJwpmlaSnv1OBf9NnGqqqIp+Jfao/2OVVSji/s" +
                "AP9ro/seIYFhhNEeoAGvNXFP3BG9n1MJbwdyyJRiNbuGPBnBwP/xXIAjX/whS2zLpWm/gXTxeWR7sl0Jsl2/9P9uPHwv113/5f/0" +
                "/86Ve5zX//j//p/vd1zqnf/8n/9f/37rz9vN14DGMxP9Oj58xJHaRCvtaf3enNH0eovDS/vgUq06cDQrvpxcaab+npIHiHAoIdZ0" +
                "RPgGeI4yOQ3OVJ5nAkcDlQlSzWbDL2qr2eyCnI5ucmq40e13SrHSzIOyAFBPc3yXF+YM075eJ+cSdGsgYTw+WR682R682R7f9r/z" +
                "Wl1U/7//37/2vdzdc//+v/+mv9rrnVH//r//ZP86vxd1gSioGCRqwOZvvHMNEgucmACUYoLioCu+nEBIBDF4MnH09QEIY6HQ7ib9" +
                "4ogiAAAAAS5IwnphUjHdegCLgP/xXIAY3/wheY+o39//dayy2uue/PNue/8bXNZMVxkvEq6lMgUcX07b3eIgVURBEptCEHZ2b6b+" +
                "UZwIWCA95WmvRiAZVF0jplHCZrDiQwoIClJ23LKj83JgQICZ+Rf9uEwnYdBupHbXzewMpmIMsjwRWLpNBr7bwy5BoR1jCYVrDJTh" +
                "GvDXXOa3OJrN+PiayYl5LxKupTdQb3166uDmThkuyNU+8Ky9NoFucrxHPpeP7z8KZBflaaIFbUkICqkxQA6AE0bgDv/xXIAcH/wh" +
                "GYgAFBHlNOyyuhGa+0y7nu/FZ551tR7eM4tdbzjL3qh3fx5MJfuzITu4AzIEfuZpittaiGbdHMFnyQLKthY/H59HGBIAHqtn6O3Z" +
                "Dypxh6vnUbfi49B4AP1eICiR30y774mBq75GCrkayfTU6UY4TUG16NuacvJEmHLxRFbIJQNuWvDXvzaKcg14a9/aZquu8m/0/xMv" +
                "XKSs42uozW+QdT0L4hk3Dfvk47Sii+P6fEwPhsGy+bpafbND8x/acZWTf1yKAmwBGRL5xYtP2QvCpuBk8HnuUGEc//FcgBq//CEZ" +
                "iACN3/+UsDVIqJiEXnqlx49fzJzIvJvzlKuZEoUCqDEuNIlFLTOm3X9LC8uliPCm8o19tKVT/eQoWbvS2oAwN4KYxdmXmWXnV0GW" +
                "oxLZR18D2UG1hxoYmPbd4xuIC6kDiVQezBCKntwzN2kzMwYnYecZhFXI4QY21JqmFKUa8JBl1rdTPn99zjMlzKVcyJRuhbazh9TF" +
                "6e4091Wz6FjOiNyGXwXQXG+3+vA5531XqU9H4Va/2v2/DAjR1nG9QC1ISXBFUAGsuEQAsLAH//FcgB9f/CEZiAAGAxI1bNKiBIjL" +
                "Vvhm/X7zKnmvUutbr4zdOu513Aff4d2r9+P8Vdc+C+E+CsTp6ggCBrRp3Zd2VKIf9v3Mafa72mEiKl7UwoJk19np6qrJHuyXxVc5" +
                "2uLSDQv1yY1VQu1uoHxOoVM/D9vhyQJr7IoJHnd93zbraHw9n8/duOQu9nUFQZXuaM6ts04xXSSzMRfErj+k1nDvTx7/yVnW7UmS" +
                "UQncHbZEjdxo52TSIRUWvErS/G0DcjKFBC0r5mkjUeqJgXSmAilUSXw9TTrOfg0ep+tIxqBYsy0j42KNVf6ssCgDTmDz12cG3cKH" +
                "2ebTvP/xXIAb3/whGY2Qj///k7A2Qm1a+3dcc7zx/jm3WU1hKulXUKnNjixKGZqmGgEgoiIgir/EbO5++cXQAPoSCribmzjHFDaF" +
                "UrjvAputpsgVYDZMW8iCzYvLNDYO9l+K7EX5i8UxroruefnhP54yE1aaQPH3N1Aa5ZhI98i64hE+S3fzGyYLfknTYEMktoXdSali" +
                "pWTXmDk6wyRBBr4mcfbA47k/T8aymsJV0q6hU5B1z00qEfNXz66722222KFRuPhJM4hBgjlcCgCwmA0ABQGYMpFRtWRHGcwCICy5" +
                "wP/xXIAbX/whGY9gHb67kqzykgSI7z9OLznNteUlecz43vrms6711vnVzxoN19f7+b92ZmZAd3eutzxxZAB3d3d8MHDJ8fHw2B7+" +
                "8Lr7+YB/MPcs8IAf+HdBw2n39wEzZgBnIBXx8fGbP76M2gH+DAe4K+ujoKp8zr7ef3mbAT75fjMM78HvoHx8ZgIW/fQB7nflSXAx" +
                "twnefMvjxW3nmuJU1nE31zWmOtqucgfJ8fADuf39/ckJhCayu6jUamb/EHhB/CAAAevBiiAp4QAEoCn8R/DAxRAAFMI4"

        /** RMS of every block of each channel as ffmpeg 6.1 decodes the fixture. */
        val FFMPEG_RMS: FloatArray = floatArrayOf(
            0.0154291f, 0.0058462f, 0.2304279f, 0.2288054f, 0.1797295f, 0.2604327f, 0.2266171f, 0.2142308f,
            0.1878438f, 0.2114787f, 0.2429378f, 0.2292318f, 0.2180935f, 0.2118143f, 0.1503804f, 0.1402150f,
        )

        /** Samples of both channels at a few frames, as ffmpeg 6.1 decodes the fixture. */
        val FFMPEG_SAMPLES: List<Pair<Int, FloatArray>> = listOf(
            1500 to floatArrayOf(0.1461487f, 0.3386087f),
            3000 to floatArrayOf(0.0962129f, 0.2025784f),
            5000 to floatArrayOf(0.1449993f, -0.0088925f),
            7000 to floatArrayOf(0.3381162f, -0.1434926f),
            8000 to floatArrayOf(0.0094064f, 0.0003307f),
        )
    }
}
