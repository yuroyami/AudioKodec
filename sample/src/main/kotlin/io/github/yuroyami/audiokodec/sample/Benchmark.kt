package io.github.yuroyami.audiokodec.sample

import io.github.yuroyami.audiokodec.AudioFormat
import io.github.yuroyami.audiokodec.AudioKodec
import io.github.yuroyami.audiokodec.DecodeOptions
import io.github.yuroyami.audiokodec.EncodeTarget
import io.github.yuroyami.audiokodec.Pcm
import io.github.yuroyami.audiokodec.ResampleQuality
import java.io.File
import kotlin.system.exitProcess

/**
 * Decode speed against the floors the library set for itself.
 *
 * A floor is a multiple of real time: 100 means that thirty seconds of audio
 * decode in at most 0.3 s. They are sanity floors meant to catch an
 * accidental quadratic loop, not marketing numbers. The library committed to
 * 100 times real time for MPEG audio, 200 for FLAC, 50 for Opus and 100 for
 * the resampler, on a laptop-class machine in a release build.
 *
 * Each coding decodes [SECONDS] seconds of [TestSignal] through the streaming
 * decoder with buffer reuse on, which is how a player uses it. A run is the
 * whole file; the first [WARMUPS] are thrown away so that the JIT has settled,
 * and the median of the next [RUNS] is reported. FLAC, ALAC and WAV are
 * encoded by the library; every other coding is encoded by ffmpeg, used as a
 * program, so a machine without it benchmarks fewer codings and says which.
 *
 * Arguments: `--enforce` exits with status 1 when a floor is missed,
 * `--seconds N` sets the length of the audio, `--runs N` the measured runs.
 */
internal const val SECONDS = 30
internal const val WARMUPS = 3
internal const val RUNS = 7

internal const val RATE = 44_100

/** A coding to decode, with the floor the library committed to for it, if any. */
internal class Subject(val name: String, val floor: Double?, val bytes: ByteArray)

/** What one subject measured. */
internal class Result(val subject: String, val floor: Double?, val audioSeconds: Double, val medianSeconds: Double, val bestSeconds: Double) {
    val multiple: Double get() = audioSeconds / medianSeconds
    val missed: Boolean get() = floor != null && multiple < floor
}

/** The median of [values], which are not empty. */
internal fun median(values: List<Double>): Double {
    val sorted = values.sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
}

/** Seconds [block] takes, from [warmups] discarded runs and the median of [runs] measured ones, and the best of those. */
internal fun time(warmups: Int, runs: Int, block: () -> Unit): Pair<Double, Double> {
    repeat(warmups) { block() }
    val seconds = List(runs) {
        val start = System.nanoTime()
        block()
        (System.nanoTime() - start) / 1e9
    }
    return median(seconds) to seconds.min()
}

/** Decodes every block of [bytes] as a player would and returns the frames decoded. */
internal fun decodeAll(bytes: ByteArray): Long {
    var frames = 0L
    AudioKodec.open(bytes, DecodeOptions(reuseBuffers = true)).use { decoder ->
        while (true) {
            val block = decoder.nextFrame() ?: break
            frames += block.frameCount
        }
    }
    return frames
}

/** A table in the form docs/support.md holds, one row to a measurement. */
internal fun table(results: List<Result>): String = buildString {
    appendLine("| Coding | Audio | Median | Best | Times real time | Floor |")
    appendLine("|---|---|---|---|---|---|")
    for (r in results) {
        val floor = r.floor?.let { "${it.toInt()}x" + if (r.missed) " MISSED" else "" } ?: "none"
        appendLine("| ${r.subject} | ${"%.1f".format(r.audioSeconds)} s | ${"%.0f".format(r.medianSeconds * 1000)} ms | ${"%.0f".format(r.bestSeconds * 1000)} ms | ${"%.0f".format(r.multiple)}x | $floor |")
    }
}

internal fun environment(ffmpeg: Ffmpeg?): String {
    val cpu = runCatching {
        File("/proc/cpuinfo").readLines().firstOrNull { it.startsWith("model name") }?.substringAfter(':')?.trim()
    }.getOrNull()
    return "${System.getProperty("os.name")} ${System.getProperty("os.arch")}, ${cpu ?: "unknown CPU"}, " +
        "${Runtime.getRuntime().availableProcessors()} processors, Java ${System.getProperty("java.version")} " +
        "(${System.getProperty("java.vendor")}), ffmpeg ${ffmpeg?.version ?: "not found"}"
}

private fun subjects(seconds: Int, cache: File, ffmpeg: Ffmpeg?): List<Subject> {
    cache.mkdirs()
    val stereo = TestSignal.stereo(seconds, RATE)
    val format = AudioFormat(RATE, 2)
    val list = ArrayList<Subject>()
    list += Subject("WAV 16-bit PCM", null, AudioKodec.encode(stereo, format, EncodeTarget.wav(16)))
    list += Subject("FLAC 16-bit", 200.0, AudioKodec.encode(stereo, format, EncodeTarget.flac(16)))
    list += Subject("ALAC 16-bit", null, AudioKodec.encode(stereo, format, EncodeTarget.alac(16)))
    if (ffmpeg == null) return list

    val raw = File(cache, "signal-${seconds}s-$RATE-2.f32")
    if (!raw.isFile || raw.length() != stereo.size * 4L) {
        val buffer = java.nio.ByteBuffer.allocate(stereo.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (v in stereo) buffer.putFloat(v)
        raw.writeBytes(buffer.array())
    }
    fun add(name: String, floor: Double?, file: String, vararg arguments: String) {
        ffmpeg.encode(raw, RATE, 2, File(cache, "$seconds-$file"), arguments.toList())?.let { list += Subject(name, floor, it) }
    }
    add("MPEG Layer II, 192 kbit/s", 100.0, "layer2.mp2", "-c:a", "mp2", "-b:a", "192k")
    add("MPEG Layer III, 128 kbit/s", 100.0, "layer3-128.mp3", "-c:a", "libmp3lame", "-b:a", "128k")
    add("MPEG Layer III, 320 kbit/s", 100.0, "layer3-320.mp3", "-c:a", "libmp3lame", "-b:a", "320k")
    add("Opus, 96 kbit/s", 50.0, "opus-96.opus", "-c:a", "libopus", "-b:a", "96k")
    add("Opus, 32 kbit/s", 50.0, "opus-32.opus", "-c:a", "libopus", "-b:a", "32k", "-application", "voip")
    add("AAC-LC, 128 kbit/s in MP4", null, "aac.m4a", "-c:a", "aac", "-b:a", "128k")
    add("Vorbis, quality 4", null, "vorbis.ogg", "-c:a", "libvorbis", "-q:a", "4")
    add("AC-3, 192 kbit/s", null, "ac3-stereo.ac3", "-c:a", "ac3", "-b:a", "192k")
    add(
        "AC-3 5.1, 448 kbit/s", null, "ac3-surround.ac3",
        "-af", "pan=5.1|FL=c0|FR=c1|FC=0.5*c0+0.5*c1|LFE=0.3*c0+0.3*c1|BL=c0|BR=c1", "-c:a", "ac3", "-b:a", "448k",
    )
    add("WavPack", null, "wavpack.wv", "-c:a", "wavpack")
    add("True Audio", null, "tta.tta", "-c:a", "tta")
    return list
}

fun main(args: Array<String>) {
    val enforce = "--enforce" in args
    fun option(name: String, default: Int) = args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1)?.toIntOrNull() } ?: default
    val seconds = option("--seconds", SECONDS)
    val runs = option("--runs", RUNS)
    val cache = File(System.getProperty("audiokodec.benchmark.cache") ?: "build/benchmark-inputs")
    val ffmpeg = Ffmpeg.find()
    if (ffmpeg == null) System.err.println("ffmpeg was not found on PATH, so only the codings the library encodes are measured")

    val results = ArrayList<Result>()
    for (subject in subjects(seconds, cache, ffmpeg)) {
        var frames = 0L
        val (median, best) = time(WARMUPS, runs) { frames = decodeAll(subject.bytes) }
        val info = AudioKodec.probe(subject.bytes)
        results += Result(subject.name, subject.floor, frames.toDouble() / info.format.sampleRate, median, best)
    }

    val stereo = TestSignal.stereo(seconds, RATE)
    for (quality in ResampleQuality.entries) {
        for ((from, to) in listOf(44_100 to 48_000, 48_000 to 44_100, 44_100 to 96_000)) {
            val (median, best) = time(WARMUPS, runs) { Pcm.resample(stereo, 2, from, to, quality) }
            results += Result("Resampler ${from / 1000.0} to ${to / 1000.0} kHz, ${quality.name.lowercase()}", 100.0, seconds.toDouble(), median, best)
        }
    }

    println(environment(ffmpeg))
    println("$seconds seconds of audio, $WARMUPS warm-up runs and the median of $runs")
    println()
    print(table(results))
    val missed = results.filter { it.missed }
    if (missed.isNotEmpty()) {
        println()
        println("Below the floor: ${missed.joinToString { it.subject }}")
        if (enforce) exitProcess(1)
    }
}
