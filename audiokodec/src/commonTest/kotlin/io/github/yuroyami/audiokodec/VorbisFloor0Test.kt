package io.github.yuroyami.audiokodec

import io.github.yuroyami.audiokodec.internal.vorbis.VorbisBitReader
import io.github.yuroyami.audiokodec.internal.vorbis.VorbisCodebook
import io.github.yuroyami.audiokodec.internal.vorbis.VorbisFloor
import io.github.yuroyami.audiokodec.tag.TagField
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VorbisFloor0Test {
    @Test
    fun aLargeUnexpandedVectorOnlyCopiesTheRequestedCoefficientPrefix() {
        val bits = VorbisFloor0Fixtures.Bits()
        VorbisFloor0Fixtures.writeBook(bits, 65535, 2, 1, 0.0, 0.25, intArrayOf(1))
        val book = VorbisCodebook.parse(VorbisBitReader(bits.bytes()), DecodeOptions.DEFAULT, 0)
        val floor = VorbisFloor.Floor0(4, 8000, 32, 4, 30, intArrayOf(0))
        val state = VorbisFloor.FloorState(65, 4)
        val packet = VorbisFloor0Fixtures.Bits().write(3, 4).write(0, 1).write(0, 1).bytes()
        assertTrue(floor.decode(VorbisBitReader(packet), arrayOf(book), state))
        assertContentEquals(FloatArray(4) { 0.25f }, state.lsp)
    }

    @Test
    fun bothLookupTypesAccumulateVectorsAndDiscardUnusedFinalValues() {
        for (lookup in 1..2) {
            val book = VorbisCodebook.parse(VorbisBitReader(VorbisFloor0Fixtures.floorBook(4, lookup, 0.25)), DecodeOptions.DEFAULT, 0)
            val floor = VorbisFloor.Floor0(4, 8000, 32, 4, 30, intArrayOf(0))
            val bits = VorbisFloor0Fixtures.Bits().write(3, 4).write(0, 1)
                .write(0, if (lookup == 1) 3 else 1).write(0, if (lookup == 1) 3 else 1)
            val state = VorbisFloor.FloorState(65, 4)
            assertTrue(floor.decode(VorbisBitReader(bits.bytes()), arrayOf(book), state))
            assertContentEquals(floatArrayOf(0.25f, 0.5f, 0.75f, 1f), state.lsp)
            assertEquals(3.0, state.amplitude)
            assertFalse(floor.decode(VorbisBitReader(byteArrayOf(0)), arrayOf(book), state))
            val curve = FloatArray(32) { 17f }
            floor.synthesise(state, curve, curve.size)
            assertTrue(curve.all { it == 0f })
        }
    }

    @Test
    fun theCurveAgreesWithTheDirectPublishedProductsAcrossBarkMaps() {
        for (order in listOf(1, 2, 3, 4, 10, 31, 32, 255)) {
            for (rate in listOf(8000, 48000)) for (mapSize in listOf(1, 32, 63, 300)) {
                val floor = VorbisFloor.Floor0(order, rate, mapSize, 4, 30, intArrayOf(0))
                val state = VorbisFloor.FloorState(65, order)
                state.amplitude = 3.0
                for (index in 0 until order) {
                    state.lsp[index] = (PI * (index + 1 + 0.03 * sin(index.toDouble())) / (order + 1)).toFloat()
                }
                for (half in listOf(32, 256, 4096)) {
                    val actual = FloatArray(half)
                    floor.synthesise(state, actual, half)
                    for (bin in 0 until half) {
                        val expected = directCurve(state.lsp, rate, mapSize, half, bin, 3.0 / 15 * 30, 30)
                        assertTrue(abs(actual[bin] - expected) <= maxOf(1e-6, abs(expected) * 2e-6),
                            "$order/$rate/$mapSize/$half/$bin: ${actual[bin]} versus $expected")
                    }
                }
            }
        }
    }

    @Test
    fun amplitudeFieldsUseUnsignedWordsAndZeroOffsetStillProducesUnity() {
        val book = VorbisCodebook.parse(VorbisBitReader(VorbisFloor0Fixtures.floorBook(1, 1)), DecodeOptions.DEFAULT, 0)
        for (width in listOf(0, 1, 32, 33, 63)) {
            val value = when (width) { 0 -> 0L; 1 -> 1L; 32 -> 0xFFFF_FFFFL; 33 -> 0x1_0000_001BL; else -> Long.MAX_VALUE }
            val floor = VorbisFloor.Floor0(1, 8000, 32, width, 0, intArrayOf(0))
            val state = VorbisFloor.FloorState(65, 1)
            val bytes = VorbisFloor0Fixtures.Bits().write(value, width).write(0, 1).write(0, 3).bytes()
            assertEquals(width > 0, floor.decode(VorbisBitReader(bytes), arrayOf(book), state))
            assertEquals(value.toDouble(), state.amplitude)
            val curve = FloatArray(32)
            floor.synthesise(state, curve, curve.size)
            assertTrue(curve.all { it == if (width == 0) 0f else 1f })
        }
    }

    @Test
    fun truncatedPacketsAreUnusedAndReservedSelectorsAreCorruption() {
        val book = VorbisCodebook.parse(VorbisBitReader(VorbisFloor0Fixtures.floorBook(4, 1)), DecodeOptions.DEFAULT, 0)
        val floor = VorbisFloor.Floor0(4, 8000, 32, 4, 30, intArrayOf(0))
        val state = VorbisFloor.FloorState(65, 4)
        assertFalse(floor.decode(VorbisBitReader(ByteArray(0)), arrayOf(book), state))
        assertFalse(floor.decode(VorbisBitReader(byteArrayOf(3)), arrayOf(book), state))
        assertEquals(0.0, state.amplitude)
        assertFailsWith<CorruptStreamException> {
            floor.decode(VorbisBitReader(VorbisFloor0Fixtures.Bits().write(3, 4).write(1, 1).bytes()), arrayOf(book), state)
        }
        val wide = VorbisFloor.Floor0(4, 8000, 32, 63, 30, intArrayOf(0))
        assertFalse(wide.decode(VorbisBitReader(byteArrayOf(1)), arrayOf(book), state))
    }

    @Test
    fun setupRejectsUndefinedGeometryAndScalarBooksAndChecksScratchLimits() {
        for (bytes in listOf(VorbisFloor0Fixtures.file(order = 0), VorbisFloor0Fixtures.file(floorRate = 0),
            VorbisFloor0Fixtures.file(barkSize = 0), VorbisFloor0Fixtures.file(scalarFloorBook = true))) {
            assertFailsWith<CorruptStreamException> { AudioKodec.open(bytes).close() }
        }
        assertFailsWith<AllocationGuardException> {
            AudioKodec.open(VorbisFloor0Fixtures.file(order = 255, channels = 2), DecodeOptions(maxAllocationBytes = 6000)).close()
        }
    }

    @Test
    fun fullStreamsHaveDurationAndExactSeekingWithEitherSourceSizeAndBufferPolicy() {
        for (lookup in 1..2) for (order in listOf(3, 4, 10)) for (channels in 1..2) for (block in listOf(64, 128)) {
            val bytes = VorbisFloor0Fixtures.file(order, lookup, channels, block)
            val whole = AudioKodec.decode(bytes)
            assertEquals(5 * block / 2, whole.frames)
            assertEquals(whole.frames.toLong(), whole.info.durationFrames)
            assertTrue(whole.samples.all { it.isFinite() })
            assertTrue(whole.samples.any { abs(it) > 1e-4 })
            for (known in listOf(false, true)) for (reuse in listOf(false, true)) {
                val source = OggDamageFixtures.source(bytes, known)
                assertContentEquals(whole.samples, AudioKodec.decode(source).samples)
                AudioKodec.open(source, DecodeOptions(reuseBuffers = reuse, allowSlowSeek = false)).use { decoder ->
                    for (target in listOf(whole.frames / 2L + 1, 0L, whole.frames - 1L)) {
                        assertEquals(target, decoder.seek(target))
                        val frame = assertNotNull(decoder.nextFrame())
                        assertEquals(target, frame.positionFrames)
                        TestSignals.assertClose(whole.samples.copyOfRange(target.toInt() * channels,
                            target.toInt() * channels + frame.sampleCount), frame.samples.copyOf(frame.sampleCount), 1e-4f, "floor 0 seek")
                    }
                    assertEquals(whole.frames.toLong(), decoder.seek(Long.MAX_VALUE))
                    assertNull(decoder.nextFrame())
                    assertFailsWith<IllegalArgumentException> { decoder.seek(-1) }
                    assertEquals(whole.frames.toLong(), decoder.positionFrames)
                }
            }
        }
    }

    @Test
    fun silentFloorsAndTagEditsKeepTheCodedAudioAndSamples() {
        assertTrue(AudioKodec.decode(VorbisFloor0Fixtures.file(silent = true)).samples.all { it == 0f })
        val bytes = VorbisFloor0Fixtures.file(lookup = 2, channels = 2)
        val edited = AudioKodec.writeTags(bytes, AudioKodec.readTags(bytes).edit { set(TagField.TITLE, "Floor zero") })
        assertEquals("Floor zero", AudioKodec.readTags(edited)[TagField.TITLE])
        val original = VorbisFloor0Fixtures.audioPackets(bytes)
        val rewritten = VorbisFloor0Fixtures.audioPackets(edited)
        assertEquals(original.size, rewritten.size)
        for (index in original.indices) assertContentEquals(original[index], rewritten[index])
        assertContentEquals(AudioKodec.decode(bytes).samples, AudioKodec.decode(edited).samples)
    }

    /** Literal odd/even products in section 6.2.3, with no shared synthesis helper. */
    private fun directCurve(coefficients: FloatArray, rate: Int, size: Int, half: Int, bin: Int,
                            amplitude: Double, offset: Int): Double {
        fun bark(f: Double) = 13.1 * atan(0.00074 * f) + 2.24 * atan(0.0000000185 * f.pow(2)) + 0.0001 * f
        val map = minOf(size - 1, (bark(rate.toDouble() * bin / (2 * half)) / bark(rate * 0.5) * size).toInt())
        val w = cos(PI * map / size)
        var p = 1.0
        var q = 1.0
        for (index in coefficients.indices) {
            val factor = 4.0 * (cos(coefficients[index].toDouble()) - w).pow(2)
            if (index % 2 == 0) q *= factor else p *= factor
        }
        if (coefficients.size % 2 == 1) {
            p *= 1.0 - w * w
            q *= 0.25
        } else {
            p *= (1.0 - w) / 2
            q *= (1.0 + w) / 2
        }
        return exp(0.11512925 * (amplitude / sqrt(p + q) - offset))
    }
}
