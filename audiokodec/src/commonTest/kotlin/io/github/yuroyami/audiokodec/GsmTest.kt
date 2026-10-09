package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.internal.tag.Base64
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Checks GSM 06.10 decoding on every target, against small files libgsm
 * 1.0.22 encoded, in a WAV file of Microsoft's layout and in a QuickTime file
 * of the plain one, and a hash of every 16-bit sample libgsm decodes them to.
 *
 * The decoder is fixed point, as libgsm is, so every target must give exactly
 * the reference's samples.
 */
class GsmTest {

    @Test
    fun bothLayoutsDecodeToExactlyTheReferenceSamples() {
        for ((file, frames, hash) in listOf(Triple(WAV, 8240, -1117512579), Triple(QUICKTIME, 8320, -849849123))) {
            val bytes = decode(file)
            val info = AudioKodec.probe(bytes)
            assertEquals(AudioCodec.GSM, info.codec)
            assertEquals(8_000, info.format.sampleRate)
            assertEquals(1, info.format.channels)
            assertEquals(frames.toLong(), info.durationFrames)
            val samples = AudioKodec.decode(bytes).samples
            assertEquals(frames, samples.size)
            var h = 0x811C9DC5.toInt()
            for (s in samples) h = (h xor ((s * 32768f).toInt() and 0xFFFF)) * 16777619
            assertEquals(hash, h, "${info.container}")
        }
    }

    @Test
    fun aSeekToTheStartIsExactAndLaterSeeksConverge() {
        val bytes = decode(WAV)
        val whole = AudioKodec.decode(bytes).samples
        for (target in listOf(0L, 1L, 4000L, 8000L)) {
            AudioKodec.open(bytes).use { decoder ->
                assertEquals(target, decoder.seek(target))
                val block = decoder.nextFrame()!!
                for (i in 0 until block.frameCount) {
                    val diff = abs(whole[target.toInt() + i] - block.samples[i])
                    if (target == 0L) assertEquals(0f, diff, "seek to the start, sample $i")
                    assertTrue(diff < 0.01f, "seek to $target, sample $i: $diff")
                }
            }
        }
    }

    @Test
    fun aWavFileOfMoreThanOneChannelIsRefused() {
        val bytes = decode(TINY).copyOf()
        bytes[22] = 2
        assertFailsWith<UnsupportedFeatureException> { AudioKodec.open(bytes) }
    }

    internal fun hostileFixture(): ByteArray = decode(TINY)

    private fun decode(text: String): ByteArray = Base64.decodeOrNull(text)!!

    private companion object {
        /** A second of speech at 8 kHz, in a WAV file of Microsoft's 65-byte blocks with a fact chunk of 8240 samples. */
        private const val WAV =
            "UklGRs4GAABXQVZFZm10IBQAAAAxAAEAQB8AAFkGAABBAAAAAgBAAWZhY3QEAAAAMCAAAGRhdGGaBgAAjxXXYovC7/9bKcqGAmnO" +
            "0UWG+eTIo8GouWrHipS2FdbtXNL1mWSnPE7YFDc6w8pqIHXSw4YaUXtOEViTJKafRwkONl8r+ZVK3KoyhbUrii+piXDb1mrRCJ4t" +
            "p9MHFni98fBYFHJ6MJPis08WmLqc7nAbaJnzuFZJoj5aYKHQhU7nGJL1GqcnPshhXsmbGIyp+7op1g63ihbhaPvsUkpcxExnC1mO" +
            "bXbFokZWypg+zXT2pBjWsL6WrE+hMFEuoyw377pq08XMFYlLikznfafIVimbwDCxABSqVWV653ozzBkjUAszXYnYTFed+rSVt8vh" +
            "klaUoVLE9sKwbWNGrTCJ0n70yPOkB1GNom3oOA+PHo9TanJ0rqwOLqhoaR43uo2I3LouHlhRypAI5pJgpcs2ijp9rpHS0A5bgzhd" +
            "ZCNdOn/k0qOfGeT2zMAA1KjFJ3oj9yoJpOV1VRl1qrl0LnS6KonAJ5BwRlGrUEyoenKITTGD2nI0wavh3fDUZu5s6ZZpv961oyZA" +
            "FMUOXnvtC0okidzqzmm1nBPGUi1s6qyzJf+SCeyILablYPPxtbOSrsZZs67Ynly3Hiru+Ka+w5rHlkmzpIU+vSVNxmUr987ocn+Q" +
            "4AOW6yUWnt4Sjqf7SeJe2W6qJhnyarqI3LZyYpWolypatkHFrsrpSmo2y0ZuKyfqda8I0lfkL4vp6yMViwniR3VfSRSIFWtp7yHO" +
            "SJ61txqJ3OmgeAl/xJi/OnpdjUxLH0mSwHzYsrZmfuuUvFNrK+/HiwqZxvZkjPhpZ1ceSHcco8tklKic8jI6SzMo/8H52Gx2yOL2" +
            "xRPLeL02dvN2aOZ7Rq/jerS20Pd48pVKWpruNK/AxPGUUppNhmVnt8pn0N0uLM6N6Ui5suvELEwkTVJD51PrtAqCqSVZLaGVw7jc" +
            "pik4stOcCgLUSGntsFqapzp1VIekhexCSFPlxg3X02gAwWVGp6qp4gySoq4qa/VW77sZtstVNwmSBFmQ8bXPmFTxs1duwbTGbfp4" +
            "Oaicjs25PofIlCixPamBTHYlr+XMSedFC6UM9CnWOtm4lArKkk526FjW6arilc3IaLm2dt2qQusZxXlcs7qM4kVRSre47jDHrdKC" +
            "wLMryF7PdaNnF3TJKNxZEWWv6OyqcP2Is0ljZZpotruLrEgVnRdZry12OYs8TmKnD8Smkq/XwyarnDptJb3n3ZAwBYYWkhTFjg01" +
            "a8p0qx4U5y1JiIpZnKqOShYnVlmhyonkXs/vGVnxbXJzq77uIdEltcYyXfZKTqi+KNLlCKZB0zpWW1pei8hrJ4UPasWk7isCrYn1" +
            "JP2Z8rLqpbaT2lf2DK0XukLdrL+uTbG6vsxkSJLMorAeK8M65qw6xs54rV3HlpIquaOO/Ofa1JIHtzqrKKdTKCduwo1OD2tURY6c" +
            "Pv5MrTrVaPXQn64psJpiwo0pNsai2rTZwWt0z7RKjzq9rYxk58u8Uo/8JxeHjamdTr2w887I4Edr13aV56pDks34dKncNBbX1LR/" +
            "rm3tj7aVqTh02bWWK2xDR115yoKd9OHWkCrYEs3Ii+kt3yL2x2KLejq/tgr3sJbpeiuqXNhO1K1ObVJPYqrtXDR6cqmghMgtCYes" +
            "uIqSUteS75QCjO3l0+7AFEFaOPIUxQxW16uqpsu3/L5rab1keF1zL0snqSpKVlRs886SCcJ0ODZ2q4y0Xa66acywoJGexLjdnFxU" +
            "3xS5VeeacdbCnoz5bRvzSizRy0UzuexK8Rid0XbtSOC10IxMdmySKd0I26y/djFAmb7kJFwTYZ0afZSb8FifpK3byA1hiVQ1XnaM" +
            "zfqrH+WyiExTkNteNQhXYU5PN1Rpz/JJpaA1qpXIJmbAoN6ycduxPIPcWJHFio7tmuht8L68Ji0HujHhmpCXtHpOZmkjRQsM4kmJ" +
            "K6eNy+O4aXorD4yExrcho1IsM39Sy0JVziBxsJQIeidZ8s2yuEnhuLNDk66G+2qdTcnUTMq28o2nKScFs4ruSKGgPU7tJBL2psIL" +
            "y/oZTN54Dc0j0lrZ2cA/KnHm1LBZG7uIr7yIuYm5dD+/VuGMrjgp1eKFziw9Uv3fUfdKDCZYkRLXjEzdKhZmes+JlJNjldjbaQ31" +
            "dDlG4d9ZUcqzuqNNN261hlJdUznC7ny0UHscw/KUYlVpHqsU/p3RVr2JpKyHaPMa6SOcxiE7NCgMPeehGK0IHDVv20bVH6JN4Y5U" +
            "idMo7tWDKnp7sh3E2WCSG7eRTya0SJmm7A=="

        /** The same speech in plain 33-byte frames, in a QuickTime file ffmpeg wrote around them. */
        private const val QUICKTIME =
            "AAAAFGZ0eXBxdCAgAAACAHF0ICAAAAAId2lkZQAABrxtZGF009aK7i1QT//9qRRbUAk8eKysNJ5o5zCFU9XsSsiTboy+05eTtfTJ" +
            "amcaDaOpdSxZUxIvJoboUqJ8+KCxKSUN7ziQ05ib9qS/CqjdVhoydqpPZE1BrdtKspwTy2N1R4yE69OY09akMnNhKR3l5LI0dMn8" +
            "Y2xMtOaLupJRdzLBCgrMxrxw1JZ7cnPFqGMPqWbUgonX3W0cn+EqzKDFN95dSijKha8t1BZzLmuKqjkqRcN5msc+TQw6ZXzpZdox" +
            "BqJdKmWp9ddT1NdhnWRwykhrz78U2UkW0IYTkIEKWqrTPx1vLGLhQiZh1NdJGNqvSd5tqzfWwqk6LDEaDuysBttwuJ1hCJt7mcnn" +
            "2R6CFNG06HHnGczncUo1ivHZV8CoUWzTj4W2iMjddZzE1VRRkJDMqQyNZbcUdUf05EmLWLcoRrqSlLp1Z61mc3Lw2Rt5jBiBSNo7" +
            "UzcjXqkJMs66rKNqU9LxmY1WqUA7QkGL1RRZUFpQp54UpoeEtKcmhncM/uDNPR+WzWzTa/rtcTQC1RRh4e2u64RKSUjd3Mm7lj4M" +
            "GaqsNV5eWydfCYlcDZ0y05ib8e1mqXVOLpddsOnrrcxVH/Dqd8ZPOWyTKyTM3mbJ01nktqPeyPWv4kE5wQv7AzPM2cKH1/yQfOza" +
            "6l0kMnVN0uLltqPFSUXsVFstg0x1VjXZE20suRt2NRfrasEm6K3Z0ub1fiJiiYU7ir7siQhKpck/eMTIrPJ+1OSRyfEU6Q/K0yb9" +
            "ejO7SOmS7kkkgMfLZdk9O9bJZ+J21Z+PqNC0wb5L0yL9NmvLiC+OOFZajQjY0fYV5WkoH/iPcN7NaGUb7Oal0x7tdivm500PfLmd" +
            "xqenbQu+c+XJWTJNfGlejCjniRs001nEtmtWx7C+tZlHuInwbHZXWpisKSaSJit9S3mVEENy1JZqYayGi+tNNEclpsnRAInIk9qL" +
            "WzTxVqupaCTMlZgS1NVw3ON7SLAgjLwrVKnVHhJFF1SrO5u59/TDK+qraQSS1BaDMe2eiaqnezsbgss4213OsVDJ8N49d4yQyUSl" +
            "vkTE0xm8svKcya86mQsWgWmcnWth0lKKZJeSsVzLSfVRzspc0xrNdqu6qpmXLijzuStWXFOoopVrR18aON2kqAnldEl609e8emuD" +
            "aWFGryKi7Kj5XUY73HEppyrTQUt3q8lUiorz1FZ7bitzKOcaUrXYiOoT3euZSVbJ1puyZ8+6iQaIwTMk1RRh2OJm6mmtV4ijvKkg" +
            "VQ8o1dSKrKNSouhUyckfue981FaL7mPnKvdfMgrJauwWutnZGlDrxSaxwQ2DLVcqbNs60uLldmLwijoaVfdBoKnLmkvy0V8q+xN+" +
            "RPvLbItzM1Qu02v9bZpiq/ZekMkkmKoHeHYZcczK1w7Fzp27bDNkVkfF07L87ZpbB4+VeVFjdUgfB9BqV+iLOKqRyNfH7KtVauFq" +
            "0/T9LZFgqZ0Gp0UpjKpabS+J0+lsp1LlVr1ayK2PZedi0/L88WOwidrXi9Gx3sjhO5k+m68H9XGSSlyfaVjaco6a02387Zvb6Lbs" +
            "NUYrs8sz0ZaZirsH2UStLsesiVQ2SmhW0ub1sdnFZ+UtFzXn1yqPmHbDXW6qWMy1qL7UzTSvkDV205ekemNSiiBWtkGMWIvRZEq7" +
            "ZN7JEBy3rebcjCKCTMcn1RRhoWt6qt01ntnfduk70sM66e6rLyRVFEuLTDeflkJB0x3EditWyKb61VbTmIsE5M0odbrJ6irtorWr" +
            "blLjyZl80yb9taFezBK9jKekluqjnCvCm67ooTpmaFbJbDS0S+Bl02v9dWGBCfZPkGugw0nSu4tSZ7FppN1tyNjDCKqpzL4c02v9" +
            "eeJdKMhpokbfqygOqNGua4dJPlntKxC1KlqUlJ0I0yj1saO3i2eEjcqkiqjw301M2+Dr50m2MTVjDlJk6Sdz05m1NiJojAU8iUdj" +
            "tIvnHG017eSMSBOe0yFVTBen8iZZ1RVxoSNgyUEz8ksnmqtG0jHH5YcpdUx93LybDKpaRbdn017VMmJhKs1ciFE2xs15ATL9EXiL" +
            "ZfwhrNyujWcBtyL803D8aSPMzQbyblZUXsvA9SdGa39ruoeFdHFTTRzMxeS51LX73StezA0EqkSjmoyq3VKNDe7JyLE8OpR7ybpi" +
            "5rPD1Tj7mWKVq1fFprcbaugaumrHBtzHpmJ844bkyRyqNPOV1Tj7mRurqckWXMFpX4l3BjTjJYcoGGZ48xSiqIjiebbb1TX8IZrC" +
            "6KqUacUfq6hVM3yW+ImNjSRuNuSeYiZSLTRfAAACeW1vb3YAAABsbXZoZAAAAAAAAAAAAAAAAAAAA+gAAAQQAAEAAAEAAAAAAAAA" +
            "AAAAAAABAAAAAAAAAAAAAAAAAAAAAQAAAAAAAAAAAAAAAAAAQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAIAAAIFdHJh" +
            "awAAAFx0a2hkAAAAAwAAAAAAAAAAAAAAAQAAAAAAAAQQAAAAAAAAAAAAAAABAQAAAAABAAAAAAAAAAAAAAAAAAAAAQAAAAAAAAAA" +
            "AAAAAAAAQAAAAAAAAAAAAAAAAAAAJGVkdHMAAAAcZWxzdAAAAAAAAAABAAAEEAAAAAAAAQAAAAABfW1kaWEAAAAgbWRoZAAAAAAA" +
            "AAAAAAAAAAAAH0AAACCAf/8AAAAAAC1oZGxyAAAAAG1obHJzb3VuAAAAAAAAAAAAAAAADFNvdW5kSGFuZGxlcgAAAShtaW5mAAAA" +
            "EHNtaGQAAAAAAAAAAAAAACxoZGxyAAAAAGRobHJ1cmwgAAAAAAAAAAAAAAAAC0RhdGFIYW5kbGVyAAAAJGRpbmYAAAAcZHJlZgAA" +
            "AAAAAAABAAAADHVybCAAAAABAAAAwHN0YmwAAABcc3RzZAAAAAAAAAABAAAATGFnc20AAAAAAAAAAQABAAAAAAAAAAEAEP/+AAAf" +
            "QAAAAAAAoAAAAAAAAAAAAAAAAgAAABhjaGFuAAAAAAABAAAAAAAEAAAAAAAAABhzdHRzAAAAAAAAAAEAAAA0AAAAoAAAABxzdHNj" +
            "AAAAAAAAAAEAAAABAAAANAAAAAEAAAAUc3RzegAAAAAAAAAhAAAANAAAABRzdGNvAAAAAAAAAAEAAAAk"

        /** One block, two frames, in a WAV file. */
        private const val TINY =
            "UklGRnYAAABXQVZFZm10IBQAAAAxAAEAQB8AAFkGAABBAAAAAgBAAWZhY3QEAAAAQAEAAGRhdGFBAAAAjxXXYovC7/9bKcqGAmnO" +
            "0UWG+eTIo8GouWrHipS2FdbtXNL1mWSnPE7YFDc6w8pqIHXSw4YaUXtOEViTJKafRwkA"
    }
}
