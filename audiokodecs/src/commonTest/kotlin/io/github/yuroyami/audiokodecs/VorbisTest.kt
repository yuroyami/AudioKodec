package io.github.yuroyami.audiokodecs

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Checks Vorbis decoding on every target.
 *
 * The oracle suite compares against the reference decoder, but it only runs on
 * the JVM, because that is where a reference decoder can be run at all. This
 * one carries a real Vorbis file with it and checks that every platform gets
 * the same answer out of it, which is the property that would otherwise be
 * untested on the web and native targets.
 *
 * The file is a quarter of a second of a frequency sweep, encoded by libvorbis
 * at its lowest quality so that it stays small. Two things are checked: that
 * the decoded audio still resembles what went in, which catches a decoder that
 * is broadly wrong, and that a set of individual samples come out to the value
 * the reference decoder produces, which catches one that is subtly wrong or
 * that behaves differently on one platform.
 */
class VorbisTest {

    @Test
    fun aVorbisFileReportsWhatItHolds() {
        val info = AudioKodecs.probe(file())
        assertEquals(AudioContainer.OGG, info.container, "container")
        assertEquals(AudioCodec.VORBIS, info.codec, "codec")
        assertEquals(8_000, info.format.sampleRate, "sample rate")
        assertEquals(1, info.format.channels, "channels")
        assertEquals(2_000L, info.durationFrames, "duration")
        assertEquals(null, info.bitDepth, "Vorbis has no stored bit depth")
        assertTrue(info.isVariableBitrate, "Vorbis is a variable bitrate format")
    }

    @Test
    fun aVorbisFileSniffsAsOgg() {
        assertEquals(AudioContainer.OGG, AudioKodecs.sniff(file()), "sniff")
    }

    @Test
    fun aVorbisFileDecodesToTheSoundThatWasEncoded() {
        val decoded = AudioKodecs.decode(file())
        assertEquals(2_000, decoded.frames, "frames")
        assertEquals(1, decoded.format.channels, "channels")

        var worst = 0f
        for (i in 0 until decoded.frames) {
            val t = i / 8_000.0
            val want = (0.7 * sin(2.0 * PI * (300.0 * t + 1_200.0 * t * t))).toFloat()
            worst = maxOf(worst, abs(want - decoded.samples[i]))
        }
        // The file was encoded at the lowest quality the format has, so this is
        // a loose bound on purpose. It is here to catch a decoder producing
        // something that is not the recording, not to measure quality.
        assertTrue(worst < 0.2f, "the decoded sweep is off by $worst from the original")
    }

    @Test
    fun everyPlatformDecodesVorbisToTheSameSamples() {
        val decoded = AudioKodecs.decode(file())
        for (index in EXPECTED.indices) {
            val at = index * 97
            assertTrue(
                abs(decoded.samples[at] - EXPECTED[index]) < 1e-3f,
                "sample $at is ${decoded.samples[at]} and the reference decoder gives ${EXPECTED[index]}",
            )
        }
    }

    @Test
    fun seekingIntoAVorbisFileLandsWhereItSaid() {
        val whole = AudioKodecs.decode(file())
        AudioKodecs.open(file()).use { decoder ->
            for (target in longArrayOf(0, 100, 1_000, 1_999)) {
                assertEquals(target, decoder.seek(target), "seek to $target")
                val block = assertNotNull(decoder.nextFrame(), "a block after seeking to $target")
                assertEquals(target, block.positionFrames, "the block says where it is")
                val count = minOf(block.frameCount, whole.frames - target.toInt())
                var worst = 0f
                for (i in 0 until count) {
                    worst = maxOf(worst, abs(block.samples[i] - whole.samples[target.toInt() + i]))
                }
                assertTrue(worst < 1e-3f, "seeking to $target gave samples off by $worst")
            }
        }
    }

    @Test
    fun pullingBlockByBlockGivesTheWholeFile() {
        val whole = AudioKodecs.decode(file())
        var written = 0
        AudioKodecs.open(file()).use { decoder ->
            while (true) {
                val block = decoder.nextFrame() ?: break
                assertEquals(written.toLong(), block.positionFrames, "block position")
                for (i in 0 until block.sampleCount) {
                    assertEquals(whole.samples[written + i], block.samples[i], "sample ${written + i}")
                }
                written += block.sampleCount
            }
        }
        assertEquals(whole.frames, written, "frames pulled")
    }

    @Test
    fun aTruncatedVorbisFileFailsInsteadOfLying() {
        val whole = file()
        for (keep in intArrayOf(20, 200, 900, 1_500, 2_400, 2_800)) {
            val cut = whole.copyOf(keep)
            try {
                AudioKodecs.decode(cut)
            } catch (e: AudioKodecsException) {
                continue
            }
            // Decoding may succeed on a cut that lands after the headers and
            // still holds whole pages. What must not happen is anything else
            // escaping, which the catch above is what tests.
        }
    }

    @Test
    fun aVorbisFileWithDamagedBytesNeverEscapesItsOwnExceptions() {
        val whole = file()
        var state = 99
        repeat(400) {
            val copy = whole.copyOf()
            repeat(4) {
                state = state * 1_664_525 + 1_013_904_223
                val at = ((state ushr 8) and 0x7FFFFFFF) % copy.size
                state = state * 1_664_525 + 1_013_904_223
                copy[at] = (state ushr 16).toByte()
            }
            try {
                AudioKodecs.decode(copy)
            } catch (e: AudioKodecsException) {
                // The only kind of failure a damaged file is allowed to cause.
            }
        }
    }

    private fun file(): ByteArray = decodeBase64(FILE)

    private fun decodeBase64(text: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val out = ArrayList<Byte>(text.length)
        var buffer = 0
        var bits = 0
        for (c in text) {
            if (c == '=') break
            val value = alphabet.indexOf(c)
            if (value < 0) continue
            buffer = (buffer shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.add(((buffer shr bits) and 0xFF).toByte())
            }
        }
        return out.toByteArray()
    }

    private companion object {

        /** Every 97th decoded sample, as the reference decoder produces it. */
        val EXPECTED: FloatArray = floatArrayOf(
        -0.020696338f, -0.6489089f, -0.10727353f, -0.008667044f, 0.51955765f, -0.39940616f,
        0.59786946f, 0.42682326f, 0.5197495f, 0.09061864f, 0.049158145f, 0.49625587f,
        0.2212309f, 0.42516088f, -0.033251226f, 0.711975f, 0.5392932f, -0.6205755f,
        -0.5286667f, -0.65846896f, 0.6592648f
        )

        /**
         * A quarter second of a sweep from 300 Hz, mono at 8 kHz, encoded by
         * libvorbis at quality 1.
         */
        const val FILE: String =
            "T2dnUwACAAAAAAAAAACn6N0DAAAAAJsj4+QBHgF2b3JiaXMAAAAAAUAfAAAAAAAAoEEAAAAAAACZAU9nZ1MAAAAAAAAA" +
            "AAAAp+jdAwEAAABTe9/zCz////////////+1A3ZvcmJpcwwAAABMYXZmNjIuMy4xMDABAAAAHwAAAGVuY29kZXI9TGF2" +
            "YzYyLjExLjEwMCBsaWJ2b3JiaXMBBXZvcmJpcxJCQ1YBAAABAAxSFCElGVNKYwiVUlIpBR1jUFtHHWPUOUYhZBBTiEkZ" +
            "pXtPKpVYSsgRUlgpRR1TTFNJlVKWKUUdYxRTSCFT1jFloXMUS4ZJCSVsTa50FkvomWOWMUYdY85aSp1j1jFFHWNSUkmh" +
            "cxg6ZiVkFDpGxehifDA6laJCKL7H3lLpLYWKW4q91xpT6y2EGEtpwQhhc+211dxKasUYY4wxxsXiUyiC0JBVAAABAABA" +
            "BAFCQ1YBAAoAAMJQDEVRgNCQVQBABgCAABRFcRTHcRxHkiTLAkJDVgEAQAAAAgAAKI7hKJIjSZJkWZZlWZameZaouaov" +
            "+64u667t6roOhIasBADIAAAYhiGH3knMkFOQSSYpVcw5CKH1DjnlFGTSUsaYYoxRzpBTDDEFMYbQKYUQ1E45pQwiCENI" +
            "nWTOIEs96OBi5zgQGrIiAIgCAACMQYwhxpBzDEoGIXKOScggRM45KZ2UTEoorbSWSQktldYi55yUTkompbQWUsuklNZC" +
            "KwUAAAQ4AAAEWAiFhqwIAKIAABCDkFJIKcSUYk4xh5RSjinHkFLMOcWYcowx6CBUzDHIHIRIKcUYc0455iBkDCrmHIQM" +
            "MgEAAAEOAAABFkKhISsCgDgBAIMkaZqlaaJoaZooeqaoqqIoqqrleabpmaaqeqKpqqaquq6pqq5seZ5peqaoqp4pqqqp" +
            "qq5rqqrriqpqy6ar2rbpqrbsyrJuu7Ks256qyrapurJuqq5tu7Js664s27rkearqmabreqbpuqrr2rLqurLtmabriqor" +
            "26bryrLryratyrKua6bpuqKr2q6purLtyq5tu7Ks+6br6rbqyrquyrLu27au+7KtC7vourauyq6uq7Ks67It67Zs20LJ" +
            "81TVM03X9UzTdVXXtW3VdW1bM03XNV1XlkXVdWXVlXVddWVb90zTdU1XlWXTVWVZlWXddmVXl0XXtW1Vln1ddWVfl23d" +
            "92VZ133TdXVblWXbV2VZ92Vd94VZt33dU1VbN11X103X1X1b131htm3fF11X11XZ1oVVlnXf1n1lmHWdMLqurqu27Ouq" +
            "LOu+ruvGMOu6MKy6bfyurQvDq+vGseu+rty+j2rbvvDqtjG8um4cu7Abv+37xrGpqm2brqvrpivrumzrvm/runGMrqvr" +
            "qiz7uurKvm/ruvDrvi8Mo+vquirLurDasq/Lui4Mu64bw2rbwu7aunDMsi4Mt+8rx68LQ9W2heHVdaOr28ZvC8PSN3a+" +
            "AACAAQcAgAATykChISsCgDgBAAYhCBVjECrGIIQQUgohpFQxBiFjDkrGHJQQSkkhlNIqxiBkjknIHJMQSmiplNBKKKWl" +
            "UEpLoZTWUmotptRaDKG0FEpprZTSWmopttRSbBVjEDLnpGSOSSiltFZKaSlzTErGoKQOQiqlpNJKSa1lzknJoKPSOUip" +
            "pNJSSam1UEproZTWSkqxpdJKba3FGkppLaTSWkmptdRSba21WiPGIGSMQcmck1JKSamU0lrmnJQOOiqZg5JKKamVklKs" +
            "mJPSQSglg4xKSaW1kkoroZTWSkqxhVJaa63VmFJLNZSSWkmpxVBKa621GlMrNYVQUgultBZKaa21VmtqLbZQQmuhpBZL" +
            "KjG1FmNtrcUYSmmtpBJbKanFFluNrbVYU0s1lpJibK3V2EotOdZaa0ot1tJSjK21mFtMucVYaw0ltBZKaa2U0lpKrcXW" +
            "Wq2hlNZKKrGVklpsrdXYWow1lNJiKSm1kEpsrbVYW2w1ppZibLHVWFKLMcZYc0u11ZRai621WEsrNcYYa2415VIAAMCA" +
            "AwBAgAlloNCQlQBAFAAAYAxjjEFoFHLMOSmNUs45JyVzDkIIKWXOQQghpc45CKW01DkHoZSUQikppRRbKCWl1losAACg" +
            "wAEAIMAGTYnFAQoNWQkARAEAIMYoxRiExiClGIPQGKMUYxAqpRhzDkKlFGPOQcgYc85BKRljzkEnJYQQQimlhBBCKKWU" +
            "AgAAChwAAAJs0JRYHKDQkBUBQBQAAGAMYgwxhiB0UjopEYRMSielkRJaCylllkqKJcbMWomtxNhICa2F1jJrJcbSYkat" +
            "xFhiKgAA7MABAOzAQig0ZCUAkAcAQBijFGPOOWcQYsw5CCE0CDHmHIQQKsaccw5CCBVjzjkHIYTOOecghBBC55xzEEII" +
            "oYMQQgillNJBCCGEUkrpIIQQQimldBBCCKGUUgoAACpwAAAIsFFkc4KRoEJDVgIAeQAAgDFKOSclpUYpxiCkFFujFGMQ" +
            "UmqtYgxCSq3FWDEGIaXWYuwgpNRajLV2EFJqLcZaQ0qtxVhrziGl1mKsNdfUWoy15tx7ai3GWnPOuQAA3AUHALADG0U2" +
            "JxgJKjRkJQCQBwBAIKQUY4w5h5RijDHnnENKMcaYc84pxhhzzjnnFGOMOeecc4wx55xzzjnGmHPOOeecc84556CDkDnn" +
            "nHPQQeicc845CCF0zjnnHIQQCgAAKnAAAAiwUWRzgpGgQkNWAgDhAACAMZRSSimllFJKqKOUUkoppZRSAiGllFJKKaWU" +
            "UkoppZRSSimllFJKKaWUUkoppZRSSimllFJKKaWUUkoppZRSSimllFJKKaWUUkoppZRSSimllFJKKaWUUkoppZRSSiml" +
            "lFJKKaWUUkoppZRSSimllFJKKaWUUkoppZRSSimVUkoppZRSSimllFJKKaUAIN8KBwD/BxtnWEk6KxwNLjRkJQAQDgAA" +
            "GMMYhIw5JyWlhjEIpXROSkklNYxBKKVzElJKKYPQWmqlpNJSShmElGILIZWUWgqltFZrKam1lFIoKcUaS0qppdYy5ySk" +
            "klpLrbaYOQelpNZaaq3FEEJKsbXWUmuxdVJSSa211lptLaSUWmstxtZibCWlllprqcXWWkyptRZbSy3G1mJLrcXYYosx" +
            "xhoLAOBucACASLBxhpWks8LR4EJDVgIAIQEABDJKOeecgxBCCCFSijHnoIMQQgghREox5pyDEEIIIYSMMecghBBCCKGU" +
            "kDHmHIQQQgghhFI65yCEUEoJpZRSSucchBBCCKWUUkoJIYQQQiillFJKKSGEEEoppZRSSiklhBBCKKWUUkoppYQQQiil" +
            "lFJKKaWUEEIopZRSSimllBJCCKGUUkoppZRSQgillFJKKaWUUkooIYRSSimllFJKCSWUUkoppZRSSikhlFJKKaWUUkop" +
            "pQAAgAMHAIAAI+gko8oibDThwgMQAAAAAgACTACBAYKCUQgChBEIAAAAAAAIAPgAAEgKgIiIaOYMDhASFBYYGhweICIk" +
            "AAAAAAAAAAAAAAAABE9nZ1MABNAHAAAAAAAAp+jdAwIAAAAsM7SiCSEWFxMSFBcVGaYUq8wrgOMcAIAaSSGFFFIpKyWq" +
            "9H6HMAXXjfg8ffr0Kaac7hevAOb5Tz+OAAAAwNbTia7zhA+unpm3ogQAdnoAAAAANv6Tlh3sPEsAALLiUH3z7M0AAHj+" +
            "8boCAAAAMyey4ztYiWJcAQDg8Xf31TQAAACy42vyDEmY7yUAAIBw5VvSu2MJALLjzqm7SALzvasGAADAOfv8qDpeIQAA" +
            "suPYubNIAhieOgAAAED0YB5lTgsAruLOS2sp4GsAIBNLYuvc/TEx3LIAVA++Sw=="
    }
}
