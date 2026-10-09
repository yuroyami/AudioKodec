package io.github.yuroyami.audiokodec.sample

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * ffmpeg as a program, for the codings the library cannot encode. The inputs
 * are generated once from [TestSignal] and kept in a cache directory; a
 * machine without ffmpeg simply benchmarks fewer codings.
 */
internal class Ffmpeg private constructor(private val executable: String, val version: String) {

    /** The file ffmpeg writes from the raw 32-bit float [source] with [arguments] after `-i`, or null if it fails. */
    fun encode(source: File, rate: Int, channels: Int, destination: File, arguments: List<String>): ByteArray? {
        if (destination.isFile && destination.length() > 0) return destination.readBytes()
        val command = listOf(executable, "-y", "-v", "error", "-f", "f32le", "-ar", rate.toString(), "-ac", channels.toString(), "-i", source.path) +
            arguments + destination.path
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(300, TimeUnit.SECONDS) || process.exitValue() != 0) {
            System.err.println("ffmpeg could not write ${destination.name}: ${output.trim().lineSequence().firstOrNull()}")
            destination.delete()
            return null
        }
        return destination.readBytes()
    }

    companion object {
        fun find(): Ffmpeg? {
            val path = System.getenv("PATH") ?: return null
            for (directory in path.split(File.pathSeparator)) {
                val candidate = File(directory, "ffmpeg")
                if (!candidate.canExecute()) continue
                val process = ProcessBuilder(candidate.path, "-version").redirectErrorStream(true).start()
                val first = process.inputStream.bufferedReader().readLine().orEmpty()
                process.waitFor(30, TimeUnit.SECONDS)
                return Ffmpeg(candidate.path, first.removePrefix("ffmpeg version ").substringBefore(' '))
            }
            return null
        }
    }
}
