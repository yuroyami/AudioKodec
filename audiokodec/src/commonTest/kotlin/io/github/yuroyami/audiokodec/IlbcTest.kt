package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.internal.tag.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Checks iLBC decoding on every target, against small files libilbc 3.0.4
 * encoded, in the storage format of RFC 3952 and in a CAF file ffmpeg wrote,
 * and a hash of every 16-bit sample libilbc decodes them to.
 *
 * The decoder is fixed point, as libilbc is, so every target must give
 * exactly the reference's samples.
 */
class IlbcTest {

    @Test
    fun eachFileDecodesToExactlyTheReferenceSamples() {
        val cases = listOf(
            Triple(LBC_20, AudioContainer.ILBC, 3_200 to 1514316682),
            Triple(LBC_30, AudioContainer.ILBC, 3_360 to -648612387),
            // ffmpeg states 160 frames a packet for 30 ms frames too; the packet size decides.
            Triple(CAF_30, AudioContainer.CAF, 3_360 to -648612387),
        )
        for ((file, container, expected) in cases) {
            val bytes = decode(file)
            val info = AudioKodec.probe(bytes)
            assertEquals(container, info.container)
            assertEquals(AudioCodec.ILBC, info.codec)
            assertEquals(8_000, info.format.sampleRate)
            assertEquals(1, info.format.channels)
            assertEquals(expected.first.toLong(), info.durationFrames, "$container")
            val samples = AudioKodec.decode(bytes).samples
            assertEquals(expected.first, samples.size)
            var h = 0x811C9DC5.toInt()
            for (s in samples) h = (h xor ((s * 32768f).toInt() and 0xFFFF)) * 16777619
            assertEquals(expected.second, h, "$container")
        }
    }

    @Test
    fun aSeekLandsOnExactlyTheContinuousDecode() {
        for (file in listOf(LBC_20, LBC_30, CAF_30)) {
            val bytes = decode(file)
            val whole = AudioKodec.decode(bytes).samples
            AudioKodec.open(bytes).use { decoder ->
                for (target in listOf(2_000L, 1L, 0L, 1_234L, 3_100L, 159L)) {
                    assertEquals(target, decoder.seek(target))
                    val frame = decoder.nextFrame()!!
                    for (i in 0 until frame.frameCount) {
                        assertEquals(whole[target.toInt() + i], frame.samples[i], "seek to $target, sample $i")
                    }
                }
            }
        }
    }

    @Test
    fun malformedFilesAreRefused() {
        // A frame cut short.
        val cut = decode(TINY).copyOf(decode(TINY).size - 1)
        assertFailsWith<TruncatedStreamException> { AudioKodec.decode(cut) }
        assertEquals(160, AudioKodec.decode(cut, DecodeOptions(tolerant = true)).samples.size)
        // A CAF packet size that is neither frame size.
        val caf = decode(CAF_30).copyOf()
        caf[8 + 12 + 8 + 4 + 4 + 3] = 49
        assertFailsWith<CorruptStreamException> { AudioKodec.open(caf) }
    }

    internal fun hostileFixture(): ByteArray = decode(TINY)

    private fun decode(text: String): ByteArray = Base64.decodeOrNull(text)!!

    private companion object {
        /** 0.4 s of speech in 20 ms frames, in the storage format. */
        private const val LBC_20 =
            "IyFpTEJDMjAKoz2X3Uyk+N+Pg4FA8Y16O3w5FQ20YjQF7Re1+L0WaI0o/EVwE2KjPZvM1/eHhTwsFA+XBh2GREzyD/mVmeufcR2d" +
            "gUWXjGMfGAZQElTVmdTUX/HyBlfjgOiiluRs4t9L5NWDkX8nEieZkScvmBYfUovCoz27xNZfiwDufB4XGxVCJ3Crj5OESWAhafrp" +
            "ftERiu+WOgJFEpKg1znM1H+g+kkG5+jAlmpSbmaWba9XwpHBSp5VUocj0+sfKPjaTKMtva22J4AAAUb//+oBT1UkIGZkLoaEi5pq" +
            "X3eY25OfsbHyaxIMoTW7trKF/39AAAAV34m3perGx3ZDGBzKlFlJijQaL9oolX3sCqwXsnej8PgDAP///6ziJJlwVW9t/vv1dtVu" +
            "jkGX9OQcbyvUeKFoVIOw+2R01ASUuhYQsOzcdyguGDCKQRYD8XNQVaYVxIM0QBAGlN6AN6D5WS3m3NA7Lja+/dQBvVeZvtr5PMBp" +
            "GSmapACT4GVVHJ/OcUA3tjli/I4sqkpHHQo6DMOReUqlZXTJUQpkD8pYuBSXprkpS7ONQBU2PbvzyAAJ//axFEAkqtVFFUBGENMh" +
            "6KiGimm239yLH2HFdgIQRTY5wp5I///AAAACv7Wbkq3QSdZFFBhS55JlFWe6A1xso4vee2ArKne8z+n/mn/8AAAzfYi7vleYgmVb" +
            "q2UXWPTq/dT5eCx3bB3iYFn2O7l2Fa+gYIgXtggKV036+ke+OXX+ljnS6SelQmc9qYpza5wu4fGrsfnvL8Ae+oS3ICHXWjyvVEF0" +
            "czFJdezypq2RElytNXEZTsSF9b3Fdp0F9F6B/INsCYve6YpME25ogcocoOtu5ZoUN2837slZVFxVubV2VRb6P6F8gfaKRbPwURiQ" +
            "YHaimlKsi4EVErRcr/ciaq4u4fW5uJPn9QJubEG9QwJp1Eg32kGXj8Z5pmRImI2gZVkYxhhxXiJjjjWh9f39QG/2Av4AGZp4xMIB" +
            "o1yEM6eal5N17AR02IRt9mGFbg=="

        /** 0.4 s of speech in 30 ms frames, in the storage format. */
        private const val LBC_30 =
            "IyFpTEJDMzAKoNWaM/M+iIfxx4vDwWDx/fqCFJqkqLmoVW2EYgQF4VOo/IM2tN+BHzsjRNq+eRanjOqg1zVNWX4nF/jyCafx4PBz" +
            "LuvYmvIT+cG3TqeScFRRxFLekFymz7ciA+Pe6xjG7sOr2KM9ug1Zfmf+oPvEgqHhaDH5dLKrqZJWICrLniN5ri+5qxKyoYXsh8cv" +
            "+kXYx7cp8YKSoNc6Mts95yDw4e1RgaD48wYkXIrvUemhAg/mKnohK6dd2pFrcbpFjO1pf29gsScpcmBFNbFTW54gtP/+QAACl//J" +
            "XoSeotpVemdEWBaU56IpwhUVVVngH2L9n1fvGCeMsB0p2se3+DsPPaT89+ABp02998u52ZROhsR76prUEcTuTbEeWeAhtB4Lpe24" +
            "qMQqGL7YSLmAg6JoOyZ7a5X73S1t+9zxJi5Dn0SJ8yigcaSnqmqQBW1GpOnaEB05UqP9Kxj2vigfB4I3tjFTY65g7AgE77tYiiAI" +
            "w81oQaoqSopDELQwyH4uIqOapXEayuHphH3tyeaWIRy+8ke38o5jTdeQ///gAAABS9ML/R6Am5JntCN6kokLFKmgm3UR9nD3ey7i" +
            "1vh4Wx7HX3dQKyp1n2Ns42++AChv/aIF49o/uas+GYlKZgbVhlLYEkYK5X3EpfYL7IzWuDyPADINShBjK144823sL4H/wgLakV+8" +
            "ED9oqjJoWthe9mvlOndoSIyYvlc99v9H+mFQ4WqnuS9J4oX1tcVbXeeGv8E5BtgF5AIIFpLaJSqZXfdsclPpq0ruyokktuLBaemI" +
            "loSi72nKFbpUhfW+HwJt5/uIfwlgtwE/k/onsJsNKp9n2z2CdnLx+lTTlHHuyrMbq05nKDoiEyoDSABbvjWFMzzK9O7hD9kB+cIn" +
            "J8NUEJvno9AvEy9wKOg3vkwFIG0poEryYOlJQ1NRwkfYVg=="

        /** The 30 ms frames in a CAF file ffmpeg wrote, which states 160 frames a packet. */
        private const val CAF_30 =
            "Y2FmZgABAABkZXNjAAAAAAAAACBAv0AAAAAAAGlsYmMAAAAAAAAAMgAAAKAAAAABAAAAAGNoYW4AAAAAAAAADABkAAEAAAAAAAAA" +
            "AGRhdGEAAAAAAAACwAAAAACg1Zoz8z6Ih/HHi8PBYPH9+oIUmqSouahVbYRiBAXhU6j8gza034EfOyNE2r55FqeM6qDXNU1ZficX" +
            "+PIJp/Hg8HMu69ia8hP5wbdOp5JwVFHEUt6QXKbPtyID497rGMbuw6vYoz26DVl+Z/6g+8SCoeFoMfl0squpklYgKsueI3muL7mr" +
            "ErKhheyHxy/6RdjHtynxgpKg1zoy2z3nIPDh7VGBoPjzBiRciu9R6aECD+YqeiErp13akWtxukWM7Wl/b2CxJylyYEU1sVNbniC0" +
            "//5AAAKX/8lehJ6i2lV6Z0RYFpTnoinCFRVVWeAfYv2fV+8YJ4ywHSnax7f4Ow89pPz34AGnTb33y7nZlE6GxHvqmtQRxO5NsR5Z" +
            "4CG0Hgul7bioxCoYvthIuYCDomg7JntrlfvdLW373PEmLkOfRInzKKBxpKeqapAFbUak6doQHTlSo/0rGPa+KB8Hgje2MVNjrmDs" +
            "CATvu1iKIAjDzWhBqipKikMQtDDIfi4io5qlcRrK4emEfe3J5pYhHL7yR7fyjmNN15D//+AAAAFL0wv9HoCbkme0I3qSiQsUqaCb" +
            "dRH2cPd7LuLW+HhbHsdfd1ArKnWfY2zjb74AKG/9ogXj2j+5qz4ZiUpmBtWGUtgSRgrlfcSl9gvsjNa4PI8AMg1KEGMrXjjzbewv" +
            "gf/CAtqRX7wQP2iqMmha2F72a+U6d2hIjJi+Vz32/0f6YVDhaqe5L0nihfW1xVtd54a/wTkG2AXkAggWktolKpld92xyU+mrSu7K" +
            "iSS24sFp6YiWhKLvacoVulSF9b4fAm3n+4h/CWC3AT+T+iewmw0qn2fbPYJ2cvH6VNOUce7KsxurTmcoOiITKgNIAFu+NYUzPMr0" +
            "7uEP2QH5wicnw1QQm+ej0C8TL3Ao6De+TAUgbSmgSvJg6UlDU1HCR9hW"

        /** Two 20 ms frames. */
        private const val TINY =
            "IyFpTEJDMjAKoz2X3Uyk+N+Pg4FA8Y16O3w5FQ20YjQF7Re1+L0WaI0o/EVwE2KjPZvM1/eHhTwsFA+XBh2GREzyD/mVmeufcR2d" +
            "gUWXjGMfGAZQEg=="
    }
}
