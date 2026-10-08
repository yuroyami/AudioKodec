package io.github.yuroyami.audiokodec

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Test-only provenance, with deterministic Ogg serials in encoder fixtures. */
internal fun ProcessBuilder.oracleStart(): Process {
    val command = command()
    if (File(command.first()).name == "ffmpeg" &&
        (File(command.last()).extension.lowercase() in listOf("ogg", "opus", "oga", "ogv", "spx") ||
            command.windowed(2).any { it == listOf("-f", "ogg") })
    ) {
        // The output format flag fixes random Ogg serials and suppresses
        // build-specific muxer metadata without changing codec arithmetic.
        command(command.dropLast(1) + listOf("-fflags", "+bitexact", command.last()))
    } else if (File(command.first()).name == "flac" && "--ogg" in command &&
        "--decode" !in command && "-d" !in command && command.none { it.startsWith("--serial-number") }
    ) {
        command(listOf(command.first(), "--serial-number=0") + command.drop(1))
    }
    return OracleRecording.start(this, System.getenv("AUDIOKODEC_FIXTURE_RECORDING_DIR")?.let(::File))
}

internal object OracleRecording {
    private val sequence = AtomicLong()
    private val tools = HashMap<String, Map<String, Any>>()

    fun start(builder: ProcessBuilder, directory: File?): Process {
        if (directory == null) return builder.start()
        directory.mkdirs()
        runtime(directory)
        val command = builder.command().toList()
        val working = (builder.directory() ?: File(System.getProperty("user.dir"))).absoluteFile
        val executable = resolve(command.first(), working, builder.environment()["PATH"].orEmpty())
        val tool = identity(executable)
        val id = "${ProcessHandle.current().pid()}-${sequence.incrementAndGet()}"
        val journal = File(directory, "processes-${ProcessHandle.current().pid()}.jsonl")
        val files = (command.drop(1).map { File(it).let { file ->
            if (file.isAbsolute) file else File(working, it)
        } } + listOfNotNull(builder.redirectInput().file(), builder.redirectOutput().file(),
            builder.redirectError().file())).distinctBy { it.absolutePath }
        val before = snapshot(files)
        val caller = Thread.currentThread().stackTrace.filter {
            it.className.startsWith("io.github.yuroyami.audiokodec.") && it.fileName != "OracleProcess.kt"
        }.map { "${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})" }
        write(journal, mapOf("event" to "start", "id" to id, "argv" to command,
            "workingDirectory" to working.path, "tool" to tool, "caller" to caller,
            "redirectErrorStream" to builder.redirectErrorStream(),
            "stdin" to builder.redirectInput().toString(), "stdout" to builder.redirectOutput().toString(),
            "stderr" to builder.redirectError().toString(), "filesBefore" to before))
        val process = try {
            builder.start()
        } catch (failure: Exception) {
            write(journal, mapOf("event" to "startFailure", "id" to id,
                "exception" to failure.javaClass.name, "message" to failure.message))
            throw failure
        }
        return RecordedProcess(process, journal, id, files)
    }

    private fun resolve(name: String, working: File, path: String): File {
        val file = File(name)
        if (file.isAbsolute) return file
        if (name.contains(File.separatorChar)) return File(working, name).absoluteFile
        return path.split(File.pathSeparator).map { File(it, name).absoluteFile }
            .firstOrNull { it.isFile && it.canExecute() } ?: File(working, name).absoluteFile
    }

    @Synchronized
    private fun identity(executable: File): Map<String, Any> = tools.getOrPut(executable.path) {
        val name = executable.name
        val arguments = when {
            name == "ffmpeg" || name == "ffprobe" -> listOf("-version")
            name == "exiftool" -> listOf("-ver")
            name == "afconvert" -> listOf("-h")
            name.startsWith("python") -> listOf("-c", """
                import platform
                print('Python ' + platform.python_version())
                try:
                    import mutagen
                    print('Mutagen ' + mutagen.version_string)
                except ImportError:
                    print('Mutagen unavailable')
            """.trimIndent())
            name in listOf("flac", "metaflac", "lame", "cc", "c++", "gcc", "clang", "clang++") -> listOf("--version")
            else -> emptyList()
        }
        val version = if (arguments.isEmpty()) {
            "Compiled reference or test helper: identify by executable SHA-256 and the recorded build recipe"
        } else {
            val output = File.createTempFile("audiokodec-tool-version-", ".txt")
            try {
                val process = ProcessBuilder(listOf(executable.path) + arguments)
                    .redirectErrorStream(true).redirectOutput(output).start()
                check(process.waitFor(15, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    "version query timed out for $executable"
                }
                output.readText().trim()
            } finally {
                output.delete()
            }
        }
        mapOf("path" to executable.path, "sha256" to if (executable.isFile) sha256(executable) else "missing",
            "versionArgv" to if (arguments.isEmpty()) emptyList() else listOf(executable.path) + arguments,
            "version" to version)
    }

    private fun snapshot(files: List<File>): List<Map<String, Any>> = files.filter { it.isFile }.map {
        mapOf("path" to it.absolutePath, "bytes" to it.length(), "sha256" to sha256(it))
    }

    internal fun sha256(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65_536)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        hex(digest.digest())
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    @Synchronized
    private fun runtime(directory: File) {
        val file = File(directory, "runtime-${ProcessHandle.current().pid()}.json")
        if (!file.exists()) file.writeText(json(mapOf("javaVersion" to System.getProperty("java.version"),
            "javaVendor" to System.getProperty("java.vendor"), "vmVersion" to System.getProperty("java.vm.version"),
            "kotlinVersion" to KotlinVersion.CURRENT.toString(), "architecture" to System.getProperty("os.arch"))) + "\n")
    }

    @Synchronized
    private fun write(journal: File, record: Map<String, Any?>) {
        journal.appendText(json(record) + "\n", Charsets.UTF_8)
    }

    private fun json(value: Any?): String = when (value) {
        null -> "null"
        is String -> buildString {
            append('"')
            for (character in value) when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                else -> if (character.code in 32..126) append(character)
                    else append("\\u%04x".format(character.code))
            }
            append('"')
        }
        is Number, is Boolean -> value.toString()
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { json(it.key.toString()) + ":" + json(it.value) }
        is Iterable<*> -> value.joinToString(",", "[", "]") { json(it) }
        else -> error("unsupported provenance value: ${value.javaClass}")
    }

    private class RecordedProcess(
        private val process: Process,
        private val journal: File,
        private val id: String,
        private val files: List<File>,
    ) : Process() {
        private val finished = AtomicBoolean()

        private fun pipe(name: String, digest: MessageDigest, count: Long, terminal: String) {
            write(journal, mapOf("event" to "pipe", "id" to id, "stream" to name,
                "bytes" to count, "sha256" to hex(digest.digest()), "terminal" to terminal))
        }

        private fun input(stream: InputStream, name: String): InputStream = object : InputStream() {
            private val digest = MessageDigest.getInstance("SHA-256")
            private var count = 0L
            private var recorded = false
            private fun complete(terminal: String) {
                if (!recorded) { recorded = true; pipe(name, digest, count, terminal) }
            }
            override fun read(): Int = stream.read().also {
                if (it < 0) complete("eof") else { digest.update(it.toByte()); count++ }
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = stream.read(buffer, offset, length).also {
                if (it < 0) complete("eof") else { digest.update(buffer, offset, it); count += it }
            }
            override fun available(): Int = stream.available()
            override fun close() { try { stream.close() } finally { complete("closed") } }
        }

        private val stdout = input(process.inputStream, "stdout")
        private val stderr = input(process.errorStream, "stderr")
        private val stdin = object : OutputStream() {
            private val digest = MessageDigest.getInstance("SHA-256")
            private var count = 0L
            private var recorded = false
            override fun write(value: Int) { process.outputStream.write(value); digest.update(value.toByte()); count++ }
            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                process.outputStream.write(buffer, offset, length); digest.update(buffer, offset, length); count += length
            }
            override fun flush() = process.outputStream.flush()
            override fun close() {
                try { process.outputStream.close() } finally {
                    if (!recorded) { recorded = true; pipe("stdin", digest, count, "closed") }
                }
            }
        }

        private fun complete() {
            if (finished.compareAndSet(false, true)) {
                write(journal, mapOf("event" to "finish", "id" to id, "exitCode" to process.exitValue(),
                    "filesAfter" to snapshot(files)))
            }
        }

        override fun getInputStream(): InputStream = stdout
        override fun getErrorStream(): InputStream = stderr
        override fun getOutputStream(): OutputStream = stdin
        override fun waitFor(): Int = process.waitFor().also { complete() }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = process.waitFor(timeout, unit).also { if (it) complete() }
        override fun exitValue(): Int = process.exitValue().also { complete() }
        override fun destroy() = process.destroy()
        override fun destroyForcibly(): Process { process.destroyForcibly(); return this }
        override fun isAlive(): Boolean = process.isAlive
        override fun pid(): Long = process.pid()
        override fun toHandle(): ProcessHandle = process.toHandle()
        override fun onExit(): CompletableFuture<Process> = process.onExit().thenApply { complete(); this }
        override fun supportsNormalTermination(): Boolean = process.supportsNormalTermination()
    }
}
