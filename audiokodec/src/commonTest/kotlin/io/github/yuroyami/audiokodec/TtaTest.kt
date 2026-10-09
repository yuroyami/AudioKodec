package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.internal.tag.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Checks True Audio decoding on every target, against small files that the
 * format authors' `tta` tool, libtta 2.3, and ffmpeg's encoder wrote from a
 * deterministic integer signal that [sample] regenerates here. Each must give
 * the signal back exactly. The JVM oracle suite runs both encoders and both
 * decoders on many more files.
 */
class TtaTest {

    @Test
    fun everyFileGivesBackTheSignalExactly() {
        val cases = listOf(
            STEREO_16 to Shape(3000, 2, 16, 44_100),
            MONO_8 to Shape(18_000, 1, 8, 8_000),
            SIX_24 to Shape(300, 6, 24, 44_100),
            STEREO_20 to Shape(1000, 2, 20, 44_100),
            TINY_16 to Shape(400, 1, 16, 8_000),
        )
        for ((file, shape) in cases) {
            val what = "${shape.channels} ch, ${shape.bits}-bit, ${shape.rate} Hz"
            val bytes = decode(file)
            val info = AudioKodec.probe(bytes)
            assertEquals(AudioContainer.TTA, info.container, what)
            assertEquals(AudioCodec.TTA, info.codec, what)
            assertEquals(shape.channels, info.format.channels, what)
            assertEquals(shape.rate, info.format.sampleRate, what)
            assertEquals(shape.bits, info.bitDepth, what)
            assertEquals(shape.frames.toLong(), info.durationFrames, what)
            AudioKodec.open(bytes).use { decoder ->
                val access = decoder as IntPcmAccess
                assertEquals(shape.bits, access.bitDepth, what)
                var frame = 0
                while (true) {
                    val block = access.nextIntFrame() ?: break
                    for (f in 0 until block.frameCount) {
                        for (c in 0 until shape.channels) {
                            assertEquals(sample(frame + f, c, shape.bits), block.samples[f * shape.channels + c], "$what, frame ${frame + f}, channel $c")
                        }
                    }
                    frame += block.frameCount
                }
                assertEquals(shape.frames, frame, what)
            }
            val floats = AudioKodec.decode(bytes).samples
            val scale = 1f / (1 shl (shape.bits - 1))
            for (i in floats.indices) assertEquals(sample(i / shape.channels, i % shape.channels, shape.bits) * scale, floats[i], "$what, float $i")
        }
        assertEquals(ChannelLayout.SURROUND_5_1, AudioKodec.probe(decode(SIX_24)).format.channelLayout)
    }

    @Test
    fun seekingIsExactBecauseFramesStandAlone() {
        // At 8 kHz a frame is 8359 samples, so this file has three.
        val bytes = decode(MONO_8)
        val whole = AudioKodec.decode(bytes).samples
        for (target in listOf(0L, 1L, 8358L, 8359L, 8360L, 16_718L, 17_999L)) {
            AudioKodec.open(bytes).use { decoder ->
                assertEquals(target, decoder.seek(target))
                val block = decoder.nextFrame()!!
                for (i in 0 until minOf(block.frameCount, 500)) assertEquals(whole[target.toInt() + i], block.samples[i], "seek to $target")
            }
        }
    }

    @Test
    fun damageIsReportedAndPasswordsAndFloatsAreRefusedByName() {
        val bytes = decode(STEREO_16)
        assertFailsWith<TruncatedStreamException> { AudioKodec.decode(bytes.copyOf(bytes.size - 100)) }
        val damaged = bytes.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 0x10).toByte() }
        assertFailsWith<CorruptStreamException> { AudioKodec.decode(damaged) }
        assertEquals("password-protected True Audio", assertFailsWith<UnsupportedFeatureException> { AudioKodec.open(withFormat(bytes, 2)) }.feature)
        assertEquals("floating-point True Audio", assertFailsWith<UnsupportedFeatureException> { AudioKodec.open(withFormat(bytes, 3)) }.feature)
        assertFailsWith<CorruptStreamException> { AudioKodec.open(withFormat(bytes, 4)) }
    }

    @Test
    fun truncatedAndMutatedFilesFailOnlyWithTheLibrarysOwnExceptions() {
        for (file in listOf(STEREO_16, MONO_8, SIX_24, STEREO_20)) {
            val bytes = decode(file)
            for (keep in 0..bytes.size step 37) {
                val cut = bytes.copyOf(keep)
                for (tolerant in listOf(false, true)) {
                    expectOnlyOwnFailures("cut to $keep") { AudioKodec.decode(cut, DecodeOptions(tolerant = tolerant)) }
                }
            }
            // Every byte of the header and seek table, then a sample of the frames.
            val positions = (0 until minOf(64, bytes.size)) + (64 until bytes.size step 29)
            for (at in positions) {
                val mutated = bytes.copyOf().also { it[at] = (it[at].toInt() xor (1 shl (at % 8))).toByte() }
                expectOnlyOwnFailures("byte $at") { AudioKodec.decode(mutated) }
            }
        }
    }

    private class Shape(val frames: Int, val channels: Int, val bits: Int, val rate: Int)

    /** The signal the fixtures hold, as right-justified integers at [bits] bits. */
    private fun sample(i: Int, c: Int, bits: Int): Int {
        val triangle = kotlin.math.abs((i * (3 + c)) % 400 - 200) - 100
        val fast = kotlin.math.abs((i * (17 + 5 * c)) % 64 - 32) - 16
        val noise = ((i.toLong() * 1103515245L + c * 12345L) shr 7).mod(23L).toInt() - 11
        val value = triangle * 150 + fast * 40 + noise
        return when {
            bits > 16 -> value shl (bits - 16)
            bits == 8 -> value shr 8
            else -> value
        }
    }

    /** [bytes] with its header stating [format], its checksum made right again. */
    private fun withFormat(bytes: ByteArray, format: Int): ByteArray {
        val out = bytes.copyOf()
        out[4] = format.toByte()
        out[5] = 0
        var crc = -1
        for (i in 0 until 18) {
            crc = crc xor (out[i].toInt() and 0xFF)
            repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0xEDB88320.toInt() else crc ushr 1 }
        }
        crc = crc.inv()
        for (i in 0 until 4) out[18 + i] = (crc ushr (8 * i)).toByte()
        return out
    }

    private fun decode(text: String): ByteArray = Base64.decodeOrNull(text)!!

    /** A file small enough for the shared hostile-input sweep, which decodes it thousands of times. */
    internal fun hostileFixture(): ByteArray = decode(TINY_16)

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
        const val STEREO_16: String =
            "VFRBMQEAAgAQAESsAAC4CwAAv/Adm6UeAABRuElNDvj///91GC7hobcEBJcJfRgZYYHJr5i09HnlENvAQO8id2TEBez611hQRGay" +
                "84rwElgwZM9mm4aGeYyeicneWkcqGMLJtISfN1TgrLhG43jAcmNlTCLaM0dtAJtBzne7BFqrR9DQTZeMxZj6E419syUAYZMkUAbP" +
                "2JMJsSeUjKOS+x0LMyqHyIckdPQVJ1YfNKc0h0YFHySYcveV7y1RdT9Q+Mpk1XdPDJYiTYUC8G8YObsum7Qu9T1G66UwVOpZ2jrt" +
                "mp17+N4COSmUwvHWyFXpV+8JiufeFMYa7AnifOmTja5fIahV6Ly3xaWi8ipUm7fvUVcJ2W5uLdGKblgH+kCmO1IKi7y+mJZ/bxRz" +
                "s/2p2HtYHOtfphjU/75W+PVsGGbXQ4r3fDtYXu3y5fzmZLO+yJ0TFNT8M9aSQIVUtR6OJFHOiV4hSleaR3hMV2jcrGquhR6xEI7P" +
                "TTxlXEAYqpGl5qmubayqz0cmjxDYToA9SjUGgndChux2nFp9T/uZga9Cx2/d5q+CKMMPdoZ48COGG4hhbfJTOnd5KH0ajR55DkFy" +
                "iHaY0M+CyVZyuA+19IQkgiIdwmQ7IDPDkkIpj6ZHsOVNz5IOKRPy7ZwHTjgZOWLvYN7WR7aSyL926XJTh7ES07XmpZjJj5s9x3rX" +
                "lqsy1xynry2ZcoBLFjVeRUaU+Phl0kgr3sXqGPiqxNfDu9SdNZcg1FsECkLaujY1vrRwVxlkpfQS1jxA6+P0W293Ul/jOpz7jYiF" +
                "Pqpxt5gvK4KaEVkJNHBr9BRqB4FDwvJ8W27PzArTZVi5g8Evl4+h5HCb/5Ag2uYaBT8wphLNzbFFLhocIU9FyOI6oZ1ki2SalLaN" +
                "bZdaKiPvHckRygRdt+ieY+P9tIK+5YSlxRaHg01EDgoL4T4D84c/wFOZnY71DDxKnt4wlxdYG0f1IyZfnFOztBEHqFYnFUqMnbAY" +
                "8I+/PqYL3y3n1VuowsXBOhv8faGJ+uL0g2ZtrWqSXX12XcxCKAnYNU2f1+fEO1eaOWGwfrx9SSYnedFolecOTGSNbiV510vI6TZe" +
                "iPt/V/DwHoQ4uu4iKGUZUull2yS6BgET8xpL449q6b6kr5yR/c5e+Cxip65zfVldHUT8+SKsZvurhOM2lgvo5Wyqr19Aixhl+fhA" +
                "Z/HhjLMfFnWER6YW9n+oVRiycACDs6HdpXMTWeATCWmhgtmjJ0T4SQDPn5qcnL4/P8QYEBDIGvLD2sFZmCGCIZmzy3gO8DJEgcTZ" +
                "5kG1/KlDS6CZiI8hDsN1OHlJdfhp5cNtMyPgWH4UsUA1Eeqbp2CHdmjEDRa0GJ1bWPJ5mBVNZfqNPQeHDYFnGKlvmGveQjVxu4K9" +
                "KvidJv/AycbdInljSpEHhVZktC/Pa0LtJyMzUXwc1r6BBoqCwGhckBfenmDQ7qdLIbg84ajfJjMok5JO9WrWph5F0iw0RcXS23ao" +
                "zzJEyoOLMlH73k5470+pjtQtgOjqvCImoap2KbsjQu6vdd3gbkMYvX3ceLeHogF0EQ/Slu27CnWfwKeoXcTDfmchCUzO41pw6AQQ" +
                "dOJU02mauoRrXDc6e6H395QKcR3iVMYsPqk2icJS2SdHxlBOSbMiR3mTnodDBpwyXbCI6G4OY/C5HWMcNi5xY57WhCsFv3HafGyN" +
                "yeCUiTg08dqnk8csFUedOjvTbf6pT35MI+BTVc4NiM8ize4NKzBG/kz7Gr/KrBJq+P11QxmZFFpfrv1C8/0LfhU8aFW712Y6r8J0" +
                "bV0+GnR5Aw/jZUOLbzYZRvLNskGXyOTzCounXIPSsJ7+/Lof0TJ3j7hywi5nosd3woRNgmQebkuUG98IWDWPABZfneRMLzS0Rl4c" +
                "jvduZ60uhIIQXAFKt35lFO0NY+y9TS++pAom0Gh9cfW1nNC82pHqMBETrKuKGONVIOgouDQkKxEAciCcxmjse84XwqlTBIBGB34h" +
                "Q+aes/KIlkFPQS/01YximY2wYuO7XJTjzr0sR3TPD4/awJgBQ0T5tMkfd8O9QXrMaUulS+RJSJwfIa5hybeupDx1Z08r1CIdCSAL" +
                "NSl1TfjpIwZXQiji8cJozDNjaTTAf9kIdIdBFrkyskzz64RJ8BVAR9n7DNqlh0CbvahpzQ0f7juAIFH3wAYIXvkoNegMc5teORV7" +
                "lDYV5Jhw1Usbza4dhA/vgVlbe2MO/Ao5QOQFSLWfFyx7y7KiRLHbpHff5uRob8K8ikKmdh1nKsobwFnhr9FYbyUYmFdZKyW+MmmV" +
                "C7QlFrUpFWz8NJ97GCDc2Mma9sWU0ajthqBO+yMjnaY+pUyyZA0uG69Z0k+rHdOUqiAPgznK89s2A8IsjShmnVtWNVcOLhbPZ+Yq" +
                "Aem+SPEUPBPEzNafCPbunhiKLRwvSZbgY9KBDDf/GViAxUKm2aFN1DImpwybPAvrEHrGCWVXhLDzjlAu51cHSmywYLrTilf5v1Qn" +
                "BLG7azo82+Xk+zJYxB3c94lbPw0XWjXlOGPneN2cBVqWNSDM2tkhuGUzA/ZGM8jLKtCp2Ho52XL8BnBP4t1vexitHUAk36xzFC9a" +
                "ULbcGP2LbxCIPfdJ6yLgs+fLYFD9aSLvFsR3U7Wcw9o6RewNCanKC8tWjO0xd13L1t1CYSJUPdq7P2g/ykaNf97p1ORuHPPD2lqf" +
                "hppbOCC4W0PhUFKAB0mqsPr5wQAGbSaM8vCR7NtMAVdmShG/KOewFaEePbIlJWipTsuYGaZDHLGS7NeROw1qXCly7eR2fqrxpO35" +
                "U6b9EaQNzkeDHLyFg+xw+czrYJmfQVhJGHpsjDisGnE62XbM6MTjMsxwmjCNu1bIQC9lw7/BsDyEC9LY4CuOBpebQx0kdE5QK+4S" +
                "dAU4iVNnAb3A6lnoS0Ssv9Jg77B++Pe+wXmqBv52l0wNFrqK9I2JqH4NSaNtywm6DYM4ug1PSH29J97nqgC0Bm0M42JoWG3XQBBT" +
                "bUKvjKdS7AZG/CWH5CaIEOG2JWf8XsZf3NtRs7NGns7zc6dYYv8ker+hmYXNBKXlm782UibbL3YyaCyQ0aYl0EoVDmP3ZpJin7FX" +
                "FY1TbSFS5ykv/WzXKGyUcDgXVGzE2eD8BgZ1AKVoOmvLDjOGDY1ch1KfyR6YegYUog6o+VPEN4szoeBDptYcZxbowqoE6Acg5xHA" +
                "3vcjzMf2B9IcrM+ewC/xPd3I/P5Lqmzm0Hn/NDTnId6hAvyXt8Ip3lVa6yEpBMK8adndjCnQprCLUKH/k6lMgWzePL06F2k8/u4b" +
                "B7WeiVlzPVB3xN6ONppAj6LG8tq0X7j9R7C7zeF2Zba4WiV6ddkF1HU2va7yQwGrLjFvr+KgChqXyS8bsGW1fZqbeBGgbsWb3oP/" +
                "kDDS8PPP/+Jta0ZnzlilB8d8soMglMnkL+S42UEEvnoodf495XlvKIYYaN4nZYnZMhOwwcxKwzPcbnjDhAjOqajnKCsWVwHa4X0S" +
                "ygrifvI4sPBwfCRWarwJu3A2Hod2BBTAMTPXQhjF5CaEVSeCJfGIR1x2wjSy80wVi+GUKCB/GOqN80reWJrZvXwXC+1D2rEaDpJP" +
                "LxRYcc4C1jXa0sO2Dbz3YuH1I1yFi5xfpYgB28M2OFq4VG2R6XPb+xPlOkWl3gZM0H0T4r0DGBJlrVNd3pugiouPxi3WUM3eDFWO" +
                "IhYv1PCYt4KrLX2p0/vCEFurUBFusHbL9n6eXUVK4806zWql6DfV/D+3iLqJ8zyfYRusPoHiviyIBuQhOF3MC/VAcFxvMsupLX5x" +
                "MHU+EJIMjiTDEAp0zhhFwPd+ChxYgNOnum60tRr7ALrymPcA8bLcwDlyhWxAWRmHKMtRxgE2R8WBO7MfM4S72hjis34IkeM8AAyZ" +
                "LuHnWaIEeMqt4cMSw5PEGV3qi701v4RNMPbg/6IfnBy+UF8kASFF5ppltWBp5Fne2SjdiV7dm+iUpQ0HfPtGqt9Vo6x4QsLpiwGY" +
                "Ygnk9afHDfR5PqvgOjn70NP37jCUrVm6ra+J1apqhVWnhEvaDxLF4i/HkhHqf8VCLn2RsVGva+VbjcjEGnjsYJV66iknJjqU5OoN" +
                "AjtY/7R+9ftxfYZfcVtBOiSUeoxCxh2M38EcEQepWT7qTlJV0oHApNMhrET0oUKSwHCXeH66DmPk0igZW8bG3N0JJtSNasFrfDCi" +
                "4QwXfZMPqJexhhE43hHSJCm//P6UNmWIG6ynWIBw59x+MBC6Nty4qJi8FV7T0zDPA8R1DNbcip292Vzt9z73Dv6LbnIqY1f55qYw" +
                "iv23g3BB+DcmjK4CMEOlr+j0rgG0rSAcoNVHvc6lyaAG7qeldvFWJRvoUlSE/a1wUu0zD7G2T+GGdqHag0DSVzqTYm/Vi8hSjmXD" +
                "CtePCPeVqR/eVr10bCcwhXYBd8cyKmhSKuZFk1/wbig4zjajZYf3eRsUtjs/whB+j6M6xW32kRS7U1DHCnY7IWzEUORbQMfJMRlK" +
                "uHsaol21B1y40lFqGoubduZiwt5cQRkmxyP5WM0E3gi68SlMNbGuzwtvGYPcDK4zhxSZMU/zsohxou40wWY+C7gOBtIJ+bHVlv48" +
                "OjlVUIsZo+uoHoZAvGL+Etaz8UD+kr+kkznON8NpIEJfxx23ZCBrxlQe0/qbWXnklctNugYWpseyzLbDwtN71SxVBRWmHWnPoqsb" +
                "AS6Yea6Eell0c8R6yEKxPfNUZ40TWEObQLq2pPBtgFS1D+gaWYlyO7k40Knkq7UMvHIoPbx3VtovAuGNN4/GvHevcQ14Pt0/whzL" +
                "/nN5AN/WDp+8NJfgbifzeBySGrV+hkaSUSR8Q3xETCaoyTQkowHCwW9TC1QphByhLsLpKioYQXF2niLOyr3JLnLI8bl3yGZRiYVn" +
                "Q8Zhy81B+L4/mDjcLZw8uOCayfF5UKILiczJkzsUxIsYDRNvPp/fwI4jLcYlCDwZgnV8P7fiD63/9q7zATiNy3CLUX02V6ItFjtE" +
                "Flxh49gFTcxzcay8ToZUdAAUO6yiiKKh9NeUv64rhLWv+Mw0iKDt/SS2AUEyXUqr+vU7xvtCzGNXPQOpIm/NHHXyXRbKfQCRhWZx" +
                "8csFLeAMqBRva8UOJnvFjvzZeb3ArnOY3wMMnf/DDNlyuP/wHOZLeoUzPOqVgh5NRzh1Msm4lpSB/LAQu4/JJqeZEEdxlaLlMQPl" +
                "fJC7QJrZnSSxEJkDAHtp7Rtq7pJDeCfuTkUkaJBJ0EmKJ0zGwBGnhTGMKvlSxjYGKkVJothUxwUAI2vAc7sRbbiITIuQo2jwcsSK" +
                "nMxhviP/usPY5JL+X3OUnmafF/dgI7wNt4g7ToN3ECO9nvSsRjtCVAx8BQ4tIXNZdOzUFtkKhan9mBH7djYSEtLcTnhCGYhAWxcN" +
                "qqM7sMKoWxLVFumBX+2FF4NLD2z02xyyHVaGt+Y9+NeW9fJlZG5RT4A1i9vUcTsbNtK/msW0ler3D/JPQ5fXeP4f6o1b9uftdN6s" +
                "RY/YkfWNY0TI1YE/jiFJB4PgSPSL0XzG8DPkPIH2hiBUzMGRoxoCKaZUSagpgahBBioOro1AjsNqO4i2TPlniwC4vqSfX6UpWf/0" +
                "v0RFPq4WVWVhwGjJ1iQuyKozkIUIaEHxmxkWuvbQj0EHb6r9vui/Qh3R/+/sf8kfjgdy7V/rkB/sXtWXHV80N0QiEgn6YMKzECRd" +
                "1smj/qC4r6sYW4mNQVlNaIlCrPVRlrRmKuOPSgrKlMKUabmAyOYeK4sxq7mQkgoMKPtdISmOQSabdD4vx5W04EFQzkrwbH07qxfD" +
                "X4PUMvUE0TaC1vfLuQ7u//+nKK7zR/EfQ1l/+XEdo1I3fDSpF+2SFajAewFQGpAIeoSQqW4DFKXAmLSLb+ykbPQBR+QcusofU62Q" +
                "EuEckxMDdhjF4BFvMnbmAKIM4uWm+SmcsZU5iwi3z/VUBfSMmn9sDATAs2KMQU9ycYJE5mw5QBIhx9fYGOqIYBD//3+Z9342bP5v" +
                "4Bs8WR/YPb7+GRj+talMJXDi+/LdUFNg1Qq9+94ZqrDTk2mikjBUFMQ7R4ljjrbdeXRk6d0R2EnVY71FbSH4xlmGWthpVONEKrWD" +
                "s2OPWtctg0vwDb2gskxa5weGOP/mC+LYJQOl/lgKb1iqdNj4Gum4ynDo3zrm//E+t37p/p/SB3kD3+AJHu1C7mT0k6XFa05FsGED" +
                "oSDmQDXjrXAboAQqQm0QDo9JjADUAjCEkjakEilKpVW8KhGCmtmgUthg/mqTMxxVZmnMhfrtiTpSH+cV5KDb9eBsaySQcMQE25eV" +
                "dLmwVxYhHjWJVzfzIcMyYQXW5xdJ4CjY/+/jb083r/fs9Z+j07+XtzGLZ741Gw+Fk4qjFJd2JFy3RyFB/EpPCXAvFVRiBAIRDdmH" +
                "wXQWyhNzaqROKUXnFDA7LLurRSFWxdRrb+rSELw0D5MwsRplwMuJUTqe6eruSm1pXLQkMNMWT5vy05bfJpnppNx8GTOJ2iWVfDt0" +
                "+P9HE04wqX+wTvcXeLFHfOYFuOkspFc9vLXSMJqdFkshYRpRmOdIyiKgIdSNYBp4OJgIb5QF3E3yAtW2DDqGGxgKjeRpIhcbdw6S" +
                "1MiRWkYYbSDiKpHLuFwBANAvK1qaEOfGsFOnjQSPrqpk2E6w9D6sj0dannsymXM6YPiC/P+3Qi31O/a/5HP2E2/o+zmag1j7PSAh" +
                "jHsp/CzNSqzE9E4bF4WMulIMV6qthrSOA6lsCAUEPAoNb0scBqyjWlAnd4WDYV3swgbMe22maJROYwP0mGdUuVpQnZOgv1MV+xIq" +
                "XdW62DUCDOvMEDqvnkuz0KILReyrdctTXi+csv9J/GOVTuzh/X/6K/62Du+8795S2B8gvtAFc+EFPkxsWBUnFw7swkc1Vr3giMVe" +
                "ozT0sJsYJp4T7pKMbMyAaSP6Uhbilkxg3aBFnpcIPCZ0NkOzEmq0f9WpcjpKPbcYy5TyabVE+pAotxPLfJUyDRNUxpRZqFHJi8Qs" +
                "YdckRf5f0781rLHcwq/8GB7Gai9r4obens16CxU9p0hp0sKYgErcsUWxVR6MisxDAYxgtQvrNEO4d7aRuQESsUBJKQHs2CSVPlZn" +
                "jfCuwUYLXrHKgUtrqZw1r8IU7akFPgWU12M0IJjtRWKa8tDUGEY1AA0aoK2JhafeYOptgjC2/3/U6FUN80/U5m8E2+fQ4btJ56xi" +
                "MjFIVCohAZgtZTZJYUiQAA/JqhOYgacotB40zjdCdLAaqvARLRUPbowQskCJDg4rD3lZO5yu8REVlWZJhxHVZdNNEFCKeibMAEpz" +
                "kIY9CraGIYkZaocjAbBMgmt9MPoOYdZY0n+n7yPPDf3fw/s/awd+o1d7HE7kmu0QRy7ySjKars5mtBMJhyzhuVSKjgZsad3+0FVj" +
                "RI+J7nQzQstNIe2AjWMNQgG1TxIe2sBzVS9d9csrk2W0pbEEg9w4VEoylavxruqUpsgWVgCd2Rl04ybYtYqoiZz1tFcI5BgXChFy" +
                "rD/6PzHtYB/1/7Rv21s4v8e07jd3QqblkmK2kMGAMQDiEiVfDuJ6IOI1piBsh8JAIWsBM9ywAeDKTvKEEFJAAJMwGnWhkIkXxKYz" +
                "+IlNQMgKOOZXBt9NHN77i/K1jHTyRJWeKRTSWkWkyTpPtHrge0MfIw0Z74kB3An9AWDuSfgv6jthj+R/u1/uk/FRXLabPJ6T3/CV" +
                "+ERPOJb8CBPXO5T8lgUbwhQMdwcMYdeqc5m+uhyFMK0Chppw8A1KhiyK1AjcavxnUyYJA8HZWJ7xwNF2qTSodnCgsGZt493AP5hP" +
                "Hi5msTBtkV6zGOOUhtbWkaXSsHOu+exTKxzX//9U4zUa/T9VR/5Le4ODAXqxqg+cBGKnAi+gP4rgEcLE5NRGsqQEOxlosYYjsSig" +
                "BKcBq3bKVP56sEuOW0F86vIerYZQQTGjKjtCBg9HTwDCeARHwBclQgclShDNQRy6bYJTK2eabDmsPz1Q383GwXz0IhJKG1wu1NCa" +
                "fcH+fbNB+vvwf+2n92ks4bVN3jlcmdVGNwzFlxYXwpWpADFu6nmjshRzHKoAp1qYVzVZkYPqVm5MNWp9LBGtvCkc50HMtuqgGZJq" +
                "PJCxKiUIAXR2guvRwQABOw0KRuMvhxNZBH1hA66DRAjTtGE/ynmkNXeVQzgfjHYRUvFp+Vj/R2sW2E/V/5hvz4uc+wdwmW/2MDxK" +
                "qTbDQRYeWSMrPKFEIGGBNFJs0JOAskV4QGAIJeCSAKoZktSlpq22MECtIJ4EUuslW5oU+Vm5BSGQjCoYLj2csJXlrNGp3CrolCLZ" +
                "U06i7B3AKNl0xiyyliRBlcjRz4mNdfh2nGFVI/y7+MsmwfNL/Mv8QD7LL8ltHfNHsC73iytOP1xjAFRAS+xZR8WPcmJI9GYvNBz8" +
                "s+7TF1rV8MlfSoDtFQPuUMoQCNCNKwyywWhjpMCBj0VPm21ETa4hyt2hIwYPtfNchdTyWqKRlXM9zP4S1PRCegN3JKHIPh8EDYEu" +
                "Zzpj8M+ImtoT+Z/1i/ByDOG5rOh9CvBkGfLKLg4saCHIFmQNCPhhwo4ccjmOcqAxGSB3AVlAUyFYe3AoGvLkFTAHTPT0hAipFGWY" +
                "kOqAxyIfYieYElImJHQ6I6s4alLk5EIwLs4rEatI5aQZq+BrhDAZ6Vk4LsCi9jAmCf6/zd/jcPLy/7a/+o/S0L95C3FEY71zVVWC" +
                "rIli7GFihTj4dDTBj/DEMiDDNZPJrBEMRRv2YjDcQGm458RweaPJRGFuCaBCHiOiBFIjMrEEupyr44CzAsi5jaNx5aciGEGttIQm" +
                "h0yRNkt/yTIozOojh9tIDLW0wdmNDqfMsdzif8Y/yq5M/w/tM375XtlxH+Ie385kztzgIgCkiYabRAm014hQXEMEzbMOAxGASK6Q" +
                "5eyynRSJwg6DPpwAAurBK0JG4RBrkYyncMYo4a2jM+C+ENgjRb9sg1Qh45UYOYrygqWVnGVolBA65qrIYMopaXcUHSHPomjSIaVX" +
                "cbvTCee/5//NUWT+3fs6Ho5Tvyg7P0nHcmE3hHEURLRBhR4LiQst2pFUrdmIRPgF82kcVFuV+GKGLWHOA1ALpBk5rfARoX76xlkz" +
                "3Bzl4nM5FrIrmWuI4EsJkA1bpp6+oRwmYNYmBaaExQvF641+2DT9NTIumosHPZgg1iMdqSILlf8/8Hn7AfgflkP8Bdr1OY9zHZUZ" +
                "gbPpqYcE3Lki4EkTqk4npG0hR+0OGS6YqOCmSIRAOX5s3EliQwIBMDC7rWLKSGDwkxH26jTkg2ZhJJMXCd2y4eJjF1obqRUhjNh0" +
                "AbUAqD+BMzcUlptFKFw5JFMwQelkYtcHDIP42N+MfztuJb5K//d7rp/mq3WJJ+R467hvz8yEyVDhMcQCRjWQUO3TLGdKMfCIR7lA" +
                "N3xuhclioUIOVQcGCslKh71IrRri05CWwi1yTMonh2qwE02FBPb52hcBoREKHwgc/fQrcCpRsHOGA6N9cQ+JKqhWZFyORADWRuWE" +
                "DcYcwn8s/2HKQvP/ZD/tL95NzvdZ7fnaZ2wUtNGwWwcd0TlCgGtNlnGSABsUeUgnEUsPzIAUBaxcgVBPBLDknAhckDQiGJKNXHg6" +
                "GcLUh+cpOmKybFQZwg77CgNUx5G7Qw/cuRS5MzkNXw46tIdcCYoeLoPFBy0K6uw0IKtxJmMC/57/LxnfEr6L38/pPszVbuuAZrjR" +
                "2+GYhJD04EMgjFuavKhlAgQFlVSpsIfZUTunKtjAmQFGawpDWJUsITSp9EcyIYXIGBmEBIFiFoalKkYMC+oFjh4H9wAUTDfKreAN" +
                "P3lxWqSlgTgQyTgmonBp5KgbP6Vfk5zAjWh2aP9/2pRgff+HL/B3F9I5i3q5tE6hGrGi5EytODAkCFMCgKkEsJKSZqBaBftqiAGl" +
                "OJrqUQNrwb1iNCB6dNRMC8L+4ECjigCQutVoDKsQ2moG6UYS5UbSi22TnjkH07ptxooK7IQySwAzgOLqMA+Xchx3Fdd8mGiW8Uv7" +
                "Kt4f7X8xX9NP7FAX9zqm96jv2N5ZhW2dCRokmeTGYSJuwpOMAw/m6WCmtpXz0xWYtzJKzbUZOznm/EyF5fo9REOijL658oKHrK6i" +
                "ZjmwV8RMTbgCbg96bvY70YZNixOJbUE2FUaluQWPDLDUYlGRdv0O0UaA3MKaAk3mz+qJJjWn5/xzfZ++lFN/itf+ygZ2sjKpZAAF" +
                "wup0olMPrDlTQJdOsC3UjFcqifWSSOqcAYJlOASDYXllDqYQERYNoiATCE0hJS2BJVwxyESnVZ3C9vB4Koh+UZ9UFNxcqwT9SjRO" +
                "Thxq75lo0o+auPkEKyaVl+i9NM05QoHl/e/hClFz+Lr+Up/qh3jtd3t4jnGRd8ggExot+EevFkLZBK9HQayQa4rTICTVT5EoC+NI" +
                "k/B0lEHpiyxzCmAPqGw8xUHPFEZllAMquOQ8OUmnOq7c1uAXgKlcDgHNCECxxKffDmSBnKrE4BXHlbV+VNaZd1d+dIg+zQHLU4r+"

        const val MONO_8: String =
            "VFRBMQEAAQAIAEAfAABQRgAA0M+kJsoJAADNCQAACwIAAMGMtRryQAAACSQAQAAHBCDAAAcAEChwQACBAgMMCBgIEBAIICAICDAw" +
                "AAAMBIAAIRAQDISAKACBIATBGARShjBmCGNCGBHEJIQmSCIECsqUCkAoQAFgF0BugNSAArgAFaiACtBAA26ggQIaaKADF9BBAwUq" +
                "dOhQwAWEBgUACyggoEAAKCBAAgIMSICAgAQSIkwkEolAKBGIJCKBKAmCKGKUREmSpRQJMEGBJoBgoJRULFiwWHZpl11dvexVy93o" +
                "trurlmkbslhCB2CyAyQQwiFMmEzOZE5mPsyZM/PBnHFABAgSADaIjpohU1ta11261ttu2+v2XtiwUyxZkpIG6chuR/HZcX2Jb9LH" +
                "yWFOZs4MwYGQRQJrLRW3hnM8Jt94OjffZtd17UJKM3KYCLUzeCOL8dZeRR13ffbVXdUyDAmSXdxpmsjlfDfnRFXfPYdzg8wWIrBa" +
                "02tY4/F67Fm8OiuOjzuBfdLC00wjiVYqUb5Vu3P50HXL2zcwkrY8g93Ystl6+Ci8rT3KF599OxXjnYQ00bKvkWpH2umlWudQOV92" +
                "JqYdgxHWu3dVsGUz/cbR7SrjsiGMoO00CmLf69bd4t1SRFLaXYsjmkXTG/VYyxqztfbIt/LyOPIo08McQKzd7NRutS/YodTKyVnt" +
                "7/TL5t8Qykgz/BmYGZlZ250VYNua/WH0TmO3RODclpsdNbSs1b40s6VtGHo3/TQLoJmxNq/X2GxtVzywv/QU17JqrTvTXJaatNRa" +
                "coy0/MbtyZp+Bm1q5qZnhBdtt22Z5THcsR0WXmktu90ODWHVUpO0e7pa9T/P3SGUErB6FpsY/Y61984iwhlNV1ukrR1rsXsjXW/Q" +
                "asmI90WT4N+sjbV4dsmg9dJjepZP9MRsdwYzf4y4dNmxqHctkB0sVCy7uReI97O2aVnPMpoBPa9m6TGw3o3tPosfHWwghnbPE3cn" +
                "jLT0Lno4S0a71fs89kFkWgnGbWvtLctEs9y2yxDW+KppaZ62dmcSmhW7J6tF21FP2wnenq2Mi9FYZq3pndCWB21u640wvfHYIQrL" +
                "rrdLCJNo9zjBotFW2Z7XMxbYrGaa0buH1u5gWZvF7J7spjtt5GS3ZkeiaYUprJ7kHUu2tyXPWluecS/DFkNPDxrrsbaevOXE+QI7" +
                "TXi1S4Z7C3pSk+ZkWLLnabbWemIW1uuNAluZ6TcO3sqMyXVYwu5xuKegrKbVT0oYsZDvNT/TDE1vqRfL7oSx1J75vEGuJz3i9OHm" +
                "sFvotWZH3sJdWESehLa87+G/Y/QRIDFEmH4ieWZkZtnNNmizxp+GfdFwJwp2bqPZydBurN4LMxreHhh67XjYSQBmxrJeMzy0ndmy" +
                "/8ChBuLl0NyTXIZVs+qZrtGOkV5+R+uTvHGEbGqbtp4RfjtgF1tmeUzuxC4Lk9ay7i10ipWXbIndQ5r6H6d8Sjstg9Ujs4lBPXYz" +
                "IEbTV1tErxWYlmXtMC21yYTVarff7Apr8axl0EY9FmtrerSZM4v1xziWpp1JvWsBdrBMtaXd6gHE+1nb1lhjTWygZ7mhbcS8sN2T" +
                "+WPBhTLk1ktMbcXSu2haS6Z2q/fHsQ/DG51AXBfJG+xJtC03u9iEtXebJkusMr2m0Mwx1oTYybQbvPfsyrjZNJZZa9rbSY1Cm5up" +
                "GdTnSBqyu3eXELZAzQqsNNpUfttrZg02q9katMbWepC1WZndwTq900ZOu2SIaFKYwupJvWPJ67eH2yxtvOZehi2Gpjc0bEvTyxt+" +
                "w/gCO5ro1S4ZvidQLMxhWLK3p9mSLUaY185GtjLTq4e3gphcxxJ225EpI0u71Sgw0mL5Zn6eGNrundhmeUvMRu2Za/FaD3pMu2nn" +
                "8OFCazc7smhBJpmEtnrf9PtM/wAcs4KeXp4MGcw7nkI2C3/J7IvGnSjYyYidbOSGejdt7UK/uyDQEz0tg8XMWOY18LLszJb9p4Zi" +
                "kzmaG91AxUuv1UI7Rnr2O1of5ukR8khjt/WM8OsAvdgy8jNzf0AzwfRIa1l3Cz2MlZdsCbaE+l/n+kPytAymV+oF0Mt4X9hA1rTd" +
                "nSyilwLTslo70FIbJqym3f69WsFaPGsZ06DHZKzpaTM3A/4666XJzqRGWFZYtoplN/eYFu9X0gnsHqzeIgY944a20cwL2z2ZP15g" +
                "QRly6xFTm7Ds7LbLLFNr9f449ofhjU4g1kXyxJ7AttzsYgvWW/fDCBKvwr1GK7ZujTUhdjKtwXuPXbEdNs2WWWt6mSYbbZqpGdTn" +
                "KIVl3bsSYQvS4wSTRpu039trWIPNajaN0dhaD1mbldk9WJ+KdjTtZIhoWmGrnemVesdSzz8+sEra9AzeFZ4YmjY06bGs16Q7nQ/Y" +
                "aaJXu2S4BZSFOQzLzW8P25ItRpjXspGtzDT39OQhJteXJy27GpoysrRbDYFRFuOb+XkwtN02bhvTGIb2Wov2mtEz6T6Yy5rIWrOT" +
                "RcusVjIJmd639fvM/QEYxAp6enkW1GBePIM2C381+8K4JxUsGbETS26s3k1+a7B+d0FAT/SgTRMzY1kbakPeebY7fupQjDCX5raj" +
                "pjna0qiFdoz07He0PsyioE3GbutR4ifLG5Vr8kzuE9ZF0yOtZaWtUUyW2hK7J7uV/3WsD8nTzvD0CNnE6OXxvlBA1rTdTRZpoxam" +
                "ZbV2oJU2wa2RdvtdrSAXz1rGNOjNGmswmWk28Hd6fLWkmaiRLCssVrHs5h60eL+SbaYZ413MoGfc0DaaeWG7J9t/bKABNeTWQ0xt" +
                "wrKz2w4jU2v1/rj2YfBGJxDrInliT8DGzW49AV7TH0YkXlq7Z5I0c2BNiJ1Mu8F7z+5ga71wLLPW9IJGG3ra22kw1OcohWVnt12K" +
                "LUiPE0y7l0ba3oP1ANhMszWwpq31kLVZmfVYV9GOpp0aIpoUttqhV+odSz3/+MAqyfQa3hWeGJo2NPRY1jPpruYD29FEr3bUcAu9" +
                "1MRcDMvNrwPbyIZNmEf2xFZmevXwRojJdVnCbjsyZcRujntSYJTBurX36wBDW9u4bUxjGNprLdprRs+k+2Cu1kTWmp0sWmaZZCkw" +
                "vW/r93HuD8AAG0xPr54FaVjveAZtFv6myYF1TyrYyYidGKmZ1jQ0vPfG0IveWgajmbHMhtqQndmy/9Q5LZlLc1tqmqMtjVpox0iz" +
                "36/WhxkUtEdtk/XYiseybMvIG7lP7GTJVGtZd4Y9jJWXbIndQ5r87471h+RpZxBeqUWMXsb7IkDWtN03SdJGLdi9Y1o7oJU26dXa" +
                "ot3+XVmRCY9cBg16s8YaTGZuDfi7enyxZGdSrx3QCgs7TLu5BxbvV7IZ6bXpLWLQjBvaRswL271s//kCi5QgNwQBlAwkPDDAAQEI" +
                "UEACAAhwQAIAAiwoAKBAggMBDjQYCFhYGAhoSBg4aEgYLBaHQuMxACQKwjAMQnAERFEIYgwDBhAgEAJBNkAkCAACZKQMRICMQATI" +
                "DCQgAQnIQAKYQQQJBBBCBgkwgQAcQAIAA1gMYBEFIBoEoEABaNAggAUKLKiwoKChwIoKGyoKBTcablRUqDQa7jSKphALmYWJQoCA" +
                "QVAEJBJBCCGEBDESYgopM0bmyIMxInJmZooMICDBQBobilZVW7eXdt1tt92+3K123a1RiAULCjIaAmsSloENl5M5zMnMOZmZnJyc" +
                "g4QeiMHBoAalKLeObmo3f3TLrruu2+1dS2dFjBjKNGAMbNlsvbiuuKrrjrs+vuOJhGQnIYTY09agOh/WTo+bw9t06sdSIqnJDTJh" +
                "vXttG7Zstn523HlXXHfckgIQ2o4LRap967jdw9dD0Smnx40XMy6TmzbqsZa1YWvtkT+Li+OI1+XhKgYGsdzaqd1qX8pRVbsxctT+" +
                "cl82vyWGkeT0MzAzmPH6LY5ibFuvfhjtNPctAXC7kpsdRc7ldvaarMloBtK76SetYDAz1ub1DJvN3jou6peeYkvStO5MAqcmLddu" +
                "yUnS8sbtiRuDtjRz0+vh7BltV2Wx9gi7sGPwSmvZ9XZKJEs7NUn7ThPV/zx3h1DCYPUsNjH6Xdm9s8gwRtPVFmlrxy6xeyNd+8Js" +
                "R420L12J2y9aYC2eXTJovfSYe5af0JOx3VnE/LViabJjUe9asOyIhYplt+4JxJ61bS3rWUYzoOfVLD0G1rvx3M/i1w4NxNDu7VTu" +
                "hJGW3qVP8w0Z7Vbvc+iRyDQlGG1r1y3LRPOkbZdFWJtPWy3N09buFkjNSrsnq0WTerU7wduzlcciNHabtaZ3QlsetGnrjTC99ViI" +
                "wrLr7RBhEu1eTiBa2pLteT1jwGa1nhZ6d1juDtbWZjHryW66kx6Z2WVxEk0LprB6knezZN6WPGtteUaPxsTQ04PGeqytJ285ab7E" +
                "0oSX3TLcW9AbNmlOYOfmeWBrlydmYb3eKLCVmX7j4K2gebTjsARtR6+grKbVb5hkxEK+1/yIZmh6Sz2s1QKLtta7G+R60iNaHxY3" +
                "dgut3ezILtqFHSKvNNrOe4ffmf4JUEY208+gx4zMLGttA5s1/mj2pXEnCnZuS7Mj0G7sei+Y6eJtGHrtPLIrADNjmdcz9mi2Y15k" +
                "/6FDDYhNaO5Jl2HRlkYt5Bhp+T27PskbR2hT27T1jPCTZVclszyGu9ixsNJa1r2FTrGy1Ja0e6hW/Y9TPqXdKIP0kWxiUI/dDIjR" +
                "9NUWaWu1sNpZrR2alhrxbppWu75oBbl4dsmgoR6LtTU9NnNmsf4YcemyM6l3LZAdLFNtabd6AvF+rG1a1lgTG+h5uaFtYL2w3U/i" +
                "jxcsKENuPYmprVh6F01rydRu9T6OfRjeKMHcXiZvUS7alptdjLD2btNkaZ5megtCs2JoQuxk2g3ee3Zbc7NpLLPWtLeTGoU2N1Mz" +
                "qM+RNGT17iXCJNSswEpLm8pvez2zBpvVbA3u3SFrPZC1WZndwTq900ZOu2SQaFphCqsn9Y4lr98e3GZp4zW9DFsMTQ8attH08k7f" +
                "ML7AThO92iXD9wZ6wybNYZDa29NsyRYjzGvHyFZmevXgrSAm17GE3ZzMZWRpt3pQYMRCvjU/TwzNvSe1Wd4Ss6G91trhmQ96TLtp" +
                "58BuSa9d7MiiHZZJnoS2vG/6faZ/AgargX7yZE0gM9Y7nkI2C3/J7IvGnSjYyYidWHJDvZu2dqFfBwy96GkZLGbGsl4jHrIzW/Yf" +
                "GoasZY7mHnQD1bz0Wq3RjpGe/Y7Wh3l6hDzS2G09I/w6QC+2jLyRu1g3TU+1lnW30MNYecmWYEuo/3WuD8nTMkzPyguGXsb7IkDW" +
                "tN2dLKLXCkzLau0wLbVhwmq12+9qBZl41jKmQY/JbE1Pm7k14K9jvjTtTGqEZcFCxbKbe9Di/bbsCXYPlreoQM+4oW0088J2T+aP" +
                "DTQQQ249YmorLDu7aa0ytVbvj2N/EN7oBGJdJG+wBbblZpflsN76pskqXoVeW01s3RprQuxkWoP3HruyFptmy7bW9DJNNto0UzOo" +
                "zxGFZd27LcIW5B4nmDTaJL/tNbMGZjWbxmhsrYeszcrsHqzTaEfTbhkimlbYamd6pd5hqecfH1glbnoG7wpPDE2DpvVY1mvSnc4X" +
                "2GmiV7tkuAWUhTkMy83Pw7ZkixHmtbORrcw098MnD2JyfXnSsquhKSNLu9UQGNVi+WZ+nhja7hVuG9MYhvZai/aa0TPpDnNZE1lr" +
                "drJomdVKJiHT+6bfZ/oHYJgV9PTyLKjBvHgGbRb+0uwLxj1JwU6sWMWSG+rd5LcG63cXBPRED9o0MTOWtaE2ZOfZ7vFTh2LEXJp7" +
                "DZ3maEujFtox0rPf0fowgw1ty9htPSLzk+WNyjV5JvcJ66Lpqday0haKyUu2xO7JbuV/netD8rQzPD1CLmT0Mt4XEpA1bXeTRdqo" +
                "hWlZrR1opU1wa6TdflcryMWzljENejMxL0ybudnA39HjxZKdSY1kWWHZKpbd3GNavF9JJ7B7sHqLGPSMG9pGM68ddt+Kny+wQAly" +
                "6yGmtsKys9sOI1O71fvj2h8Gp+QEYl0kJ+wJbONmF0+Arf5IQ7Fk7jWSmzmwJsROpt3gvWd3sLVeOJZZa3pBozf0tLfTYKjPUSuW" +
                "djS3whakR4Jp99JI23usB2AzzdbAmrbWm2VtVmY9WKfRjqadDBFNClvt0Cv1jqWef3xgldr0DN4VnhiaNjT0WNazpTudD9hpolc7" +
                "MtxCLzUxh2G5+e2BbWTDJsxT9sRWZpp79GTE5O6yhN12ZMqI3Rz3EwKjDNatvV8OMLS1jdvGNIZRe61Fe8bomXQfzGVNZGl2smiZ" +
                "ZZKlwPS+rd/HuT8AAzaYnl6eBWlY73gGbRb+xtAvGu7Egp2M2ImRG+rdNDS894KhF721DBYzY5kNtSHvzHaPP3UoRtSlue2oaY62" +
                "NGqhHSPNfj9aH2awoT21TVuP0vwkvVFpllfkPmF9wfRIWta9hT6MlZdsid1DmvwvQ/tD4mjOILxSNzF6Ge+LArKm7W6ySBu1YPeO" +
                "ubUDWmmTXq0t2u3f1Qoy4ZHLmAa9WWMNJjOtAX9Xj6+WNBP12gGtsFjFspt7jMX7lRQjvTa9RQzYyGgbzbyw3cv8xwsWmCE3FFab" +
                "ZNlZ280qU2u1f1z7ILxRmSEvlif2ADZudusJ8Ow7nVbxqrU7k5abiTXWLdoOPVqD9452UK4XjrWmae2Jttpo02Y1hvocpbDs7LaL" +
                "sCXSsxNMu5dG2l7nGRnYTGwNrLG1HrIAQjTAHIBAAAMMCEAAAwoIgAADCggogGAAgAEEAwAgIAgIMBAoEDAIGAAgFAoEQWFACBQG" +
                "gOEAgKMAhqQIximjrDHSCmOlcQ1QACtAA1wbUAFVdTI3ECVwAcACOAEwAiAhAFAACUgJJEAAEQQQQAIJJBAgQgIyQ4IAjBCBCTIw" +
                "QERCREBGCCMiqIAAAgFCgEYgoAELAQoWaBSwoFbaNBRNUxRVY9UUrVaXVuxeWqlGsWRRloCgISSAYRARGUQmMZM5nMzJeTjPJCdz" +
                "MgqAAKKBSAkVqh2P1rdZXa/dtrftbW/3+spyXKRQKIAODKFNW3vDkOv4mOQbzpycnDmHE0wSgwEJiWt19lB/OMfmtt5zW7f2+bI7" +
                "FJ1GDR2QGJ4HPNIR5s3jOpnM5K67O77kZdyC9FuiCePRGHJOyrl8VJ60lFdWIyjVO/RkVzBmZrY2z49EV3HnHV/SveRTzJJUzN1S" +
                "Jc5dKrmckytVt+MFt8UN0JZm7q3nYexZbHd1FGfXURPYYeiVdsnO3qkxIu3ynUPdl3LS9T9jJwhJNlDPi40ZvSvv3pA4gzhy62iL" +
                "tLVjl9i9ka59YeZcSPtWK+X2wA2zeO2SNVrPfNTO8jM4q2pnEfP1iqXJjkW9a+GyI3aotLNbdwOIPWu2lvUssxnQ82qe9NjC/BY8" +
                "jO2PBSKGdu+exD1RoqV36dN8Q5Z2KaV198A="

        const val SIX_24: String =
            "VFRBMQEABgAYAESsAAAsAQAAEXh5tN4XAABDpKb+/d//+6//99//////////////////////////////////////////////////" +
                "////////////////////////////////////////////////////////////////////////////////////////////////////" +
                "////////////////////////////////////////////////////////////////////////////////////////////////////" +
                "////////////////////////////////////////////////////////////////////////////////////////////////////" +
                "////////////////////////////////////////////////////////////////////////////////////////////////////" +
                "////////////////////////////////////////////////////////////////////////////////////////////////////" +
                "////////////////////////////////////////////////////////////////////////////////////////////////////" +
                "////////////////////////////////////////////////////////////////////////////////////////////////////" +
                "////////////////////////////////////////////////////////////////////////////////////////////////////" +
                "////////////////////////////////////////////////////////////////////////////////////////////////////" +
                "////////////////////////////////////////////////////////////////////////////////////////////////////" +
                "////////////////////////////////////////////////////////////////////////////////////////////////////" +
                "////////////////////////////////////////////////////////////////////////////////////////////////////" +
                "/////////////////////////////////////////////////////79//v//////////////////////////////////////////" +
                "/////////////0/6/////////////////////////////////////////////////////////5P+////////////////////////" +
                "////M/7//38t/v//y+H///////////////////////////////////////////////////////////////////////9/Cvn/////" +
                "/////////////////////////////1+c/////////////////////////////////39h/P///////////////39x/v//////////" +
                "////////////////////////////////////////////////P3H+/////////////////////////////w+F/////x+6/f//Of7/" +
                "////////////////////////////////////////////B7f//////////////////////x9i/v//////////////////////////" +
                "//////+r/f///////////////79B+/////+PNv7///////////////////////////+A9v//////////dun//////////38N4f//" +
                "/////////////////////////////4j9////////Hyr8/////6e2/f/////fs/3XMf////////8HL/3//////////////1+f/v/B" +
                "/X/qqP//zZv///////9jjf7//9+l9P//////////p4X9/9/Y/Z/V5P//f5Tc////WrH//1mZ/f///74j///QYEbf22TC/8iNvuTD" +
                "/3+SCveNfIzrfFPq+W0YcZKvDgORzKS7f8DDdsXPFNAp/nz22+e8cea6Dn4+cWG1erFGS4nbao+8A7uoWgeAwDQkpbBB2MLCayJM" +
                "oVz/gvztLLpXywl7BMI0ArAh0v9EjTonVQfnM1uftLVRre55lpwa9rv2VgMmM23qQ4QxD5zfs84T7vdA2pUpamEsg4Dm7KUeBIpt" +
                "9XYap7Qp6Axm+DxDffX1zjbwlSX6Z81rVi3cwrEf1uZ1lXl+2HxfAKbMKRRfPAw/EtB8zH/sEsAZ/7m1M4mP3EwANhtuuVmJJ6nA" +
                "oe+BHJOshmsQFBJaywbHUI2ZoOsn8rkrC87XqIKQdVhp7JM3id860yKcd4pImYz49lbG+EvYYGvQ2QgLuiCLC+SR03+jtHKHRjl2" +
                "/cPgC0GiGNzBD4CMIVbdc2QzwTH2BeH7sN0K6n6sM1x8052i3SNLXBTV9Tnkxt7u57fYbxpWAOdvu4N7M/Un92jXGIpF+hWoU4E9" +
                "H3uetXl4qnz8cuOe2cgVXRsBlaldtZ+TpV2nWFQlI7/WBynafrXHVWVJwX/uWNjQIrj/feP+lWAFPqy9UZzD/BxmerfR02apereq" +
                "MxPqkKivdq7pJUzg0sn7dwYBnFA/fWWd0Z1Rc/Rs29FFi6clStXf6ed0v7rVnAZqwZB+gnnTs5z7fbFsfecP9o17aLjwYa8RUjH9" +
                "xNTA8i2vAW2Lt9FJ6TLsmXWwDcvRegEHjDidNZdak50fNeTfaDmc5XwtQc/X2w3cZXx3/0y0E01Ujl72aH1KLlfsy7FMoMIapFna" +
                "WpeAwLMo/grW4+sBZ4nzqrFKK+h03o56a5SEXwBbsOcK/tF5zGUQyor9ZXygB6W9YbuUcwWYspuRGWB9Zj7TvPEq/9hBlxi2Cw/g" +
                "0RnbhI42xqZZTEN5ODr3rPqB3rQrf7qmtxtukwgXHz5a1uv7lWlIwgo0AO9lwYH60TYzcrsQP/atH5GKh+fcKxxlmkrwflpJ1epr" +
                "rvRHKIiATF9wlUMM8Zp2+1RUMzf6nM6qVYZ1NM5hzjmWkIIzQ+lD0sSNM+Rj9EfwBn8rTRWxW0weYdsrDOMo4/NUCwLdLKDNxUyE" +
                "WeX4vYTr+dl0VWmikvNdM+qfcg7MwjQTNTb/jQkdQ1kI5OHpFNCxJ8sCTOnkK+buMW+P2DcnSOvt7L44Y8yUe0gM5CYcsdqruDRY" +
                "WPj3H32GJoGai44hIrG90IKTJXgj029w/p5uI9RGHf0UkTIzYN7ejbq7RvnOCV+qvwwkZB2snv4O2d/1vjD5zOj18N9DoGfMCVSu" +
                "GvzuQCk8EGAkOG74He6zN8ddTZ5iYqyE8brgbUAOyPaFPdcV1HkgaFQOMzf3VkriIqy9wveUuz2i87SXo0z1/jTuc6bHu9dQ9tl9" +
                "09W4nCz/PUVmLpmNSZvTRmg9xPNMjnEomNUZWJ+FiI7kAtKkwAbawHeOa85vlKTAFeE02BeixqYlg9Bg+9sgcP53ZPS8Jda99Jit" +
                "nERjwONNz9eTh1Hii5KQzHpsEi28gYOIQfa8hRock353N9TnPooPpE70sjcuBiaU1XpTRkjnxAeHKRS4ZNdmsdUy7TwoF9hPD9rM" +
                "MDr4Kw/2ecJnng2L36wYqKyk8XJFRQj02IP/vgDxro9+5GneiLhAk2tBoW40llWFFJpZenBzzXWPN5C2GjFecGNajxvqMQQAUQ9C" +
                "pquuW50rCs97sABJuHydISKpcvA3neOJ1xIvW1dsdYUX3bhlEQnMJxVfDCt+BxPRaKWGO8bGa96jhvRLQpJ0MJaewzdw+cNDV9uJ" +
                "jfNyeXZazwWJJsJUKSOe5AvsNikl9Ku+SxPHijcDyzUE1GrT6Fif11jxOQeAZ0XXU1671NxkUjWDAl7skVH/Bs4knwQvnQ86JCiX" +
                "3w1kfJszhkeoShO1WABL1pKcfsc2iR0LKC/1b5Rjdx8b8kzGtO9N6liMpcDolUmHayj0odLaEJoP7VKowjd7XB0eiKUeqgCckUxt" +
                "ibrGgzjb51NUg8hHXhrma2XrQNvHotOe1OWU55NjcAW9SzrJSTkJYdVL9fMP2H8nJpN0PdL8yBQHJQCVAWYf8Zsig6PVKAFclAPs" +
                "Hoa81f6ctwT757Jdp4GclpFCp9PX5LrmPw3Tp1Yy6LPTXf5FFwGNCBnjuiWl8adFIY6qQr38aUbZWHmahaUf/9p9PSnNlunEGvNs" +
                "TQ74E9T89TFurEJF117V5pzZWTE3dl1Pj8s5hWt4ADHys0T6qy7usuHWHJuyJ6+Oi+fHgUyTwpKnromxZbbn4ZrDC7lfq0lPxVt8" +
                "6B25/qF/tVSg8TQVDXkCu/Jgr+PRhSR+u+/whzpd74sYq6PDwXLBgeoACzb9jjXWqX048iM7xELbSeduMOIefbTJsxZCD1zaUNpo" +
                "IMA7BUgYnIJUi7wERaOyaGBtTUAh8ZB36WqaHXE8DZhvnFDwEM4JCUuWJT7EkGSw01J3mCmlf+tUa8W2zAD0W2nrdDzBWOeKDnTd" +
                "xvkqoW3+bBY8aOLUzMBtqQJI8esgWsoZZ4vqzcWyjmXO+CxVVACwULjV6xB0xMDLHg2buROxE+xoiDMN0jw/3fV+Q7Q6S2Zgrb4l" +
                "mk+5YP9ol0UlSj2xVpHLgJKIWuio/e58QvkkVT+UVk9uPXE23wYW1A6PZFe8/uhG8BmLElYlvhTyc5Jz4Tkg+FisqHtx6ipGkHkg" +
                "K0z4aGFX25zd0ceeo4HUsBmA8X8hxxovJEJHzzh/CtYg1PHsSdsT2y/PeiMcwRe2wB0pXwQnuMX/k2sn7x/ez8qp6uFBw5RaXWBE" +
                "p94rVfWJmLsJgZWpwhstf4QwVQgrKloxsVDteVkSnOHj+e0Y96iMv+U8MMx2rw8Fdi3PLPDAB3Qn26nbJQvJVsrLEPvIsNsPpRxa" +
                "4erhjdvvu2NvDMLWWTJZPbMna9UCkhdEuNPV0hZcVFbFtZnPm/vhUf64iAYOCQDcQisGGku9Z41TC0nMm3XEtpfEgcmjfsQybRDs" +
                "uSRrgeg9ZOVUcWE4pbk7hER2Ar63UeBKjDwHKToo9ZmV7Wcri6fzZODzx2JXhyMHRy+oBmpWxY9HhQjtCj/GeXkMuBf4Csv5w8L2" +
                "qr80D6HxBGQh9lMlOyGVOpIa9xxo8TBCnPxmPu2n323Xjqq4fJsQ/3zGZnAzS6Nui+hXGbtbsaoB0kc13ZsaTrIk3B7vauFRmY00" +
                "7LWW1Q4D5eO15XhmbA83jIMypJX6a22R6MmA+FmIVdPXBL55l2PpQnfNAmGc2pCr1pKOlT2nInC1bKG4l0A5dtSVYf+1UPhaApmw" +
                "41pTa9fjSTdIVK3+8BAOqu0h4vwvp33BwK1d99DJK0peMN09PJbP68usCeeJa3lvvEYXYEw7mx0DcotIais7tclMzzBM96SJXqih" +
                "J2HpXY8yJGVoB/yRzF5MfzhAb47cBGmdWnyIkq1AKEJqc9Fyvtw2fOz0aSzEg9lOeTMjTKEjy7JQHylXpV1+7TkYDMxqyIS9AYA2" +
                "+y+Obb/NtQas4jJfMNo7PXObUa0O4MLFJ12Rc/H1RVk8WplbpfvwblD1skE8f10ykjtG97/MM5cyocVTjZsG5hSj1zxcKnFeik71" +
                "4SmKg0iY2oNLB9jYIlAG5oplg8s8bIobAhgoPjFHgOn7xefDn2NF4D8ldlyqHk3Udf479Wd5oy0F6Fr+lbTNxXo7gDmKjvwAa9BQ" +
                "ubP7f1P1Hk1iK9LEzHazH4DeTIbuVkx6BjvzZ9L+KrqQLcfi+BnuDqsH/XFAZO1RoHzXROQn87wX4VG336YWc/P/ard1dODshvpn" +
                "bpPaW39uVi4WL/CoL9KLUYayHyv1EceKg5+y+JWoMgsHXyS3qsti9Y4AQ7o/XE8V7U1sAM3x+2l/EtUJDDBkfW+gDEYa9I78x2LB" +
                "beXw9BB79a9sWJOwWrFthvuYclOtXQBBz7TKvx9FnjdNg8MMzmVgDo5iUjjRmdbAi9Q6gyfthdWn4Eq0CqhMSIRl1HouPjnsA7gg" +
                "KoUCetyIN4FAg6MyuAupKDLIzDWxRPdFE4OewgDmoNpFnLQU0zBT4+q5amRlkD27Jd7jNd8k3yOs1NxEWKHnFlghNUxlZKNkTlky" +
                "JksOHH7CdEh1phbN6mU5liKqWP7e36RqWHvIMdo56qxwTO7ZRww9YQtCr5Govbu4CIfv0/1N/uhskXuniowDlnsbZWPzHJWkJHwj" +
                "BFT64jIXVy+6J5PrmRDvuXXfqL0xnKXE2CWa9zHxxzkXEQ0gB6lAZSPF6L7DIFmyblfmLqGVCHTuKcHlnVXERQGIOgDKARNNh0ej" +
                "KpFRBfpxrApdUfWzJNt2K0eNqVvmYlWgF+lm2L25N1O56iJAj+F8xXz6SSoIqmjQTHGPVWSx3+9owI7L3/tz12/zv5G/mHvCjmmq" +
                "iYax7eBGyZp4t6+JMHMKXBwMbK0GDKn2SeHXIp5noV/kCbX+ygAcbYpvQEfn0Xq6+wIkSpeskxtL+byp1QwrNwrcGOl7DL5qlbTb" +
                "Msx+aXfDsXb/5c99fusbaI4U60PjCDAoKy0UCVTKA3eXrBf8lHvQC+/TR1GB8mu3/+vrm4YHS3UIQ7lgFdzGP4/NE550d/3YIL3Z" +
                "F2Rcb3SdhQ81Ji0dimbQfli4lCV5OTcHMV6cW6DKEMjVyP4I1DFJKspNef53QpyB4kCrOPl8azfIVMuWHuTI9kYM0/TPrtRvKbPY" +
                "jW3isKSbK3CHnTE8DsGC4qasosvIAfcJsdu24Rb5V5AqStUufPMVDyDVK28zR+pg4rZspg5QXGS4I4fr2kMzSSnspExLohqxne77" +
                "BHeSlqBQdInkz8/NMrNBX8d9Zp/QGjgy2ROTttV1x8j9jJg46urpHco8wAD5bKrgV2V57jWvBMVWLrPuqtTu4ZIBG7WZRlCO2GqD" +
                "RHJhuGDwy9X+v1mSD8lCXRi9atpmXtqS0eNibDwO7xwJlvSz0xiNwsShhZt/6Qj7tgE4padca1qbRRI2fM6UZeEzFJc2jLQcjKa8" +
                "06WQw+7yFJr4VZ/q07rdpRVh9rwPBsc6h36Pgyo9seZ1nUEJZkghGUSBHuh/vK6DyeF7K+ukeqCbAh9rhmCyGxU9UNiEgkJ1V4Hk" +
                "KaCqxOTTdfnwuLFgNBc/LYctWuHJCTt5d++3wSfHrVOJuf9QLSkpFqK+Y2hFOg+ZrCySTLxKM4Mb2qwHxNbVUoRMdCh/Lum+IWgK" +
                "jyFTNsg9d9u+40JGoA0zX1Gfy5wr1otlERAoWEcDsnz8OQjF5PDnWZEYw+4OBoZkPppw7vJRuVly8Rtd3KGoi26q/sGXRoMZcjW2" +
                "qfbh7LoRW3CSSZc2ftJsR1ljj8/c8J8Hp5EeuOswBzYRA0Fryw6oGzdrCsXZDTHGx6qgjx7COAan9PZ0hkwzbzHGxLKOcmchAPN/" +
                "bWqgixzk5suZCJKk9ysx6CfcRbCZXxD9I9gFuadXSyGQqxFzI/CrExxmd4Ri1VX4f6JF0I8ZvocWQMJLl/3H51TyRb8bTA+a3w0l" +
                "ipA164DzRbqW+ThoUMNl3g6awIvpb6k/MHvwhFW8hUrsJBcCS9KtNCUPS7zeqvH3Totv+zJ2bjleTLgAcfd+Mw29PX1IW8RtzcdT" +
                "WR142UAjbUsky7kQ+v9OHdXLZIKDP13Wlla5RmtCyW5QPE2XTpA17peLxXQmw4qiBFknW6s13tTnHDmxPlYT9n+QHj22YNKIWa2E" +
                "QtK2INYLmN7ZQznfeiyBJDxHvbumT01p+WvgUth4bB/5R2UQ1fxaN9EiK8+/C+D2zLkvs8758UIClp2syBx3cFVKSl17Do3qVUve" +
                "O2+r0ouDoDG27XETBvfntDpeHtMa5RREhnFordl7HsS5xKoiWT6gIMqFItdWIbmj+czZXMdxK3ar8Fnf3zlJy0iSQEhEKbd6LL+s" +
                "qlArBh3QLeb9L17e7eF2U8gw4oxKfE/uef/OKGqxepQrTRyhhFl/DqtXcDPwsr9IUZfHKFZPSN/SBlqgBS/bwK4="

        const val STEREO_20: String =
            "VFRBMQEAAgAUAESsAADoAwAA3w7oVz8XAAAegmWQ/f//////////////////////////////////////////////////////////" +
                "////////////////////////////////////////////////////////////////////////////////////////////////////" +
                "////////////////////////////////////////////////////////////////////////////////////////////////////" +
                "////////////////////////////////////////////////////////////////////////////////////////////////////" +
                "////////////////////////////////////////////////////////////////////////////////////////////////////" +
                "////////////////////////////////////////////////////////////////////////////////////////////////////" +
                "////////////////////////////////////////////////////////////////////////////////////////////////////" +
                "////////////////////////////////////////////////////////////////////////////////////////////////////" +
                "////////////////////////////////////////////////////////////////////////////////////////////////////" +
                "////////////////////////////////////////////////////////////////////////////////////////////////////" +
                "////////////////////////////////////////////////////////////////////////////////////////////////////" +
                "////////////////////////////////////////////////////////////////////////////////////////////////////" +
                "////////////////////////////////////////////////////////////////////////////////////////////////////" +
                "////////////////////////////////////////////99//////////////////////////////////////////////////////" +
                "////Sf//////////////////////////////////////f9T8//////////////////////////////////9fnP////8Dnf//P8f/" +
                "///vhf7///////////////////////////+A9nP5///////v2f5Iiv//mzeTtfj//4+Sy52vyYQWVfim1BnZ+oCHpQwnc13267VH" +
                "ntB9YQsLjn61nMzzd06qDMeTGvYMb3/POmnud/ZSYdTxeYZihfYtHEMWKRRfchroubXDCKakAoUkIRuc+YoVVZDYguGdouTzshEW" +
                "LJHKsUueaLHqWEPwY52BWnqH3GQc5b2ZCueMjz1P9uhEQCUzCdf6IGCRQotw1j1zmA+aKqhDsnkUAZxPgpYuWrjaP1ALgDubP9iB" +
                "B9PA8iRvBNtwDi581JDzrW589+AVyhX7BHw9i+IzAkOnc8a+6DzmME4v5VxUa1f5RzXoNsYmhN2VPx19lV+ZBh93yO0CKS5NJQg0" +
                "4BdcJYr2apUBOs6SJta2yS0mTyDVAto8A32VJmodzea/aYjzsgA74q23s3vcPqu4tIP2ISIh49I2QnV5o1G+i/WS/V2lC6hctYrF" +
                "77M3GUKQA6JEhXsrb8ZkL0epPd03XTHgaSO0C2qH6MhlkixJAdqQfxsEKoBNY8BdRWOTaKOw726oZvnWepO6emy1XPDgyoO9nlnl" +
                "igorU/NGtODbzNJvw6HW40xnvaLwbpxvOseLMxCRwO1aumNs3cv8Bi5vekiiiexDkUsTLzT8a6wowfeaQdGW0KBDbhBT1GKrcJb6" +
                "tyHjwVhKPRgf2qW6mJ+RTGDWvTRst9f55JB9+ucf0FhrKgMYVs3u4SjhnJaRzqgGffYW8cefVl7gwtIv7h8D/mSN6M/sLPO+8rMk" +
                "iUpcPK/qysM1R/H2oX9lIxQeXbRJOBws8Q468iMbKZ21EE6BTkGqoSo55F1q0jonJPZ2MqV/Z6kHxjrJxxk0cWsycLaoqiACt3pk" +
                "Z7GjYTZpBtYqeumsVUTyIFU/jDZtsiuOuAknOZtKzDyQM6wTSA2n7zh/ChT2OYIvYIzO+4fzD+rU+0x/HyFMKboZPp5xCRPY9Qxq" +
                "ZSvlMZIat9+ISFctYJG4zHw+gnPFQCPLkyQOulfsIasopWyjwDjfWDy9ZxCBmqVF3V7gY89zIfaDQB0hTpZ5iH++R5wrVjWZgmrh" +
                "GZqg45m3vd6TAVZD7pqF0/taLVt+vGoJpC6z/vCAYvq6hwYQThPODk6QW8Qa6As1VFKnxfQ3Y04FQslOuRAPrgF2VdqNYXGz/0Zp" +
                "0zO39yEkymLxVq5LxnCYbhoYz75EcWC+6rLBNcTw/eJDZafz32HbK9bbZd/eVL1SiHS34tmrFMfPvl1MRP7qDGq3dSb+F4sXnS1W" +
                "HNy/rdU76uF2P+0DNdQ78mwKCquVV+er/LtvPVI4mRYwV6JxkooLorsXdCGVxCgAc3C4O6wM6uVzIqy4PSgZkxkilmMpznJz1JlX" +
                "gai9czmyIuPwVg4BlVHH5dZ9eQE45xILE8mSB+/LKmK2bhIZtVHqqDGNDF3lqumMBs2UD4nnrp9PCbYdRPXU1mpD7j6h1vGxCpCo" +
                "ZY8CN+b0U8Kx9lwARoBBw+RT7oFljzQ8ODJVPOlSZnCoMWmBNwcxIl+SpCKB8POtHdbRsyvV2h87Y4DzjN225xB9AKkaYxAZ7jd4" +
                "ErGdj7w8N8vmvDBpW4t2PMAAkaHlMusA22Krjdaxh2Qh8CvxOIx7Gzf/BnEaNnwt4+Sd7qvQ7NKKHUATax4Dfbyuf/N/wWT3TaGC" +
                "qhKcyhataG5uYu6/BNzJyrJkFKUI2T0/NshVe2rOFb+mSKGYLMy9cO7EZUzVv/61E1vwKrK64Z/Iqtqyg7C6jx4W0SixrAXl23w5" +
                "5hVfEH1NawO/Wr0PP2Z0zfu7waQAlMzHAZUEZs9GpJWmZEUjtxwvfSBF3K6RUs6F141ulWt2Qb5cLGKT9DnHvrqIWb0Li289lmOx" +
                "l8IG6TU8/y6WtZMVX0LOkvfmhqo/p2kT6z2Pd/bYViGFOb6/c3TRrqrQAurdFLID5epR1qfzZX+hXp2SijXCz8t2uoYWriIYtwIN" +
                "+om48OjQ4RvRlfhoaNjTo3PSBqYRtwaRmnge0aWQ0aitGTHtc+UEke/OfOjm0txO9EmD2fEIjz2QwOpTRoFIML0e6mZoXm8klXtg" +
                "qr2jQWcaBYshTrQk7bMAcP8x5skpLVXJhEmKRxyN7+H10K/baOpcF0zMRRaTOrCRlc2adTxiEEx/Gn5AyNJQfecHhhMH2HXPJ7cB" +
                "o9hCofjcd4FaCLGUKYMyMXsWVz+N8sTjjlQKrhL6m5y9lPkTm5S7o6ngCF/CQvkkDfvH+ZjhkTRR288VhbViB29inpQUrDgUG8cu" +
                "DSW2nzE77G9XjKmrmJachuY2/X+rcwuEqhZIu/sbrS0p3gDOx1bCJZ7k3H6xkaySDYK1p+lbEJcN/Pu7gbsCzUb3pYhmZoFe162m" +
                "eITaM651IS2ah/9SOEAMgs6aPXnP4L5dud4Cnr3Cc/oYCknai+CWgPlORO+guFJR3pIs7PPRS1sUYsvSd/i89u8uNEf8rnK2vGe6" +
                "K53CGC7ZoivMCICQkMlVngy10u2CVeEfDxOX1L8IyfPqcHP34gWy573307UnTcjhaB1vSs3vrKVI1zdG3UDEgg1jCNFO4tFo5aX4" +
                "4whMr8C7FynVPj8CL6/Q8bpdWrUlTNVX5IqkU0H73GyKcI+CyKxsWRwOiryYhxje/Cxd1EmIfTjy2vnrZ9ZjrdOk/mImIzuNEoeu" +
                "RCDf56JUNcbi1cCo57WSSZrdHEhgUkECRqjg4XEAEge0KjKd+lhuYuEAaTChOYlLNX1/2gqZUrev6rDvN28AjdQnrIr6D/pPZQ39" +
                "fi0NtcQ84+T5ZM7AOMpqr142Ya1BcP6XgoaPRxuISpVLArDIYfCsNt8QVc0un1wnB4wriKntRT1TCJZUSOXxU4TNQrkrZYJZ22iB" +
                "Q/0x0hVzaZC1HXZFFHw0T6+6Tjh3/UnKYo3o/KERJuw3wANKttBzc8ujQWhtniiIhIHy72g7De7G68tsvwBfjIu/COwsMCwG9TwX" +
                "90sF/aU+EDTurNSgaXd/w5TGHqCi8CULAQoYJBSvUmHE7L8Z1dzGI1HOwgLH5hapZLvSAUKxWR7RjDhtXD1ak6nbs92EkJC/dN6o" +
                "HWRgbNt9I0tcoRZzm89dUvDRQ9nAs6hYKt9b8m7s3ZYyEgsSuetOwnvYet1Zu5hMaV17hDQ8sXzHdZOCbUcfxFbukmy62um21T95" +
                "IxtdZ7xrfdrfUCEjiuktV7Zcxn8uqe2tdC3fUX8aqeGhmd2uY+sd8YoNlu6Q+r4tOq69Chg77m3uo72GDWD/1egaz5sYmxmnRqql" +
                "+DidthBBPJL00x7IARXgZhO0wvlYhyLHB5GveeYdlZUFeDj9qwUr57bsUIu4hkczjYd1tXu0B8xarVJJ46F/HSJkaj0+NExtEKO4" +
                "vr62RCKNG9jZHDOq6NAWruzRnDkAsYxF2uAdzoD9IjL8lCtUEoT/qJPOQL6HmAanEnxfUxMEqzUD/Jpg+zweMJjezZl3sTQhrAho" +
                "okwkPig2nGxz/Zi31HIQp8LGsdl6nPk8pjLvT4+J8WINR66pwQ+mwM1tx115TChR+EnQbfkM/JXxDErpbDyPqmmyq5xgCj1mQWfp" +
                "dWWPRyHCvhix6G2X1zBUyGV+RcPzykh1Xg2QiM5sEvLusQMIqVVkRofJqcBaCUQ+uq7MEvo6DI0Czwchm+cJa3/m/4erDdsuN6ji" +
                "1mqziIcv3S6TtxuxrSBr6DRsAZ0+kAziBcsr8tqdQHzjtSbwuMNVJr4+H1t7rT5o006cYNtqTMcECEwhZaVa484IsqutEYvwTu1j" +
                "PQz3Hn0vrPcpKV4XDOKkAWrKwQiH1sR5xyXF+k3W9sbzHbBKuUMuMtXfAzbj354N4e5fLY+cM8D8zI/xykMUYv3aJyactOdy6YqW" +
                "kfa6bfY8mBfJ6zF02o0R0+2dAkxPF/sSK9Qh7W9C4hw8UqLYMO2pDqACVWWBUvbsTeGQdMYxsnhKSHHa9JpHA49HAUJTmbwn9NXa" +
                "qJKrBscJP6advgyyH8SOGn90djvTmTIxgor4AUx8rKeQxzEbP4plUOM+dHWkZp6M/Mj9kcQfBBmXKALqvQeMiVGHwGSG90Qh9FPK" +
                "Az0zhc9O5wrc1BxLnO0nSlcxnI5rrBqW5oE2RiPzWgqD7606oacqI8gcIHhgr8F53XnMSPvqeoU9xk/fSxslGDEe6R/GkE4YPSh/" +
                "JJeanWIqsvI3f6xr+f5FLV7LOgR9PefwASAX9IvSaf6C0MDHx6hHrRwZ+BWrl/faFWuz183t/EmGzjojpzSeMZh6L0HM97+9wpbA" +
                "EwqUP5WmKIat96twpx/KLa+Iww4THoyqLUA/Aie/JlI/wEhU8B6rkVwJ550SZyYMjc7KV8XMMbAdXwCIWI62t7LFyrlXaVBI4lyW" +
                "WuWeeUaWKF32vKCpJGtes+iYTHQ9sZdViL3v86UpH8Q3vBnSqvKwtE84aIraLQvnrecVSLFj+MDH56jgAoaK7XEJ3+vpmN7XvTWx" +
                "xUEGF1d37pMc+6i/aguY/VXm36V+EDgv3uAOh2hU/u6Y0s4BGQI7MT0De5ujNswYan4fOZyjcJDIWHAwLEKfLY6M/LjsN++cEeRZ" +
                "KXC/gBupWSNc5dlRkbncYOhCiTnmzq0ZFKRB1u3gAf/huykCSxxs2RqpZp2Tnd8t44dvhuYMaXWOM1xKK4tr9EAb90RycDeHvCkp" +
                "MOMWsLa7kNhn8gvdjgfXB3dpxjoE6pQLqOTSf+w6ggTMMEeo+LEc023joxe5Sv0jTpZXLP7EP2+xLP4VuiIJL+AJyb3Zc7HMwCHA" +
                "g5X95EbBgfUR6XKkAQa3tjwlgbIn5VzuEw+xB0yMAHM8VHLYG8Dpe7Rs4rtOTPjhb0Dq8wDy0tiixMnMYwpIzlbNRI3VRGa557cF" +
                "j5iwQ9ncCALL/yJsetVvGbVwoW0jyXAt9LxQe0XTp12JqnZK5ZlutjV6BJlUUpcUqsdXNixBe/vWzKGXgwE63d4eYtZKbtDLIbFJ" +
                "kK42QWeYw6VxQTyEY2/Ob33p9YiDkoXV2DNNcJUxclbdVJ6EyGmI4sdbm9fsl/zyxWRh0WhjtcykZWDY7GvIPId4i8vcfVA6Ly62" +
                "0d6xRtb9ZkDIQnMJryvDon0u5BcE3mXL8Df65azGxg4UsJUppSJ0LLLdVEnc9RsACqTuuiG+ZRiKZ8wy1h8Lj+LtX3jQ5mRtQF/J" +
                "Ks7na8ZD2dRRsVpV7nwSc+Wm8jxjzFvEac+HrqMM7fudlQgPSXUZO9qRuixSrhFWn+vTQDT46CHjFjXxtU5DdYkORWNV18zHz7xU" +
                "uq+YUYcWLiiB87iEq5id5IiJuivqZeYUE97ZN+jpquN2wOrZM+EAZpL3vXil73LDLCJHDTXciWQSK5Lq0PxUoCzAera8OYGxwnoK" +
                "ZV3TECzt00Xf8rqwCI5zKPsRc1iXRdeFDzg5J8T5Z6twTO7I+HLYECfqTj4+NeVw9uUslRlUnxUZ+394aGUieqiDSZdwYu+R18no" +
                "pbR5n33IBuJE9v8odxKdvNRCJfPf4Ifx4K2KMDlbDLIVeGurHU0xq2Xhg9fZSQavUxSPVCOhmOZnoPYKlkYGa7pL/ZuR86YjwjeB" +
                "ICarm6IXaR8GKvW52msn7cTrUJx8p78aTVSA9ssLjyBPoLq/a082tgKRii/0AqTqDScLsxLOjIss6MkHUVwN1ker5YqUbqGH2Wsq" +
                "0iZxHE6I+0pXR94FnsvXOkrKDdvSfAxQTe8lED9ssl3f2xeauIw4Xr9xQGaWZlnFvNpClsLQVQ6WXvW35LCHl5dVt1nz8ppnBRKq" +
                "haLe7D5Z05fsvD6csUXc0/82dhETfCXE8IVZWBnUBfOjJ/nbcY+fUhYP1xSSuvKCRGtNYTv05fr8HsGUSzEBY3YuGf5/CMxkzA+X" +
                "hv4xbnGY1ooLiT2J88yRks/hsey/MdRZnaUSeUDeDmoavrdnHnLHtZmsc5APxKGjVYqMn7jcR1R3SzypAmfo3UURvBWeo5Sqaz6C" +
                "M8K9amAAdiPklpEb/gMP4kR2Ehqvl4csxIFOpy4OYLFP0eqe+iGDnKiOCoZO4JqyEdRmjuwoXURYtplZuuGeg8gNgmzgqEduBJ8H" +
                "orNNL44XQI/lbJThaOuSDw5CB/Lb5JMRwolCIs7jwuzv1fwfpM7qBMvDvNpFyRrhssuuWdLiGHcq6Yf7wYeb+MlVJ1771s29qoEs" +
                "BVftZRJpJjlYchqWSVzLb+D1oZJs3a7SLRxFlBMW1hSuzt2KeFJ73T7PkSVSRg1u360A7Qx3yHgIFYLZ0tVFynf3Hlfo3wSTNjg+" +
                "3iI/y27Agv9C8vPdpqvMvl6+Jiwl+ADqs1uEv3seeJOGM5Osqm5Cv9gMuKKIpdwTznwPkA7IqYCc3appsHPzz1Xgz5ngDYXF1E+O" +
                "3x2xY0/Eof2XxAg/3cpgzcAVKo5tGrWa6cU6LA/PmfLtqYVwQEWgeMPt1d9ICGIHms72DUuj+yQ="

        const val TINY_16: String =
            "VFRBMQEAAQAQAEAfAACQAQAAj3tFERICAABmlNDY////vwxpnO5ESkERDymVUAbw1Rk8TuiYojaPJpHE4Tt75A52SwVyUYSLCsB9" +
                "JgEVVuIr+aBBvQGQMjiLrqkAePql8yRhMP+mTlRjDcBTIeRgmjh5nTc8DsNI5zuCh0DSExHlMy8ZEeEM8MpLkwBCuQUahFYgC9sb" +
                "2M2prcSqKNozhW2qVTflsm8B+iyHfytbTQtLtSy1kr3nC9OAkizhQvnblFuzTKb2ftNLXK/129F6dfMO7GUFKnmTF9OnBb1pTS2g" +
                "eZYiD30nLIwmhp3BJcC02Eg5rfQgSTS17DCYOcSdCfM42ddg2vlnPu2IgiA+3xEFhXS2gx8GmCM4obSynm/uiQ31FLJOZXQHOyxo" +
                "B/Pw00jtMMeYwGiP8SqgaANoGKt1cgh+NwGhScJ644h43dYAsI39qsda3vZ19Vy15fr203Bp1hdxA6U5W9VV9tLR+rstcaeb7LaJ" +
                "C7ip7lyv9S3Izsjtt2hb58vSUcbNrAjIwzAHCIFhuxAeBiYteSBkAI51KoS9AZxPLPoDZwd/hoy4hJplRughDnHCTFwGHTHSsYJw" +
                "Hm7wyZJhnvxhIgIb6iNiHG2oKFFHHRI/o7mfBeG/sVsyhVOJpaZhlAR8ClMGxsWe/lMReEGS6mWx7rsTXBV7ybUXGUL0m8gS7kKx" +
                "7SXgajVoeA/1HItcumnEizhtcg8lkuptO0sS9lyxDLyTvzg="
    }
}
