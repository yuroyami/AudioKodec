package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.internal.tag.Base64
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Checks Musepack decoding on every target, against small files Musepack's
 * own encoder, mpcenc r495, wrote from a deterministic signal, and samples of
 * what libmpcdec r495 decodes them to.
 *
 * The bound is 1e-5 of full scale. On the JVM the decoder gives the
 * reference's floats exactly, which the JVM oracle suite requires on many
 * more files. Kotlin/JS keeps Float arithmetic in double precision, so there
 * the result differs from the reference in its last few float bits: around
 * fifty roundings stand between a coded value and a sample, each at most half
 * a unit in the last place of a value below 1, which is under 2e-6.
 */
class MusepackTest {

    @Test
    fun everyFileDecodesToTheReferenceSamples() {
        val cases = listOf(
            Triple(STEREO, STEREO_PICKS, Shape(6000, 2, 44_100)),
            Triple(MONO_PNS, MONO_PNS_PICKS, Shape(5000, 1, 32_000)),
            Triple(TINY, TINY_PICKS, Shape(1500, 1, 48_000)),
        )
        for ((file, picks, shape) in cases) {
            val what = "${shape.channels} ch at ${shape.rate} Hz"
            val bytes = decode(file)
            val info = AudioKodec.probe(bytes)
            assertEquals(AudioContainer.MUSEPACK, info.container, what)
            assertEquals(AudioCodec.MUSEPACK, info.codec, what)
            assertEquals(shape.channels, info.format.channels, what)
            assertEquals(shape.rate, info.format.sampleRate, what)
            assertEquals(shape.frames.toLong(), info.durationFrames, what)
            val samples = AudioKodec.decode(bytes).samples
            assertEquals(shape.frames * shape.channels, samples.size, what)
            for (p in picks.indices step 2) {
                val expected = Float.fromBits(picks[p + 1])
                val actual = samples[picks[p]]
                assertTrue(abs(expected - actual) <= BOUND, "$what, sample ${picks[p]}: $actual, not $expected")
            }
        }
    }

    @Test
    fun seekingLandsOnTheSamplesAContinuousDecodeGives() {
        val bytes = decode(STEREO)
        val whole = AudioKodec.decode(bytes).samples
        for (target in listOf(0L, 1L, 1151L, 1152L, 3000L, 5999L)) {
            AudioKodec.open(bytes).use { decoder ->
                assertEquals(target, decoder.seek(target))
                val block = decoder.nextFrame()!!
                for (i in 0 until minOf(block.frameCount, 200) * 2) {
                    assertEquals(whole[(target * 2).toInt() + i], block.samples[i], "seek to $target, sample $i")
                }
            }
        }
    }

    @Test
    fun olderStreamVersionsAreRefusedByName() {
        val sv7 = byteArrayOf('M'.code.toByte(), 'P'.code.toByte(), '+'.code.toByte(), 0x17) + ByteArray(60)
        assertEquals("Musepack stream version 7", assertFailsWith<UnsupportedFeatureException> { AudioKodec.open(sv7) }.feature)
    }

    @Test
    fun truncatedAndMutatedFilesFailOnlyWithTheLibrarysOwnExceptions() {
        for (file in listOf(STEREO, MONO_PNS, TINY)) {
            val bytes = decode(file)
            for (keep in 0..bytes.size step 23) {
                val cut = bytes.copyOf(keep)
                for (tolerant in listOf(false, true)) {
                    expectOnlyOwnFailures("cut to $keep") { AudioKodec.decode(cut, DecodeOptions(tolerant = tolerant)) }
                }
            }
            // Every byte of the blocks before the audio, then a sample of the audio.
            val positions = (0 until minOf(80, bytes.size)) + (80 until bytes.size step 17)
            for (at in positions) {
                for (mask in listOf(1, 0x10, 0xFF)) {
                    val mutated = bytes.copyOf().also { it[at] = (it[at].toInt() xor mask).toByte() }
                    expectOnlyOwnFailures("byte $at ^ $mask") { AudioKodec.decode(mutated) }
                }
            }
        }
        val bytes = decode(STEREO)
        assertFailsWith<TruncatedStreamException> { AudioKodec.decode(bytes.copyOf(bytes.size - 1500)) }
    }

    private class Shape(val frames: Int, val channels: Int, val rate: Int)

    private fun decode(text: String): ByteArray = Base64.decodeOrNull(text)!!

    /** The smallest fixture, for the shared hostile-input sweep. */
    internal fun hostileFixture(): ByteArray = decode(TINY)

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
        const val BOUND = 1e-5f

        const val STEREO: String =
            "TVBDS1NIDSAFSFkIrnAAERtSRwwBAAAAAAAAAABFSQeBAR4BU08IpiYAAABBUKYe+AQCFcbZZVpWlpapCGKutWf///8v4njDBg8i" +
                "HQnkiFMkTqFKdSqVKVipYqWLFKxctUnXoGCFWsXCn8VACncU7ilEVeC6MhGSj6mpf1fTtAWVvIo0PO87lUhkX+pru3ImBuAUESz9" +
                "6wVUbQYN3lQMFIDDeqtCATFZ9999+D95+N8l9tkqbAG+UxIwokGxdIC0szLJlIRNtHvL/DAUMZRo1RiS8RMIznjZ25GkWd6/622t" +
                "wIMaPv9/1oW4YS7lh6GSQN2BreMOiasyg4T5AWttIoTgHXVuYASzNiPKwCPlGKKH4lgh3h/v9/5mfKtKiEm9BQVwLRSytGIWfb2S" +
                "vglnLMWofIWcY4ZuVEKHz4+vJYZnw3hHSfgu/f19aD+y23PQTGIAZqg9AEMERyoj0AXvZIamPEBbBEaqg8wFb8R+ozwAWvxGfv8/" +
                "rke2YlRU6A5qTRtgG8TqsUCG3FnqSKXDuIHeXfWJ8T9iUheiRhGltMMfpljMfa+/fn/K1l27/Imic4wlmceCB8UgKO4ZBBrx3Myt" +
                "IZLHMe2SJo46gW1P7+/n/yu2YOIFZZIXIBJCkVasVgCBCkUrIqwESEItWBWAIkIRdqxWgHkKRWsis+/funob11RJ+Ka6CASeYdRy" +
                "zw9ChqC8QTkNQKJ6fkPIEwBh1gZCVRj/fv3/81caECuygZ5SF1gdryAdpPUQ3SEPjkJ1NMwkSwZ0sFL6H5lg999/o25IBR6RYVGG" +
                "oSkA3VxqJG8Tg6c5hcaMaGSy7FUAt7iyf79+6Glk9CbCNdxAfx1gajCAED02BgIeb2YfAIEjCoREiv2G0DScsYj999n04fRirg9P" +
                "zzY9kvJ6HDdclhPVvqYYBpUeJ0cQ1c2CaX99/n35pM6Hd+jTI0MR4WGheAxQMAVMBOkRqpaflG+y2Xi9MZ/VtEEK1o7DxgMzZCJK" +
                "deQjZGX39BRkSZPToHQtXP332nHuV5A4GgbFRTRoEUqtl7O1r0ChTZeLQ4OAgQQeRAiOwtn9iQ/Gfkze9DiydXigkfgDQ3wpXawO" +
                "VgUWylkjjP/rmkK74CkDoVAZfKN3abzjgUaWhoBmZwNEa33P41ETmNA/5Nd0ngqYuTaDQPniMxLTc2pPBH45hn8qzR1IPR0QJOOy" +
                "UIJOOyEIJMOyEIJOOyEIJMOya/uxIQ9ER4TM8rQKuCEC1r7XqEyG2141MRX/mlZvRmypOQubtu71EHkWEHpWps31fscYQUHVRrq1" +
                "lG6TyrZOmsS88bAKiOnX/2VVzIyWkrL883nuf7I45nF71XOv1nDSHaBNglqQRQASBvMIWCZJGQB0BMoI8r/1JHQqABmDosCqBbkx" +
                "UwN4Phy2h+PNcKr8rGjMiFxQ8oKNCTyLB56xqZfRnBfBfCUi6/44Ds4CoVwHhghAtAFjihBuAYCmBIOCr+wKECD1CblMQOD/BUM+" +
                "woxdGFC3X/GN0uMXvIh2x14rnB/JviOl6/d0D1c5tifOqqrDup33W1HpJjkhvd8r/pgvQYphGWJmoxFFKVYepmU3332j7Pb+FAeH" +
                "AVHIYHIVHoSH4QHgTHAVHAVHwSIASHoTG4VHATv7gAbIRCEQhEIQhCECQhCEIhCEL7777R+1Nc8VDgNDomGpMOgsPgyOicbEw4Co" +
                "/C48CY3CQ2Cw9C9f60ASRdEqwmuLxTcLGCXIusXitX/0nbTlH7Z9D4mrG7LH5/TNnr+UFHC6YusDuhVliLpRNgJFKaKDyISTQjBR" +
                "2p1dMqkkCEjf///r99lttSJK7Hc2kmypa9dLe45D3KoB2coRpc4JugEf+IDAv0g+EITiIhp53iPlkpqLkdcMiOQ9yqAdnKEaXOCb" +
                "oBH/iz8AxnDBfAUfMg+oSku8RvPGQqI+q2lZkFr2hgBn3pwcdKkMAJmMufgGM4YL4Cj5jH1CUl3iN54wK1IjRggbguxOmDxiIEzl" +
                "NIRCGRBKT8ArUhMmCFuC7E6USSuYFncZL3lktFoexYGIZpRCHzkLxXmYNxHJ4LCSWDAs7fJe0rlgtD4OQAkCHIASBDkAJAhyAEgQ" +
                "5ACQIcgBIEOQAkCHaPBeQQDYuNPXvdtJ08JhomEFS5+ef1o8F5BANi409e94T+JvltBZQrpZUwZFFPhP4m+S0GBBVgYGFNAwIKsD" +
                "AgpoGBhTQMDCmgYGFNAwMhrSiillCcUj+nmIupPBDWhFFLKE2FoJAXgijnFKlJRTjqE4K4SwthIC6EYc4VEERhA8YK4EJmGUGAiA" +
                "jogyYQsMAYbmFEXEjnkghZOOmzCCvww3MKIbEjnowkxAk1GPmIE+oSQgTqhJiBJqPRQSFBIQKPavfCw8eRAoDJSuJX0NT2Eh7mN/" +
                "khXEp9DGIZI9LFyUQwQgAgAwQgRiGSPSxB/mQgTNOENUbAIF3jUL8yECzTiAYw0IAkDVgC4YUBBoQeEAxhoQBIHHzU1abJEfsxcX" +
                "LO59pd4xIR7EhHsSD+xIR7EhHsSEexIR7IEFGG34haODk0Rw42VRkkiEMSn61IZK9jUQySRCGJTzjfHM54+XFS5ffwCwssGhuePv" +
                "xQc5t8VFX9/+ZKlZXjLGzpsWCyuwikRxqFkuQZOfr/IRiZ+jw3xQCAowqE0EINNFAogQBgYGBgYgQKNNNawXQjWiqGSYUVl+TWCy" +
                "Ea0QW7T6Ax/X9AhECxFB5MUVAsYGPDRSwTCAD//+AD//+ADVVUADVVUACAAAACAAAAQAYBABgEAGAQAYBABgEAGQlJQugQDYECJ4" +
                "NTyISAAJFCMXFNOrplUmCEW/////fbbbbbLLrLdbjxLbLbLaDBfiDQgE5ERpzuI8saZFydYY45HuqYOnKIzcgT6Ej3hBgvxB4QCa" +
                "iI053EeWNNIVI+VsVkgu9QwBnvUBxpTC8E2Ms+wY3DC9hR5iPqJQu8jY8aFSPlbFZILvYL8Z/0wcqUh4xECaCmgIhjIglJ9wRqRG" +
                "TBC3BdidMHjEQJnKaQiEMiArGAylIR8yLywZhsRk4KEhWYLNvI9ysqLB6LBiM5WEfMa8sGYbIASBDkAJAhyAEgQ5ACQIcgBIEOQA" +
                "kCHIASBDktpPHxQNEwgqXPzz+tHoxJIRwXGXj3y6o8eEgwTCSxc9KFdLqiDIop8J/E3y2gspM6WVMGQFNAwMKaBgYU0DAwpoGBhT" +
                "QMDCmgYEFWBgYXFI/p5hV1J4Ia0oopZQnFI/p5iLpDIQYnpYXRoFsahZGwVhvIZCDE9LC6uBCZhiowOgIVEERhA8YK4EJmGUGBJR" +
                "TT6T2opH1KNchFxCfkopp9J7UUfMQJ9QkhAnVCTECTUY+YgX1CSA5Fes9PvCABAgSEAMW1OancJC3Mb3JSuJX0NT2Eh7mPmhKIYh" +
                "iCEMQyU+9eLmhDEMQxBBDVGwSBd4Sh/mQgTNOENUbAIF1WALhhQEGhB4QDGGhAEgasAXDCgINGYyV6Hix2NCGQ2uyRH5MSAkhHsS" +
                "EexIR7EhHsSEexIR7EhHsTLoe65iaBBRht+IWjg56lEEJT3MYIQkkQQQIy9aiCEp78cZZZDV2SbLrkMEcURlE4xRoxuDRrrFCC6Q" +
                "HhUOIUAMGROP+MRCN/D6zAsCAwCA5rh7tiMnP1/kIxM/R5WaWWWWWWINNFAoKIAwMDA6ZkmFFZfk1gshGtFVMkworLNBw52M7QiU" +
                "gsVaCXQQiBYig8mKKgWMAH//8AH//8AGqqoAGqqoAgAwCADAIAMAgAwCADAIAMWISkoK0A2NiIeeDsiEgACRQjFxTTq6ZVJIEIt/" +
                "///++22222XW7W22Sx622y2i5HXDIjkPcqgHZyhGlzgm6AR/4oMC/SD4QhOIiGmngI+WSmguR1gyI5D3KoB2coRpc4JsMAJmMufg" +
                "GM4YL4Cj5jH1CUl3iN54yFRH1W0rMgte0MAM+9ODjpUhgBMxlz8AxnDBfAUfMY+oSkkJuAVqRGjBC3BdSdMHjEUJnKKQmGMiCQm3" +
                "BWxEZMELcFEcJgXEkrmBZ2+Q9pZLBaHsWBjGaUwl85C8WBnDcRyeC4lFgwLO4yXvKwCQIcgBIEOQAkCHIASBDkAJAhyAEgQ5ACQI" +
                "cgBNN40ejEkhHBcZdu+rajx4SDBMJLFz00/zV6LyCAbFxqhD4T+JvktBpQrpZUwZFFPhP4m+U0DAwpoGBhTQMCCrAwMKaBgQVYGB" +
                "BVgYEFMk8ENaEUUsoTikf08wq6k8ENaUUUqYJaaJKcI6OMpzqXkOXEwS00SU4REBHRBkwhYYC4EJmGKjA6AhUQRGECK/DDcwohsS" +
                "OeSCFk46bMIK/DDcwoi4QJ1QlxAk1GPmIF9QkhAnVCXECWEwcHAoFAYORXrPXXhAAgQf4lFcIZ9DGVwKluYR/iUVxDMEIEYhkj0s" +
                "SEohghAhgBghAjEMkenGoX5kIFmnCGqNgkC7wlD/MhAmEHhAMYaEASBqwBcMKAg0IPCAYw0IBBZ3NGl6QZjJXoeLHY0IZDcI9iQj" +
                "2JCPYkI9iQj2JCPYkH9iRNEcONlXLoe65iapBRg1EMkkQhiU/WpDEruNRDJJEIZcuXi73nHM5ZXXZZzl/Dh16QROMUaMbg0a5IFQ" +
                "mgqBAex0KIcBYpE6/yEYsUFS8WrOdFCsTJtIbOTZMBgYKCihQKIEFlllliDQUF/JrBZCNaKoZJhRWX5NYLIR6lt8PPmj0SPDRRgJ" +
                "hBEgsVaAboIRC4gAf//wAf//wAaqqgAaqqgCADAIAMAgAwCADAIAMAgAxYhKShdAgGwIETwdkQkAASKEYvtTsimVSSBJG/////32" +
                "22226yxpbLb5fLbbbboBH/iAwL9IPhCE4iIaed4j5ZKai5HXDIjkPcqgHZyhGlzgm6AR/4oMC/SD4QhOIiGmngu8jc8aFSPlbFZI" +
                "KvYMAZ70wcaUwvBMjLPwGNwwvYUeYj6CULvI3PGhUj5WxWSCr2DAGe2J0weMRAmcppCIQyIJSfgFakJkwQtwXYnSh4zFCZyikIlg" +
                "tD2LAxDPKYQ+MdeLAzBuI5PBYSSwYFlbZL3lksFofBYGIZpVCXzjr2QIcgBIEOQAkCHIASBDkAJAhyAEgQ5ACQIcgBLx51ZQdPCY" +
                "aKBRcwfnn9aPRiSQDYsMvHvl1R4+JhgkEWgspV0sqYMiinwn8TfLaCykzpZUwMCCrAwIKsDAgpoGBhTQMDCmgYGFNAwMKaIOnFI/" +
                "p5iLqTwQ1oRRSydOKR/TzMN5DIQYnpYXRoFsahZGwVxvIZCDE9PGCuBCZhlBgIgI6IImELDAXAhMwxIT8lFNPpPakI+pRrkIuIT8" +
                "lFNPpO1GPmIF9QkhAnVCXECTUY+YgX1EhAoW1e+Fh48iBQGPTWr+ancSHu1exodxq/2p3Eh6qrt6nKgiSJIgpze3Pk9V28xyoIac" +
                "Iao2AQLqDUL8yECzThDVGwSJA1MAXDCgINCDwgGMNCAJA1MAXDCibJEfsxcXLO59pd4pqaO1HsSD+xIR7EhHsSEexIR7EhHsSENv" +
                "xC0cHJojhxsq5dD3RKfrUhiV3GohkkiEMSn61IYlcznj5cVL93/II+7qtx8vFBze3xUVf39cuuN/L0mEwYJIFQmgqBAex0KIcB8h" +
                "GJn6PDfFAICjCoTQVAgOa4FECAMDAwMQIEChRpppZZa0VQyTCisvyawWQjWiqmSYU9SLD92GYCg9kUVAsYkeGijATCCJBYqAD//+" +
                "AD//+ADVVUADVVUAQAYBABgEAGAQAYBABgEAGmQnykoK0AyNiIkesUZusm1G/UIYwCFdeN1VCVQVaygIoFK0ClUApcwKZUClGBS6" +
                "gUlwKVoFIKBSUAo+AUfgKKIQBS8AojAU0oFJKBSlAoLAUGwKDMCmOUCjaBRNAojAUMGAoeAUKgFAQClYBQrAphgUWYFFMJFqTGSI" +
                "GKVljEzZjTDq9cND+IlxeufYYqPqIXEVCLZF4i8ReIvEXiLxF4i8ReIvEXiLxF4i8ReSLvfzbN+Y2FIMugWplkxCLh5/nR5zOZrN" +
                "ZrNZoAoAoAoAoAoAoAoAoAoAoAoAqGMiCQm4BWpEZMkDYJydW8Zaf4bCQkJCQkJCQkJCQlWZDhIKBRMqXV24NBhmNTfY6Z/hoHBw" +
                "cHBwcHBwcHB0nUWUnUWUnYl7V//DIQhCEIQhCIMbKV87/5JH6XO3tX/xZ55555555555QwGDpjQN3/4AAAAAAAABxUtJxUtJxUtG" +
                "zX/4ZCEIQhCEIQrCoSCoIW/gCgFAKAUKRCkAxAO7/8BgMBgMBgMIiY0MAbb+AKAUAoBQ0rBMA92/gCgFAKAUlFDqC9vwAgBACAF+" +
                "psE1vwAgBACAEbK+nC34AQAgBACuwAOwTv/AFAKAUApEZzwNW/AFAKAUAvClMYH//gALFGQqA////8QJBwIeIEn//gMBgMBgMB+k" +
                "1QQP////2BRZwdt/AFAKAUAt9LOgf///+MwP//AAKlJonf+AEAIAQAgMOgTDQMt/ACAEAIASPvwXAf/+AAHCdy9e38AIAQAgBCcD" +
                "////wtMpWoH////numv+B//4AAAgCqqgf////83OBA////8QIECBAgQK//AAAAAAAAA6SvBgf/+AAJgP////U1QGARLAU0UD"

        const val MONO_PNS: String =
            "TVBDS1NIDXxMxVcIpwgAfgtSRwwBAAAAAAAAAABFSQehAR4BU08ImlEAAABBUJpJ/A63XW3W6Hx+ttt1u23+qqqqypKqpcxOMHkK" +
                "RMnUqlStYrVLapIQIVh10rmDNoyaNlZtKyzZWrin4U/ingU6ilUVRC50fjUFDDEj6vBkrMzbHsnf6aZxPnU+jubO40nftfwhAwig" +
                "TmRFRp7PCiPosmprRc0dcBkT7/3v2aGbhYTO52HqGckG3cBrXjg6UatMpjhj5UFr25Ipk4YdZVymDBLDNkjzrCCPJSIo0H5pYyHn" +
                "h77/r+zQf85b2+eiJkxEA0VRHpAIODSOlRZ6IC5fAkPU2eIAtYLI2VRnmAKt+iQVSB4IC1f0jP9/x89IynWl3PB80m4nbkQl5nx9" +
                "CAfJSIKHuhlhB69HiMyrYh1LRzx8WSibjlaoFvVL7/PvT6DeXaip/FOuoQBJcwdY5c8HsKDUi+IM5JrAoT6fyHyCmQGDqgghVVMf" +
                "9/f7ottlIDUXpReFcY2pEqQU4FfGyJFwBQg4nboBdGqx6HAMF1iajBg+MYhM+/r8z+ow3pMVXgeJ9oJvHzJvoPUOhurjLgoROPsm" +
                "JhBrVJ5Z4HHDlfNwJ6X/fvzSxt1PSi8dsIDmqhb8cogkAeIdJjePJg4GuCooiO5IgeAAkQ8IH9/fdTL5FwOHAOE2IV3PpKHoSwnV" +
                "KJjr3H6pz+IsBeBIcA3V+e/RjnH37+z9vBgJl1A4babwexQSNVTLOe8iaI0n4eTJSU30XaV9RDbZzyPv39n7WG/RJ/sxeolRiYlS" +
                "ivC7ln95M+V74quQfHkLDulHq37exXn3981cXjSo4SMdbodbEgL0WMincVGc0SfL4jbko+5TcwWMUea8aq779+aeNxooc4KbJWHs" +
                "9JtNCiihNQybQg5lgjyES4kcHyfXig/RU9Cp+/P2dsYDeKPqE1SDgZjGRCBIJKjyqRmR1KQEoNr6Et5iM8A/d5Yjpm++/zc7lSEF" +
                "CVapXG/vF19VntA5sDBlK3gEvx43YNcysjFp+/vutncDIH6fcf6wTRMzb+LyuajbtuM/NQRT2e7qBPEYokJ2Y1GF9/v+Pl99Dt/8" +
                "ijh7DmBXjoBijpBVD6EwICCDHpCbHJCuHNCtH4CWIICXHzCZHDCwHICb+/7/x+r5tLVh9xP0VcOYDQjtSZxtRN47QLIPyDJjuieh" +
                "vJNo5kKsQMC9j0gnY4AJWN4C0j4wvf39918rdTZK1RxutoR7oB8VV61KYFtjQg/ke2bhzukjQTM3BaQDhcn33+XhZkQWzK9zlSC1" +
                "ALk7rNEBaNTzHHD7YcF12BHEadlTL99/n2Y3k44nr4sDQHyp+/opBDsQkPxaQjN9TIkMjRGfEuYCX32jTi5pY+yNWZKfZgjZHJbg" +
                "bKiCbjHNWN/cdN7wIazBsK8+VbQ+Qi1j+ChyFuy/ewqXkeUtiZEl55E8RbBDAz7EkBUEn6RZsIKALkns9kolPETIAPB9Mg5HKze7" +
                "HP4xZKd1phCjv0cFWlEgJuWi5zu0CJ91sdOlRyFuf1agiKfFyzxsEVu1S7QZCeWTFE9HFVzxkb3lKl9f2oaQSuSO0odF/aPE0zph" +
                "lFNQfsb2Mq/2HmV5Znrk9u26IvOISVLluDUmn+lX9hxZIh/BMX60UhtzSgAucdwEHWV/1B5tjuFfbqHCY0OYGMS8MhKjV/KUeh3M" +
                "5cp9igW1+k3Srcjsy//SzesBxXoDDaGHU0xHCn///+MGDMYd7d9bfDD/VVVqVVVqpSFtrRG0uj6HHY7M+txG8fO6+OQ9yqAdnKEa" +
                "XOCboBH/iAwr9IOhEE6iEaaeAkBY6aC5nXDHjkPcqkHZyhGdzgnaCR94gKeB0amI2fQXsIacHauXCTKRmoJSYoD6UQuIKeByamQ3" +
                "fAXsIacOQAkCHIASBDkAJAhyAEgQ5ACQIcgBIEOQAkCH0LR+L4mMuuVIaa4ew4bEsbYYpoWl8XtMZhYoo6yqtTC5KLZPJcit1xKG" +
                "1kolG2VVqaXJNZShyrHKmaRL9gpWDlkJpjN8MTqashBQ3KRVQ8b/B5Jz0VVeMNJdtIb2RwxlURv8HknPRVIkWsKMvHMMW7FQXOMF" +
                "XdIgWsIMbvMtnbWGmPbzaqX+wiUgXCm0/ra05hxb291BjiBRQyjbiXGX5fQhwBAJASNEDED0kMxmFUVyUXnRY2hdG0+bEOZRVFcl" +
                "G6EyNSKPZ97t/EUiUyPA0DQNKaRZ87JtELnXPobou7sM0hs/hvjfmfKSLRL+Uibze53QyGQpzHJ5PJxPL5jshjueDu3ccyFFr70U" +
                "ctZc7pfGWsfkAgAwCADAIAMAgAwCADAIAMYhiGIYhiGIYhiGIYhiGIYhiGIYhiGK/FKTJs8bDbYB//+MGDMYd7d9bfDP9VVVUKqq" +
                "lWatttstS79brbbdXtufPH0MGC/EGhAJyIjTncR5Y0yLk6wyRyPVUweOURi5gnz5H/EC9fiDwgE5EBpzuI8sqZDtVLkzMRmoIyYo" +
                "D6UQ+IKdxyamQ3fQVsIicHKqXJmYjNQSkxOHyAEgQ5ACQIcgBIEOQAkCHIASBDkAJAhyAEgQ5JDTXD2HDoFjajFdC0fi+JjLrlSG" +
                "muHsOGxLG5QJd9briUNrJRKOsqrUwuSi2TyXIrdaShv9y1ItXPokTrpalDlWPVM0iX7BSsHLITTGJaceY/X1eSQLdXyvsMMQh5JL" +
                "TjzH6+ryQxbwRBM4wVd4iRawoy8cwxbsVBc4wVZJHkYFIGPptT62dtYaW9vNqpf5CJSBcKBiBxBzXJsCaSYU4gYUMo24hxl+H0Ig" +
                "APY2hdG0+bMOZhVFclF50WNoXRtPm9x9TaaGkLA8DyFkakYje7T4mhpHkMmLSF/M6Zw7LODMlVOsZYypd2ZQyd0zxfxftzYuLi4u" +
                "tze53QyGQpzHJ5PJxKOGnc0Y1JFAbCg5dvzgIZkSzMAgAwCADAIAMAgAwCADAIAMYhiGIYhiGIYhiGIYhiGIYhiGIYhiGL1/5rF+" +
                "n5M8k9ZUrDSsNKw5kgzFpI2MfQbnGSiEIP8dPWDXcqM2jh8LbeRK9yS578WXG223QA/8YMGYw72762+GB/qqqtqqq1VV223a2Sxz" +
                "pbZbc9cl8mwuU64MhHMPdVRB2OUkabngm9AkgPFAwF+yD8IgTlEUadPAI+LJpqFynWhkI5R7yqIOxykjTc8E2lELiC3gcGpkN3wF" +
                "7CGnB6qlgkzEZqCUoJw8lEPiCnccmxmNnsFgEgQ5ACQIcgBIEOQAkCHIASBDkAJAhyAEgQ5ACoUpoWl8XtMZhcqQ01w9hw6BY2wx" +
                "TQxH4vaYxZKJRtlVamFyTWygS763XEobWSiUdZVWphfekrWLHHOXLyqLSuSvBAgZRQVvwxehg09o0k4YyqI3+DyjnoqqbYYS7aQ3" +
                "sjhjKpDv4PKOXdIgWtIMbvMMW8EQXOMFXeIgWsKMu0/ra05hxb290skjyMCkDH12p9bO2sNLccTVm1OyK48DmCkStyuC4WZXyDEH" +
                "aZRWKxXLS7H2jBtTv5sQxVCGCMJUDQNA0ppFnzt38RSKTI0phMjSij34z5nykS0S0Svm+N6bxDbMs4XozBlWWOuaciCoVLJZDob2" +
                "5dNpsNhuX1OyWSyKOWsuaOGu5rW651TqkjisAIAMAgAwCADAIAMAgAwCADGIYhiGIYhiGIYhiGIYhiGIYhiGIYhih1EL66p8T9Ki" +
                "fr0WRWSXSWNcHdDxeuFYW+MFrO7kmOkXhT1uPjV13TY2XjZRMWiUtwZdKNDlWWZazMWT1hZNvR7uiNMk5y0ncZlbJXyhbn+OfjBr" +
                "vP3eimXeCI2ZwjzZtv/ngYBmwbfrdbdof7//jA//lAC0CgeAAwAAgFAQAEwABwABgUHQAKABIAEgFAMBOHAHoAHAAPlABtAEwABY" +
                "AFAABgA+AA0AFgAHAANgBMAAUADYAXQxouuDrZOLy+USpOFSpIGfOlMqjyMCoQrFvvUrwaARgOQMgMQMIMIMIMIMIMIMIMIMIMO0" +
                "hpwdqpYmZiM0hGTFAeSyBzAR8kGTbj1AE55f////////7ikXOgK4oFzoBuKBc6ArykbQHt9NMxCAsdNNLbbcBQREMGIBxpIY8SGV" +
                "BSGwfPaWqE73rX//kinhhj/J8eY3Sf5Gt1mtsQvav//6RL9gpWDlkJpjN8MUyhetcTLtSkkkkh5JLbjzH6+rySBbqJclWuDd0ta2" +
                "22+ggUW8CJFzyhzdxBqWpwFdW222038FGR7uEKI08MgfCtU4I8zJJJJ+X0e+zGG4Xi479X6g1V//5drR8Lj67yMyP24Mm2222/zd" +
                "zrPR5RKYRB3o9XJmpJJJJRcLhdFOIcIgEOCGBhYCxlAfcu7bbahkKcxyeTycTy+ZAlTXAW8u2221fNY6AzzP/vS6JbYAQAYBABgE" +
                "AGCPkf/jCjCjCjEGFGFGFGFGFGB9F0BGuv//fcmf+ZPX6LbGLsXYUIfCSSSRdg+ojW5wKNttrzTsboNeO/+ngvlDjnNf8M0s8uw6" +
                "xF3x27u6WjJFmnG0Hvgf//lZxY21nDgBQCgFPjnqQB/lj/59U+qfu7wkkkkbvjCwPjigAlwvomtPbAFAKAU/U2XIZc4//1NUBgES" +
                "wFNFAw=="

        const val TINY: String =
            "TVBDS1NIDWVdGzgIi1wAJQtSRwwBAAAAAAAAAABFSQdRAR4BU08Igy0AAABBUIMl4A7WzA+qqiTB5CkTJhT8KfxTwKdRSqKohc6P" +
                "xqChhiR9XgyVmZtj2Tv9NM4nzqfR3NncaTv2v5L3uJiC+ujSBkAKldp68KJGNK9CJV9/f7odvhHcYdDCP7sGrxDoNUyhwPkC3bET" +
                "TgOtWzAEpmiOtWEeURRB+Swh3D+++z/5dp4JsJLqTwBMBGpzwBbxHqDwBcBFqTwBb5HqDsBb5F+++/yal138k1W4hKZjwe+UYU7D" +
                "IQVx2ZKxCSzkesgmHGoLVP332nPuu0M+KV0ICTkOhxnDwTaheQHI1BSfPkOPTAMdQJBqWPP41xOn5fBY7oJkptWQCf4yDx+QwfmQ" +
                "gUteLgrd+FV/2WU0IQqoRSyaKLcmNX6gUKZxUh+JYhCP2Zn/6gWYFCwCg6BQtAoagUHAKLjR6qA5yItwE5yP4Ba+QYMKIIthu3Th" +
                "u3EgjkcjkcjkcjkcjkcjmZSYxLRllHn/6bFdCd6/g6tf//1MGhKYMCUwaEpiS38Fb//+HTGgZAWCjzDfxn//CQVCQJihnXzP/Ghg" +
                "ESoFTSvmf/2Em3L/xz/gU1QGARLAU0UD"

        /** Pairs of a sample index and the reference float's bits. */
        val STEREO_PICKS = intArrayOf(
            0, 1056127474, 97, -1093082503, 194, -1121529036, 291, -1094939167, 388, -1094148651, 485, -1096806713,
            582, 1039623724, 679, -1098628506, 776, 1050518347, 873, -1102021582, 970, -1102264825, 1067, -1105819796,
            1164, -1100682850, 1261, -1111765699, 1358, 1049564804, 1455, -1122457195, 1552, 1041090953, 1649, 1016669648,
            1746, -1095238030, 1843, 1033266565, 1940, -1118520144, 2037, 1040369390, 2134, 1054853625, 2231, 1044373178,
            2328, -1123766962, 2425, 1047844087, 2522, -1095054013, 2619, 1049992103, 2716, 1039050123, 2813, 1051933122,
            2910, 1049577680, 3007, 1053792065, 3104, -1102137700, 3201, 1055361385, 3298, -1102501785, 3395, 1053715240,
            3492, 1049765739, 3589, 1051929235, 3686, 1039151607, 3783, 1050080349, 3880, -1094860422, 3977, 1047789334,
            4074, -1122412270, 4171, 1044201056, 4268, 1055447252, 4365, 1040390966, 4462, -1121060359, 4559, 1033337539,
            4656, -1094056613, 4753, 1015656557, 4850, 1039974140, 4947, -1122868771, 5044, 1050734694, 5141, -1111946607,
            5238, -1102054607, 5335, -1105865198, 5432, -1099921682, 5529, -1102075187, 5626, 1049624242, 5723, -1098644405,
            5820, 1042108905, 5917, -1096783009, 6014, -1095124844, 6111, -1094867433, 6208, -1114433180, 6305, -1093152162,
            6402, 1055161499, 6499, -1092336311, 6596, -1137304339, 6693, -1094368631, 6790, -1094309094, 6887, -1096198117,
            6984, 1036203521, 7081, -1098039994, 7178, 1050282733, 7275, -1100998281, 7372, -1103602576, 7469, -1104653139,
            7566, -1101210993, 7663, -1109499236, 7760, 1049100324, 7857, -1117775410, 7954, 1040537328, 8051, -1164810108,
            8148, -1095578748, 8245, 1029973282, 8342, -1120058129, 8439, 1038412302, 8536, 1054876418, 8633, 1042929791,
            8730, -1122331551, 8827, 1046731517, 8924, -1094177937, 9021, 1049472039, 9118, 1039789712, 9215, 1051298832,
            9312, 1050632631, 9409, 1053146182, 9506, -1101910731, 9603, 1055054969, 9700, -1100267189, 9797, 1054333624,
            9894, 1049759525, 9991, 1052582915, 10088, 1041935097, 10185, 1050679278, 10282, -1095021938, 10379, 1048779491,
            10476, -1114579902, 10573, 1045434755, 10670, 1055228273, 10767, 1041558933, 10864, -1141191770, 10961, 1035652587,
            11058, -1093624584, 11155, 1024443809, 11252, 1035308958, 11349, -1130362000, 11446, 1051071644, 11543, -1113924610,
            11640, -1104425099, 11737, -1107087846, 11834, -1099575339, 11931, -1103347832,
        )

        /** Pairs of a sample index and the reference float's bits. */
        val MONO_PNS_PICKS = intArrayOf(
            0, 1056190981, 97, -1121649139, 194, -1094121689, 291, 1039491490, 388, 1050507973, 485, -1102273963,
            582, -1100745138, 679, 1049584200, 776, 1041073793, 873, -1095201262, 970, -1118566837, 1067, 1054859058,
            1164, -1123194659, 1261, -1095097067, 1358, 1039222849, 1455, 1049524935, 1552, -1102240121, 1649, -1102456861,
            1746, 1049723668, 1843, 1039184089, 1940, -1094889616, 2037, -1122195518, 2134, 1055486820, 2231, -1120681142,
            2328, -1094076363, 2425, 1040050443, 2522, 1050742365, 2619, -1102216826, 2716, -1099999907, 2813, 1049730099,
            2910, 1042053984, 3007, -1095155143, 3104, -1114279375, 3201, 1055061421, 3298, -1136009973, 3395, -1094283350,
            3492, 1036233715, 3589, 1050251320, 3686, -1103615275, 3783, -1101188068, 3880, 1049086452, 3977, 1040552899,
            4074, -1095573488, 4171, -1120138232, 4268, 1054815734, 4365, -1121890047, 4462, -1094144359, 4559, 1039923926,
            4656, 1050615062, 4753, -1101925144, 4850, -1100134903, 4947, 1049771636,
        )

        /** Pairs of a sample index and the reference float's bits. */
        val TINY_PICKS = intArrayOf(
            0, 1055658673, 97, -1121070142, 194, -1094761763, 291, 1039940500, 388, 1050068955, 485, -1101924784,
            582, -1101596868, 679, 1049916294, 776, 1040411348, 873, -1094920618, 970, -1119914094, 1067, 1055069762,
            1164, -1122310050, 1261, -1094607306, 1358, 1039267783, 1455, 1050345092,
        )
    }
}
