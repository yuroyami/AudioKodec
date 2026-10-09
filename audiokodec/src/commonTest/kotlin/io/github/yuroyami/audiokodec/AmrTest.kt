package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.internal.tag.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Checks AMR and AMR-WB decoding on every target, against small files
 * opencore-amr's and vo-amrwbenc's encoders wrote and a hash of every 16-bit
 * sample opencore-amr 0.1.6 decodes them to.
 *
 * The decoders are fixed point, as the reference is, so every target must
 * give exactly the reference's samples: Kotlin's Int arithmetic wraps the
 * same way on the JVM, in JavaScript and in Wasm.
 */
class AmrTest {

    private class Case(val file: String, val channels: Int, val rate: Int, val frames: Int, val hash: Int)

    @Test
    fun everyFileDecodesToExactlyTheReferenceSamples() {
        val cases = listOf(
            Case(NB_DTX, 1, 8000, 17600, -1032761531),
            Case(NB_MISSING, 1, 8000, 17600, 466844253),
            Case(WB_DTX, 1, 16000, 35200, -1975987323),
            Case(WB_LOWEST, 1, 16000, 35200, -333746243),
            Case(WB_LOST, 1, 16000, 35200, 1294316217),
            Case(MC_STEREO, 2, 8000, 17600, 1535657741),
            Case(TINY, 1, 8000, 1600, -1684227075),
        )
        for (case in cases) {
            val bytes = decode(case.file)
            val info = AudioKodec.probe(bytes)
            assertEquals(case.channels, info.format.channels)
            assertEquals(case.rate, info.format.sampleRate)
            assertEquals(case.frames.toLong(), info.durationFrames)
            val samples = AudioKodec.decode(bytes).samples
            assertEquals(case.frames * case.channels, samples.size)
            var hash = 0x811C9DC5.toInt()
            for (s in samples) hash = (hash xor ((s * 32768f).toInt() and 0xFFFF)) * 16777619
            assertEquals(case.hash, hash, "${info.codec} at ${case.rate} Hz, ${case.channels} channels")
        }
    }

    @Test
    fun aSeekToTheStartIsExactAndLaterSeeksConverge() {
        val bytes = decode(WB_DTX)
        val whole = AudioKodec.decode(bytes).samples
        for (target in listOf(0L, 9000L, 30_000L)) {
            AudioKodec.open(bytes).use { decoder ->
                assertEquals(target, decoder.seek(target))
                val block = decoder.nextFrame()!!
                for (i in 0 until block.frameCount) {
                    val diff = kotlin.math.abs(whole[target.toInt() + i] - block.samples[i])
                    if (target == 0L) assertEquals(0f, diff, "seek to the start, sample $i")
                    assertTrue(diff < 0.02f, "seek to $target, sample $i: $diff")
                }
            }
        }
    }

    @Test
    fun reservedFrameTypesAndTruncationAreRefused() {
        val bytes = decode(TINY)
        // Frame type 12 is reserved in AMR; the file's frames are 20 bytes each, after its 6-byte magic line.
        val reserved = bytes.copyOf().also { it[6 + 20] = (12 shl 3).toByte() }
        assertFailsWith<CorruptStreamException> { AudioKodec.decode(reserved) }
        assertFailsWith<TruncatedStreamException> { AudioKodec.decode(bytes.copyOf(bytes.size - 3)) }
        val tolerant = AudioKodec.decode(bytes.copyOf(bytes.size - 3), DecodeOptions(tolerant = true))
        assertEquals(1440, tolerant.samples.size)
        // A multichannel file that states no channels.
        val noChannels = "#!AMR_MC1.0\n".encodeToByteArray() + byteArrayOf(0, 0, 0, 0) + bytes.copyOfRange(6, bytes.size)
        assertFailsWith<CorruptStreamException> { AudioKodec.decode(noChannels) }
    }

    internal fun hostileFixture(): ByteArray = decode(TINY)

    internal fun hostileWidebandFixture(): ByteArray = decode(TINY_WB)

    private fun decode(text: String): ByteArray = Base64.decodeOrNull(text)!!

    private companion object {
        /** Five AMR-WB frames at 12.65 kbit/s. */
        private const val TINY_WB =
            "IyFBTVItV0IKFP1SjyER/RRRU04Uliti8jdmfFzN7d9UxF3vVFfPXRTAFPxMUSEAFhpFVM74hrs+YBvtWmlOfQJVZyNVA6CE6A5Y" +
            "FO9KSmgoPCgB00iCvrnsKo0h4qcuQifkidhBKIZV9y3gFPdKRiQwOCpE0szG3rl5kMURCjzV/D05y6FkTxEskbm4FPdGVZQ8FmjB" +
            "9Q5A9qn29pqJ4tzVAreP0/O1rbcHLMbg"

        /** AMR at 12.2 kbit/s with discontinuous transmission. */
        private const val NB_DTX =
            "IyFBTVIKPAFvZ4jRw8fM7Ytv5XV0Z4AAP1ogMh3YAAUAGgSdKlA8GjFnkHt3ZO9QRAs5pMMHS/gzLNEgdibdILVwwLkyYDwQvySI" +
            "drLbvgh5X3Hld6O2S4swlx7uSDnjbVdLHytgPA4lZbBkj2icI4YKfxsm3NB/MkoloqftGzXfDijv+/A8BKaGgG1P+Z4Todpc8hlE" +
            "+381+td5Xuc2pWSzC0pHkDwEJGWw1uRbHgGqWlV/h6noS1HtKEGBihkGQPf3sn/gPAKT1jtBj+HWneWKPQNIz5K5/CytPsgmwaJk" +
            "0rKUGVA8AmdkltvvnfpV/iqneePr9VNSLUZagE6BurX71o2hwDzQ7SGtdjgBvgEnCj6Tol9Ug6WaOHz4oyUNzgXZpuegPFMEiLBI" +
            "/AGeAOVa34xFjsyXaDvV1JkVDGkpb+7edBA8UvVduHIwC14ANr+LAzdagNBQs6BX5CsyetRIf6O3sDzk9yCQRdIh/gCiK5eBwJBf" +
            "KadbfrqIeChA4WE5KiswPANzZp/HcoaeEVAvSZ56yI1OckdkgeBr+1JoQyzceqA8DDBbmWvej74ARHpbo6gowEVBqBSwsq0S3bxd" +
            "quvuoDwdWn9ATN+PXhgf7/A/OI+Yz3r0JDjbWMXDVvWfSjHwPC49d4gQS6/+CBVqf5YiFBuvIFTrrbWTkGOIKID3pKA8+A6EiHMn" +
            "aZ4IY2pTOlk67alKVlzySEjw4F4DmCifQDw3eF6QZB3zXgLdCpTY5+fhSkj6VHEk2wdKaew4ZI8QPPgTZZBjeZeaRYcffwRHW6LX" +
            "Qskq1pJwIlb1jmhCqFA8JBKEwEQvLjpVGrp7m3cUJY/EavTnU4cOWP7jgRMvcDwMIWXgC87nvgDLf//w5xyY13+kVvbw2t/uM6N5" +
            "EZ/QPAKkX5DPMQ7eAf5qL1NPyiuFzgqkXW9z/fhMPW+pWsA8CTcgNEmZI74BfP8tmviKhMpA1isjdhb6b2eWP6ipkDwDbXKViiqA" +
            "/hABCimOX7/LizNwGnAL95VJv3a373ygPCROeZBfitm+CB0aU3xTKN7WB9K/9n9mA5ozmTde6WA8E2dluB0/2b4AjVrbb04xy/OO" +
            "sjxYpFUeTSVA0rw04DwSNHiQD9Ovek2WD0d91LO2xcOjLJ1AVhvwz0mu7bJgPAS/a4A++NF+EelagdL+kc2bS+oQtQcC+whtHNVg" +
            "ZPA8Bjl0kAggAwBwIIoJ/GyXyyAISh8Xcc4B0NManb5YgDyzJJ6y77AAAaWk37JBhIeibu8Z4ccokOAAwSJ7DbOAPFR2hZltVBBh" +
            "w9oYQQUUVzz4rIOjDZQPJXtMn8pm3LA8TQCbtVxuCwDw9s84gI+KukBgqJJISlpwfIZ7wd6A8DyQfnmTD4QBAQ8x6md6/qrKIjj+" +
            "1ubar4nxMPeXkNHAPHEAobdOmADhpD7KgiKgCaB30JAvhR3IYKygLR2qdYA8bn8glQc8AMFpFC+UmJ9KmwHgmhr7YIEl5FU7CPsS" +
            "YDxVJx29tGgQgNMhygnzgpn0qi1somXl7XgDIDNLhYmQRCbEk2pufHxEJsSTan58fHx8fHx8RCbHg2p+fHw8wzY8QATr7cy+H9/n" +
            "Id9euFNxgN1OB7wcbgOgx6NnQDyiXFGQUjhYPkErinE6VqrR9IqpBaPRjPREY39HQYVwPKxWSkgFYPKeAw9fJmjnpqDo+u0EWhdA" +
            "GPpBK69c75A88g9pmAFAK74IVIubppXwXvPX9sR7drb6AXRZOo180DwIs1+IhKZFXgDvT790OOekeJIFK14p89CsUrrOkHGAPAdb" +
            "cpVlHIk+BFEaFAT3h+JpejPhzjzv0U/21zUcj6A8pFHLkAtAab4YIkqRwhPN9b3j1RYxckAkhfc+NdKlQDyuUjaQBQxl3hDiKrKp" +
            "en7DLtEPtvt/ew9GpEB9ofrgPKxiW0ByafBaXHrfww+Z+6zAvxUA8hDcyQ/8bLD5ZWA8rFY2kE5EcL4Bk6qOAtn+4P6XeG/r6jX1" +
            "T+DkBRk/wDxyRlmQAoB8XhHcSmeK+v/od4Gpw8r4oRlXKZVoUExAPBgPWuATXHs+C/z6aXYnihfgr7caCACD7UDw79v3khA8AqSH" +
            "kvIqETab1nqWUq/rx9Co6PKO9Xb3T0RL6gK/wDxlgZxNdV8i/htcn5G83iixnbuY4jDBu1tKg/Rdd8LQPK7yNpAYoCf+AEqqG5H5" +
            "Jv5cvk38q3i+yv+wi4dQk/A8/vIcSBiALD4DeNq8LKvx9rze/cymvORQVxV1BbHfQDy12BzwEj4j/hB2mzMeHLAo61+IjtrHNZVW" +
            "U/s85uDgPLks1vgmYk8+CcQ6iAuu4c9VU5PQvu0JnnnjKVcR8WA8sPAROA0m334Q3VonKmEezVhVt+7mnSzgOZJwoBPo4DyuWDaQ" +
            "DC2j/hpTmthUu1voEEq4EkOE9LOSGs/p6puQPAuAUTgBAC4eEn/ahE6B+4kAH79ESBkfu8+wr3hmW+A8CKZtsL+GMzabdlo9Tt+E" +
            "wmemv9uwbaeAfToD7Zm5kDxpgOVADzxqPhoiylqaF5zscsCm1H4vEe3USoYnnPYAPK7zkEAMaCP+CDNaOjorrtfmRc0agXfsSsRr" +
            "kC8QEVA8vuuZkA2oft4QapoXMyTg2j3/4p+aoXpIaLdsks0EwDy5LZCYEQIePgEBup3nvmAbcDX0BxEA0mD7B+UT9voQPLzoNogR" +
            "7Zv+CSNfbC5ta708kebpFDs2r2Sph0D1ofA8eFYuiAU7zV4LsFv7XPpHU9c7ESEuIJ/w7AD5veNPEDyIVWNACTqAgGCLWnse6rrE" +
            "ACM01xuw9GkxzSre/83gPFUZNbgcohCBLUE/ttVQqpld1IQetTzOIiLE9t01qQA8Vux5j7SaB4GkNKoi4CoD6ehSICEJY7vGcfy/" +
            "XXHsEDxXApu1X8IJAUvp7zXKI9StGzPafel0shXE5hO9PLTAPFbtZJzgPBJBwl2awcKbKcu9cBpXy34ORtvkPBs6srA8WO0ckeGi" +
            "AkGlWo8ivBLgmhJ8vIW2mbz+aOw9blbvQDx9AIi2ASgKQS3bysZc0inNCxP8yfGrFg/nZuWD1IZQPESCdJQIOhhBLH+vNgnVApr0" +
            "h/OoyR0vAav5XNPor0BEJsVzam58fEQmxXNqfnx8fHx8fHxEOezjan58fDy5/0UQMGNHbL6pOrdRxUrPTkCFAsJb6Kj3GkGXwqfw" +
            "PF0vYBgF6NO+EDIKpsLp2dAY6IZ/TcC73itQ5Zsr6HA8/y+OWAcNLj4bgToZQyqU/kuhAf2z6+ATX03xAL6PQDyELAaZfZQC/hJm" +
            "itelaSnWCgH6v/C/U5dLqcNmtvwAPOF5E0CoogA+Er1qelWl1b8S2+gqKOPI5H79UNFk0nA8aEj8jVw1Qj4eErt5Bg+YRWv6ox0j" +
            "NZQxgMRs0B4K8Dy/K1JZWX4GvhJwWv5TKu2vHc6+svQAw1TzbEEgPyRAPLXSZngD1ng+A4/KTz7lqySry4FTL/cLPyc+X19O94A8" +
            "aek6UAw6MnaKlXp+Y3LD/+eLlhl8WlrmtzeU4KWUIDy3zmhQA3Gbek+4ejrWK/bglIat18MdTFQPHDh1D7Ig"

        /** AMR at 7.95 kbit/s with every sixth frame carrying no data. */
        private const val NB_MISSING =
            "IyFBTVIKLAG1tMzcQflu7nfuRkBWoehu50hKLCSwvLVvAfH/Es18Ls9RNXvNM/AELA6ntWjJYZNtBjVJGLTFishi6Ki4LAuGzLWv" +
            "AYn32m8623UUuH88gH1eLAtMsvGWgZ4BkuuzYBb6blj4f3PafCxAwDPgUObnss9M2RErZZ12TjZqqCyBTI/gKBtPNSKLxeDS4FfZ" +
            "PGhLSCyjqX3gARVp7fKsNKKY1Xlw5Fi6XCyjqYfgAJVg/wNnAbzGqZJMMXdRFiyiGM3gAAHAXoIeAuNTnivw1xi+SHwsBSgz4Qy9" +
            "pJQGtgmYoGZsDxzSWp4sF5Yz4IHFqNH3kKD2Ani2g+sYioIsH5e14JGBITW22GLnAtqnCLW+yuIsPQeR4EDgpeiv1+/j+d/0rKsT" +
            "auwsPhu1wi8hyfYfWlKRWJeOJQxopNZ8LD2XMvE0oNYdLyGv2f4rI1dvnZaYLDJJzeAFIaY/C3Sj6heEzlcGQTykLAuzM8Jh4Ajx" +
            "vpbUDdHYpPDn4rlqLBDas+ERwx7067bjItgFYd6I0SSOLIBuj+AAsF1vywt/FL3Bth4GGkdofCwrf2HhAeGR7QgQAHNtnqbDCz3t" +
            "hCwfO83hAeBhLhOOT5AJJYrSngEMmCwOsDPgAKAZuq65SCyUehpSh3T/GCwgmTPhFYDetF+cDS4ht4nZXWgKCixOmI4GgBAolUKq" +
            "Nl08+GH6FPnBDHwsoo/MEOAO8W8bTL//UwtR/bOV8jAsiljMCWCVw99aRzbeI0YqnnPIAIAsrKR8FKAZLGoSRLrj5g3t2ir43xYs" +
            "xQPMEsAYZgxqj2zsI79+xfjHRi4sl6ksEsAcC837GW3bIpo6KkXvwVJ8RCbM42pqfHxEJsSTanp8fHx8fHx8fHx8LO/HMs+cwIoN" +
            "Bpvq7UZDHuMAgcPCLL5OteDQ4T6Cv0CAQGQeHgfad4HKLKu3jvF8IDD9wj7YoBW7s/1Hgr7+fCwQtL3gWcCj+bdyH6+bR0iuY6ZW" +
            "9iwzbbPkAJTUW4JsoPvHnuH/O1So+CyrerPgzSAkMsIaEU+BewTV1QEZUiy9e4/gweArc2qfgfahcJpy9omaRCy/Js3gyUGdsHtJ" +
            "YyY9ZCd7uTYzVHwsZzKP4FDgR3u7IcseJ8C8nsunkeAsFTqz4BCARD47XPx3XhGlfxrn2NIsEg2z4QGrZ0zrjSpCP0nYjsbxfLIs" +
            "fTWP8ADgkC/YZmYPwUOvDBWQCwws3hQz4JCAD/2P7nS40kgTJIBFypp8LPEeT+ABoLKW1txfHxO2q0jF/aVMLON2s+ACAIMb8gP8" +
            "Lfr5nDZZj6BiLNxUj+BYYF76mjRShLukteLp4GqSLKoJj+EhwApuo8jtlYmSvA1L04b2LBaiteDBgAOTPgkzB6OiGxiFJHsAfCyr" +
            "NY/DJKA9fqISHn0i3h2CcC1XzCy+FI/D5aAPqtvgkyBOAHyFxFX0oizuFL3hAeAijSLTxtXsKJbznGEIVizuxY/gkcCzR0M0poLX" +
            "k2g7ViOJtiy/x2HhBCAWL3jPo3KEMd8/kK1FDnwsQxEsBgAFQxG67jQYfESvQh28jCgsuwPMGkGRlAQLAqRdDCR9TZbtAywslj/M" +
            "FKCeMMoKMy8oB9Kafi6Mk3os0QN8DSAdVRLzX7D4vbO1VLbXj0YstBh8HDkCy4IjXkq91s65dDxH3BJ8LMUDzBpDkqzkCvJAOV+i" +
            "C39J5QAmLJySfBhglQwHktFGL/dDfHG7N7jyRCbFc2pqfHx8fHx8fHx8fEQ554Nqenx8LOFbzM+UQOGOL/lsaafjaa/DpJBCfCze" +
            "1Y/g+EAQcF7vgL3xhw6lZcw0JCxnUo/hwIeUesJuZ17VoQy6LFiKuiwJzzPh0ILy2W9zgvyrRiDRxpF45Cy5eR3hwVVh6oBXKrpb" +
            "gYvwabZWtCzuC8HhoIWlVIBXbSMppHeP/16RaHws1MuP4IPASDKwM91qqScF/5AakzIs8Aux4MlhQEKsbzpw8Asg0VCGV0g="

        /** AMR-WB at 23.85 kbit/s with discontinuous transmission. */
        private const val WB_DTX =
            "IyFBTVItV0IKRP1SjxIzPTRRUfyniISeC38Gubl4qOb+8LleSN4e98SfXTv7P3qOkTWh27y//xN83NOg3/ov7er/iqn9+ET0TGUw" +
            "GVAKRVZDZwhohpt/f1l82wgX6Pr8Z6CwLgDeqjKVt5o8L0itKoFuC5e3fTxWnH0Sme0Kqy9V+zBE/UpTJcCcSAHRfHXKAr6Zzs9b" +
            "zS6jVlpEdEjU7Y7kveq3BhxeoqkAlmPLzRQJ0c1reEPa9msv3UnGaNegRPdKNjUEuDpE0lUkjkbeqX8fvzNDf6LD4P1s0/oICzoO" +
            "fIiFqG4vn+IrEB50nK05WKSm+rvXOpv+dd4ZSET7SGNSHCwIwfc3iI1A9qmmf54E/ydLNHv18EeuH42SVtN/mkaRjJUu+TDZcy/b" +
            "Vrbjt3i/0nLNan7AzFhE40x+pby0fMBUmblOA9y599HrYXdb/TJ2XUX+ZmNXS1gUHgnf6GNDLFPhNKSfv6jPfuDJqJ4Y7UQ2vkUQ" +
            "RN9XG6X+lm0Q9npnDob0GxP78I/UaRqH4DzIXOv45xKFe+QAjPBylFmdgCT22ge4/bsVcf+4FbcEZo8ymETbbAERKDFdgdWIq870" +
            "hIsR+/rRWd1m2dz7oAMYWWRKRAyQJam6579QgRwwwGnXi6Z1TuiuUzEbmh6jQABEyX4yqDqRYcFRp2tIw5wrf/2agfD9R7DNHYpD" +
            "XWxRpMtA1JpHMVdKuB72bpjkjTtMGuIj0iRwObGrN73wRNFAHA3QLFWAwot8jd68qz5n23g+GTBeJ72K0Y8GBpMuBxiPeBpMG6U8" +
            "gWcPcs5wgPilaXA3qrYnPnj/GESRRDyE0kiVgNKdzQjXxIs9bf1em/8tngiyvNtvn45tZ33yqMxGiCcCBpUYEFs+63k5g+susUnV" +
            "NH1sfUBE20AhFejXTQBVmLqMRsSrd31sJRty01zo4bUf55eELcZv+MiG3w2J532d615bmluoF2Do01D1tEWFBJk4ROFBYJLzbBbF" +
            "UrV4CuDGqSv529f9+QjinZoTY2ioB2vOik83rGZckyVtkYlamAwZ1fTrCPkbOagihiYFuET9WnEH4jwYAdF5eAgZ1otP97pJHCrE" +
            "080KS6OJuLV69y0ZN1tw54qpyzqmeFaZHEwZ2mo5AqkhH6DnaXBEqkYfWlB6TEEnmH6YKp6Zx3/5WzuW3OdY3uBWNzYxgJ6jP6W7" +
            "e7jKDIa+aZwNWF8RV3/fc8FD8G6XxTKYRP0Eb61SuTCRrzdpWaeUCw/9fTvN/RFYYekDgxv7R28Ma8Uwmmy5+0cHBrgGtE+7P8VM" +
            "bzzbRwFut159oES+SjAUJTBAAANjSlwztptK6/fdTeiLVN5g2Q6xG215SO1N4WppA0sWCHsj5qZWuSraXb6xsKYFmFFgAoBE6AYx" +
            "Ddg6MIAmacpYQp6rXsvcNz3bfLqTk05qbFtvbJJILuQIzBVuMtuGvaBNeTdfncVUhlp151c2v97QRP5GN2Fdr5CQl7q1SAemqwcb" +
            "w2ufnXmkNtPLo08uRUOtOQBomQZ3A9+4j/GUjNG3olty73JhvBZlNDgwAET2SFclnJw4EKTGmp/onpsj3z31+LTyO41eiBpsdyMy" +
            "LyEPWzz8O89YwWsdDhAx9xj8DD7gMAGPXaFZUTBE70Aq5baFDEAQeYZYNaSZhve7yNzwchlBYGofwUyiL87N0g6z2QLhqVxVnPuK" +
            "90YZlYSCagMk07N10jmIROlIu0s8IHbFd8pl33jEu3/6+RHsO+TefLYP7vPIZoT4mcnzH7FDmXCxWLY2eYa6vmyOaMuLYjPe26/Q" +
            "mETDQAEauFgMkPFZqkoCnI933+mba2FHXNwtWxLWL/E0DPn9dV9eYO/As2Mqe7SkVCEpffx46m2Swa8+sHBE6X6nT7gDXMW9aEev" +
            "ZJ1Jf///edhiUkXabAHdA6MQIqcGtlGy9OySOF517ghmmPEk10A6mk2OwXRmWRjwRP8MNFAhMQxBlbS6Wri+u1/38r3+kS93S4lq" +
            "VuRxDxLFpicf/RnKe9wpIiNM2ZOrEBK+w8ecxJE5ShPveET/Ah9o4aNAwJdayU9zvIsH2xqruA+fD4gUEV1GExRrwOHxjKlAZZhC" +
            "7eMpe28eyK1Kz/drTtcGEGPeEIBE4kALFlZ7mFDzurvZFNS7d/fvt9CDogeHJp6Ds4f6Ywaj+o+IfP2ugqDO0FmXglHOd19/Bika" +
            "UBJ2aNrgRP1EaIxcWilA84aAzGqUCzvd70huWHumqpd1hYzRcJ8hP2AFoA6FUUeRVqcG5oZUtbEwZbKL4P0Dwj27GETge/AABAAW" +
            "M18E7iDUVMzLz7hFgDavzuQa7kXIHCWjxgetCAnCi0oIxQD5ZyCK9N1q1e+i0Ejnuw+qZPhE2W4Uc77i0eFgzM9CdEEzBtu/N5z+" +
            "yMFL4yXhsu2wjhcI7tyvHV/GZHzm3zBb8SAyF/B1uaKYtCWLqs8ARMESKqqJ+qNh7erLoYDcFi/h1QZ+a5cdonZotBT1O6wDfHcF" +
            "BaMRvhe9N/1thqPne2xTEibsmyB4O/BL0ESBK/H/j5iahePuqulLthaz/t7MrmHkJij9M+V1nlTagAb/mJcl4nHI1r445aqqY4RG" +
            "ys85pHS4sLy4abhEwSdu9xBi8+Fh291+mPeyjP37aL7ajuHp3/+xZMdWjscj7Ls6Iec3DShGilqJusYNVPhz1gRiCTCaqGRARCEn" +
            "Vf8CqPtxwc1oPu8N/vf/nl3cJ25WZf8SAtXBD9uPSYsqg/vQBIy6YOZtxDdwaeaiMD9k/YXhPrcBqERBAcGZA6iz4GZDESvK5Sq3" +
            "79sQ3R8ze+dWzatDtw7IbvDjUDF37BlKh0w6Y+Nbc2sUHWKlgL71f6BO5nhEAT4pdweY7KHPAAA+C481D/77ARk6kDoe3/ILaOrc" +
            "FEGxezn2wcNS09DbktPh0cMAMzd7183bK4TyCJjQREEcmgCJxpjB7AAA0YDXAc//eMTvC/SqFiNC2ynzWIGUTdyVm+GtH7LuhO33" +
            "f+Q6jKM00wULJGb2USktOEQBESj3Af7M0UsAAGSys+CH/7gTLdymvT7J+2JAAA/+QWU19RdMXH6pMNq5yO/yTEb8DmcWb2uLHx1m" +
            "lMhERTAx/Gck0bFLAACsEPc2I1/59/6EARmo713tZEaTEWXVbhhjTYlk1KXBh/ZsICA06QHyLw9+yJ/IjWywREE34P8n9EVh6AAA" +
            "lt7tdg/7flNMblK/smVh3/fWRIhLG90sPp3ksIJN6DQT6SeLapdMhOEIUKZ+ioUo6EwAAAAACHx8TOv/vfSYfHx8fHx8RN0EJgMd" +
            "NQgAwP55CCGMOeovn8nq33Pi+vB5h/IM503JXR7p/3v37A3TeRiR/yYWzxmMedx+OZBVbPQe8ETeBC9wz84QgIJYt0gCvqsP/c6g" +
            "i9hGWurhBzd7QlPAFaVFI3oBaW0UHSJD9hxMnH/VLc4bmV+ZVbxiMpBE58Yfsm1E0ICXSbtLgq6bFX39+Mr4YRaFoHuKulSA3aqu" +
            "y4TTkjd4ttPHG1zf6kaJX//brwyLQzxmRioYRPwGIOscBnDAhLqcTkuGuy//7zDpokE/ugmFIqhToCOjTN91fDT/oa7xj3P+qUIh" +
            "1/LRUyUeOQjebfhKCETxQGbDWQgKlXKldphI1rt/9Z7MfAeXsXbJpt8dZWIcIz1ISrJp19jytd5lhI8Pgcl+DNPnIW2qJ/6ER+hE" +
            "7Tg0UFh9UdAiV6kdroaLT/VZmBngmdpElBmCoXjfSSFwqVqdQGahkwQt1S0yQpvCrrUIhd79P9RwzoSwRP/4IVgyYICAlZepzQyW" +
            "m0f/+ZmoCHPvSqEDv95tkbLbAHziQtZG+BiRtvBN8e0c7WbX9zkuEZ53YAwQEETcCi/8EZw4gIiVWJ4Kjhtp977QOHSuef5vAmUE" +
            "OdEOIqlf/xkZOIoVt91FSqyx48PGahMOaDGaswRdOwhE3kIftlL4YICElygdIr6bB/3+rArhkZryoEoKdjrX9JGMOk7IbFupy3yT" +
            "pu0xJ61lGGLqJoN1EIRHCTFYRN5AEI1QHmiAplg3W/uOqwT72ziu+nt20ngUnD+fSpMRH8yCBIRCKISs5nGVtKUj+xToLe4RfJtq" +
            "N7GakETmBDiF/p2wgKKbu9i6npvn/8U4uQtuVNhdLwqF0zuQVqKnpZRuW2SWj5iD8P3gzDM2Re+qtukIulUYmvBE8wIHFnwlCVCl" +
            "nNuJJayrDvl/k57oZ37Ur7ntmwr2651C1C9qfi583zvtpEQFTbRkX/bArBjgx67zvoJwROFLuJD/xmyFeqem7hfVaW/2+Bz7FaPd" +
            "38I1IXNlAjr9b5vPfQbfUdaYXr/PdwghCbEbUnePOcNDe8FSAETmBkYngt+AgKRYgwlzJqvmv91SYUfnePxFK0ZpxbRtfn6Dm8is" +
            "Aym7SxVejvKWfd7uAxK5F/zBCya4MyhEwhQSSuU0IIQqe3goywYafd//cXwYM3SaYC2SK79I19zgizlM7/5dQ87sSKLk91ZhG4i1" +
            "Q+Y48ga++kowRM0GP3Bb6X2Ah2W1zCGMic9/bT2P57f7e6Yg1lrz3oqZX4cubiSuyimfZZNDw7h3PpdBwjeUyuw/1njm6ETLAArC" +
            "qCcgwcWpbMopnqsHv8nbaQ+k21tTd7xCLHAEyxNPP5+qgbSonJc33VWKXi6N/tKgXlU4JP7EzgBEzQwrLwk1B5RBtlQJNY67b//J" +
            "0anVZiqJYeKvU61f4Jrd7mjhxSzarag8XwfaRopV4eFb2cLK6Oc/ykIQRMcAPKTCBQCAw3d22TmGu2P/2DbKig03EUmS5/WiFm56" +
            "nuQKxQeLqWS+0O7N5l2wVyveZmUjZ+ilG6k2cET/xgT/DRLQgZe5esxCnbtP+YeJ8M9qbYHz+PB49aGmuEsL6oGMgcw5i3M15AK1" +
            "wlWtc59TWshjuuEeYyBE8EaFtHjJcFCFqqiMsqyLP6f7PtbuVMd5douUzLUZYK6zDcglGgvZ/gd9GVx008Iskzl7yFwMiZG8XFJg" +
            "ROFZ2MHzlA2V/2Z4KlB8Knt/6Ewnqhq6KHkPfq4W+unwomu51yDnsY7Izx7lgxlLrGYiwhd+Hb1AjV7kkET2BjITeSvAgKSbY57z" +
            "nrt+/phejD2wssd6GiJdrcVEaYPjZ8iJt5n+hDwqfsVmXarengajr0pipuWrdchExhARluJ+AoQmdycIS66bVWve/Pq1HGsN/ncj" +
            "wiorpHQ8FaPMHL9RUcutRynuOcq5pmgkB4JRk82Fxib4RM8GHeXaBX2Ah1vLnrXGiwR335BXt+evdI1Kshk+t4q08mks6n7k+zuv" +
            "2GUGA/1pykGbZTrbdfSy1xIREETdAjVeNmlPhIKTpxs53rsH+f6Rz0wgVmbchOM7ix+aes6njS0xG8uvbtxVQ7sTfEbdA5QvEhTZ" +
            "Zz/klhhEwgYvLXikAIClmoeIQra7N//wic0Lb77MxmPpWfI+8jnguRS+2j08IKv0SQ4vjzbZBG4lzpW8VdrqCabwRPxGJMUQCLKA" +
            "mXYwD2GGTU/6fVItajSrQYP2AYbiZAeMO5bUF3IhMC0hb0ggOJ7clLw4l5hPJbvvIlV3AEShD/BATQDSZdMCjr7gziSZ99hkGZNB" +
            "/7XMRQbM7ULOaE51Youk2lGZYA7jQAdmIJNqm/ZbnU6PjZAWdOBEwRJxvwOIc7Fh/s2oFEX/V3/HHxuvLYvCXQaBkUwIC6Zj7vnX" +
            "neGlP4p2ZnqeJgEnis55pXPveIV9MFyIRMUXjHU7Usrh5O5+QcRXzwvv/kao4Wq//80H+WzW/HX4QEgPNrGHy0zC4eDREoEMCRmb" +
            "ibINDZEr9VnoaERBVaP9Hzya8YKEyGpsvDrj1c7n80lBNdwGahtItalJATEJcb+ln7h9ub4HtwExj/5P+UFTl9MCYBOMHcBEIwZg" +
            "fh+a6xVAQzF+PpDuh/2ccclkhWYrLPGLnIdOsJxoHaX64w4MyUH2iB3/DUcCCVR6Xum7QpLgwKNYREE9Y+47OKfhYQAArgjHCz/z" +
            "3J7ZHQXcoDNvq1lMbs1o8hBTDME4lTs3dLA7FrGy21fq9MD7Mn+1ywiZeEQxLdD35caz8EIAABPlB1aF+68Yq77fbXPqhIgPzIBo" +
            "CZJiaFNHjmwyNFndH8ac+ZA2EzfyE/wMpakR/OhERR4ItTlgn6FlAAC8hEwPJ3b8ObZj5aLIAoD81RV4Z8Va5MuQJNEqfr/C1ws3" +
            "6GSH0fruW1dBIyPKCJQoRBEnoPeN3JOhzAAANRveBje/faaqD1304dsIGzFZP1TtxntuTunmeBl9jBcRNfVgzJgNsswqHDpjzUjS" +
            "sERBPc3KDNLn8UcAAIXO1ekH/817TwYnUpbrtaBrlKuPX4vUK5C0Hp3r+Nxe+OUUtU8eNxzPL7T0lAg2ZpBMAAAAAAh8fEz//730" +
            "mHx8fHx8fHxM6/+99JhEvzKkm4mbzJCi+4nKBbyrfi+JqCVRBvl0kHk6mwQtmewd3etBuqC1B9DhuPHfm/dfSclF6N8dzdUsh1r4" +
            "RKEHuzzYUbgUzndo+mt8Gj+/1rZ8g1u83+oX5jEIv93tSCcTfcTfy8y9T0YGYPexXqI389Rk5loH+zJmuETBHcHw57stkIZLQ529" +
            "nLt//v9pfL75ryM4uU9FKgEZqG20U2CQasDeNS99snOg737idBCv5rFFIBFedLhEwcxQ8Pf8zEC1aWQcQqKrf9eekz4lSjGP6Pip" +
            "OZ0eKDlc9Y5936d/+y28he3HKXHkrvqEUe6a8dos1EtYROFRWIBhB0GA0VTNW2Tcm3v73VCSaQOzT1n+S3FD61HQIfKc+QMyJHpA" +
            "SsEiaYu9mdkwbL6PxM2+45EGYESx/BfatEKABKpGaGl5lgk9z/Afqd3N8W9M+NqLnte8J2f4ZTcdfEne4FOJ6GB0nqxXJKaXp8YJ" +
            "5Ga3QEhEwQpfWukBHZCBdlXauxy7//7vvZBzsXSWFVVLkYT9c9uR+YG+zFhX3kYwCxQ1Fb2g9izcNeMURTRqNalgRKERZMLjJZGU" +
            "ikZVrmH0KX7jX41vKj1x46z7UdUE7VRTlrHDH2HUubmgkb0N8opuX98Zimzdfb/j6iK4iETXQn45qbGMEIekhZq9nLtuf5sV+hZ0" +
            "mf5tQhnC4NWOmouKPTkNn4lNmlB2kaDlMiYu6oqbo5g9oTqnYhhE3QRBZFBjNRTrZTa85bxZbu2Yngz8CdvrAuDRs9TDBD/0UkDq" +
            "hM+efbPld9l9heWmUWXRrFMTEBHy33rg"

        /** AMR-WB at 6.6 kbit/s, whose high band is extrapolated. */
        private const val WB_LOWEST =
            "IyFBTVItV0IKBLD087S+OCHBNTYGjrm+jZrwBKmz5i/uBjfGNQoMWvb6KeMwBLYxJjLztzNCASYKJgEfTBjgBLBx/F7ltBYXER4H" +
            "JjNcRdMQBLhyVfD98BUJBSoGpbC7FiPgBLyzUCD7/wdCHR4Lr4mLEsDwBLGm6gRk+1VANT4MzuXgkoVwBJTxo8rICAAUOR9c8iSv" +
            "BBvABJTwRKKSHJWWIT5ibDuWm6GABJgxgeyCDsGEBQYOQFaZqLYwBLugABXZq1eCKQdSt04CJD2QBJ/wVUrCGpdWDQ4OAONMI+yQ" +
            "BLP/TFyQ2BPBLRoLaDgoEkfwBLP0e7SLBlMKKQYMkSq7hCTgBKPRwByoAmIaGT4ErGWCCQ+gBKbE7uiNBOIcsQdK5gPDNMCQBKTC" +
            "3XDlBeEYFQ9U16hr/yWABLnRZzxqR+bCg4dFg2N6tQxABKBB9XrZpOZaMR5ua7BICutgBKTSrebl4fbar59FXt+IzyzgBLFwRSL8" +
            "ifHJKRoCc5hzgU4wBL8ygIiROxXXn49LyjFILQ7QBLqgFSIBGgBaHQ5uAIsPt1GwBL3/RhiIuPYds5YJ6BwBJiCQBLoyVWy4CvSJ" +
            "GSoFuiYELvqwBKlA2XTgAMfcDQdVhIMCkUBwBLCwsE7inWfKGR4OIuwFO7+ABLax5kzJP3UFOS4KkKzKrj8QBKA6AACALxUj2gTM" +
            "DABgKkYABJIRArGdlocLVEVPnSMEV40ABLoDqJkgVNTLa1/sPBknn62wBJEJEZE1M5IVE8+pGyJVAPLABJ2YVRlM8bVdoykdMI2K" +
            "RhoQBBMJMwkFGZCcrCTZzX51p7wQBDcKdlsF11QCQ0ra0lKZxesABDMCEYE4QFBK9xZZIBRDuHqQBBMbEssc6nOd7xBv5aL2sgNA" +
            "BBoXqlsQJ4PSR3vY7ccX6uhQBBZUYg0EELKB5wjfA4qDLNPwBDiFgEtZNkTIhBdyw/imXE8QBB2UVRlN9zTEafkYW0dA99pwBBFo" +
            "AHcPFnWIuzzLQYlUM03QBBsdq3EQMybVSH+sVPsg8IZQBDdHcxUEkdXKVFChmYxB4YmABB2cVQhM15GL3yDNwhHYREYgBDVOFRkc" +
            "F5WLf3/8bbLuAsQwBBESEEMRxZDBvw2HMUU9WFYQBBgdiFMYo7XWMPg9PfJzofAABBqVAKH/3ePVA+9EipL3UxhgBBacZjlBAKHB" +
            "RGhM5Fj6XXMwBIPRe4Y1pOANETYFqIrJuL7ABISR/yZlPKYZORdWsQMUU7vABIhTp+wIXPNZs4dCi6koiffgBKISceDXhmJQNRNY" +
            "nTU6XimwBLPw1cDROaUVMQoDtERkyCjQBLhQN+QKIcECISNRm9GLZf5QBKwDkXgoJOMao49FVTOkClHQBIGULtYcffJZGRZnEiDA" +
            "qLigBIhQ/lb8H+JYPRYJyFVCUEuQBIIQj3b8X+FZPRQ5WC4oURMgBKaS6ruPjKSQCRNSLH9a6TGQBLSR2kIvhsCQCQNSRMSF6mMw" +
            "BL27E9iAmJDPPT4Ms4wPF4XABKRRKVrnJucQq5NE2Cwslg5gBIPQQKQFxuQVPQYGQREmFYKQBJGSxsZb/MKADRdVMhahyv2wBJIR" +
            "GM4me9XRCS4MRFGj9ewQBJEQ/Z52usKSLS4LzMUYD/TwBI2VcohIBeNZm7dHtLi2QphABIRRdY5xnOWVASYBokZDw9oABK/QdQIA" +
            "PXWYCRdVpFhmZ4bABLIshWkb0lAYg79EOw6otYpQBPgx+SPcPPRUFQ9aVMLFzTEwBIkQhSQ5xeGZpQYOQILSgPZwBJqUrj5yfMCB" +
            "BQ9T0HgBIZ/ABJgQjTZ73sXZs44CmtQyS2PgBIXV16ohheCdIQdDL+/Coy4ABIGSaBic/Oaar5wFqpKakHbABLAjAAkJH1Zxzg/R" +
            "0AAnN2aQBJcUVYAsEqbDU2ZMzwCWwjIwBJRVAD0dcxNDwwjSZiVCH7HQBB/bameFoneLji1Q3EO2ek6QBBdRU50F8qeHVGhpLFSd" +
            "g9+ABB2exUFIQDABuz1jmtws65AQBB1dyV2I/iCLZkIiWwkgw5WgBBYXRLksSJZbd1QjuEJDcaMQBDOFIelxDeJbd3vsNqrgE0Sg" +
            "BB5v6iUB5pQcbffTzVYgP1twBBiQCLEw0LXJfRulU+1OjFtQBBIWYj0UwPLRQ2gqxlpsmFGQBB4dzLAoOpfGB+SNzKZ0UoUgBBIZ" +
            "YiwU0LRHYFNm5K8GDm5ABDsNuYkhMkSKgzHgO65AIXZQBBuaEZh8CFJPZQqxmadUDpXgBBMQMZEkUGBGQ1p+yKFoIJtABBzcgCVY" +
            "t4ORVB5uTIuGMGyQBD+O9ZElckIJa24lVlLw1IfABBzegAVYCFFXjF0bHTvAKE+wBBOVMxFQLrQVUC7TcFq7V06ABBsZmtMRsCcD" +
            "3l9luHbsk+VgBLrE6Q9Ta4WLIQYE6Ji5q52wBL3D3pkjHeXTp7NAyHqrqDxgBICW23gdpGVQCQIMc9eJCj3QBNpyEQOQRmPNs5dN" +
            "KXNMGxHgBLl4ndDWvdSIKQdPM8B7jSQwBPAu7n3qJOSQi7IIGCBWOqQgBJDWDPge8kMQHQNKatIhGVuABLWJytMPnNMJl6MI3M0v" +
            "JKZgBLAA2BdniWLVGQNXYwBCpwBABJoR/ExGHYCai5IJMJ5Ji/Ug"

        /** AMR-WB at 6.6 kbit/s with lost frames and frames carrying no data. */
        private const val WB_LOST =
            "IyFBTVItV0IKBLD087S+OCHBNTYGjrm+jZrwBKmz5i/uBjfGNQoMWvb6KeMwBLYxJjLztzNCASYKJgEfTBjgBLBx/F7ltBYXER4H" +
            "JjNcRdMQBLhyVfD98BUJBSoGpbC7FiPgdASxpuoEZPtVQDU+DM7l4JKFcASU8aPKyAgAFDkfXPIkrwQbwASU8ESikhyVliE+Ymw7" +
            "lpuhgASYMYHsgg7BhAUGDkBWmai2MHx0BLP/TFyQ2BPBLRoLaDgoEkfwBLP0e7SLBlMKKQYMkSq7hCTgBKPRwByoAmIaGT4ErGWC" +
            "CQ+gBKbE7uiNBOIcsQdK5gPDNMCQBKTC3XDlBeEYFQ9U16hr/yWAdASgQfV62aTmWjEebmuwSArrYASk0q3m5eH22q+fRV7fiM8s" +
            "4ASxcEUi/InxySkaAnOYc4FOMHwEuqAVIgEaAFodDm4Aiw+3UbB0BLoyVWy4CvSJGSoFuiYELvqwBKlA2XTgAMfcDQdVhIMCkUBw" +
            "BLCwsE7inWfKGR4OIuwFO7+ABLax5kzJP3UFOS4KkKzKrj8QBKA6AACALxUj2gTMDABgKkYAdAS6A6iZIFTUy2tf7DwZJ5+tsASR" +
            "CRGRNTOSFRPPqRsiVQDywHwEEwkzCQUZkJysJNnNfnWnvBAENwp2WwXXVAJDStrSUpnF6wB0BBMbEssc6nOd7xBv5aL2sgNABBoX" +
            "qlsQJ4PSR3vY7ccX6uhQBBZUYg0EELKB5wjfA4qDLNPwBDiFgEtZNkTIhBdyw/imXE8QBB2UVRlN9zTEafkYW0dA99pwdAQbHatx" +
            "EDMm1Uh/rFT7IPCGUHwEHZxVCEzXkYvfIM3CEdhERiAENU4VGRwXlYt/f/xtsu4CxDAEERIQQxHFkMG/DYcxRT1YVhB0BBqVAKH/" +
            "3ePVA+9EipL3UxhgBBacZjlBAKHBRGhM5Fj6XXMwBIPRe4Y1pOANETYFqIrJuL7ABISR/yZlPKYZORdWsQMUU7vABIhTp+wIXPNZ" +
            "s4dCi6koiffgdHwEuFA35AohwQIhI1Gb0Ytl/lAErAOReCgk4xqjj0VVM6QKUdAEgZQu1hx98lkZFmcSIMCouKAEiFD+Vvwf4lg9" +
            "FgnIVUJQS5B0BKaS6ruPjKSQCRNSLH9a6TGQBLSR2kIvhsCQCQNSRMSF6mMwBL27E9iAmJDPPT4Ms4wPF4XABKRRKVrnJucQq5NE" +
            "2Cwslg5gBIPQQKQFxuQVPQYGQREmFYKQdASSERjOJnvV0QkuDERRo/XsEASREP2edrrCki0uC8zFGA/08ASNlXKISAXjWZu3R7S4" +
            "tkKYQASEUXWOcZzllQEmAaJGQ8PaAASv0HUCAD11mAkXVaRYZmeGwHQE+DH5I9w89FQVD1pUwsXNMTAEiRCFJDnF4ZmlBg5AgtKA" +
            "9nAEmpSuPnJ8wIEFD1PQeAEhn8AEmBCNNnvexdmzjgKa1DJLY+B8dASwIwAJCR9Wcc4P0dAAJzdmkASXFFWALBKmw1NmTM8AlsIy" +
            "MASUVQA9HXMTQ8MI0mYlQh+x0AQf22pnhaJ3i44tUNxDtnpOkAQXUVOdBfKnh1RoaSxUnYPfgHQEHV3JXYj+IItmQiJbCSDDlaAE" +
            "FhdEuSxIllt3VCO4QkNxoxAEM4Uh6XEN4lt3e+w2quATRKB8BBiQCLEw0LXJfRulU+1OjFtQdAQeHcywKDqXxgfkjcymdFKFIAQS" +
            "GWIsFNC0R2BTZuSvBg5uQAQ7DbmJITJEioMx4DuuQCF2UAQbmhGYfAhST2UKsZmnVA6V4AQTEDGRJFBgRkNafsihaCCbQHQEP471" +
            "kSVyQglrbiVWUvDUh8AEHN6ABVgIUVeMXRsdO8AoT7B8BBsZmtMRsCcD3l9luHbsk+VgBLrE6Q9Ta4WLIQYE6Ji5q52wdASAltt4" +
            "HaRlUAkCDHPXiQo90ATachEDkEZjzbOXTSlzTBsR4AS5eJ3Q1r3UiCkHTzPAe40kMATwLu596iTkkIuyCBggVjqkIASQ1gz4HvJD" +
            "EB0DSmrSIRlbgHQEsADYF2eJYtUZA1djAEKnAEB8"

        /** Two-channel AMR, one channel at 12.2 kbit/s and one at 4.75. */
        private const val MC_STEREO =
            "IyFBTVJfTUMxLjAKAAAAAjwBb2eI0cPHzO2Lb+V1dGeAAD9aIDId2AAFABoEnSpQBKudSP/kYfgWAWNKAjwaMWeQe3dk71BECzmk" +
            "wwdL+DMs0SB2Jt0gtXDAuTJgBLVRNM6YZet0RBNniDwQvySIdrLbvgh5X3Hld6O2S4swlx7uSDnjbVdLHytgBKY6LN3kiVlDu/bH" +
            "BjwOJWWwZI9onCOGCn8bJtzQfzJKJaKn7Rs13w4o7/vwBKYUJs6bZpYlwqEIujwEpoaAbU/5nhOh2lzyGUT7fzX613le5zalZLML" +
            "SkeQBLXqLb3UraMtaT2GPDwEJGWw1uRbHgGqWlV/h6noS1HtKEGBihkGQPf3sn/gBLUeN/3bVwTRBbLyzjwCk9Y7QY/h1p3lij0D" +
            "SM+SufwsrT7IJsGiZNKylBlQBPNTY/3W2bUZ4gyZBjwCZ2SW2++d+lX+Kqd54+v1U1ItRlqAToG6tfvWjaHABPGOq/6Lf4Xxgc8F" +
            "CjzQ7SGtdjgBvgEnCj6Tol9Ug6WaOHz4oyUNzgXZpuegBHgYFj6T0jiBwuIIeDxTBIiwSPwBngDlWt+MRY7Ml2g71dSZFQxpKW/u" +
            "3nQQBCuOHAzHaWWHrTIpijxS9V24cjALXgA2v4sDN1qA0FCzoFfkKzJ61Eh/o7ewBFgYMKzLQTZRijknZDzk9yCQRdIh/gCiK5eB" +
            "wJBfKadbfrqIeChA4WE5KiswBPhTJD3HazRWcxqUajwDc2afx3KGnhFQL0meesiNTnJHZIHga/tSaEMs3HqgBKtMvz/mvgWl/mVD" +
            "TjwMMFuZa96PvgBEelujqCjARUGoFLCyrRLdvF2q6+6gBLU3TA6ZYgG2UwMhnjwdWn9ATN+PXhgf7/A/OI+Yz3r0JDjbWMXDVvWf" +
            "SjHwBD58JA3kNFhd5NRSYDwuPXeIEEuv/ggVan+WIhQbryBU6621k5BjiCiA96SgBA4QCA3IwoogPEKQ5jz4DoSIcydpnghjalM6" +
            "WTrtqUpWXPJISPDgXgOYKJ9ABA58J17ei2p1AtIA2Dw3eF6QZB3zXgLdCpTY5+fhSkj6VHEk2wdKaew4ZI8QBAF8Iqvbp/dQyQg3" +
            "TDz4E2WQY3mXmkWHH38ER1ui10LJKtaScCJW9Y5oQqhQBA58Ir3WFWWsZjo2CDwkEoTARC8uOlUaunubdxQlj8Rq9OdThw5Y/uOB" +
            "Ey9wBD7lK/2d8GJq26A4ZDwMIWXgC87nvgDLf//w5xyY13+kVvbw2t/uM6N5EZ/QBKZmBHn2y6X1y5fpxjwCpF+QzzEO3gH+ai9T" +
            "T8orhc4KpF1vc/34TD1vqVrABLVDK/7ExhOZ7lw5KDwJNyA0SZkjvgF8/y2a+IqEykDWKyN2FvpvZ5Y/qKmQBPHqnwyEuYl5ZJNN" +
            "1jwDbXKViiqA/hABCimOX7/LizNwGnAL95VJv3a373ygBKv+U4/rwgfgXMMKNDwkTnmQX4rZvggdGlN8Uyje1gfSv/Z/ZgOaM5k3" +
            "XulgBD5fJivc7VMEoAAEEjwTZ2W4HT/ZvgCNWttvTjHL846yPFikVR5NJUDSvDTgBD4PER3rpLYfAnDKqDwSNHiQD9Ovek2WD0d9" +
            "1LO2xcOjLJ1AVhvwz0mu7bJgBKZRCn3Ugil0xJoRAjwEv2uAPvjRfhHpWoHS/pHNm0vqELUHAvsIbRzVYGTwBOdmI/3LNVTdrxAT" +
            "xDwGOXSQCCADAHAgign8bJfLIAhKHxdxzgHQ0xqdvliABPhsfBAbmHQUSIvw4DyzJJ6y77AAAaWk37JBhIeibu8Z4ccokOAAwSJ7" +
            "DbOABDWJ28FnwYXL8sFGFDxUdoWZbVQQYcPaGEEFFFc8+KyDow2UDyV7TJ/KZtywBGM847JdGfM+vze8gDxNAJu1XG4LAPD2zziA" +
            "j4q6QGCokkhKWnB8hnvB3oDwBNyYOMBNN5NWBj8DmjyQfnmTD4QBAQ8x6md6/qrKIjj+1ubar4nxMPeXkNHABHBDmEEnvpWIZVuI" +
            "MDxxAKG3TpgA4aQ+yoIioAmgd9CQL4UdyGCsoC0dqnWABP6YVHZdFfPUenrC3DxufyCVBzwAwWkUL5SYn0qbAeCaGvtggSXkVTsI" +
            "+xJgBBwY7zJXnfXKYp5BljxVJx29tGgQgNMhygnzgpn0qi1somXl7XgDIDNLhYmQBP48VfQXZPV0Z25GPEQmxJNqbgShhDDiKK+b" +
            "VCAplPR8BDcor7JDvtnrxllIinwELDz4AVmFoIskNIeARCbEk2p+BDeLG/EXyrV5ZmExBHwEFjzENRuoVt7SDqT4fAQ26u00R7Fl" +
            "Te0mu358BM8wRDUHPhTiKvlcSHwEN44g5QS+G25PndUwfASUmB20A/7ZZt8AD4Z8BDZMK8Imf6TtQpETlHwENTww1UM4ePD3inH6" +
            "RCbHg2p+BDZDL/AJ7fH7ZhKownwE/yMw8ArRu4y2hRc2fAQWi3eDJ2S1Xnrzgo48wzY8QATr7cy+H9/nId9euFNxgN1OB7wcbgOg" +
            "x6NnQASZxhA/7mX6ZpSCMVo8olxRkFI4WD5BK4pxOlaq0fSKqQWj0Yz0RGN/R0GFcASBkwOt5JqJeRmQ6zo8rFZKSAVg8p4DD18m" +
            "aOemoOj67QRaF0AY+kErr1zvkATAYEz+6+u0E/hZ3c488g9pmAFAK74IVIubppXwXvPX9sR7drb6AXRZOo180AQdYSc8+oNpBcVE" +
            "As48CLNfiISmRV4A70+/dDjnpHiSBSteKfPQrFK6zpBxgAS1gTj8mbswNIemBtY8B1tylWUciT4EURoUBPeH4ml6M+HOPO/RT/bX" +
            "NRyPoAQ+dNv/7MWznjNiVxg8pFHLkAtAab4YIkqRwhPN9b3j1RYxckAkhfc+NdKlQATAYDwO+IOaQHEuVow8rlI2kAUMZd4Q4iqy" +
            "qXp+wy7RD7b7f3sPRqRAfaH64AQgkwTv6YSgLGLnaEo8rGJbQHJp8Fpcet/DD5n7rMC/FQDyENzJD/xssPllYASBFyle5o5U+ZB6" +
            "hNY8rFY2kE5EcL4Bk6qOAtn+4P6XeG/r6jX1T+DkBRk/wAQgng2+11aFrELatcg8ckZZkAKAfF4R3Epnivr/6HeBqcPK+KEZVymV" +
            "aFBMQARgdBf86+/kpcGWd8A8GA9a4BNcez4L/PppdieKF+CvtxoIAIPtQPDv2/eSEASmfBfO9IKz3F92Oe48AqSHkvIqETab1nqW" +
            "Uq/rx9Co6PKO9Xb3T0RL6gK/wAS1Q9c4+0HVRaZGUaw8ZYGcTXVfIv4bXJ+RvN4osZ27mOIwwbtbSoP0XXfC0ARgPW/P5s7pwQk3" +
            "0VA8rvI2kBigJ/4ASqobkfkm/ly+TfyreL7K/7CLh1CT8ASBxi8c63MVYWcBjCQ8/vIcSBiALD4DeNq8LKvx9rze/cymvORQVxV1" +
            "BbHfQASe9gBZ689kfqHBa6o8tdgc8BI+I/4QdpszHhywKOtfiI7axzWVVlP7PObg4AS/PggN6+FlKMCBALg8uSzW+CZiTz4JxDqI" +
            "C67hz1VTk9C+7QmeeeMpVxHxYASK9gc57IGiIq8I2Rw8sPAROA0m334Q3VonKmEezVhVt+7mnSzgOZJwoBPo4AS9kgs++yJFagLx" +
            "gSY8rlg2kAwto/4aU5rYVLtb6BBKuBJDhPSzkhrP6eqbkATALig9+4N0xsZgJKw8C4BROAEALh4Sf9qEToH7iQAfv0RIGR+7z7Cv" +
            "eGZb4ARxYZA+5I+ImjJVPyg8CKZtsL+GMzabdlo9Tt+Ewmemv9uwbaeAfToD7Zm5kAS1AJMv+0ZVRLZg5QQ8aYDlQA88aj4aIspa" +
            "mhec7HLAptR+LxHt1EqGJ5z2AATApwjP63KUsSFn2oQ8rvOQQAxoI/4IM1o6Oiuu1+ZFzRqBd+xKxGuQLxARUASBEr9/64oV5NJI" +
            "sQQ8vuuZkA2oft4QapoXMyTg2j3/4p+aoXpIaLdsks0EwASZEhlt69amxARAUdA8uS2QmBECHj4BAbqd575gG3A19AcRANJg+wfl" +
            "E/b6EASEKSKt6xBE5XREy8w8vOg2iBHtm/4JI19sLm1rvTyR5ukUOzavZKmHQPWh8ASBzyO+/CaC2pjESCg8eFYuiAU7zV4LsFv7" +
            "XPpHU9c7ESEuIJ/w7AD5veNPEAQY/DQu+4O0g96GB4A8iFVjQAk6gIBgi1p7Huq6xAAjNNcbsPRpMc0q3v/N4AT4MV7AE8wI6o1f" +
            "qgg8VRk1uByiEIEtQT+21VCqmV3UhB61PM4iIsT23TWpAATtGBPBWxn3c7JUOuo8Vux5j7SaB4GkNKoi4CoD6ehSICEJY7vGcfy/" +
            "XXHsEAQzNssUDbDjQKwMRkI8VwKbtV/CCQFL6e81yiPUrRsz2n3pdLIVxOYTvTy0wARjGBfVE8xI9v4AXLY8Vu1knOA8EkHCXZrB" +
            "wpspy71wGlfLfg5G2+Q8GzqysAQ3GCDxDdsSNnRKgnI8WO0ckeGiAkGlWo8ivBLgmhJ8vIW2mbz+aOw9blbvQAT/Rq4kLbTjcFlR" +
            "0mg8fQCItgEoCkEt28rGXNIpzQsT/MnxqxYP52blg9SGUAQ2hKUyV0rlQ2Cv54o8RIJ0lAg6GEEsf682CdUCmvSH86jJHS8Bq/lc" +
            "0+ivQAQzAhN0RzzU/mpAiDJEJsVzam4ENuqnAlgTeoMoHSXafATPPOnCaqH7f7qQYJB8BM8YNhQ3Q6XHuZAhXEQmxXNqfgQ3Nuv0" +
            "Sz1m+9iDFt58BDc8zORXSPUPf4vZvHwEFY4MMjlLRbv+Bqz4fATPGIPwZ8LEn+GswVh8BGUoNLBNMfKsJi2qYnwE/zY04D1C8vvc" +
            "0IEIfAQ3mHjFRDkJF72Ph4Z8BDYlm1Bajur9EAjNvEQ57ONqfgTPKKBSG+6W7ynwGvB8BBXq20QmbOVl5JrG1HwEFmfwsk1W0+x9" +
            "u7cGPLn/RRAwY0dsvqk6t1HFSs9OQIUCwlvoqPcaQZfCp/AEacUoD/S9/gtMKfMkPF0vYBgF6NO+EDIKpsLp2dAY6IZ/TcC73itQ" +
            "5Zsr6HAEbe4rPeeK3lahkQUGPP8vjlgHDS4+G4E6GUMqlP5LoQH9s+vgE19N8QC+j0AEmRIJD/GGPU1CLWOSPIQsBpl9lAL+EmaK" +
            "16VpKdYKAfq/8L9Tl0upw2a2/AAEX7Rk78vLNZd9uiHoPOF5E0CoogA+Er1qelWl1b8S2+gqKOPI5H79UNFk0nAEq7pk/+YPJL2d" +
            "qMe6PGhI/I1cNUI+HhK7eQYPmEVr+qMdIzWUMYDEbNAeCvAEFk73v+jHu9MgZxgWPL8rUllZfga+EnBa/lMq7a8dzr6y9ADDVPNs" +
            "QSA/JEAEnuBnz+bIoRKW9ng6PLXSZngD1ng+A4/KTz7lqySry4FTL/cLPyc+X19O94AE4YgRDpzT7BDxGDc0PGnpOlAMOjJ2ipV6" +
            "fmNyw//ni5YZfFpa5rc3lOCllCAEFLgdreyjHWoaOvlePLfOaFADcZt6T7h6OtYr9uCUhq3Xwx1MVA8cOHUPsiAE4uAYz9H1XPtx" +
            "fkSo"

        /** Ten AMR frames at 7.4 kbit/s. */
        private const val TINY =
            "IyFBTVIKJKtXh+7s+Okzh6nrTRpXdEQlUEAktVEH2U5m+nGxvsnA5CxelA7PkCSmY4ZSz8jsSxjnlbttGyC4HJfAJKYnBjcemT4J" +
            "ZnuYiJ0abQS+0LAktY4GNDvazr0/z+DYP9wZZNsf4CS1YY0/XLo+Dc2BRfev9+ri7U/QJPOBG92+fsEnl97QcEpvEhvy9EAk8Y5s" +
            "NY/6PuXYx0Rb3KPfbKJCQCR4TdcAH9PxedHKyLXtVqC0P2LQJCtN1SC//iwFfj6k2nAa38xBe9A="
    }
}
