package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.internal.tag.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Checks WavPack decoding on every target, against small files the
 * reference encoder, WavPack 5.9.0, wrote from a deterministic integer
 * signal that [sample] regenerates here: lossless files must give it back
 * exactly, and a hybrid file must give what `wvunpack` gave. The JVM oracle
 * suite runs the reference tools on many more files.
 */
class WavPackTest {

    @Test
    fun losslessFilesGiveBackTheSignalExactly() {
        for ((file, shape) in listOf(STEREO_16 to Shape(1200, 2, 16), THREE_24 to Shape(600, 3, 24), MONO_20 to Shape(800, 1, 20))) {
            val bytes = decode(file)
            val info = AudioKodec.probe(bytes)
            assertEquals(AudioContainer.WAVPACK, info.container)
            assertEquals(shape.channels, info.format.channels)
            assertEquals(shape.bits, info.bitDepth)
            assertEquals(shape.frames.toLong(), info.durationFrames)
            AudioKodec.open(bytes).use { decoder ->
                val access = decoder as IntPcmAccess
                assertEquals(shape.bits, access.bitDepth)
                var frame = 0
                while (true) {
                    val block = access.nextIntFrame() ?: break
                    for (f in 0 until block.frameCount) {
                        for (c in 0 until shape.channels) {
                            val expected = sample(frame + f, c, shape.bits)
                            assertEquals(expected, block.samples[f * shape.channels + c], "${shape.bits}-bit, frame ${frame + f}, channel $c")
                        }
                    }
                    frame += block.frameCount
                }
                assertEquals(shape.frames, frame)
            }
            val floats = AudioKodec.decode(bytes)
            val container = (shape.bits + 7) / 8 * 8
            val scale = 1.0 / (1L shl (shape.bits - 1))
            assertEquals((sample(100, 0, shape.bits) * scale).toFloat(), floats.samples[100 * shape.channels], "${shape.bits}-bit as floats in a $container-bit container")
        }
        // Three channels from a mask of front left, right and centre, carried in a stereo and a mono block.
        assertEquals(ChannelLayout.SURROUND_3_0, AudioKodec.probe(decode(THREE_24)).format.channelLayout)
    }

    @Test
    fun aHybridFileGivesWhatTheReferenceDecoderGives() {
        val bytes = decode(HYBRID)
        AudioKodec.open(bytes).use { assertNull(it as? IntPcmAccess, "lossy audio has no exact integers") }
        val samples = AudioKodec.decode(bytes).samples
        assertEquals(1800, samples.size)
        var checksum = 0L
        for (i in samples.indices) {
            val value = (samples[i] * 32768f).toInt()
            checksum += value.toLong() * (i % 1009 + 1)
        }
        assertEquals(HYBRID_CHECKSUM, checksum)
        assertEquals(15658, (samples[0] * 32768f).toInt())
        assertEquals(-5021, (samples[333] * 32768f).toInt())
    }

    @Test
    fun seekingIsExactBecauseBlocksStandAlone() {
        val bytes = decode(STEREO_16)
        val whole = AudioKodec.decode(bytes).samples
        for (target in listOf(0L, 299L, 300L, 301L, 1199L)) {
            AudioKodec.open(bytes).use { decoder ->
                decoder.seek(target)
                val block = decoder.nextFrame()!!
                for (i in 0 until block.frameCount * 2) assertEquals(whole[(target * 2).toInt() + i], block.samples[i], "seek to $target")
            }
        }
    }

    @Test
    fun truncatedAndMutatedFilesFailOnlyWithTheLibrarysOwnExceptions() {
        for (file in listOf(STEREO_16, THREE_24, MONO_20, HYBRID)) {
            val bytes = decode(file)
            for (keep in 0..bytes.size step 5) {
                val cut = bytes.copyOf(keep)
                for (tolerant in listOf(false, true)) {
                    expectOnlyOwnFailures("cut to $keep") { AudioKodec.decode(cut, DecodeOptions(tolerant = tolerant)) }
                }
            }
            for (at in bytes.indices) {
                val mutated = bytes.copyOf().also { it[at] = (it[at].toInt() xor (1 shl (at % 8))).toByte() }
                expectOnlyOwnFailures("byte $at") { AudioKodec.decode(mutated) }
                expectOnlyOwnFailures("tolerant byte $at") { AudioKodec.decode(mutated, DecodeOptions(tolerant = true)) }
            }
        }
        // A cut file is truncation, and tolerant decoding keeps the blocks before the cut.
        val bytes = decode(STEREO_16)
        val cut = bytes.copyOf(bytes.size - 100)
        assertFailsWith<TruncatedStreamException> { AudioKodec.decode(cut) }
        val kept = AudioKodec.decode(cut, DecodeOptions(tolerant = true))
        assertEquals(900, kept.frames.toInt())
    }

    private class Shape(val frames: Int, val channels: Int, val bits: Int)

    /**
     * The signal the fixtures hold: a folded ramp, a faster triangle and a
     * small pseudo-random term, scaled up for depths above 16 bits.
     */
    private fun sample(i: Int, c: Int, bits: Int): Int {
        val triangle = kotlin.math.abs((i * (3 + c)) % 400 - 200) - 100
        val fast = kotlin.math.abs((i * (17 + 5 * c)) % 64 - 32) - 16
        val noise = ((i.toLong() * 1103515245L + c * 12345L) shr 7).mod(23L).toInt() - 11
        val value = triangle * 150 + fast * 40 + noise
        return if (bits > 16) value shl (bits - 16) else value
    }

    private fun decode(text: String): ByteArray = Base64.decodeOrNull(text)!!

    /** The smallest fixture, for the shared hostile-input sweep. */
    internal fun hostileFixture(): ByteArray = decode(MONO_20)

    private inline fun expectOnlyOwnFailures(what: String, block: () -> Unit) {
        try {
            block()
        } catch (_: AudioKodecException) {
            // Every failure has to be one of the library's own.
        } catch (e: Throwable) {
            throw AssertionError("$what threw ${e::class.simpleName}: ${e.message}", e)
        }
    }

    private companion object {
        /** Each sample of `wvunpack`'s decode of [HYBRID] times its index modulo 1009, plus one, summed. */
        const val HYBRID_CHECKSUM: Long = -409481212L

        const val STEREO_16: String =
            "d3Zwa8gDAAAQBAAAsAQAAAAAAAAsAQAAMRi8FMobMZQhKFJJRkYIEwAAV0FWRWp1bmscAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
                "AAAAAAAAAGZtdCAQAAAAAQACAESsAAAQsQIABAAQAGRhdGHAEgAAAghXV0dIQ1dHSUxKSEtNRFdHAxB8f/T03dxhZn7+/hTgpAsB" +
                "7OkN+UpE6fgrHif09/fb4wQEhPfw9v4OBA8FBnYNlQ3/DrYMJQ3UDGUCGAAAACoAioABAOdM6x5ivp3JsbFJJfGQnPvnc8RizJYy" +
                "L/et+sehEAjORXfOQFDcEN/ME0TOQqtrCqeqbzU4R5IDoiQQofHYrZGrfBVAorcYUOhK2KhUSZ0pMMhcmtCO4k01Jg6DIR8OpYSB" +
                "5EStYLqDqTsml6a5Hvg1lIQ9+7q5zZ0JvDqF8RcM6MT+sfwSLN0if3Z/L77e1a62zXFL9x0LE1BkvSogf1PImd67Ll2EFkhyMvwf" +
                "5xGjo/NuWZk606LIk2F4zxhUdSfhpsusy8FDYfHaRjAcIBs3lxtu6YTAErOKCOysT85GTNfWpWAWo++4sMTHUJT8njjakDgIkNsR" +
                "gfTdw05bMlYde0z5h4h4H9Ek8EPTU0n9hHx3oY/Fp6JcLV2ubf5iQurz5UQVCUi7N0QtcRmOTjZN7UUOg1w1j01NM29mVNU+IQ6B" +
                "EYOGrojhngQ2ZKB8oxhdktTNYTMLCMuv8zN58JKHeq+1XFQZsN+S0mjB2o4JJBu3ypEhHM4tz5xNLoNFU2cGurB08HkwP/v1xD8L" +
                "v1zKkYvbkbvIzLV0XePC504Cg2zTlwmjYZVBvaPtdm3sJMU8BbuUi/QFagaEGox7sEakUpAO/rplTlIAK9lCBjDsDMvgSZURJYKx" +
                "S13HOSiC5VnC/TgtJJaIa2s7Gl+bVg5Sa5JGsCX+omwBYZHpHQjNIiDE63jC2P5CrpMP0Bom2eM0z3G8v4A5W8MuMNXbLHG/Efpo" +
                "MF3m+5kBKoQc3AK6htVQhstx7Swjqg73BiFwU4CuF0oPEw7cNsNcSQOycMjxihVkADs2ZER2i+THn43z8pEwVemVHa/HyYCQ68x6" +
                "L8OQd1E0iPptbeodC7eKw2/yeP0yHcPeoA/Zr9TJfWr/9XOPVvwQKMieFcyalHNfuhkk5fvngcJjlbnPnlGuIyVxxX6g8/hSRuTx" +
                "dAgLCAtpShcekoZHwiy4LrXc8OdfDmQdsmtAADdzqkqnipkDwMuztJsWd32n1IDfHSK9tN9UsUJhYjKd6UbSLIWajWne/S8CLRgd" +
                "9Hd2cGt6AwAAEAQAALAEAAAsAQAALAEAADEYvBQYBCDrAghXV0dIQ1dHSUxKSEtNRFdHAxB/fvXy3tpjZX/6ARPfng3/6+QO+E9F" +
                "5/cuHyn39/Lb4AQELfFt8dMN0w0FBjMNmA1kDxAMnAzIDCoAioUBAHF/Jz5d3fbz7AF8J/j/l9zz9suSVX+Z99fVs8XZ9Bw3yFp4" +
                "3NNuT7cjgsX0n6RGMD00bGZD4/aZXITbjXAfh9C85aQV1n7WFx0FRUeTRTslQIqrbzb6woZTQTsi7XdQeOGlJNPqFg2Hjdb3EAOD" +
                "rj87yDF0OdeARYPSrBDYxsxQHXCqOnExGCncEWM+3j2AMe7kl/0/CxQ8r7lH56BjxAY/F9fSy6NroyCuTjrctS2RseK2oTRavYID" +
                "WJ718/k/BS9GaR1uj7GQB6kxqIfXaUJknzskA7htqasCFQ/Tol3ck4DAGhdRKJqKKMil0aRfs3u1AcPMcDFWzQcH5tLpCCMv7n6I" +
                "XCZXbCjO6eDwJ/5Gc7W/Sv0v93iSEPh+Ft4/YHJurgoZJKoypR3jMtclgo1PB7CEggmkxHWGERFcp7hOOhHKjXepS1jH3Ms6uAwW" +
                "hZN7GSMJp7VzwU3g8AmpLQUnRIjYTi+6riJtu2dSkvokhC6LRDEGux1OX0KjIMRV64XYG9JUIz72TSDvYi/pxTinxufviXPlrGOZ" +
                "989JY4ZbkEeQ+cxUInAACHCL/Tk7qnhaL21yvEbsZpxL1+xLOm+EbJ81f9ponulBSWcVUEaxii8W1ySEE+RvM73MoxZ/ah2EMx61" +
                "ZVDTNMgEBELIXhxJ5ozsiPSuUiDUoxyuFtRYQzTkKYdGUMQYTgnQ+LeMPJ/AKvmx50mWNyYJ7up5vHox4G54AHnMpdV9fu4RCUEN" +
                "Fp6g7gezG6OMW9Fb8A15RhBqSVIApXCKjQrWyQ7So+VaqPjGAOfgRt4F8SkRwQG7XSR4vaBFPc8MPrUDx5FRyGb55toqbixMjZCb" +
                "wUz6XTcp6sRDzX4LsWUSoEH957lxu+VvxvfUt4aVzDZ3RU5c85xN92paVe/ZV3MHyIDEiaKqa+UvpESlaw+V1BBcC05NlmdeaRKS" +
                "VYrogVcKfvS0dEpqWFpzTlQF4kEb2Nd9v5SKAEnfTOtZtuJH+R4ASDvcKutqtLESgHCKxrJGt6BQQRcXrImnrcBq+v8vAi9jWz53" +
                "dnBrdgMAABAEAACwBAAAWAIAACwBAAAxGLwU2tXJbAIIV1dHSENXR0lMSkhLTURXRwMQf37v8NLVZ2h++voP4qL6Avz5DgtLPesI" +
                "KBoj7fb46fgEBBTwD/D1+fQFBQY9DQsOgg6iDHYNXAwqAIqDAQCjyfCJpyQ3383d7Qpv8rAT8a9Q+5Gj3JlN4syQ88ADKYqUlcXG" +
                "EzuCHaYjbNqqs3tiuVMnP1qX3+TMkAcqZQ5kZE+zH9Ckozi4UAhVqggpDNGCCLFYlmhjBFiwm8OgZIK04DwQtzhooy8wQKi6HD0D" +
                "L4ABgCEo8wCbAGEZnD4w2DXPzZ/NZ2vLH/lv5y3r2bI+6L07WndBmP3MQFOFf9SFaYDegmDxOlp7AZKLJ/AgZMlvxTd/BC7T0V5F" +
                "oHT7+meMZuxu0CeSpYsjgi8ssWLYTg1DCLQJheX+xAsLUWb8LCRwxRu6gZVecYGRVFRueiqKTJLcWdfwwaZsOOts416zoOaxHt8x" +
                "V/hFkNSvKktV4ct1R/+R3Acg4oPdmdhTKl6zmM9G86uiaB80UXWy5hSw8av7G9PURoRQWO9ELZtTVgs2QOG/CesSTYaVPgF3Lg0h" +
                "gp7TXS/zEcONv8lAs/x2+RiSynMVNozCvJ7eRhmOpnDVjuflZmsHhfEecQ9+Ng7PLBS6VvkkiOVjdCw+DP82wql/OzbqliN3PNYR" +
                "g32r5WRSHsAAI9VCqxQNW4iCFb1zWJ16A4OZRAHwkoOB3q64Yb3PY1n7As7yzKDlFQSqq5tBg3NpAfoSx94w3GwKpbTeGJbRMLDE" +
                "sqRYBdFXijisjewpIDm2KkX0psoGl0OxVLyzSfN/8fqzvCJF+rvK/2/6JUam/gZz/13DnGFADsSRBcicq3Z5UmSuP0tEZkFdzOI5" +
                "UKDqkp3g/QNY1E8HQFQCL+jwOTHpjEBzo8hHsEh1rNgbGRo9+DinAlWp59x8/pUSGNbaALJExSZ4YDjQDIa3lFzwbzxxOzBO2iVT" +
                "XNTaPKYip5X9PP947Prn/+V7MhyiH++VuucMP0GP9YzmeOim6BcM228nqs7Nc1hs6wZN7HdJAj25uYEkLMyo96RrNBQZJKiSaql3" +
                "gPq6gW8RgZM1btjapaFed/34UoCfzuSt8CYMwgnqffFpjLdilnGqOTgBtMV5t3K9p9+7jsKYiFWnCx6s/f8vAtAPStB3dnBrbgMA" +
                "ABAEAACwBAAAhAMAACwBAAAxGLwUDuTGbwIIV1dHSENXR0lMSkhLTURXRwMQfn/u8tHVZGJ++fgQ36H4Avr6Dg1OROkKKhkj7ff2" +
                "6/cEBBnxHvHXDZMNBQY4DZsNGA9eDAENbgwqAIp/AQAPXXqPkh6PSdPvA8ED+S9g21vcpw/Dib2kKnPlkoJ5aR84TTIQ+DxLi8Ru" +
                "RgUT+lZofGNLCBnqcLqCSTPW9MeYXMYycw0REvBmU8cs8qIlTY2iARdPTlOGehd3Y7+eof82/1tFdNSHaWW9kHVCM+RtTLzOyqAx" +
                "CMqgVEU7VayyDsaDQeX/lJmw8XvJX3EPTg7pxOSSLURcPZxeZ+yu3oIyaUZceaHTCi+ok07GsL4WCyQ2QJYHY7C5bjHujk5WZssP" +
                "KoGEZunG5+SIpQ+M9ISigNVa0oydwnmXKF56lbfQAJpt0NIPF2CW4UKjdijtFBQcRUP1IYPYfNY6aYmlWoVp4T6+8QnPZ5gj9WDE" +
                "D7RTAHk83MV7LocSe2GXq+rNZi+x5YTS98u7jTHsZcYmmTJWBte8O6CYMKYxoN1pjiaALaOOOpbJtfrNNWYET0B4jVQiApGPOCmB" +
                "GQ4QOSPqDnlH0ITbcETLi8IbFeU5kKssJYS/Kpgi0zLT5acxZgRRp7F72dA8Px78rfWTFmIOzH79jqeOa6Lp1owd43zo0xyzHWUR" +
                "xcfujod/eDgSfJxTM+yxOPBQ722rvt2T5v0d05B7weT4uF+aigqvA+dmEjqiUGNOvIl7yRak5QiAMq21TrnPVpAN47ZUltqwRYHY" +
                "awXayx9YyhpE8FICTC8LMJXnEyLtk7WrPVSL/jO2+UeQS1B/iTdbSGKHIsRHHdAOQO7hqlBPXs2WjhfAG0EdWbp9Vp4lNVpCl7Pv" +
                "+DrFWdIAyFU0PToPCqIjlBexxOIb4Ag7+hE3o/879tSYvOtuJ+l+s1C+FYtal/JytU6UOck8xBcrIxyMChmNbj/GpGJlDBOtf7J7" +
                "LH+27tjb+NR5cgB025iW3aV4jYBTGexP2eJeYZIM7DbwzIwKgEXgHz0lS0FUPlKlzUxXNhh7KoupmN2yUsAYYAkZkHysOBO3sKYA" +
                "RAHlzbgiJMRtqowMpdFu6ACSQfuZ6gg1QbUuISs/me48reiDouAQiUaOEOU+hW74LwI4Y8Vi"

        const val THREE_24: String =
            "d3Zwa+4CAAAQBAAAWAIAAAAAAADIAAAAMgm4FO/jZwAhNFJJRkZ4FQAAV0FWRWp1bmscAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
                "AAAAAAAAAGZtdCAoAAAA/v8DAESsAABkDgYACQAYABYAGAAHAAAAAQAAAAAAEACAAACqADibcWRhdGEYFQAAAgVXV1dDR0hKRFZJ" +
                "Awp8f/L59/fgB9TYWmjVy+MfFAhGBgQEhPfw9v4OBA8FBiYOPg29DDcN/wzHDAkCAAgAAA0BAwdlAggAAAAqAIoLAQABYC2Tf/qd" +
                "q7brXKm56/apxiiquXZF0vJOkvxGV1IVm3DKYE1rpa0UsPJQEhOvWPknumji5Wt2uLxcOUI7bBQ7JJwwnTvzaJqVu8QubW+6jPVS" +
                "4d6wFSqzsCT4691mrYZvtUMrcWBgGtfl8+PV7nTxq97F9Ev4XmBujQXAk5DK/tJA4BnCxXZg/7/h1zHs6C36DUEahIcqwka8dg8c" +
                "4upoyXBFY9OpGJX2uqJkLEccVJP7OG/NWo6PFSOQdo94ogXooCMIWCHe4krNQKOGTUSppVClQci2ZBMRuW+j8SGawf7kacwbvg18" +
                "5c7Qno6+yu0bv5SzSCY22xur3ixGmsuOWKDQSocAnd8or5iuubwducsPYJ5RPf4vE7U8x7vMeoR3/w3scwx+tNZgk8WOQIifbDOd" +
                "Of6vq1LFcQ0W869AS6U/U6ypvcka1K8C0SOwto7MBYxskfnZiIVBkFCvV07UvpSy4PkJwnTbiy/uSPMIDqYpAtqb2oSi1EHlmYCA" +
                "vAjduDtY4oEPOyJP5m1Gpn95hlKnqnJ/OZbH7JP4XXBgUWO4fldJeA9bb5wCO22pkzDd0exdrMpRGnJ+Pd+q+QUERYs0RgV8H8B+" +
                "aYMxeYyrC/kb1P7weL9kwzUkOpBUe2wNOWE/4NvyAJ6xRCPCsXSCNbkUgm74BnnvGGTCYfEJP2bQ3EFv1r3/ZJpKimbcSJYpM6oB" +
                "IvYvAklcAZt3dnBrZAEAABAEAABYAgAAAAAAAMgAAAAGEbgUMXj9OwIFV1dXRkdISkZWSQMFfszuKEPqT7sHLgQCBA8ODwUDAA5H" +
                "DoIOCQIACAAAiosAAJfkkF/SZ0KebakcYvGd7vXUpNcoPYwVJsIuzBoHpI03HfAiU1B6IDojg6J3w1q3OOCcq5IkMVJ28r9xthrd" +
                "irowDsGrWtQAEENOLloG0eWRmC5NfzBoWoUJWZQpWaMUItMiPpBum7oHhwxtUJ5ilun+/P8863SQxu3RhjZ4AhuM4vUlPbrdSFWQ" +
                "baNfHSB5tP8v7xvDMjEolbbadhnX10laPeqD1aQU7NXu1fTdSon7hH9XGqBBrnNOmjykwFspwSuGEd7CO84M0+0uSEMoElpbOlca" +
                "hcRIcKTtkUTTFnB+PH8+qi0pnXqLwCr4OiOiogANJLCNipzCj+b6WsGB40I4frZqH+0XiG5g6CXNWrw62NejVqH2LwLyYIgtd3Zw" +
                "a4ICAAAQBAAAWAIAAMgAAADIAAAAMgm4FAkHU+ICBVdXV0NHSEpEVkkDCn999Pb4+N4J0tpeZtPM5B8WCEYGBAQ28DDw7PdG+AUG" +
                "Ng4qDfkM4wyuDK8MCQIACAAADQEDByoAig0BAP95/1Ayp9kfiU/xnXh8nXo9xqOy7LhD43BL7++1mx71n2mt0NJSjW1tUCVTmxcH" +
                "bJZfFJsHgy2FR7Ek5sylP73sX6Ie9Maf9J0u5X1Tuu2QQQ0T8kCDPHkXbB01yonb1eRVLxDu3wjAsw8SPbIxoEkQZ8W7/iITVpXF" +
                "VcG81gGg8xV0rPO+M1dsP6Cbo/hflSiu3A2jDeCGvG/xbAjF1/QujAmG5iDyW/9tB18t3ffrymTEqJrqNDslz8/21lcKuPu8XfIA" +
                "vxLJZzJIhs+mcQDQE4h5FV+YcqyzKcfPF4w02R6s4UCuolca0Dlz7VJSqqFnqRD705VSaw08m+mJsQPJ3I4wLq1ZEAB0Y+LrRPaK" +
                "fmsGfuXbWTE/sXcT1eNMnveZ3GjkNwag40LrsUVhiLZXbZXv4sjyDSbqpFTp4PO3b1Dn2JA0MWajzl6r18DiIlC2H4CT5h8ERSs2" +
                "Z5otj5LsMORa6xTNVH9npv1rFhMbKfVr9zAgrjP1WoTWdi/zVpemQlLCgQU7plqk9I29g3sS/vhuEeT/Qv92oDwEXt8nA2/DFmyx" +
                "FmQZ2vKP9Tbms9YuUUbTrWoWUtMmtBVGPZ3WQR7g35AzUR4CYIVG1o563tksM9upj1R8+E6VdK8bhHRUJqmh7+ANgRTV3OLT1j4H" +
                "cKZ3G7fN1Qbl+Ff6Fczl9xKWZbwguWjtpo85rMtThfVZMgsjts94/P8vAoeRqmN3dnBrZgEAABAEAABYAgAAyAAAAMgAAAAGEbgU" +
                "4YOJBgIFV1dXRkdISkZWSQMFfcvuKUXuUrgELwQCQPE58QUDqw33DWoOCQIACAAAiowAAL+I//wnFdjz61hXaqmfKEccuoqEjFzl" +
                "PM2qjMabI3/JUpJTW+aZN84V3ROYJF5WKdGukiyek1C51XW6/VTOzdNSUhvTCK8qpXUwLgd5gQofLc27FhLIW3xMcnFcsKMIXiTr" +
                "Cz0H4LkBwC+QXfcwYBrhx5bvf+0Risl14VyqAUwsAihpqy+B82o7UgVnucTkEwOhCimmrUFjlCnQruuIwJTqAdFF+apDos0Osz9l" +
                "vxLT0Q9Qo1qZYzu0GQqhH5EuHGGuIMWc/Gp8+tDwALLBqABca/E4UdKTkXhWBkTZL+3u6l8xY485IZsP9+zTLcZ8gQjcjMaY+NVT" +
                "hA2Oqhk06quGDGBqhC57RvICYB4kcz9iZaAQfTbFJv8vAh4fMch3dnBrggIAABAEAABYAgAAkAEAAMgAAAAyCbgUCZnbQAIFV1dX" +
                "Q0dISkRWSQMKfn/y9fX33ArM2Vtl1czjIRQIQwoEBPsJSQfdDsYOBQYdDt8MZg0aDYEMnQwJAgAIAAANAQMHKgCKDQEAKXxof2+R" +
                "/KXGqkqrFbzvKvYxHtJOcOcGIi/n6pODwkAYjJ5jCP7qugR6J7QJU/cFnd79y0Ey8xR3tE6Xiyq5On89amPORm4Gg0V+pbXPLSYA" +
                "BteKOx7uDuXEwzK3zj3vrKFCccJ+j2dWhqCLPNr5tooi2RcZpX1SZwl023yEDMcKPh0V2dfdzFnrr2PV790365MX2AKvwYn/r6Og" +
                "ckbUJgjGq5MccF4wR6vAiqUtDbP7rkHfxnR0j39JR0zxgYLJAGQD2ODj0uYr3HXVhHkZiDt4uO/H40kfUpNiT4AM3kRbJgNML9MH" +
                "hTQfNC+A3WRbQwPHp7abGPX8zAwM850BB+QV0TdLTB4pnes/Gl2c8x9nj2y+d/bE+loiCDmo29UFyoimUzEQW4CVwz23NWCMEdIn" +
                "NZXd5vhML7WJHN9Y+JCfSclEEfJryzEhrDzdgnA/zsNXRJznS2PXVyZ/nq8v/f1pjgbz1tWF3KZNwG7xrmbV7yJjocUy+o76S/zs" +
                "5Z66M9WSir3aFT1XDUBuzWUddZILBibxX/izpt/njcW3LFcPIJcjLzVkuRa3+hBEwW+kghljNqQK3CF6BCHPkB6QZ1OXIQYo7MIh" +
                "lapWCXpxeOBtQ1MYnGFP/ICtj3sh13mvEuMeCW7oZt7ngVB3ZuIqdluQ8PleHSxeGat6CbnKWExjdToMsYhtbLNFbDMaGWB1YWKg" +
                "5N9RCfL5/y8CmjRjWnd2cGtoAQAAEAQAAFgCAACQAQAAyAAAAAYRuBQa91izAgVXV1dGR0hKRlZJAwV9yu4pSPBVtQQyBALHDsQO" +
                "BQOoDRYOjg4JAgAIAACKjQAAL5Vq/pkJ1pHw7Rn6ThXkZPoyaMfcZADL8LRYEWXtUUZcCaaaEVbW7xKaWh5BDlVxsH6mr4mKLIe6" +
                "1eO3lacsX5bAnd+NU/Rp3Ph5sXvSmxcQ5ozuBYOEXsI5qGhhKlZ4kgqSZeohPGmhBePISHqVnAmMzT+MXm9pdrmKvmioTtj4kKSv" +
                "ykYlL5E6n8GtxOYZ9YXVOhCu0bWc3gzWTOjBIH1elRTonpRXU0uME+6HD3asNPG1yfKeLwUVpsKGnUsUn80wQS5dJD8jYiSF2dWR" +
                "MggkxF9Q6RzhytidZjI8ctYdrKfb7Z+NYu2uGFJk6cuX3fTuxKIIrKCpAetrd9qHcjBRPUZNIJsQWx4peYfZQ+4pAJWQoxraY/n/" +
                "LwK8Nooq"

        const val MONO_20: String =
            "d3Zwa34EAAAQBAAAIAMAAAAAAAAgAwAABpm4FKtbHwshKFJJRkaoCQAAV0FWRWp1bmscAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
                "AAAAAAAAAGZtdCAQAAAAAQABAESsAADMBAIAAwAUAGRhdGFgCQAAQgM2KCcmLABDA30chiI8AAQCCQ8gDwUD1AwSDOQLCQIABAAA" +
                "JQIAAAIDKgCK7wEA3qD5ev7/LV6bAITIQLwTZG/kXQU+iuu71szNBzQUIA1hp+Y0h+lHe8QcJ6TRgGMjswNYdC73gzPfD5nyBHzj" +
                "JYstrELZnD7Wrbbxffi8I9f21p10bGscn2ZateuM9RHZPztzx4d4hgWmHlymCbqAc7kUKsSc0SNFsa24Tongoqch28zSHztDIOxX" +
                "eafWctsQggLLCnp5VfMGPUQf89sLafxaXnBj82QnH3vGb0CttNWjZ94Gz6i1044WSkwrlOAwXGUD2jnaXiP5L8ZpE8ClSkkVV1rQ" +
                "XV14m/dRIkJTepanMzDudnkqJvdRlyOX9zd9pix70jHj2+L/UmX/0/nH2Kii5v9tJgtaECRjiF2TbjSVSjprJMdXS2Mke9mqpMql" +
                "CmcBA1xtSSm3GG1YNq3kApaJk3SSlA+eMQ7jwTwPIqe6gRZ4LVuBCVnJjk+jD72ikbr0PTVinGXEUHIfA7G7QJfWiCSH/OOyRMYg" +
                "6aLHQzVO7bzVGpZuWxM8aG0VJTZQQjWy6dSlePifIyZx6Z+uEGihSlvZeKtoS1fnToH+gwKAQhJOem8OFv3+diJHsNrEMeRc74oz" +
                "E7rKQSPZLqyatcoZA4Oc6o8HMNVrcQQl3NrBCtmMDSOS/XjbeakvOhFNUOwBxhDJ2ypE3d/zMlLav92P4j/N6QOyot9vXpLPgt1r" +
                "smHQ18S5k7jDpwovfwhBY6WttkByWVEMRa2lvPCGaRMwlwJ1onN9/ynpo/sYr/HPHHbhsS+Q57wLHiPPfMU4xNMWSrwFFcdlpK4F" +
                "5v1pUuSx03ZvugGDCBREgDgHLgpXiQObzovstCXgnrPRGYCMYFLhDgUcBvnHvaPA4HojIAozaorYpfO8dJS9iJnNCnpfIKhCL90j" +
                "L3nuUYtDNrx/Fgg9dNyNQBA0sHK8FiIUc2SmH+lKTQypOq/PfA4lsENP/AT2ZB3Yy71cf5vzcKeqKcEIpeJ7J/+bDj2EYBfFiJXo" +
                "btax8g/3/1+he57JgYOoeZTRbdiv/h9p0+AbkQ8Ru2nFOLJjZOIbVUyJYNRZJSg2I++FEUrxb0wcwp2CFkkoV8Yl+Ii1MReWS3sP" +
                "Wd0NCcNmXc7YVStwXOJ9se0mg36M4MiHcXGE7GS1ECTapXHRIoBaL2MJeHVlReFEGF8BwN+EieUz/aCO7sEqU5I33B+sghV8S+fp" +
                "86bH/K8JEwVP23SiR58qCPOvFXrhzi+7b0Hv+fosp7pml7nQLpQOK2E+YVVr+O71KQOYGoPBsFuMJqEjHLYQynu0FKjVICwiUH+8" +
                "C0vduyynxPx+/ii68QPlIy/KjpFq7jcoNuD/LwJD2uPU"

        const val HYBRID: String =
            "d3Zwa5ABAAAQBAAAhAMAAAAAAAAsAQAAWR68NCDXRCchKFJJRkZYDgAAV0FWRWp1bmscAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
                "AAAAAAAAAGZtdCAQAAAAAQACAESsAAAQsQIABAAQAGRhdGEQDgAAQgNXV0dWSAADBXx/9PTd3Pn4XGYEBIT38Pb+DgQPBQZiDq0N" +
                "yAytDYoM7gsGBEcUHhTIAAABZQJAAgAAKgCKcAAAIf+7+QyrHxDnBXQNoO/D5NuA2Jlqh0/8WobtLwruDWLHg+1ijff/7Q7PrQK9" +
                "82C8g7l39WeWTpTjgHB2MF/liXgUuAsVO5vIrmL29+fH85UBeDiR3NDAt4PQH8LODfCXCuwmDneXVM4P0NcCei1gzaX6R38TYCcD" +
                "+RoYszLA9IAWHJwu8/GuAPHXiaw4EL+KoXeB6D6xP53/CdyFJHodE7Y3Qi5EpQsBbrHQLkXYT9jqAv7FelxNPHmuxO7y5+dFDf1a" +
                "NOMOHIWJfd+LyLsI5kbMOxDhbDJfA89ZnXSrAK8vAVgUd3ZwazgBAAAQBAAAhAMAACwBAAAsAQAAWR68NJJN5TFCA1dXR1ZIAAMF" +
                "fn3z9d3b+vxjZQQELPFy8dINzg0FBpgOuQ3/DGsNZwxuDAYESxQiFMgAAAEqAIpwAACR/9y1fxVDVwq8F6LFSPwLktfhsGcBtJqB" +
                "b+cnsQ+KdgmEJmlPnr55CRq9mWTLBFZ4GBv07Mkhaya0wnjES2VsRYexk7NhD9zXwey/3bh8m/BKAnfyYL04vC4F6QyFwwfbG3v4" +
                "upj4/gA8VqRfDkX9Due3Nol3MkZd4XwVByF70DuZYiUTfR1i9mbSqQdS6In1omZuz/8c26ZCdQ30pQjvw5Plg2pjB1pjgvsw0VSa" +
                "fSUqKzHQb7HAbyLkf4sR2yTTQn+4KyjeESznwfhVzb2zYbnbiLkZ080H+1UGcwPm/y8B0QN3dnBrOgEAABAEAACEAwAAWAIAACwB" +
                "AABZHrw0hr2NKEIDV1dHVkgAAwV/ffH029v6+2RkBAQt8ETwBAl09gUGaQ6IDS8NoQ0hDDgMBgRKFCIUyAAAASoAinEAAP39x5OH" +
                "d/foegsiuUyUd6gFT7ANPfilgPJB7XMQ39DTdTFxPgRv3f+xFjSRa5PGTjzuYMi/VUG+hcCoKuwRCCdquUZ+7BUovwxsxXWy/ObG" +
                "6+Bh50D1Ygb+GoC/xrRdkH41Kqv5h2RiId0K0hPEDp/ILy7/4TqayKqAwTXiVT20ok/Eq2CLJr0foq/izNyJ8WpRKnmy7+e/GP9u" +
                "lNwZSoeD6Q2Nt1aD3wexY0VzjeDX5YP3AvTiJv5agP8W+vP2O0LpZMx6gHkXgZT9CXqmYiWTa0E/tmfI2imisEa8qsde1f8vAZWk"
    }
}
