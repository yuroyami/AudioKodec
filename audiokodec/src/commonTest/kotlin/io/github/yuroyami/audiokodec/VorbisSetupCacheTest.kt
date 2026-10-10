package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.internal.vorbis.VorbisBitReader
import io.github.yuroyami.audiokodec.internal.vorbis.VorbisCodebook
import io.github.yuroyami.audiokodec.internal.vorbis.VorbisConfig
import io.github.yuroyami.audiokodec.internal.vorbis.VorbisIdentification
import io.github.yuroyami.audiokodec.internal.vorbis.VorbisSetupCache
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class VorbisSetupCacheTest {

    private fun identification(channels: Int = 1, rate: Int = 44_100, longBlock: Int = 2048) =
        VorbisIdentification(channels, rate, 0, 128_000, 0, 256, longBlock)

    private fun config(identification: VorbisIdentification, books: Array<VorbisCodebook> = emptyArray()) = VorbisConfig(
        identification.channels, identification.shortBlock, identification.longBlock,
        books, emptyArray(), emptyArray(), emptyArray(), emptyArray(),
    )

    /** Setup bytes no other test uses, so nothing kept by another test can answer for them. */
    private fun setup(): ByteArray = Random.nextBytes(64)

    /** Four two-bit codewords and no vectors, written the way a setup header stores a codebook. */
    private fun codebook(): VorbisCodebook {
        val bits = ArrayList<Boolean>()
        fun write(value: Int, count: Int) = repeat(count) { bits.add((value ushr it) and 1 == 1) }
        write(0x564342, 24)
        write(1, 16)
        write(4, 24)
        write(0, 2)
        repeat(4) { write(1, 5) }
        write(0, 4)
        val bytes = ByteArray((bits.size + 7) / 8)
        bits.forEachIndexed { i, bit -> if (bit) bytes[i / 8] = (bytes[i / 8].toInt() or (1 shl (i % 8))).toByte() }
        return VorbisCodebook.parse(VorbisBitReader(bytes), DecodeOptions.DEFAULT, 0L)
    }

    @Test
    fun streamsWithTheSameSetupShareOneParse() {
        val bytes = setup()
        var parses = 0
        val first = VorbisSetupCache.configFor(bytes, identification(), DecodeOptions.DEFAULT) {
            parses++
            config(identification())
        }
        // The second stream has its own copy of the bytes and another sample rate, which a setup does not depend on.
        val second = VorbisSetupCache.configFor(bytes.copyOf(), identification(rate = 22_050), DecodeOptions.DEFAULT) {
            parses++
            config(identification())
        }
        assertSame(first, second)
        assertEquals(1, parses)
    }

    @Test
    fun anotherChannelCountOrBlockSizeParsesAgain() {
        val bytes = setup()
        val mono = VorbisSetupCache.configFor(bytes, identification(), DecodeOptions.DEFAULT) { config(identification()) }
        val stereo = identification(channels = 2)
        assertNotSame(mono, VorbisSetupCache.configFor(bytes, stereo, DecodeOptions.DEFAULT) { config(stereo) })
        val longer = identification(longBlock = 4096)
        assertNotSame(mono, VorbisSetupCache.configFor(bytes, longer, DecodeOptions.DEFAULT) { config(longer) })
    }

    @Test
    fun aStricterAllocationCeilingIsNotHandedAKeptSetup() {
        val bytes = setup()
        val kept = VorbisSetupCache.configFor(bytes, identification(), DecodeOptions.DEFAULT) {
            config(identification(), arrayOf(codebook()))
        }
        assertTrue(kept.guardedBytes > 0, "the codebook is guarded")
        var parses = 0
        val strict = DecodeOptions(maxAllocationBytes = kept.guardedBytes - 1)
        VorbisSetupCache.configFor(bytes, identification(), strict) {
            parses++
            config(identification())
        }
        assertEquals(1, parses)
    }

    @Test
    fun aLargeSetupIsNotKept() {
        val bytes = setup()
        val book = codebook()
        val books = Array(1 + (1 shl 20) / book.retainedBytes.toInt()) { book }
        var parses = 0
        repeat(2) {
            VorbisSetupCache.configFor(bytes, identification(), DecodeOptions.DEFAULT) {
                parses++
                config(identification(), books)
            }
        }
        assertEquals(2, parses)
    }
}
