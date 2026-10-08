package io.github.yuroyami.audiokodec

import java.io.File
import java.io.InputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Runs the official Opus conformance vectors: the set published with
 * RFC 6716 and replaced by RFC 8251, scored with the RFC's own quality
 * measure ([OpusCompare]) exactly as libopus's `tests/run_vectors.sh` does.
 *
 * Each of the twelve vectors is decoded at every output rate, in mono and in
 * stereo, and has to pass against either of its two references, the stereo
 * decode or the mono one. Every packet's final range is also checked against
 * the encoder's.
 *
 * The vectors are not committed: they carry no licence of their own, and
 * their references run to about 120 MB. The test looks for them in
 * `reference/opus_newvectors`, then for the published archive in
 * `reference/` or in the build directory, and failing those downloads the
 * archive into the build directory, checking its SHA-256. Without any of
 * them it skips itself, the way the oracle suites do without their tools.
 */
class OpusConformanceTest {

    @Test
    fun everyVectorPassesAtEveryRateInMonoAndStereo() {
        val vectors = vectorDirectory() ?: return skip()
        val failures = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val qualities = java.util.concurrent.ConcurrentHashMap<String, Double>()
        // The vectors are independent, and each takes a few seconds, so
        // they run side by side.
        (1..12).map { "%02d".format(it) }.parallelStream().forEach { number ->
            val bits = File(vectors, "testvector$number.bit").readBytes()
            val stereoReference = shorts(File(vectors, "testvector$number.dec"))
            val monoReference = shorts(File(vectors, "testvector${number}m.dec"))
            for (channels in intArrayOf(1, 2)) {
                val references = listOf(
                    OpusCompare.Reference(stereoReference, channels),
                    OpusCompare.Reference(monoReference, channels),
                )
                for (rate in intArrayOf(48_000, 24_000, 16_000, 12_000, 8_000)) {
                    val what = "test vector $number, $channels ch at $rate Hz"
                    val decoded = OpusDemoDecoding.decode(bits, rate, channels, what)
                    val results = references.map { OpusCompare.compare(it, decoded, rate) }
                    val best = results.maxBy { it.quality }
                    if (!best.passes) failures += "$what: $best"
                    qualities["$number/$channels/$rate"] = best.quality
                }
            }
        }
        for (channels in intArrayOf(1, 2)) {
            val average = qualities.filterKeys { it.split('/')[1] == "$channels" && it.endsWith("/48000") }.values.average()
            println("Opus conformance: average ${if (channels == 1) "mono" else "stereo"} quality at 48 kHz is %.1f %%".format(average))
        }
        assertTrue(failures.isEmpty(), "conformance vectors failed:\n${failures.joinToString("\n")}")
    }

    /** Finds or fetches the vectors, or returns null when they cannot be had. */
    private fun vectorDirectory(): File? {
        val module = File(System.getProperty("user.dir"))
        val roots = listOf(module, module.parentFile)
        roots.map { File(it, "reference/opus_newvectors") }.firstOrNull { complete(it) }?.let { return it }
        val cache = File(module, "build/opus-testvectors")
        File(cache, "opus_newvectors").takeIf { complete(it) }?.let { return it }
        val archive = roots.map { File(it, "reference/$ARCHIVE") }.firstOrNull { it.isFile && sha256(it) == ARCHIVE_SHA256 }
            ?: download(File(cache, ARCHIVE))
            ?: return null
        archive.inputStream().use { untar(GZIPInputStream(it), cache) }
        return File(cache, "opus_newvectors").takeIf { complete(it) }
    }

    private fun complete(directory: File): Boolean =
        (1..12).all { n ->
            val number = "%02d".format(n)
            listOf("testvector$number.bit", "testvector$number.dec", "testvector${number}m.dec").all { File(directory, it).isFile }
        }

    private fun download(target: File): File? {
        if (target.isFile && sha256(target) == ARCHIVE_SHA256) return target
        return try {
            target.parentFile.mkdirs()
            val connection = URI(ARCHIVE_URL).toURL().openConnection()
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            val partial = File(target.path + ".part")
            connection.getInputStream().use { input -> partial.outputStream().use { input.copyTo(it) } }
            if (sha256(partial) != ARCHIVE_SHA256) {
                partial.delete()
                println("the downloaded Opus test vectors did not match their checksum")
                return null
            }
            partial.renameTo(target)
            target
        } catch (failure: java.io.IOException) {
            println("the Opus test vectors could not be downloaded: ${failure.message}")
            null
        }
    }

    /** Unpacks the regular files of a tar stream into [into], which is all the archive holds. */
    private fun untar(input: InputStream, into: File) {
        val header = ByteArray(512)
        while (true) {
            if (input.readNBytes(header, 0, 512) < 512 || header.all { it.toInt() == 0 }) return
            val name = String(header, 0, 100, Charsets.US_ASCII).trimEnd('\u0000')
            val size = String(header, 124, 12, Charsets.US_ASCII).trim('\u0000', ' ').toLong(8)
            val type = header[156].toInt().toChar()
            val padded = (size + 511) / 512 * 512
            if ((type == '0' || type == '\u0000') && !name.contains("..")) {
                val file = File(into, name)
                file.parentFile.mkdirs()
                file.outputStream().use { out ->
                    var left = size
                    val buffer = ByteArray(65_536)
                    while (left > 0) {
                        val read = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                        if (read < 0) throw java.io.EOFException("the test vector archive ends inside $name")
                        out.write(buffer, 0, read)
                        left -= read
                    }
                }
                input.skipNBytes(padded - size)
            } else {
                input.skipNBytes(padded)
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65_536)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun shorts(file: File): ShortArray {
        val buffer = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        return ShortArray(buffer.remaining() / 2) { buffer.short }
    }

    private fun skip() {
        org.junit.Assume.assumeTrue("the Opus conformance vectors are not available, so the conformance run did not happen", false)
    }

    private companion object {
        const val ARCHIVE = "opus_testvectors-rfc8251.tar.gz"
        const val ARCHIVE_URL = "https://opus-codec.org/static/testvectors/$ARCHIVE"
        const val ARCHIVE_SHA256 = "6b26a22f9ba87b2b836906a9bb7afec5f8e54d49553b1200382520ee6fedfa55"
    }
}
