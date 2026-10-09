package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.internal.tag.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Checks Monkey's Audio decoding on every target, against small files the
 * official encoder, Monkey's Audio 13.27, wrote from a deterministic integer
 * signal that [sample] regenerates here, at the normal, high, extra high and
 * insane levels. Each must give the signal back exactly. The JVM oracle
 * suite runs the official tool on many more files.
 */
class ApeTest {

    @Test
    fun everyFileGivesBackTheSignalExactly() {
        val cases = listOf(
            Triple(STEREO_16, Shape(3000, 2, 16), 2000),
            Triple(MONO_24, Shape(1500, 1, 24), 3000),
            Triple(THREE_16, Shape(1000, 3, 16), 4000),
            Triple(STEREO_8, Shape(2000, 2, 8), 5000),
            Triple(TINY_24, Shape(300, 1, 24), 2000),
        )
        for ((file, shape, level) in cases) {
            val what = "level $level, ${shape.channels} ch, ${shape.bits}-bit"
            val bytes = decode(file)
            val info = AudioKodec.probe(bytes)
            assertEquals(AudioContainer.APE, info.container, what)
            assertEquals(shape.channels, info.format.channels, what)
            assertEquals(shape.bits, info.bitDepth, what)
            assertEquals(shape.frames.toLong(), info.durationFrames, what)
            AudioKodec.open(bytes).use { decoder ->
                val access = decoder as IntPcmAccess
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
        }
        // Three channels from a WAVE_FORMAT_EXTENSIBLE mask of front left, right and centre.
        assertEquals(ChannelLayout.SURROUND_3_0, AudioKodec.probe(decode(THREE_16)).format.channelLayout)
    }

    @Test
    fun seekingIsExactBecauseFramesStandAlone() {
        val bytes = decode(STEREO_16)
        val whole = AudioKodec.decode(bytes).samples
        for (target in listOf(0L, 1L, 1500L, 2999L)) {
            AudioKodec.open(bytes).use { decoder ->
                decoder.seek(target)
                val block = decoder.nextFrame()!!
                for (i in 0 until block.frameCount * 2) assertEquals(whole[(target * 2).toInt() + i], block.samples[i], "seek to $target")
            }
        }
    }

    @Test
    fun truncatedAndMutatedFilesFailOnlyWithTheLibrarysOwnExceptions() {
        for (file in listOf(STEREO_16, MONO_24, THREE_16, STEREO_8)) {
            val bytes = decode(file)
            for (keep in 0..bytes.size step 37) {
                val cut = bytes.copyOf(keep)
                for (tolerant in listOf(false, true)) {
                    expectOnlyOwnFailures("cut to $keep") { AudioKodec.decode(cut, DecodeOptions(tolerant = tolerant)) }
                }
            }
            // Every byte of the descriptor, header and seek table, then a sample of the frames.
            val positions = (0 until minOf(160, bytes.size)) + (160 until bytes.size step 29)
            for (at in positions) {
                val mutated = bytes.copyOf().also { it[at] = (it[at].toInt() xor (1 shl (at % 8))).toByte() }
                expectOnlyOwnFailures("byte $at") { AudioKodec.decode(mutated) }
            }
        }
        val bytes = decode(STEREO_16)
        assertFailsWith<TruncatedStreamException> { AudioKodec.decode(bytes.copyOf(bytes.size - 100)) }
    }

    private class Shape(val frames: Int, val channels: Int, val bits: Int)

    /** The signal the fixtures hold, as WAV integers at [bits] bits. */
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

    private fun decode(text: String): ByteArray = Base64.decodeOrNull(text)!!

    /**
     * A file small enough for the shared hostile-input sweep, which decodes it
     * thousands of times on every target. At 24 bits, every frame the sweep
     * damages is also decoded a second time in interim mode.
     */
    internal fun hostileFixture(): ByteArray = decode(TINY_24)

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
            "TUFDIJYP1jM0AAAAGAAAAAQAAAAsAAAARBwAAAAAAAAAAAAAOzpujP4NyWw7voAyy4h6xdAHAAAAIAEAuAsAAAEAAAAQAAIARKwA" +
                "AHwAAABSSUZGBC8AAFdBVkVmbXQgEAAAAAEAAgBErAAAELECAAQAEABkYXRh4C4AAPdUnkvmMQEAzBt+j9V97zTsPGPj10Zkwqwp" +
                "H/2NQQaULucwG85HmDwRpQMxnAMfFJhGdg+VdeAErwMjifokpdg1FZYpmOJKfw7or3d1RN51MmcAKJYL+DLTS3uOdoTFha6fugdB" +
                "JuFzs58Cq6TFexSRk0+Rss4X6xjmaDSjdM3+b7mcJvFfr71feF8P4J4TCCBD4kPtVwb+vCDnTq5zlaYBuIv7Pm59pyNV+fXNveoj" +
                "hrKj+4/k2RedJu3GGyk9WvFSwYXxDJgYYr7AQ4lK0rZhYU9n3aUInv/vasJZaFRCD10w3yVRvp6svQ04wlPHgSkGHt3vIJb3sZfu" +
                "xStrMIotu3AOyjgLLKwsz2SGu8STzIjbj5p14xpoPB3O+n5GSeZZUIv8unBtyVlVDSu/8bHMhjePrrcOd59q+dGqzzStw2m1mR3K" +
                "KVrQizRPrpnoMZ7E3GEjsF8PLmggp+98ETKu1nbD46z5YiHEGZI08dq3KKUl3j3imUnkbYE+JQcKdrMP4L8YGCL5hEH4Z7sowYn4" +
                "mH9B1yhMcITyZ34qGXVI3xqstSqTaENh5Jc7RDMRuBAYKWQjAj+iuPGa/AXeWJIQQ28jBKvfIYgzo24IlWzQ3HZ/odcrqSCMKoo3" +
                "E6imv0L057qx/hcdR3FmHqF2J8maxZpSHHMVOc6+slVHhC5egwhVlQShh9JdW9ZU945AnzbDg9X1jY3oFyWYcEw02RSUOlVNfe4B" +
                "2x6fYDkQly9eDISDBMj71YLkWBXu4j2zY5huWmi/7hdzKzGoeNUPbp1m2XZqCMP53UNTLE9JSYke2Wb2QuuN1F5n8K90JGVy0pRN" +
                "Ra0znXd/09EWBZAjyRQjFeZLqKHB6aPetd+RrQl3zq9D3uSWxuBzgQPMItHNWGYwIUkQNNk6CttFt4EsIKo6iMAXMlEPn5aBwMpm" +
                "f7Kw1zixYuSblwt/Xv+tk8q5jcewbqhOdw6xvRBwxxmp6Rs5F0q65z5Mn9fkoJOw9kfhrSIzjcjukBbdNSSpO51Yh1DU3gOdrhWL" +
                "UFZC+6td10MAmA8fwNkPoBgKvLYuSD3LewUk0zzrGk7Nv2gBAToQA664gI4RaiTGLhK/Zr7RDWwQdn1FBTLv20i4ay0lRRyfQ/vJ" +
                "fzfz7uAzdhKkl20UsotK2FNBq5LZvTe5kL74w2su6EFNd6SUZefHf/CrJfSnANvJO7Gg8z32wvd/5cSy1PYgiG4l+tIKpGeMYZUH" +
                "OkiGouqb9tetZp1C0fBTM8YWa22HX5s/xdX7+tjD/uUdVPfFTQY+tEMemf8Y98ZHiCgA2HfyR1rew/yN8lQeIDCGowgRk/YtdhGQ" +
                "sSFo8U1bvUWMYY23hssRjBQiHUGXPf1v0HxaswWZTXRO/yGi073ESFx//aRT2WGgKfhGCJr8Fie6mRJWvXEIlsRHQWHNuVjJ6pVh" +
                "SkrGk29OHnZiWTxM3YFfsMYTJ4PT42k4HUfX2UVcr9c/dokEPQk15Wk4l8OKgAkD3NUiER32rNt02EBxCQRwi3VlMWYWZjI6on8N" +
                "IyZCdigDImGsCiETpLonAB7L1wN4JKwZRf8puMdlqpfNIn5CP4JiiGbslZnwFD+qupTce0nT1wdHuz3vnMDyqWcETPh3Caiyqt3T" +
                "pbqeD9dKGUZXFrsUN7aTwpqKezEZs+cYXPocJJygttbD8vVNbH+6ZjHCppHEeRoL6MFkt+KjVpA7GIVXl1/gVJcpYsP+HXGxyB7U" +
                "fh/w+qUMWUagPp15jdNbrGUKucJ20rDwiN/FCvW1p8n8gdyww0x0yy9pBs1BxoTE51EDk0Fr9XbR7DE9eLs3QJ0po/SpCc15wiT4" +
                "fep699CZ1q2GKOaLI2dll2FkRQ2N4/946tJI5Lz0pTSw6QmA9oEzafNo9NXDJ4dxfWEQDknTvx21A8a6TWJ3qh5s1yl+bceki1y+" +
                "4NXwycdnChDTVZ/t7gGZGnNCe5ShSYUMbcWHrDqSyJy+KbY6ObytKgVQOdBogUQcP8QzWmoRaGVC3PPraEl687Y/sC9nupLIjjMl" +
                "18XXh1TrGw/t0Uoz3U5qJKcmm1w3ULRd316FljXf0T/hBDhDfT4TwH8XvcKjWQaHWAkO0zihE2KNzISf4hIWWrpzOu5wws9SqvNO" +
                "NMDoFmqjekOF/8uL8tt4XcEJBH2/twhc3l9oktDojxLEO2AlTwLvSIPzYuDS0OafeDTlgjsIL4Z9LfXq10KqROdYwBwvQveU6qAC" +
                "75QNZRDniE9nf4XCcFpIqVZ/GPYF1uXLoyPnhCS1/VWou+Dnm7M2eV7pZuOHyNGPeZ1FWT/+WJkpZNzz3zS2tTQfD6NT/91upPHF" +
                "fCkQ9hht4ua1oRPxYcSY+orEHPE1QzKynN7aPEaaay9oG7MLiqlIX15ozM23o5awAkzGfX0Psmmmy+oSH3+iPvDJ5QI3p2ARRpMA" +
                "pYWMCogPTIyDPnDuGWJBs5vn+oU94axJIC+cXudnTGmN44UmRNW5OZGDx8cxXV906iQQcaoZ51PnmbeSUAbCB6STBciZ8iMo7fa4" +
                "6Y6NrIaB5gLQIUcI5+Ijx9KgkusjkpG5sbafXU8k4eM1pY/qtowJuyPGazD8csUbQ9fq/vqrXM+cmMVB5QGOzD4ltMAT+oNGCvwP" +
                "9eiauco5i6Qke4l18MylvWx12Y/Fn/wt6USLLZqIswwEQiXgLgjcY5VOJj/qfXFnmE/xGvi1wzI2RnQfAAz+B45854ZXMfq2Oa66" +
                "gJ7nElosm5xL492aB+dyvtT0D+hpla6spKcq6JAq7IDpAOy/IWdxvTzu2j37ZrVAWQ0LCkgemU2mh9njGiKIQO390ISToIlYQg5E" +
                "ye5MVVCwiEG7NroIPedxI+xhkySN+MuRM3AVdGoBPR0ne5V9RmvEIon8Rg4hBtYmTvwcT9vRVbeXH7nUVizdjWelTqtgXrfAte+O" +
                "mSnpqDkevTasNsd+63uRqj6Tmz+rtxKk8QHPh4+9fagwDdHgxOgnkpQC96fW3JoTOs84LQ3hr55fQ62jjKk2Wsq8RnDVuN+MmsAu" +
                "dALOdBdGI4wkWV0J4CeRctNwpImc2sFZ8cG3fScch5UW0z38ZRxXgCGrhQIl7QS/KhNqAGGHBdb6D17wVjXLNoShEI7lD0KlmSgT" +
                "I4nQkOOuiykKrcT6qQKkkdyBQrKjlzK+WiRcefP6RB0URatbOAH7voeTLtBe2WKMt9bvgU4NPsFqKW8ITFVY7DLnWnXysSfg25hV" +
                "MTjdAHfG4PGrQs/pJGOGvojcI0OLMfztj5WOcec1awsPQZAhTy07ACZeU9mcE1jlg3IDatsedxBofkbDsty2s7vCq1gs8x6aowXZ" +
                "H3bWVuwvh9miv9ECyfqALaOg/zyCMd7YiLgQtuEnTY3NCqyLVFwAaS5UiUnqph4W3JZrpnY9XHNcaj2mmC76lrNDegbdC27olmnj" +
                "fC34D3iZFmbK3Qq6k5pSyDp15dTi8kVNoWv8TUGi8+Lsop/Ai4ev5nBFIwEZuzY43mOY4JOd/laFWNV+imnfnTMy7cYnp/0ekZiZ" +
                "ceFJZnysrNS2sv9AAx48eLi7K3W5iIC/KdcCpci/jaGCluE4B1IhWGqwwCHPae3c0QxMl3pKBUcYxsyz1+PDgWy2MWhQwHUqcjuM" +
                "064biAbzWDBwMK+4uYXBJmQkXehuOqzGAFLk6Jbw7cFJaT2oy0DTFYpX77Y/4Z7tEDQz9QxSaq7l2HP8XsSNjZ3IhcYCZhBftY86" +
                "Essj+eYhHLhWJMp1SJOrIiywsDUpMFPa3BfrDU2iUWiRJbkQSlYdhlLM0gd/s631i36DedOKkx8YvqZdFIPGnaYRs19Qi0KXONAq" +
                "5roLN1bTX7H44MmEfn3Dj+KgijWTv4qvkvdN+G139Alr0rZB+lTOYYcQSSD1quN+HvQDWHbbvErbcdR1lM5XWBUxO7wJRgXrPm8+" +
                "XYLl746Y2AczSgCywhmYEGTeP7Ps+YbopwdQI/3rjnTp02hQKgu5rURrhseTZ0pd7ek27ptM9O1ljx5nnsirBDT+epIN+mfMCIkK" +
                "msUxwVqYwNcWdGNL7Aq9NzHAgNVeRKRrJBerZoghIM9/IUPZe9lt/KNF60ZU7EPMi1mELf1FRRg7VulkxHGXCSNPmmgKMifwVuDn" +
                "7qZM6ZoiwQBXUnD8L6MX4wLY4K4bnyYl2eG+4JA/2JAH9F92dl2euV+QXw1w6x158gUaElVGhGFMTwBMNLwcmBxTB9F4Li83oSWS" +
                "JELsIhQHNOQb7ArD1G9iFDhyl0pKY56Pg+RwyISS1CbUou0yr4gO9+Fo1yOpB2rqSR6SAYC0A8hkiSGPXAuBdzYYV4BCacobagzm" +
                "AeUuDXCHM/d6eLW9bhE/QoLHo8W/6CBlEBsvLuYCgvdtqLCZQnp5b2vjZgkhIdtGVeryHXBYtY3F+GUHRDWDiQxI6xg/G8YE8ht4" +
                "QoD+gZKSCNnUgwBvyegxv3g76cZ0UiFZDokPlm8/Wc20quTzStNj73mWccR00K3ygiTcc3/d7/fPxS89KFCLRRAz1CkO5OQNCWTo" +
                "9kv/cY7XHbjb9mSIBCz2LLGFQf+yihXBFb2M78k4fjlKJoWZPUjdziIYcxiZQcGh3TzGsuNwVLa+zdXs+nip1P2pouBpMObXnxef" +
                "73N+rDoadGIjcMUtObjhAVU/p9eyszMVRGNSFOiwSPhtB+QwJ3eLHOCvYnr1WD1kO/BVubQYkyox1rdZVWrBRGraLJKT2ilekeCy" +
                "Sf3hoz0THzIrRRd7zzqqTm3VFnvKtb7xamWSdHEucdigdi9eoCOYGAUhfND6cYxAJH0UOLK4sNKf8e2/MFZvVcOOP/hoeVSo1V1d" +
                "3LkjAxKSruHaqbxyqAYQZuEpJVFbgrZpsLZm2g8IcaQOQFCTLpLU8fzU1aoJjG9/tcTHL6X5cT5ACJilhdaZOSM34ZntYEZWm/Mp" +
                "o06NfPrThTTVjN+aLCOUptUIDgbtVOFTagrCMUiZCABCHoWrudNUor3I4HGAgJtlO3hkpP77tqnSzWivJiIo09t0m5WTdnkOAOy7" +
                "g6vBnkLACOHf0ZW2x2wKhEuSr7xF4ubdTQVPgDKAWgRepk8HVoa44K/25YsvNN8EOI41NO/8wr1WB7g9UR2Dw6qSopgmjDLfBh6/" +
                "+mGlq5LpwO4l9axSAh30kqTbeaUHHTXk51W2ysmL/U6jJLkc54UTiEvIiB4tT2IGAfDfFaCLiyEwPOqo3BjYYlzW75olfMiZgsbm" +
                "udQpHzckB2hD873Qlni33brT4kwqGDzVicShFoLk34IPckIj4q3UZXCofUJc8wX3zhi5FYI1ilxGehRBs9yA/35kBUMekg1BH1v3" +
                "I8VKmViEttvQUsmOZlOt77gW0alXkhTKs1f8kK7I1w7XbOrTYPpITeDD1DNm7vpNZdU7vP02of3cyMYnCLuSud6aSyYe7txnebjG" +
                "DBC02xmuIpMV2Z6X3yWZx5s+9kWC4y581l0pydk/zsxnJDxOskx2wVIeeiH3L9MQ85wiwETcRW8Z9EdzAk6uskhFZht6+1kxoX4l" +
                "gz7twVYKITwyx7RsZwCCtVEPiGwtBZCtXnFoWbfiCKWerUyx0YlvwIrtDZ3I5JLXoDDseQRxcxEBkNc3FlRbPvXiZDTuWj3z1yPG" +
                "2+N3KzuPfVt8KqrPmSxM2lhkPhvx9wd89lJRjmMCIhtLXh7Iet2mvf9oEVAVXoeFXGRZD3Qc+3sH7m4/J4HVU8twK+/cZJoxWl3K" +
                "rbvS/ivCoHwxlGB3zxKhFlu0bC0B/+libRRlxpH0G5+fCSHF2OFoREYAPwOTXsY3qMIUdWFmJf8DFpYlDS2aJUqVkovtF8j8w4c+" +
                "RXTv3N0RJpNWkujx8FRhPxf4/z7rR4kOn7fnibpiqz3xFAkMVdYdpguOse6S0+E+0iktE0+UVKtt2Rl6UQjo9l6/vcf8UxJ3oZtV" +
                "dMohRumgqCVquWpvPEUdRkwCDXi3hunmhU1HUpb0rNafrJ4Ta2G+BJWC6rqSQKwwrNV2wDs9M+xtrI/ZhbMWgrYuiPO1h258f8h1" +
                "61A7/FH3Qjbo+vLTxkntlgVKGPrXL8foH72x9CTipC0ArXg+uNpim7lSsFGLrzawPkfDgIGcsDEA4i0OE0y5YoM5LGKDTakLAi3Q" +
                "P1Tl9RFz8oyM6wmmYBRadoGPD7biKUz47zfXaCCw/DwI/qRXwoClilANOdQpITYKS/Zle3ZINkcnVK1rPwvHLspB339LWNBkESzC" +
                "jw7UuB6Ltl19CxP80XW2cYjDul9Vvu84YzR2ae1qretOimRtUHW6KwJQxw6JSrDptfFXFv3MLMJwDVhGfx1bhAoE4iOKvnBkxglg" +
                "I1ikGwtg2qgGfANnkUXx4iOiNHW0lWFceeMHElB+OC7tXxE3a/m45LbXtbNFk0MNRMBHxDhOnhUWUJGoTUhiyc2NSYCN5l8NSAlR" +
                "4+9fX2o991wP3tzfahzYIK12tInmkdh0zDfrlJmcc9W4ybLbrsu4w9g6NPv5UxxxvhSVrDxDYgT+LJNqQKGLhtDR6B0XHw7uNlL+" +
                "eka9xfE8JQd4YkJpcHPXVbXup0DpffkJympZHvfULyHZnBx04J5QS+4lom5YaKTrcNuFwPAvAvG1Rpvj8z65o8qXsNMP8G0WrqXC" +
                "0hWrItDmWenvD0bPQQQzNQpEqitk6SRmX+FyE/OAbV9gi5k5Xa9tgmsYicXzqPns1xaCsLFN4lGDNuCnrfzDSdIpduSzoN8gFrGW" +
                "RNQeWYvaEJSyw2/tlmBNNfdRLqpb7QlUoJMiKSgdnLOlE6A9NMkLcQHRlMmjQuFaZ1f/rh7QlMI2dqojGIQedvNAs5H3bEEYXCFY" +
                "xYLqclQZr9im9h63gaYcoHxdcpC3nk4eno+dyWpdhlZ3MBWo+8tZiG1DFQYryR+MjXb4TOUUUEhV/lkcYr5K80cjm6pn3PdXkb7p" +
                "gcvbLFPFC6HMvfwGDESG6h9Amf8svImPmMxUEjeVLkb1OEL5Ww3vJ/2W7D4V7bkM5gZCP0nbghYwunLIKlpn7AXunta6fCx417Nm" +
                "gvXRemrBDUJGSnPWSp267lqg1V24Bvb9si9p+UTZHmWoIl+vcqIVAtT3yJjIIqjLfgxNOZBXRg7xsGjQ7JnhykLJJE/v1zj0/UsE" +
                "jLkvmWlgfTK7Tefeb43T2lqF/Yuuk2qrLFx7k0KM6WhjdKRAScmlOtErkaSCMP9aX9t9bEeJHvJ+MEDmHSm1KJ67r7AZAbLquPed" +
                "E2oC4M8iXMxzcO6VbpTJS/y6mTitlic3h0D0yinOwLbrctAP4WoYWRqqk7TqI2VHAA6RrHap1wWmYqdmdxHVuTzelY2YbfqOhRkH" +
                "6dA0RlqjfMl6g41Yizh9KveLqWIim4OrrUy8W4C5EQGfZ4lYpyfVoc0Xq/gnK4tWTIQtMgEChHdD6WDQ/J/tZt+eXgiKT0Xb7X4M" +
                "NaO6YKgWexT+3fvH/Jr8BaoMhO3Rbh0Y4PB4Xsheb20QzkSvFWZLBi4xzhYlnGF6Bs6ZBGVOZQmkVQ1yPoeKjoOHbZcloMVDW6Jk" +
                "0oJeCOtnkL8lWBzo9R6Lnr/UzXGi8vpQpLSdn52z5NnbFVpqLvFjrZMRQvQv2PmiJs4KucwSqWs47FKWIfaetQJN2l0SSt3lbY5g" +
                "qGRAUhjLCZX6wVtWKdehvhOCTJLfQfwFGW+oVlhhllDLM9/OyfSLiM8uZuF+4ETh9uwAzn877cdfEhJ5H5ovUfXClkY2BCaYI+kw" +
                "pao9u2WjLjGxw4J2R1zQk8NmGwc4rhfq8T7I9wmJKxFA/Ckbx6TwvX5FrKml5nYVkbtSBCZMjRO4N36YrYeWlsdkwCb5DCGlxU6l" +
                "r4SotHi4ZuiG7JlmOS1mQQ9/7iq3OjtK03ge4zEehFD9yWUjPclCDSzXRhDTF07QQc4dBe8ZC7aW/w4opxzVkEXx1LQvDKI69t8R" +
                "aJQP9HG/Eav9qDaE98p8fohEcMUSK7cnzBMvsrnF7nLG0vK8IHAh5NntzGoX4gdDNDrZ2OErJT0ELQM5uqI7IG+sWQAU3F6iXiHK" +
                "C4T8Ye2RSW9Iv8vnlq+uXW4czL7vl0AU6TNAtiSvHngmsKuWxk/ghTfn1FI4CzIKTFLBJugkJqCbWEtXe4x0Y3/NI8VXl/ngqfZq" +
                "tfl4mXd+4foU4Qsuw2skx8w4EqD95cDmSgZTTQwijVKKQhNd+hW3xRUipmivukRcbtV/pliS9M8JrYuAweJrx7g+v+GzPdEz+LnI" +
                "vqlYKshjwxMkxS8crzGbSe3yAHT9m0rMVhOHiKm2DtHBefXN70yc1SgnhYpasu8z1ZEyJ5+jRvNa17TbiH3RTrpiOtJVkmiWQFc5" +
                "FTShRuAzBT7CkT2mCaoxdOKwTJw9QdxYXnNSahkvu1J6PC1INrk3xeNA8hTOZe9ZPkH4yu1NOyHUnK/j3Ln4KOVRDQX7F10O7XVk" +
                "2F35BED41VbOOugBUAmbg0jhMAqXbmfQjx0iy312QE1XjQlNkrBMUrU+60AC7/bmsLEZITZ+jtQayYMFzmL7RZg0Q4oYBts1yZCI" +
                "SWpVj6aGKdOe8WaVMh5vbgTlrLPSF++OJYUrGwnhx8ccTO8di9JWXFzZF1kYPOhzFY8tIOFVu+deO82QKz+g23LLmG1BqmN7VIb8" +
                "3Y29IyLo/flmNkyxrl/MrPtNTlPs/m1lgbZm59jWmBA8S3fxolQb6EBSgbsMWMnhF7Z+cY1qkFICopFDF6CsLGeSQwfUANA4NbUE" +
                "6p3z4jvSf2YCeg2uAHml6TdGAy3x4/GVhmjEuokqkamcTdvbcUW3QdNbSwK1Psi/sdCiJ6elpDIW1A2PM3Wcb1U2vcQ4IN5qxIf/" +
                "Uyc3Oe8mRTfYv3TQ40ybRjySav+BD1KVuRECMwXKaFvY1Ee2mEmuRuw05glysxxIlff/clni32bXoW34a5n1Qt4AXGSjg/CEAwDz" +
                "4U+jDZvAa2yZLFBU9kyOv1RvvqXnIvTBr+n9GgQIV7XnXFF+y0XmKlVgvudeVt/68nMrvb/rVwhvVW8pfZ58rU26cz3OxipKpfEt" +
                "PN/QOyAz/xCNs8UMnzAKNNIeHnd5qdsgjKpyhIS74tZMANXiaj2ewkClhDWelJhKY6guuBOx14yqQi5hDp2N4ok2rVUlG3CYJ6AV" +
                "gS1z33B4ZOyYiKDnh6kIKmkWt080iLeO/LihWP73qY1Ql6hyNAH+nr4KUrc/lXq2qvrI9C/aqPRRi3h0MuPhljl+nDnQ2xs/QqZW" +
                "dMFV0k75G2EAUNbkTT/6bnj+0wtViXPE5uq5thNqgJBQ1ZpxApPfs968WdMdwEyDlM9Cfn+fbTZIbdwB7tauInJ61p+rLJ6l+c65" +
                "bQN8FnuxGFsjYRox84DPOpT+XXKP+sw0jIGrGpSE7eOA9L3uukbYtruXldE7WFYwORPVnffdSVHBEnK+dL98IaM6qZovCyu5nw8B" +
                "GlGbnAZMlg+gQNPNYE0aBy+PX/iPWb+63ilHzTjUsjCXkFypi36E9dP/B9wlz0TBZM8QZBdJ0Inkw82qBjF3QP+DzwCPu6OfCpq+" +
                "7WrOy5SpxyvSqthcxYABHVRD/fxqOQbaAGP8Q+/C1fECz5tdi/p9YFR3OGoDP5xlU5ks8qtQ9WutAE/WKYlTi7myo3kyeHyo5xhn" +
                "cEYAh+aoAAAAAA=="

        const val MONO_24: String =
            "TUFDIJYP1jM0AAAAGAAAAAQAAAAsAAAA6AwAAAAAAAAAAAAAl/HXBUjFXUyNnH4pXLz3crgLAAAAIAEA3AUAAAEAAAAYAAEARKwA" +
                "AHwAAABSSUZGuBEAAFdBVkVmbXQgEAAAAAEAAQBErAAAzAQCAAMAGABkYXRhlBEAABNQNyoA//8A/ww9AOQL6/+sgKOtCXGzDtk/" +
                "NeQ0qeGr3GtTcyXYBRLIJLP6hFUw/dRCCr1E8KYY3HRpOfc3+nO+Gx7o2xGzwRVY+bXKYSmHq1Pta4+a5K6JsGLYSclAYTrIxoQE" +
                "rAc8pyiXrm8PHTV/DD4Khc+juX71ZBf63jpC1H+pZNWIoXHNWs2Cbyr+dhV1T7LdWZ1iki+JCEi43pk4/3L9dZC79rRnmzObDHMY" +
                "yegsVuM6ID9pDmLgSp5cnvhb4+oBI3M457Fs35LHGBcFc9Kn0dv62sqEsiVIRx+vSZ0jgBOQzFT0boXDerxmlR8+9iGXX3Ai19z/" +
                "Mwr+bPIc8xTs3fL/VSo/w8zjCB4EW/Et6ehG0rcMa05KYBiI4XGoWuN7TZgyEXr9kvvAvGgaBLdDCAsQDjyF9nb7oa8DzCLSZ6U2" +
                "Q/hRE+m0KrCHj1mv0E0ByTemRtSrMGdNJS6pxq8pNElNPDd/GCK2tC9l5vffGFUhX5q5hmYYeWkPjsXn0rVc7q9sedrZ8mG+ZsK3" +
                "ITaoO3OEhd+6pAgSXqwR7Rl+QVQEULBhS1YwdKS8NKFdYZjBfUpWGH6cswqz7tB3z7OSwhDODjoe3HBU9Yxpi35eQFpHiFKiUtiU" +
                "1w7v1yLorDzVXUlQCMuMT0SB1eZOWhIdQ3dDQzDriyYmgqMoAp2FnQPCucjx4lZEZgAHeM2eXAjiIhJFBscRXzoRrRqst0oJE7kQ" +
                "lwjur4RwX6JTGryjburJh1V5hcuw9G08j8vqRYhP7mhfwma+h/FyEvvad9GgxF/L02z50OhICkU6ylpDb62rTcUCPzQt8Uw5kBJM" +
                "6JhhEGyp9rIiqDTgRqjwR7/sutpH9jPay9s8L7fGY1pfCzhET6pm03fFhjsXccy42YAMf7I4Ok+KRGXuRSduQJlH/CIIdp5ZF9rB" +
                "Uqmf8HvvyjZ4Og2xHXKUsY+vlc44eNbgOORurUNefmyGryS+NeD9Ouv0Q4U+1Rn59nP70DWuVm7aWkiTBmfSH13fq1PyGD+rzBVd" +
                "NqEJ9HK7GnCvvgzwy+L2Nc6uXjJNBDlBRLL3wiWTkDAJ6UWHV2q6GS8RzpjYa8xYHjVDEv9JRYchAb/n50kYsCenA2c/lPA0QZfY" +
                "MWBzIe8KOZNGYEy3eOSiQB442yON0lbB1TygDdqv5dETU1mwi2snCBArw8GZ9JVD69bc9E5ot9bykAeLjYYShl8LnsBeZvt2imTq" +
                "jaKRthfLO7ZkXbpD9Kugrf52WISK01j2KKaWWSFRc5Y4dRX8zhdGGbfbMjB8YBdCIMrSdUuT+PzyuWx/tVGdGQ2CFKSvdj1ksm/2" +
                "jjk/VJHoUcIQSgXgIAH25dPyxuFPvt82Nc1dM8KPaJcx830vwQLbtfl979J76e6/Mc6O3Yy7Vuy9G/l9TOG142klggnoPJpQwi6a" +
                "HHSWZgpDmVQ1PXgcHYnJYFJek0aY2wVvVcO75za04VFOgU3xtM23G1TVKIENnFzHh6svBosfxmAQG069fB9o5lj0G7DOlDKIc/0u" +
                "9s8laSLWdRd7FYmitRtC78QG407n+VtvFvXMqv/KtcPAfTDlQJLm7u8KTCdn8JiYR7kbthbE6mLB+/IsQXU+WpBb3qtXf3dm/l0u" +
                "Wyq8mdx7g/ne3xUOihyrxXSecupUg93MSEGQG820wog8FThwsJBfjJkUXqQQ4NSdw8vcnHq5hmAmgKbCSkzJob4utoI8AycbdUz4" +
                "qSvnVfDPkBVgYlcyFyDJKvV0iBGQN1gE8ImvJkztvgf6s/Y+fdXSkouO2DO+91z7UM4eOJFaIKmTuIp65tvNrDC7zj3bUHJmttvu" +
                "QUHN+AQEAuvI/BcaehMdqttZjpoWyJ1BIynjnebSy99DQr9RbrD7Lr/1GEj+0jUV8H/XJJRREMg/RZC7kcapFocngLBizisDMCnS" +
                "mD7wOCbnJPM9CKXpzGLHrazMkAnVapA7AJ9qT7dvunbASyenpWxyCI2PgD8rCc+s4f3+xwmY8778MSuOM3cG7cXnVSfGmxk9SlH0" +
                "SmquzuJ3p1o7QqwZnH8DRJOIYtLpkIBwGQUmFLEslQe0xlskKxvIwNE/jmapbz7n7rBmQMNuRpsbASP9S7ZMfwV2JW+Nm7RyOE/X" +
                "QuYm2VyO73orsNSUMT96TxlKZgSng++sS6sP2pbPs6OGtXcZWUUf2C4GoJ0LMP+MewNQ7p3kmwyGG+148McIJudXP1yOoIyA5e7T" +
                "jXlW0ig4kBFGiDLvvf4YSaDgfwRA0cBhLqNkoVjzS+dtGEB2VRUmI+NXF8hCC7NZy4FnBIEn7AfV9MdSYRsNRZV9iUa+I+gwV+lQ" +
                "guZ8mJVCADbWaZ8h9AODSdOO0nAYf30uqjaHNE00BP3yOoubAIoshCH+ozj1XX7aKPH4244FpJ8Op857SO4WDQJ+9gLg51QA4QF9" +
                "FAT4/F2T7sK/93Ga3+FczVEbOF2lZqmWRSWhh9Nfs3/k31LRUUOiJxW2MgECLwHY7E1cVZaVBMP0KWK1g36y/HjVIGa7G5kYCQOL" +
                "PQvbDJsLZ4Hhoy6M5IloLD9kJX9OdMN3uWaGbiyABiVC7U0Rv6aFrdK7xwuvYJ+q2Tg3DK9ASVjUb7o2e8OMFQD4L11rdzji+o2f" +
                "b9icA3DQ2fRjBD74hAbkKbrEuOTEL5rDMUxgUYm6Q0d7TVrDcOdRaVWJ9Q/lMiqq6rohCLX3UDNawHh/pc+TQBsJqtMWbqZfX4GU" +
                "8TTQaH/ZCStJpfmz78hfXuDp1kYugFB5+BNCmyWASGw9tlwnuReWPd83MuhMHd5LTz7GlmjFg4QHhWPpHlARot6FFVfKdMOj6X1D" +
                "AkMfWSGmJ1bw2LdVgyHAoKvkSGu6sySKFPfD0oNt7h313jIeZIgeeX3YYWt1KFaxKxhZ5RjIFKnrifmZ09ky+I9DqBBWqzEkK312" +
                "h5sDV47F3qf3UtjTNRHrvmYvxHUhsjvMTCeskJZFPOPF++oGt2+XmgN/IOzY+22OtbFa/UPBaAvAEgLDqqzjAqZ3ci7OVR/7/+W2" +
                "jorNSr3p0/RolBNlqrD0vEvW02fbed32waOjCP9zSQmpE5sOtKunFuQcT4pszRwsBPQI1XYGrg2k2c+o7hBhyYU11yp7c6RKXW4g" +
                "nN4v6fCLphetMIS2PkuwRBBfRwuen2Va0qEUDVXnzYWYHbIW5fz8tuKXmGOxYY5sLWtptADU7Ay7QGp5nF5JXQLjUq57j44lO7TR" +
                "iub49khUawHFGbucjbCg1xHn4d/x8E48zKNC4x4euaydgqRC82jZoB/2Euzygtza/EKy+5rAL64VK68xJrMhF/Pc5Gn2ZujY02K4" +
                "Jmw6Kf1Osc6vBpL9A7AOqccZxMTe0OqNPRtSDLADZ/f2lw6d40KpRIT1Vh1JvVwS+hUEvTxo4TGiOJagoRppdUa7U5Hb9m517aeL" +
                "qF6O3b/vBvTwWzfGzI2BcIm9KaLh5QUc0n17kHO4u0JrR5/ok1L/3pGb1dGKeQf8DN6ey/H+1nCnWK5mpoP6bIqeSrDiFSosljdf" +
                "F4WD1Xa/VS1C8r7+BnzF7WtyxmFJlSs/fBU3rPTH/GDys4m21ZdHKOySnEaY/G36diYbUjYKmwXuJEB1masEP9NI5SyfleWN/KEN" +
                "I42Bc13TMuatH7GO3T7aC2TU2JjvqoO+WHLM5Jt/W6qiAoa3Ub+Or8N1pWQ53ZWvxubedrnYbS7bIInpvupimuiL9bUnzP/q+5I+" +
                "RVD3c7ldyg1PyE0mIgdWQ9zV4r65O190Wu7+ka6tqxBWu2PbDcHA5sd0KgTq5J5VC5YI4dd52VbQChzQ+swq5dgo/5yOLxfniLTu" +
                "Snu5bapcMniEKO7/4FxK7wFkIBBxXFFGUAwhlOhw2eHNZXpcRADsM/EDQjPTgQVuBvn5MBbVw05x0/to4BYK3wdrIT6QtR9+dWJs" +
                "P9o+44260zET7MkILB5rtcwpn5wnHbMv4sqy7XGWOkOqGSwVtv6Nm2uwUERdKgZsm8XlT9mCM3JNQq2o4sRnw0i5H1ZQFWNhbzoy" +
                "Fl8mHIOzkeXgCEGS5XbB/lGlyIEMsj6ZzzOk3rrjITLNnDeESWpFFWLf+v+vaeW160s3GivsQCFJgoWlKgDKmjoWvL/ROGn7kVUX" +
                "MU9o2dXG8XbK9GQl3Hsz2pVHOkWkA+9/aIDdnLa/fnE5iMEMFjfOGi7ptberTHJNLl9JkbBwT2TouTiIopTV1ND3fRh1N4ndrgmf" +
                "OUBnWj7ofqNZhzRoj36fQMbRUyTNV940+O8g/ZPfswIwwqPFNT88AiQSsxryyXUIAgVJyaUIZnI3Y10UH+ExJ70Dko2HPGJ0sEac" +
                "7LT+swRUpvSsO3FUPfET0RqjoIy4K0AAtFllAgP4QGkGbJH6xkuMxZohF3TuAAD/ZwAAAAA="

        const val THREE_16: String =
            "TUFDIJYP1jM0AAAAGAAAAAQAAABEAAAA2A0AAAAAAAAAAAAAIfN0Ev8K0RD2xoDyfoZtXaAPAAAAgAQA6AMAAAEAAAAQAAMARKwA" +
                "AJQAAABSSUZGrBcAAFdBVkVmbXQgKAAAAP7/AwBErAAAmAkEAAYAEAAWABAABwAAAAEAAAAAABAAgAAAqgA4m3FkYXRhcBcAAMZ+" +
                "B3EM/f8Af4j+//o/Rf+qq0TJ1jjS6JxbR0jLKq/8GBwES3KqKTsnprv9daSp6v1nt7U9fqnbPEI30ExXil9/tX9IPQNtGwSO+BSr" +
                "+TKAJ4EzbicWExwxy2sqvZBr9R2661Rb9t5D/0qhNxPfpdDJ8KP0H2MEMqq6m9sQitR9P2MQRw+R3oCmGnLBVapkqrD5T3FraZkA" +
                "LRzTiddU96DIZWy3M51Qs7MPLu9nWsiF/+AzuAOOarmpmC1hElGf+xvLwY/HMdtRmFcON5PF6pUhMNRkrL4ZBxjnhw4W4xTvWI4t" +
                "wzZCdSrM+totgKlXDrO0bVHfEG3ifejIQQpqZmksgzwECqDJcMVOHl64bEojB0C0wE4LqCNuzdPlX6vPwUOAuA/OJm+zx1kr5NqH" +
                "V3gXUEpNMic7qAfrW7jZF93zRT/huaA8ROJcn8Dvh95t57asANgjeT5p/IEsmV8gDH4Me0I27Lun3eu62DSLguaLDY+QrHg4lOPM" +
                "+oS4QJaAIpb/k02wUafNNgFqCXLCSL5QftH0m6lD9wGSkx9KspugsSydSWEKy0fZGYgWbrsTngtfS8ds4+7YdUIZC3oja8JKCtJ9" +
                "O9KHXOA8o0kj5E52gdahtc9KKLKFX8cxfOTxgFK4H/nxzHnrpncl5uDvyME96k1gJtFWuFhSXC2ehq4UJ/SjW7U2bQxMd1eHkpGb" +
                "WkjlhWf1l1kQlSvMKC83ijOS5EK83YvkWyHu8QhK/pjr4Xh5g28dzyfQMJPhBwIcClZ+TKPXN7E9ocORLWjM3PTRaDe5u8EVRzjQ" +
                "BURZuvZDkN9PwbNBC0Tp8MjtH6aWvceXbi0rdcOhMzgmhcDY8fG9+Iamv+dlncvnW0jh8JPzm2zs8kmvVHHRdwPDx6O9i7BlY8tI" +
                "FG5t3Opj/Ze+zTTgOgj2SUOV27M/KU9kJjEgjGE8X5w4kvWIvbbvPCUg8X7y7JtmccET3tIHZQkfabqJEB4Oe5fGlXj+nLsguim6" +
                "IEDlcEnEEm2wijLAQ6X0ZIYrgN+loN/B3Mi3gvC5pynKh7SzxnXSiUjYVGYhPdhjlsKSOFXaxql5ZcF+e5Vphd5SYy9dXWZtfiAg" +
                "E1HmNoCwSh5vY1G2GVWUk+E/eQJ+vdKUMK7MhMOk1Oix0Pn6Qsad7KJjzQAV/lJI9vrApUQxzqDdNd6YKf/WX/T/yNPPycL3MsS6" +
                "Cr17OzT7fuwGSwN/Xg9IJildHbsQCA47GIVq33lcuStYRcuFeGJa9ERNE78Oe5KC9IGITBhMaPfAU90zDmk78OCqDeSEaAxlABpd" +
                "4qM83qhJMflYACO/mwUNYnOWLm5CawW2Um+bKcOueas9EHHGmva2yjwcNiOho6sA5Uj0soJk0BWarCUuUPSnU7LyMI9SIE4B0Ubs" +
                "mJB2l0GM6ssqJGa9t9KPQ3JT3Esr2ajk/+vyMrLg7D+JDLDUXyELM0SRajUweQ4YowFv+O5hJ/xLT3TSRe55Zmb0G2LWfbdqXLOh" +
                "oUAvLWbuPzUKjPpI+iNYTkw7yc5Fw783aT6fksRPPylJTN18y4cWWD+Ew5Zq/lUnbowTySxflIfLZw5MpPutLvSnLEY3THx9K/Tq" +
                "wdm7MEEcfeE4pZXqexlPp1YPwrZgP1qsqT+Fegop2hBk1eh/Bin7Zn+WjglZIWJvz8Taf3BJ6ZT+X1Ww4wqPtpgCRKbirdVdaCGp" +
                "9DxqFeE3EUIvuNERAaggyGsddWW0D6lW2Kw/xhyT+MWrkjIkxA6oJcrfVYv5OxnVmc0+oEVqCpkZWxrpm9w3hw8kP5bm1Xj6CZIb" +
                "OEZKEspzt2u4qTcJ0TX+/a5dmz9L7YI2I55cG0WZw615+eP2lLLNrts/AR8i18HLQEF+FhA+isemt+/qWItsZ6xa5csWSWHU7Zr6" +
                "xIkg9iUBB6Ebt7akSiHpEvbXddIsAujYOkJrNlvX8OqMM91lJgMZY+FvUYXqo7ODqOinjo/t0hPpIxeU9WIGe1/twR4j8b/RRajy" +
                "VWLFlCESJLB1J5mlmVlZxF9Fq4x3LMq4gzeCBspkV4WDTbUIPIahwHyQyexKDqfO4EZ4hE3F5pwocnI/BD/w//ku2UDM9002R3sA" +
                "CkCFARGv06aQh5Rrhb7E/S8/a5N0LpXtpFJ8/rVPozAhlov09dD+FixbbhjVPb8CgkbOIeJERY4x5NUD0t6eEFPwLB0jWS1Yw3o4" +
                "6zpEWm1PANrCQiQ8rFxgblKzkjy3RRhVFw3YTOCmvTvudv/Uc3zg4k3XmeyAbn2BTPBANI0AuocRrK8us8NVNeK7m2lF3wljLWYd" +
                "wSGMuKgk6RLONibDno7XeDgW/0XC8867tAaOtQmnSEcQpNRp6Q5kYDN3d3CnLKYcFXjcHOOmSRG9qDiyfIHvDagIzoQry2WBHRDb" +
                "QOHyBgC4MSUdeXoziGNuw5sJv1TMgoFJUHPGYNi7W9GKrqy7CSUMeJxchJFweifT1C4yId4Y9fTcdSdOb0qHGtjxkUNk4yI9CrEs" +
                "3fAvFzfLrL3vMoH5qXZiLFF5/VhuDT9PyZY8ZL2NirduC6KDmnpu40M1s9bpM37RFDDs5kmd+LV+W0kb28Dai4ZlMN7URb2KF0Nd" +
                "gmvghXE6dQRW+1ln120qGpFYe0zg4Q/9/Qu24GFwmXNYzaSyVw7uUWX5nEMDdKMctP0Adx+xbSr8wA2sD9hawedFbkb080C7JHQY" +
                "glwW+OIHMUSDkStcmNbuhyu0rELLe4Tg/Ydmlbqw4CJ4zT31VLhOWTxwuAwWX2mLFG+ogqxjSXabC7fC5TPguKcAzhPqQ0W6QL2T" +
                "QpRebe7WXuTpHtGwnxX2FjUTvClc/+11GfvAELTy2yi3CKrxjQ0rbG7zZ1x1zwV8hsBY6+o5iuNRM+sfQjPzwhodxNVhqPUtaZm0" +
                "avnBDvnRmE+qSr65R1MfBGfdOhNzIV+JNjZUCWNNhaxAkD7fwBC9kDTwk8SB25ROvojYYM0QRYV6pGKuxB1riRjzS1ZuFqNT7cH+" +
                "oQAFlNvtWOPMKWWsmEy7+ZP/JuTGTyZHkaio3uFJGa2/HUeXxHF1K5mPoaCamQCu+W4IbNaIfQNu+GCXmUlhIj7Or5KvIMXgviY4" +
                "a8PnUJYvDrniPt7OCVxFBSCm9TMYs/3vRY3qAHyZbTeu6Bryf4+tUwJIMimZUMOJtwWOdNHMTz8OJvDNgrr9ebZ5CWL5boPkKp8b" +
                "vzTm6mXZL8lT/tgtUgD2Tkr2CyIMtpA6ZiRyj6h/gF6HUJ1I5IGO9gRK1aS17DKBtQMLbqz5mwb4UYnBJjz+e+DD4pT/dXmKsPt3" +
                "4xjJJN2gaUP4i7q8QDb/3fwl4MDt1iDrAyAZAfOsCaqDxi58NUvScUNT7+pjWqkI3ZIuQaoBa0tz3GZPsDPrq7pqPMg0b50CLbfy" +
                "Rni4Z0FuJt7KZ3cFHTr8lCLQyiFTBrvjv8QezkLz1M6ubT3Mfj9Ma2MglGKbGlImEYxPpvzmFK65Az19gcouNLfCiwC39XksbH2K" +
                "GsxUC2KO02gu0u4YTxfeJLh3PKMGcXF0cY21W+92FT6zTGTaLHOiy01OAROUy8DB4EytiT5LhQb4SxEaassOLssEVJ776owdNSOV" +
                "GSIFkv1/ZAWaP83OnzkJlQIKxdhXRbN4NSPe/SutAsMdOtQ9mRRjQKye962sFYL9cs/GNzpr8ZLF3AOflgvmXtA4yRA9EXkeZLNM" +
                "VmT5XiEb6O1HjZKO/A5hyb1Q2IZewMpgm7jvcxjJ4MAf8jUsthq4xHu2a/J2reRxrPlu/nbjtqZDcWjoRG7giOH8F5st9Up57B2c" +
                "WeW1D3xrCEiQcEOqtS7vKA0Ll4Ha5LfACBPh5wIv/epJg8c1S53T/NDq+fE0EyK4LVVVkm3LmKE5FB0bl2hvLRxoyyXlDHgYlxcd" +
                "M+eNSEBS47/Khe960WnIpdUGaPdyKPEoSbol9MLdSY88gQE8MPdQuKfLWeo4Zxn+eV5myEuU/qmPxxbpngzeyo0ogqH4ffMYfuu5" +
                "vfwezynKp37X0C9bVDhFgznq285KeRP7GzOlkxQ9opX/F2A0rESe1dS4B8XcxcemHl2AqV8gVbxbuouGMkfEvnWzltakgEdpDZpO" +
                "DyLnDwAIziVPlyBu/vHOgDzb/c3NjDUzyPUFhyitGthSQDfWa/WiWaOt4RxYtnWAh4v+Bt7FdIPBqb2aXMIG+pBikSZQBvwbDv9H" +
                "iJPyAg2V2JSgeklG7FAZE6LWrs9mKJb1g2V115Ql10asmpj+wzo02YKbcL0b9WIyEfwK6flE0FE92ErVJOh5jlhtH0Bh+pBTC4ga" +
                "q2lSLABF0VY96ehwC5K8vEt/e+q+nKjOHppm+1wNQdV4WyuDYDpuTz+xkQdPceSlDUofG/QJgekKZYZ8DysHGQgD+2T1/zSXpNe8" +
                "lmw98beMJnz0+SjiEACEtQU9/PNgXwHY60/mhwwQ5zed8GQ7tCoeQLCS+xZCyQW9TILWmVXuziOrsTLNXSEeDLpB/+YM96iHeMzB" +
                "YHr0LyD4CgziHpnRfaU489tUBSzTY+pSa2KVxvMLk20mT4lOFq2BThdwTkPtzcMkWxvp6K0Uh/kcg58ue7KNIDhxacJdycRvZEUP" +
                "S5UU5bptWnVB2TQd168uWI4gPpONU7QIy0Q+ipuluQM1y8mA7oQjw2Yw1JUZIxJp6vs/JFyGG6OS35ed2s6N+qC2QLmtw4H4udXr" +
                "OJJnG/Hpyzd/ANGE+AAAAAA="

        const val STEREO_8: String =
            "TUFDIJYP1jM0AAAAGAAAAAQAAAAsAAAAdAUAAAAAAAAAAAAAuymQo+LRE5B3EDg23sdkH4gTAAAAABIA0AcAAAEAAAAIAAIARKwA" +
                "AHwAAABSSUZGxA8AAFdBVkVmbXQgEAAAAAEAAgBErAAAiFgBAAIACABkYXRhoA8AALEQpH+zAgAAGHuyCQSMUC/HM6R4B49j4NX5" +
                "UwWyXTl9kcliErgvx7m1yT0oiJiqyyqZRfqo01T51Sja3zQIkeqw+lvhafQ99n0uzjwsxxWGSCJLFOOgeBgYwG0EJiVnq+cwxcCx" +
                "68o+SYD/WgJs/HjvlDg1EsO00LPNHRCTgJG5vDKXFyZG1wkCc/i2jrQpt77pap030wxI6uRV5+d29S3WDiRKohtozsKs49QJlOJ0" +
                "GfJje5dq+zZhYDlFL6nB7pAhdPZZZQ1SRSDq9dgmeNgqbQKyEOvypNo0W4GifBa69tkaDmQFyf1N2XHajmPqthSHubWIRGuAspl1" +
                "69LWTMcaj+S7udYV+6yX1F+2KQcOuzIAm5TyVg1H+aKreOfeNSjgt32M/VlEFkIRfG61wGhMs+cI+v5ted+NVLQGsrsnHSOZb/qd" +
                "pgfF+MG76XJYjHHfXCR1AK/ZCpp9sWg7ytfL+Y3VmgUYSecABTATFXfNFt0aALQA/rLR08JLVFo4SYWt3S07k3sQBhi7Mi9QGvb3" +
                "gNwiCY1a2W7BfeDFedEXZcaqfIpMv1S3a+NChgFgriOqLvEEUlCxKOAI6q/AWJbH2vAk7/UKeuXDRB39CLRSYU7tA1DDkLUKWjuL" +
                "sFHViuafAUkl4sOFIrJ4DcazbZdMQydCvP34093fMhLCN+9HnT/TZ6HA17t9axvm0Zt30a3mJzFalKY/90ue2YWmZ/X8WxhW/Lkz" +
                "MuILygyAz3+VW8j5xf+Pe75m3LiwkOVeo4qrxDj3kZyBw0/VlFjukx+L7/FXdStIszs6r2rQFc3XZvNazg96aeNDZDLI+EX2mAvU" +
                "ibTcpNRsj5c8qTYq6YQmksXhxztkfPJBgQbzO+S9tXVd/+mg2MIr2Ske0OVQ+JSG5rGDk5742uS5Av2/gsAf2lzdyrVXOWWmWJL1" +
                "/lTNNMCgOf2FVTPdI6laYvRM4gudJLnkk3mT0YXuKhRNPSy6tLcMxtDDCApnIs9PdnW3xCI1Wz8WHyKgGRuSkHdysvfS/0WZVLoG" +
                "M1dlvLjdzRULVd5IgsjVn7mSqDRWoMc+/1yJ4BtEKBbcg2vUjEnB7KcoJ3/A+9vZTYd84oQVRvwrHsHZ4CCj+S7KFK0uyRwODmZB" +
                "pLy+ZHqf3+2n/HMBuWVjj9lXjxhj5YNG4OXhO5vtzn+/TYa64OI/YC/7uYf9jqCeqI1NNR8+PvxzyY98hmN8K8mngv7lUTQHS+YS" +
                "nMqYZDeOQNx4Jpskyun44e/g9lEAqauOwCJLJ7Ww/d38Boa/n7ve1xNTJjjb5o+VzdZ/YvAyDhOXputDqKeirf8Ve91VVkqLor2y" +
                "VKaEwhPokq8TboPI+p//Xvni7Ah7KFKsPxsT2Cw/RIq2hfUfpFHbb9BAGCEDf1VaBmuNyHYDFRAcixJBkIf6xPvDQ6VOLPqrbBKH" +
                "7/DDeQBmTUULMwPFUCn2CoTx7hZvZulgV0mFQx/OqyPrf+/e4Qz2gjtDqKNouMOxGtHYUzKfElG3//Ook4cRSAVDv/t3uNsPy59Q" +
                "zmcboV0uBEQSyd4OaMyw70c3cg0GgJoi78mAjBNTkNPqi0YqQKIQ1JTTVIMjZcR+u2kdWfa7LUYwFo6wwFzEV0z58FGPKQPJ1P6w" +
                "E+IQWia/zyAGe7JGOwvsewbyjyxk95OntI2u24FtZKhsuJPMcLGU/8m7jjHFWSY6k2PM5rCZgdfVtp2Q7CBi0DwNrvtN1v/atOVA" +
                "LUjLukF9ahgox2mRD1zcXV5bWMmS7plGuF6YJBPPDlZr8WNq3rTpe1s5DiOcDLXX7xFg3lC4RNZbx4yLurZweAkJkfGQN0lZhO6m" +
                "wWDFsfW78kVcdDq8AABFgQAAAAA="

        const val TINY_24: String =
            "TUFDIJYP1jM0AAAAGAAAAAQAAAAsAAAA3AIAAAAAAAAAAAAA9nSDq1dtlw55riKk8s82tdAHAAAAIAEALAEAAAEAAAAYAAEARKwA" +
                "AHwAAABSSUZGqAMAAFdBVkVmbXQgEAAAAAEAAQBErAAAzAQCAAMAGABkYXRhhAMAAPJShGYA//8A/ww9AOQL6/+sgKOtCXGzDtk/" +
                "NeQ0qeGr3GtTcyXYBRLIJLP6hFUw/dRCCr1E8KYYcHRpOQKk73OLUE7xRmAtZjs01ZGqTSn7md+Mas9wnaqW+Z1OKpb5IAnXuYX7" +
                "xJ4QSg8kRFj4A228TXt1gEVH9UxBC4wwJVoqBET2LV523J7ZHEnQDCuS5gBE4fCBHXMjchDcWA8TyVYhADIB0baVmwuBaYD/Adn6" +
                "CN8O3KXD0+nSFHQ/nfTOjJyn0PlOhR1I1yXSVe187iADfjZxYj12Q/J0Wp43nwUZA53BkoPOfDQ0/pH4fTlbc+ZFeQva7tPryoQi" +
                "jCvVpEmcxRRrF8PhbFHdwuvWCRQi/vvCapoX4cso2ARiZPq0jX6DUfEQSNWnea3inOtJ+PY2HUSZfdZQRFGVNIqBOd68w0YgOX5S" +
                "rEpCriwJfBAcnFa0PQENYmnYVW0snIpYb0r/YSvwv5HgF2IhJrlsbM7Z4UzyzgLyY5GmyRQPbAKtTbMzzF728dpKS+3iwsgIbtXo" +
                "nky9YJzfbXJUFyGYfMoYfJByFpUgciFFyl5NwYH+oS2FkC7EIOY06vLOxRwEORz7kVgOII3S4EN72IxvOYg47BkcJc1mbisUxOkU" +
                "5TnNg8nhneiDOFZ0CNp5NsZJBmDTizCnmn2FyxXM2QNY68/KIGTB5iPrqPJ6T1F2pFIWneMo1NSc3mRUxqS/Q5kjdZKV3DP/qqL1" +
                "OgoGTPq1zrLW/p5dARDwMTo0MheYBgBZjmqt7HkwAIfvls4eoQMfW72YjvbGTnPqtf4v/EaffnpDnugcpANHTnza1EsKDqbmwWZD" +
                "zTVm/qJLQwnxRqg/9Prd/IfW/ZHQHXyYB5o/MHZFyO+U1E+G9YxkqvrvtAtpaMHxJkjQh5Y5d037aYEdpOwZ2M0uKt+xP/p0WiTU" +
                "4k4AJstZpMbYDOJGPx0GAtUexWbywnQX2g4HAAAAAA=="
    }
}
