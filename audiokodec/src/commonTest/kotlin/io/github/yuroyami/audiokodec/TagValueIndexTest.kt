package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.internal.tag.TagValueIndex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TagValueIndexTest {
    @Test
    fun orderedReversedAndPermutedInputKeepLogarithmicComparisonBounds() {
        val count = 16_384
        val values = List(count) { it.toString().padStart(6, '0') }
        val orders = listOf(values, values.reversed(), List(count) { values[(it * 73) and (count - 1)] })
        for (order in orders) checkIndex(order, values)
    }

    @Test
    fun collidingStringHashesDoNotChangeMembershipOrComparisonBounds() {
        val values = List(8_192) { TagStressFixtures.collidingText(it) }
        val hash = values.first().hashCode()
        assertTrue(values.all { it.hashCode() == hash })
        checkIndex(values, values)
    }

    @Test
    fun duplicateAndAbsentKeysAgreeWithAnIndependentSmallReference() {
        val index = TagValueIndex<Int>(naturalOrder())
        val expected = mutableSetOf<Int>()
        var state = 1234
        repeat(10_000) {
            state = state * 1_103_515_245 + 12_345
            val key = (state ushr 8) and 1023
            assertEquals(expected.add(key), index.add(key))
            assertEquals(expected.size, index.size)
        }
        for (key in -1..1024) assertEquals(key in expected, key in index)
    }

    @Test
    fun compoundIdentitiesCompareBothPartsWithoutMergingSimilarSpellings() {
        val index = TagValueIndex(compareBy<Pair<String, String>> { it.first }.thenBy { it.second })
        val identities = listOf("eng" to "", "fra" to "", "eng" to "2", "und" to "2", "eng" to "Aa", "eng" to "BB")
        for (identity in identities) assertTrue(index.add(identity))
        for (identity in identities) {
            assertTrue(identity in index)
            assertFalse(index.add(identity))
        }
        assertFalse(("ENG" to "2") in index)
        assertFalse(("eng" to "3") in index)
        assertEquals(identities.size, index.size)
    }

    @Test
    fun anAllocationRefusalLeavesMembershipAndSizeUnchanged() {
        val allocations = mutableListOf<Int>()
        val index = TagValueIndex<Int>(naturalOrder()) { count ->
            if (count > 2) throw AllocationGuardException("a tag index", count * 64L, 128)
            allocations.add(count)
        }
        assertTrue(index.add(1))
        assertTrue(index.add(2))
        assertFalse(index.add(1))
        assertFailsWith<AllocationGuardException> { index.add(3) }
        assertEquals(listOf(1, 2), allocations)
        assertEquals(2, index.size)
        assertTrue(1 in index)
        assertTrue(2 in index)
        assertFalse(3 in index)
    }

    private fun checkIndex(order: List<String>, expected: List<String>) {
        var comparisons = 0
        val index = TagValueIndex<String>(Comparator { left, right ->
            comparisons++
            left.compareTo(right)
        })
        // AVL height is below twice log2(n + 1). Count comparisons instead of
        // wall time, so load on the test host cannot hide a quadratic scan.
        var bits = 0
        var bound = 1
        while (bound <= expected.size) { bits++; bound *= 2 }
        val maximum = 2 * bits
        for (value in order) {
            comparisons = 0
            assertTrue(index.add(value))
            assertTrue(comparisons <= maximum, "insertion used $comparisons comparisons")
        }
        assertEquals(expected.size, index.size)
        for (value in expected) {
            comparisons = 0
            assertTrue(value in index)
            assertTrue(comparisons <= maximum, "lookup used $comparisons comparisons")
            comparisons = 0
            assertFalse(index.add(value))
            assertTrue(comparisons <= maximum, "duplicate used $comparisons comparisons")
        }
        comparisons = 0
        assertFalse("missing" in index)
        assertTrue(comparisons <= maximum)
    }
}
