package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.internal.io.Inflate
import io.github.yuroyami.audiokodec.internal.tag.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

/**
 * Checks the inflater Matroska's zlib content compression uses, on streams
 * Python's zlib wrote: a stored block, fixed Huffman codes, and, through
 * the Matroska tests, dynamic ones.
 */
class InflateTest {

    private val text = "Hello, Matroska. ".repeat(40).encodeToByteArray()

    @Test
    fun storedAndFixedBlocksInflateToTheirInput() {
        for (stream in listOf(STORED, FIXED_FAST, FIXED_BEST)) {
            val bytes = Base64.decodeOrNull(stream)!!
            assertContentEquals(text, Inflate.zlib(bytes, 0, bytes.size, 1 shl 20))
        }
    }

    @Test
    fun damageAndExcessAreRefused() {
        val bytes = Base64.decodeOrNull(FIXED_BEST)!!
        // The Adler-32 no longer matches.
        assertFailsWith<CorruptStreamException> { Inflate.zlib(bytes.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }, 0, bytes.size, 1 shl 20) }
        // The header's check bits are wrong.
        assertFailsWith<CorruptStreamException> { Inflate.zlib(bytes.copyOf().also { it[1] = 0 }, 0, bytes.size, 1 shl 20) }
        // The stream is cut short.
        assertFailsWith<CorruptStreamException> { Inflate.zlib(bytes, 0, bytes.size - 8, 1 shl 20) }
        // More output than the limit allows.
        assertFailsWith<CorruptStreamException> { Inflate.zlib(bytes, 0, bytes.size, 100) }
    }

    private companion object {
        const val STORED =
            "eAEBqAJX/UhlbGxvLCBNYXRyb3NrYS4gSGVsbG8sIE1hdHJvc2thLiBIZWxsbywgTWF0cm9za2EuIEhlbGxvLCBNYXRyb3NrYS4gSGVsbG8sIE1hdHJvc2th" +
                "LiBIZWxsbywgTWF0cm9za2EuIEhlbGxvLCBNYXRyb3NrYS4gSGVsbG8sIE1hdHJvc2thLiBIZWxsbywgTWF0cm9za2EuIEhlbGxvLCBNYXRyb3NrYS4gSGVs" +
                "bG8sIE1hdHJvc2thLiBIZWxsbywgTWF0cm9za2EuIEhlbGxvLCBNYXRyb3NrYS4gSGVsbG8sIE1hdHJvc2thLiBIZWxsbywgTWF0cm9za2EuIEhlbGxvLCBN" +
                "YXRyb3NrYS4gSGVsbG8sIE1hdHJvc2thLiBIZWxsbywgTWF0cm9za2EuIEhlbGxvLCBNYXRyb3NrYS4gSGVsbG8sIE1hdHJvc2thLiBIZWxsbywgTWF0cm9z" +
                "a2EuIEhlbGxvLCBNYXRyb3NrYS4gSGVsbG8sIE1hdHJvc2thLiBIZWxsbywgTWF0cm9za2EuIEhlbGxvLCBNYXRyb3NrYS4gSGVsbG8sIE1hdHJvc2thLiBI" +
                "ZWxsbywgTWF0cm9za2EuIEhlbGxvLCBNYXRyb3NrYS4gSGVsbG8sIE1hdHJvc2thLiBIZWxsbywgTWF0cm9za2EuIEhlbGxvLCBNYXRyb3NrYS4gSGVsbG8s" +
                "IE1hdHJvc2thLiBIZWxsbywgTWF0cm9za2EuIEhlbGxvLCBNYXRyb3NrYS4gSGVsbG8sIE1hdHJvc2thLiBIZWxsbywgTWF0cm9za2EuIEhlbGxvLCBNYXRy" +
                "b3NrYS4gSGVsbG8sIE1hdHJvc2thLiBIZWxsbywgTWF0cm9za2EuIEhlbGxvLCBNYXRyb3NrYS4giqPogQ=="
        const val FIXED_FAST = "eAHzSM3JyddR8E0sKcovzk7UU/AYFRgNj8GXHACKo+iB"
        const val FIXED_BEST = "eNrzSM3JyddR8E0sKcovzk7UU/AYFRgVGHwCAIqj6IE="
    }
}
