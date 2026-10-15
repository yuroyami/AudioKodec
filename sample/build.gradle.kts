plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

/*
 * :sample is not published and carries no API. It uses the library the way any
 * consumer does, through the public API of :audiokodec and :audiokodec-io.
 *
 * `./gradlew :sample:run` opens the viewer, a Swing window that names a file's
 * format, lists its tags, draws its waveform and plays it through Java Sound.
 * `./gradlew :sample:run --args=song.flac` opens that file at once. Without a
 * display the viewer prints the same summary to the console instead.
 *
 * `./gradlew :sample:benchmark` measures decode speed against the floors
 * docs/support.md records; see Benchmark.kt for what it decodes.
 */
kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":audiokodec"))
    // Seekable file input, so that the viewer never holds a whole file in memory.
    implementation(project(":audiokodec-io"))
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("io.github.yuroyami.audiokodec.sample.ViewerKt")
}

tasks.named<JavaExec>("run") {
    // A relative path in --args means what it meant where Gradle was started.
    workingDir = gradle.startParameter.currentDir
}

tasks.test {
    // The tests paint the viewer off screen, which needs no display.
    systemProperty("java.awt.headless", "true")
}

/*
 * The code samples the documentation includes. They live in a source set of
 * their own that sees the two published modules and nothing else, not even the
 * viewer, so a sample can only use what a reader's project can. Each one is a
 * test that runs and checks its result, and docs/ includes the marked regions
 * of these files rather than a copy of them. `./gradlew :sample:docsTest`
 * runs them.
 */
val docs: SourceSet by sourceSets.creating

dependencies {
    "docsImplementation"(project(":audiokodec"))
    "docsImplementation"(project(":audiokodec-io"))
    "docsImplementation"(kotlin("test-junit"))
}

val docsTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Runs the code samples that the documentation includes."
    testClassesDirs = docs.output.classesDirs
    classpath = docs.runtimeClasspath
    // The Java Sound sample asks for a line, which needs no display either.
    systemProperty("java.awt.headless", "true")
}

tasks.check {
    dependsOn(docsTest)
}

tasks.register<JavaExec>("benchmark") {
    group = "verification"
    description = "Decodes fixed inputs and reports a multiple of real time for each codec, against the floors."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.github.yuroyami.audiokodec.sample.BenchmarkKt")
    // The inputs are generated once and kept here between runs.
    systemProperty("audiokodec.benchmark.cache", layout.buildDirectory.dir("benchmark-inputs").get().asFile.path)
    jvmArgs("-Xmx2g")
    // Every run measures; nothing about it is cacheable.
    outputs.upToDateWhen { false }
}
