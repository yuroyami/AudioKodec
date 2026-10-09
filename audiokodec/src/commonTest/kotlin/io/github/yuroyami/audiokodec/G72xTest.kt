package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.internal.tag.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Checks G.722 and G.726 decoding on every target, against small files
 * ffmpeg wrote and a hash of every 16-bit sample.
 *
 * Both decoders are the Recommendations' integer arithmetic, so every target
 * must give exactly the same samples: G.722's are ffmpeg's, and G.726's are
 * those of the decoder the ITU's test sequences check on the JVM.
 */
class G72xTest {

    @Test
    fun eachFileDecodesToExactlyTheReferenceSamples() {
        val cases = listOf(
            Case(G726_WAV, AudioContainer.WAV, AudioCodec.G726, 8_000, 2_404, -1126967975),
            Case(G721_AU, AudioContainer.AU, AudioCodec.G726, 8_000, 1_600, -1684873095),
            Case(G722_WAV, AudioContainer.WAV, AudioCodec.G722, 16_000, 2_400, 546200252),
        )
        for (case in cases) {
            val bytes = decode(case.file)
            val info = AudioKodec.probe(bytes)
            assertEquals(case.container, info.container)
            assertEquals(case.codec, info.codec)
            assertEquals(case.rate, info.format.sampleRate)
            assertEquals(1, info.format.channels)
            val samples = AudioKodec.decode(bytes).samples
            assertEquals(case.frames, samples.size, "${case.codec}")
            var h = 0x811C9DC5.toInt()
            for (s in samples) h = (h xor ((s * 32768f).toInt() and 0xFFFF)) * 16777619
            assertEquals(case.hash, h, "${case.codec} in ${case.container}")
        }
    }

    @Test
    fun aSeekLandsOnExactlyTheContinuousDecode() {
        for (file in listOf(G726_WAV, G721_AU, G722_WAV)) {
            val bytes = decode(file)
            val whole = AudioKodec.decode(bytes).samples
            AudioKodec.open(bytes).use { decoder ->
                // Backwards, forwards, into the middle of a block of eight code words, and into the short last block.
                for (target in listOf(1_000L, 3L, 0L, 1_201L, 2_402L, 1_599L, 517L)) {
                    if (target >= whole.size) continue
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
    fun aSeekBackIsRefusedWithoutSlowSeeking() {
        val bytes = decode(G722_WAV)
        AudioKodec.open(bytes, DecodeOptions(allowSlowSeek = false)).use { decoder ->
            decoder.nextFrame()
            assertFailsWith<UnsupportedFeatureException> { decoder.seek(10L) }
            assertEquals(0L, decoder.seek(0L))
        }
    }

    @Test
    fun aG722ModeOutsideOneToThreeIsRejected() {
        assertFailsWith<IllegalArgumentException> { DecodeOptions(g722Mode = 0) }
        assertFailsWith<IllegalArgumentException> { DecodeOptions(g722Mode = 4) }
        assertEquals(3, DecodeOptions.DEFAULT.copy(g722Mode = 3).g722Mode)
    }

    @Test
    fun malformedHeadersAreRefused() {
        for (file in listOf(TINY_G726, TINY_G722)) {
            val stereo = decode(file).copyOf()
            stereo[22] = 2
            assertFailsWith<UnsupportedFeatureException> { AudioKodec.open(stereo) }
        }
        // G.726 code words are two to five bits.
        val wide = decode(TINY_G726).copyOf()
        wide[34] = 6
        assertFailsWith<CorruptStreamException> { AudioKodec.open(wide) }
    }

    internal fun hostileFixture(): ByteArray = decode(TINY_G726)

    internal fun hostileG722Fixture(): ByteArray = decode(TINY_G722)

    private class Case(
        val file: String,
        val container: AudioContainer,
        val codec: AudioCodec,
        val rate: Int,
        val frames: Int,
        val hash: Int,
    )

    private fun decode(text: String): ByteArray = Base64.decodeOrNull(text)!!

    private companion object {
        /** 0.3 s of speech at 24 kbit/s in a WAV file ffmpeg wrote, whose data ends part way through its last block, after 2404 samples. */
        private const val G726_WAV =
            "UklGRrgDAABXQVZFZm10IBIAAABFAAEAQB8AALgLAAADAAMAAABmYWN0BAAAAGQJAABkYXRhhgMAAI225G22zt00fZd+dZNuT9bu" +
            "0lzimkf7yn/bNv9ZdSSfdi2UfyWl/rfzrJr8ftNikisS0ifd+j5Zr9/bdyyuckmuqdyTbLrzfNt0iVii3z8cV/PKdjrr8yScukkd" +
            "8av9bdud76SX+fykf97lvdZ6v8buyiscSurvMd9tP+v1qiiid8SfrrTT/acV0tiuivd6f9rbycTal2SfuuT7fbldrssz/yeyt9v+" +
            "db5+eb/+nyscSjvPdztdcuj+cUl1r+cfrNv1Tdz2/zji+bdcedraUusb0mT9sc/bpT+ddT2UqvmV+Zvj9bNvydty0ijj0V9Nrubp" +
            "qSev/mkir8915dPzbtOe/z6yV1sfnv5p9/57j0XsuWXzscutZtf95tSUf/yWlzziibrqadbPsT6dkXGUSSVSft/dtd9d/T/+eusn" +
            "l+jiVOfrzPTrjfjscTiesX8if18cTatfvdvTPT/squcvkeVz1+st1Tr/aZlP9vvv7yd6uvl+lX+esUdX6ecbtz4dT7TffT6adevj" +
            "8Vlj0XiWT0ezkesj90cdubj9Mb7NTbbtfd77779/jvT/z/z+U8v1131yXySlj0eifnUt1uST9z8ed/dz7jtObVDvP5jqdN96b7cb" +
            "v7f4ffvFd9t65zrzrjsd7j/Tsf7jVvv91qfsVv4r1//VTisdt0t9zyr8Uvj6Vz1nussv+t/0ejd+usTjsl/36sSrzfuV8dbkd9Kv" +
            "5/daftLtfrp7dTPr9b9PtttbTb7PvDr9t9b7fp/p9vVZ8b/Yd9vP9sb7Tv7r/lDfEYdqdjt9t/zqZ0oYdlj5jVXtutzfSqftV/Sb" +
            "sTjfV//tl6qrzscb3cT9tz/jdus9qv6rvzz5zjj7tjl7vt9saTMbeTbvvd5j7dvfqZ7T79bt6dNjaZ/tdvr9qobjftSbFTVcYrzt" +
            "zcduV4dz3t6X/yanVz3ysjilyVViXvlyv9zTzsdcr/fftvoabT79Vjcf87iT1kvkjyelistyvet/6bTbVprvvdasqflyjkv0ycc8" +
            "UbvidfPDrNjDt6sf3yjnyjzyscdb/drp/99jnzyUs8eTvtzTrNcr/cuTy8WVjijrfttDdT1sT0iuljXapt5vovelz0iV//fTZ7s/" +
            "t0iS6j15jbjNvj3yT31v97cdff1ylyq95jb7jj3iysv74Zt9F0Uiusdtdb8alyXivVbp9jlmSUVDd5Tg"

        /** 0.2 s of speech at 32 kbit/s in an AU file ffmpeg wrote, encoding 23. */
        private const val G721_AU =
            "LnNuZAAAACAAAAMgAAAAFwAAH0AAAAABAAAAAAAAAAB4d3eIdkc00f1kNb+57BGtyNwe8bzaX0Yk/2JmNO/tNCW+2OwezKq7LxKx" +
            "zGNmQx9BVfbRu/7imqnLH+6b60JWIi5hNiTtG07Ti838/c7KK1E1Ek1UZSL8HjGyrMnrsp/cHFMf1CJkU/8eEfKvj/77sfsqTTLx" +
            "Xz4WQ+wxWh2rzO4u3vo9QuY/PHM/Hdzt872uOSsvHBpfRBNM/FQv25w/Hc6NEV8Soy31Zh7sPBLDq8vO5L78PCU07m9NJq7C7OLs" +
            "uuox/sJMEhcz/00S8rvN3JXP2y5fEh7kciEtvcRB2tsZPU3rLS83T0r8NOO82xzyvK49X/MvPETl5fo+Hf+p3P60ox0vVvPD5FEj" +
            "u8z8H+zJ/DQ/IRtUNRG/Pl7OvJrFX7q/8kYkPh5UQ/yqwRO8ja4SEu0df1FC/+NEE53svC/L6O0iMdEfRlNDG1/+xemM0aLrnPFD" +
            "8jF8IiLDTu3W7tkZ3vH4ziEi5F5bRfTzO11dvNndLR6psUFD7t8kJyPC8VIfzspL+7+53E4vK/tDpVUxMfPEMe7soRy77N0a3j3/" +
            "T34RTvMzXfE/wi3u6o/f382zzdRMS//zMi4VXT8fFN0Sky2P78/OwZ+jH89NOyFN7zMRI/EUTT4to8QdzvzpkS27z+H8/CH8PULN" +
            "FV7S5jJuTCLOJSw+LPv9Hymt852jwe4vGzzPXrETTOPyISPVIibhJG7BQyFd4hwkLM1P2hGdLPubvKLbu769Hvr76u3u0f6h9O4S" +
            "PuM+ZV3vFfXX8zEjHyTm8VJOI97VYu3UETLi/OMeIf6u3+LM74wfzZPivh34wvye/907yq4+GR3Z0yqt/57D27HPXPj/7dPM0j7N" +
            "+fKvslz+urTUvx8dxBvfL+L+Su304ZHE/1JMze1zHv3yM/stJdLyK/Pz0uROX0sfUd0eFlGzGl9fPbHjTPP/9EoeMuyzzz/zLq7r" +
            "JO2sSh4arr0a76zqq+/q+Zqhgs75rduN797ev8zBGpvPn7Lb3R3L+bz92s34/s7BGjzbvyn93Y3//h3c28Qe+B7PpL7DTurtLPzS" +
            "/N8r8u39OQ=="

        /** 0.15 s of speech at 16 kHz in a G.722 WAV file ffmpeg wrote. */
        private const val G722_WAV =
            "UklGRuIEAABXQVZFZm10IBIAAACPAgEAgD4AAIA+AAABAAQAAABmYWN0BAAAAGAJAABkYXRhsAQAAHqSI4oihSCgIy14HBu9LSen" +
            "qK12nJo/sers7/nVEFFU2/p4nRMOTM7Sm7h6PFbREVVec66sMPZ73vcyauvt73/dG953c7Gz31ZQz9SXfN8emI9Q0Zj+efO6fNyW" +
            "n3ezbGrutHc/f3LxrW90/thY03/8ez2b1g2Ok9nbeZ/9GdJaVri07O5z+Xi3+/Is63Tzv9nZ/lvyO72a0Y+Uzv/bvt9ZlZFaWfn1" +
            "Lzb0+jv7d2zq8bL5+dpYNDxyfr5V0FHOv1T9GNxT19qUnbAz+nE4fPX28+7revA/XN53njL9PJnO19AV2rjZ/9GVm1l+OOu+8vt5" +
            "d3zsbPAw9Lxc17Z/dRvyzt/L+1DdNR7bfVVdEzDz/+03ef2f7/5srp7uU/mVPv2fcdpdD9zQEjB4/J36V52W8jpq8HM+Xfv++7To" +
            "+Pw6TdI3znB+/p7UV89ddh6suPOW+xm/cHEp83p81npcm2tdNtnZDtTUv35+vVwX0xf3vW9sdbf6m/Leb+u9sDdS1dxafjl4V3/L" +
            "mVTZnbQ13l3/2731bWp2r//6Odnztjb2/dHajlba9lqW9dIUGJZx/2z1d551W3LyLvEz+prdWvyaNfjXWk/ayjbWN1v0PlY/H/xq" +
            "97Pvtn5X7jv5dLH71VYa1Vb8nzzW2g5e3dvz7trz9xW5cjLxsrB2XBG32PT62HmbUg//D3V+fraU3tPtGLlqcfT3OFx59Deyblj6" +
            "j3jPfbrYX7tQWUqyW18u/PcdXX+09LDv9nF/XRb0evp43n+VTA/eHRvdPF1PN092tG74N/a4HHzyafp089jeVhNw91czUbcME3lZ" +
            "vDH0/Ff6lfZ88Khw8xa8/V/xOO58OdWVUdhf9hT3/sxSWxje7v05L1ZduzP9a3JrX7y7HHdeMnR2m9NP2p/RdnqZ0XzTFtz6cHE1" +
            "frmf9POx7in191H8tL79Nfj2ThnOm97afhxWe8v60i/+evmsmN499et7dqsd9pj/PS5d+nRVWRBYUzRWOtrVUlwV2Ppx8pb0fpxz" +
            "va9rNLL+916583RwenaQ3tIZ+drd+xVRENCdXvafePjZz3vy9bYunJfnfvfzHG8fcltuWFx6v99ZWd4U/M42WFEcX9/ZG7aWtTzf" +
            "8Pwy3e5z1mn3O31zsfWW7N2feHp6kH96FZZXXT3RVViZV7tcULpUbNIzfF25MvdXan+2sf52rXld9rZx1DP+mnV403XYVvqU1ztZ" +
            "WU7vDDtc17iYXt9y3NRbKrrZfDBwnDL3evF0N95v+LhvUHF6NTxVblQ++t3YWVV+2npOuRgVXdsfmJn6EffZHXHR95yzVHE3dvZa" +
            "7X3cKz2w/t/rVq99+7o53zTz03H3E/60ntxd3FxTts3t0JaeW//VXp1XHH38TDo9Xr/41Rdy9RXc8hN069V5/vBvnbz86vs+LnPT" +
            "LPG1/PNc/XAxcVZ8rTpbtHVWsVlzVllv1Vb1fxjfFTTc0dybmnsfE92YW9pcnNxNcntN91LdehU7m9fc7Nd9eZWeWi2cGvxz315e" +
            "MOrYHnN49G3fqjX6OHsqcLzun2/98XN4L1zwufDzv7f47Bl0cHPatvd6edy4ujL3Wdlzc/yduHVRcw=="

        /** 128 samples at 32 kbit/s in a WAV file. */
        private const val TINY_G726 =
            "UklGRnIAAABXQVZFZm10IBIAAABFAAEAQB8AAKAPAAABAAQAAABmYWN0BAAAAIAAAABkYXRhQAAAAId3d4hndEMd30ZT+5vOEdqM" +
            "zeEfy631ZEL/JmZD/t5DUuuNzuHMqrvyIRvMNmY08RRVbx277y6pmrzx7rm+JGU="

        /** 128 samples of G.722 in a WAV file. */
        private const val TINY_G722 =
            "UklGRnIAAABXQVZFZm10IBIAAACPAgEAgD4AAIA+AAABAAQAAABmYWN0BAAAAIAAAABkYXRhQAAAAHqSI4oihSCgIy14HBu9LSen" +
            "qK12nJo/sers7/nVEFFU2/p4nRMOTM7Sm7h6PFbREVVec66sMPZ73vcyauvt738="
    }
}
