plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

/*
 * :sample is not published and carries no API. It depends on the core alone:
 * a file is read into memory by the JDK and handed to AudioKodec as bytes,
 * which is all a demonstration or a benchmark needs.
 *
 * `./gradlew :sample:benchmark` measures decode speed against the floors
 * docs/support.md records; see Benchmark.kt for what it decodes.
 */
kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":audiokodec"))
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("io.github.yuroyami.audiokodec.sample.BenchmarkKt")
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
