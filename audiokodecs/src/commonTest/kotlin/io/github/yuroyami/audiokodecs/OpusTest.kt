package io.github.yuroyami.audiokodecs

import io.github.yuroyami.audiokodecs.internal.io.SourceReader
import io.github.yuroyami.audiokodecs.internal.ogg.OggReader
import io.github.yuroyami.audiokodecs.internal.opus.OpusDecoder
import io.github.yuroyami.audiokodecs.internal.opus.OpusPacket
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Checks Opus decoding on every target.
 *
 * The oracle suites compare against libopus, but only on the JVM, where it can
 * be run. This carries a small Ogg Opus file with it and checks that every
 * platform gets the same answer out of it. The file is a third of a second of
 * stereo in three parts, six packets each of SILK, of SILK and CELT together,
 * and of CELT alone, so that the speech codec, the music codec and the joins
 * between them all run.
 *
 * Two things are checked against libopus 1.5.2: every packet's final range,
 * which is integer arithmetic and has to agree exactly everywhere, and a set
 * of decoded samples, which are floating point and on JavaScript computed at
 * a higher precision than a `Float` has elsewhere, so they agree closely.
 */
class OpusTest {

    @Test
    fun anOpusFileReportsWhatItHolds() {
        val info = AudioKodecs.probe(file())
        assertEquals(AudioContainer.OGG, info.container, "container")
        assertEquals(AudioCodec.OPUS, info.codec, "codec")
        assertEquals(48_000, info.format.sampleRate, "Opus always decodes at 48 kHz")
        assertEquals(2, info.format.channels, "channels")
        // Eighteen packets of 20 ms, less the 312 samples of pre-skip at the
        // front and the 200 the last page's granule position cuts off the end.
        assertEquals(DURATION, info.durationFrames, "duration")
        assertEquals(null, info.bitDepth, "Opus has no stored bit depth")
        assertTrue(info.isVariableBitrate, "Opus is a variable bitrate format")
    }

    @Test
    fun everyPacketDecodesToTheEncodersFinalRange() {
        val decoder = OpusDecoder(48_000, 2)
        val pcm = FloatArray(OpusPacket.MAX_SAMPLES * 2)
        val packets = audioPackets()
        assertEquals(RANGES.size, packets.size, "packets")
        for ((index, packet) in packets.withIndex()) {
            decoder.decode(packet, 0, packet.size, pcm, OpusPacket.MAX_SAMPLES, decodeFec = false)
            assertEquals(RANGES[index], decoder.finalRange, "final range of packet $index")
        }
    }

    @Test
    fun everyPlatformDecodesOpusToTheSameSamples() {
        val decoded = AudioKodecs.decode(file())
        assertEquals(DURATION.toInt(), decoded.frames, "frames")
        assertEquals(2, decoded.format.channels, "channels")
        for (index in EXPECTED.indices) {
            val at = (index / 2) * 241 * 2 + index % 2
            assertTrue(
                abs(decoded.samples[at] - EXPECTED[index]) < 1e-4f,
                "sample $at is ${decoded.samples[at]} and libopus gives ${EXPECTED[index]}",
            )
        }
    }

    @Test
    fun seekingIntoAnOpusFileLandsWhereItSaid() {
        val whole = AudioKodecs.decode(file())
        AudioKodecs.open(file()).use { decoder ->
            // The file is too short for a seek to restart anywhere but the
            // beginning, so every seek decodes exactly what a pass from the
            // start does.
            for (target in longArrayOf(0, 1, 960, 5_000, 12_345, DURATION - 1)) {
                assertEquals(target, decoder.seek(target), "seek to $target")
                val block = assertNotNull(decoder.nextFrame(), "a block after seeking to $target")
                assertEquals(target, block.positionFrames, "the block says where it is")
                for (i in 0 until block.frameCount * 2) {
                    assertEquals(whole.samples[target.toInt() * 2 + i], block.samples[i], "sample ${target * 2 + i} after seeking to $target")
                }
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
                for (i in 0 until block.frameCount * 2) {
                    assertEquals(whole.samples[written * 2 + i], block.samples[i], "sample ${written * 2 + i}")
                }
                written += block.frameCount
            }
        }
        assertEquals(whole.frames, written, "frames pulled")
    }

    @Test
    fun damagedPacketsNeverEscapeTheDecodersOwnExceptions() {
        // libopus decodes any packet or rejects it, and a translation of it
        // has to do the same: nothing but its own exception may come out,
        // whatever the bytes are.
        val packets = audioPackets()
        val decoder = OpusDecoder(48_000, 2)
        val pcm = FloatArray(OpusPacket.MAX_SAMPLES * 2)
        var state = 7
        fun next(): Int {
            state = state * 1_664_525 + 1_013_904_223
            return (state ushr 8) and 0x7FFFFF
        }
        repeat(3_000) { round ->
            val source = packets[round % packets.size]
            val damaged = when (round % 3) {
                0 -> source.copyOf().also { copy -> repeat(1 + next() % 4) { copy[next() % copy.size] = next().toByte() } }
                1 -> source.copyOf(1 + next() % source.size)
                else -> ByteArray(1 + next() % 400) { next().toByte() }
            }
            try {
                decoder.decode(damaged, 0, damaged.size, pcm, OpusPacket.MAX_SAMPLES, decodeFec = round % 7 == 0)
            } catch (e: CorruptStreamException) {
                // The only failure a damaged packet is allowed to cause.
            }
            if (round % 11 == 0) {
                decoder.decode(null, 0, 0, pcm, 960, decodeFec = false)
            }
        }
    }

    private fun audioPackets(): List<ByteArray> {
        val reader = OggReader(SourceReader(file().asAudioSource(), DecodeOptions.DEFAULT), DecodeOptions.DEFAULT)
        val packets = reader.packets(reader.readStreamHeads().first().serialNumber, 0L)
        val out = ArrayList<ByteArray>()
        var index = 0
        while (true) {
            val packet = packets.next() ?: break
            // The identification and comment headers come first.
            if (index++ >= 2) out += packet.bytes.copyOf(packet.length)
        }
        return out
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

        const val DURATION = 16_768L

        /** Every 241st frame, left then right, as libopus 1.5.2 decodes it to float. */
        val EXPECTED: FloatArray = floatArrayOf(
            -0.031463623f, -0.0729980469f, -0.253631592f, -0.0921630859f, 0.145935059f, 0.175048828f,
            -0.185394287f, -0.337524414f, -0.228607178f, -0.0328369141f, 0.105285645f, -0.167388916f,
            0.101776123f, 0.092376709f, 0.00277709961f, 0.0911560059f, -0.218200684f, -0.284881592f,
            -0.0525512695f, 0.0486450195f, 0.0824584961f, -0.169158936f, 0.188720703f, 0.0156860352f,
            0.00173950195f, 0.0496520996f, 0.151824951f, 0.258178711f, -0.139221191f, -0.026184082f,
            -0.236755371f, -0.16973877f, 0.00421142578f, -0.131988525f, -0.0594482422f, 0.140594482f,
            -0.228729248f, -0.200408936f, 0.399353027f, 0.402374268f, 0.147583008f, 0.169372559f,
            -0.0668945312f, -0.0748291016f, 0.0130004883f, 0.00741577148f, -0.180628955f, -0.57597518f,
            -0.210631594f, -0.216826826f, 0.44400394f, 0.669033408f, 0.461385816f, -0.183017969f,
            -0.25041908f, -0.065478988f, 0.209868222f, 0.0920822546f, -0.0954281837f, -0.15859507f,
            -0.142360479f, -0.141170502f, 0.359204173f, 0.237922817f, -0.430118412f, -0.0585376136f,
            0.144020244f, 0.0700267255f, -0.211567789f, 0.139137506f, -0.205570295f, 0.285003066f,
            0.376119375f, -0.12440411f, 0.0225847606f, -0.222638071f, 0.290687412f, 0.0950186923f,
            0.0580394752f, 0.0834539682f, -0.0456594639f, 0.314529687f, 0.0987252519f, -0.115080871f,
            0.169686392f, -0.0156744272f, 0.154723749f, 0.20138593f, -0.43083331f, -0.436846465f,
            -0.155314282f, -0.140721515f, -0.462021321f, -0.463017166f, 0.338245332f, 0.339869529f,
            0.067299217f, -0.00977709051f, -0.0239835009f, 0.0233635828f, 0.0432773158f, 0.0533090495f,
            -0.0645129904f, -0.0533066764f, 0.017997425f, -0.0499566272f, -0.060003832f, -0.131177232f,
            -0.0097189378f, 0.142265722f, 0.013018908f, -0.0360034853f, 0.067760624f, 0.129031062f,
            0.194540679f, -0.0577459224f, -0.083164975f, -0.0991646424f, -0.067410782f, 0.220795631f,
            -0.173390046f, -0.250432044f, 0.0598945171f, 0.110556848f, 0.0623811074f, -0.250438631f,
            0.0423154421f, 0.170733094f, -0.0521969311f, 0.142136186f, -0.100697674f, -0.153335363f,
            0.252831757f, 0.261330754f, 0.125218317f, -0.190372512f, 0.0237375125f, 0.269806981f,
            -0.282623261f, -0.193259701f,
        )

        /** The final range the encoder reported for each packet. */
        val RANGES: LongArray = longArrayOf(
            85068590L, 11219098L, 46147551L, 145549084L, 18065484L, 17826680L,
            299187200L, 12951205L, 1727423744L, 1151742976L, 21783040L, 158404096L,
            9126980L, 926293248L, 700908032L, 757906432L, 112650240L, 8590151L,
        )

        const val FILE: String =
            "T2dnUwACAAAAAAAAAABTVVBPAAAAAIzm+c4BE09wdXNIZWFkAQI4AYC7AAAAAABPZ2dTAAAAAAAAAAAAAFNVUE8BAAAADhC0" +
            "jgEUT3B1c1RhZ3MEAAAAdGVzdAAAAABPZ2dTAADAIQAAAAAAAFNVUE8CAAAAmEzlSQk2NTU5OTFubG1MqBaLmmTb3dIVO6C6" +
            "3dF8JzF6EXqlqb9wBkeocAy9xhX5D8xU2tarzXbN+Y3n3xTc7MERxWBMqMGgwtCh4QdKJkPPH/zHFvm0o6CDte4WcmAakxiL" +
            "M0ZJ0MPDrL08CbDpuoEzy10wwEr4E0yqAf3GOHxH3cjlZanWzJLQAxix9DlR5f0yXJycHsbQSEXdQ8KZVsRN+D9ay8mb1RGR" +
            "97dqTKoB9fc2CIO+0sXVchB29zxBxNpFr/BEd5oVjN35O7BwYjy/Bs4N7dFP72iUsuEaFCuV5ZMz+5aQTKee/tGRj05O1B8P" +
            "lmFc1/KJzwUgnhlQmAwnAt5q8MSUwbgdDw0y2w9eaofyTRDK/ihTGVoJBlIUTIgBZFKNsSmcbU51/6hjY5TgBHySSlrlZ9SI" +
            "pL8B6wijydpT0BhE31voBaW22pomUHyoFnEgpKs0+Q2rI/AOUDXecsePpDYR3I/MWmOhpB2h3NSJW8uxj4J/syupKry0Bg/Q" +
            "mcsh66XnOPgjrTuN6SbTRBVvXM4VNPxALuExDLbh7Zgp09vYWY2EGjuTArXSe3io+CLiILHPjdHWW8H+fKjBa58o99AnGLl6" +
            "k2QtTb01HKj3V57tV08Hpdt30+uqUsRUoVFmHOPiFxN33iFaB0/0Q0zfj+B5eUiUdjBBjhSU4fBSiQD5VbDzSTuO7WlA97V+" +
            "h8BrZvS7BUbDiuFVfRSSBaK3so1yW2nefKoBzlF9fEfCzubVpQ0/9OR+n2INuwp2BfOiglOaskohKLedhx+ZZyWZeuIc81Jt" +
            "v3QF8Ij0MWxrCFq1auawAl7QuMAwD+b8n57cJru/iznOZtpT6btjz/4NAgsw5Mt5zyf2bXHRnEbha/Nt5U9nZ1MABLhCAAAA" +
            "AAAAU1VQTwMAAADoRuhhCW9tX9jn6+zx43yqAcaCewiDvtLF1XMv7dx9PUT7v9nLxilPwyDMpxTpH8LLS/nXlJOFE6KSIrFE" +
            "WZ8rEemSxzZ8T000u3H90Yto/YHfKEpTzdMToMWLNI++PXhhmGU3BP0O5JnSPXTWBXCW3fCBumE4EQF6g4SfvXynpWtaQB0o" +
            "8cX55BaTOP+1is16ok5wzpn4Pm1EdzpQwr0GRiZn4mRfilBZZPdcXVo6XTSrUsyCi50mhpYj42GUA5Mf6GZ+rrPLkNBl5SIL" +
            "TF2pi28YZvOVz95tCACdTs6UtONaqslFyHkj8H98iAE0lscMlnpnELbFhGULcITJ/pPUs//GbQ12Y3roBZr8XH6gaacvV+QU" +
            "PpfIc9CdzcydKhToRrT9/ZdP4rfoiDPCon107JZV5Qll+aF/e/rJzwn5KmDACroVEvjPb/wfPsId8T9+DRfCDv7BdtaUDoPj" +
            "lRoKGwLf2h0jCQJlhcXq9Nx4yUvQ2n+RmKBt2AdgJ1XfCQ65L4KgG7SxbncPLVOXSRjLYku/lJKj5p2cSHWEPdIMgrWFWVJe" +
            "sOa8f0sPo+N3Vcci6ZC3Es7BLr/BiIODKolndJ13dGAZYjrNQSEbp3x2XV1umSztJbhW+09PLKt6crxZFcEDOl9m768MMklU" +
            "ddFk3uu45Axr42YjV6pSOFZ55IrTpg9WWzsDG7LjpkGSxavhmtJvAttSAD6CgolkD6Dta/wJ8vDWJsGP1ExEvbt//AePzH8N" +
            "4JeDqE0HsJySnNjWhG+gOHqiHviIX/jRYla5yK2v7JJuMsiPnp5lhaY6lzMWvpJAQrm0fXnRTbugywLT10GJfV9d4oNAdWPA" +
            "HyHYF7qOjZQyK19JwTFzo3HJlZ8wjMZ+ogeinaLPCi2ktibeEHaW/q+AShixpfXaxRrC9X2oYWCuZFu6Wbx92BQrsLnnIzj/" +
            "jBWtdi3AXVAbJ7GoanUJ4Ko/2D7x9MXLzKWLSci0MfgDywR17EWODv8+AD2W5bPBkn26swEMDZsD+qA/6JjvQe9aCPxkEGEc" +
            "gLCVbm936gnvOziFV+CO+mx2QNWjT1zDxq5v0Wnid5K+ZFYX10rKQz4lRhj5RgFG19hx655b0MD5AeAy3bOhzpUCtAnRbgzh" +
            "ibD5LXaomrwvMeee+8fkRWSXKw+v4Vu10U2JsE2X9T1+ER+rIRT6MCC86c9S5+L5u2vxqTiJok5TZJokAIDTIPZw6CNmEha1" +
            "k4xeIgWekDi9gMFHKmoWIAgInmFLNj1WD8STPOu274LKrvbFoa9N/ysjQE47gUPe5aKWxiuCiVwDxe9Ab72EOvYWbeXSB2nK" +
            "jktAtkBbV+3Yb40re/T8q34LdMwcngpk60KsK9sEnZkK2pi1PNAZKNh6tEfWWFtof9F4XVAKhhQrbO5guqIejIvknpBehnKy" +
            "qDPe1iylCKGhWa609bDTXB++aKa3hl0JhdyXdJQBxdbZLcmYCmO5QqV/Y/p6NI4QC9GdNdWsDK6wd7wAcjYuq8ZbrA3ot2AF" +
            "zHi3LywHqOrTxZ1msdfQJNJogtoUleMyMwcZ22Ac9rdzFUFLkdqpmFmX/nSMQxtUfV9gGYStzevbYNHFX9IUmk6E2XHN6lSi" +
            "ankRanjZoboGZ6zXMv1K659sy5gSWyE1TfhJbRdDU8AI7/wUSUYcHfJoV1J7JdK2ytbdqCPK+c05cG+p+Y4xJKgJKHvXnI7+" +
            "NjT/X/4bUmQlQJCezrhCK/+JPzjo2izN6RyFiBpMs7jlO6SBBg3C1+QJN4rLBJ499H4qJ1Cr9weWQS5z1ptkf6Ndv6GElaW2" +
            "14q9iItniGyaiHZA/s8BD7k4zgXErEDHM2geFH9CdpxRHi7sxWF3A6FTU21N2NHAUfhzQBCYG54ThxQdwiqf/knFYMTjAsd/" +
            "2D1cu6/33OKw/NwSfM9TIewqQMsvap34Hzxw/tLVk6qkhYnIrOkTc+IKt74Gsm1sn8KJBKZ/N29+s6P8rURTvweRguufjWRR" +
            "nWW2yyWqd+pU67Tz+NFbHoZRy6dRCCE8EibXp7EnbrZDsiLz9ZQ35buPCNNezqUjqKf19N0+Euq3VerNgUdYGuTOF257XXSh" +
            "wZHHTpujtqrvCRKqTJmRg1qh7Tf7Vy+17VdCrDLwm0XqPW+WISmj5AsferPy/l3cmDH0ZirAVtxAuYdwm9EJfwY8HSQUrp0e" +
            "S0ljjF1TYj17mWYjpz/W0AQPb/wBHuL+aTReBHc3DzKPyDSld4TtBctrKBgpqtvrFzyc5fXk371/7NtgV6pCK91XYky26w=="
    }
}
