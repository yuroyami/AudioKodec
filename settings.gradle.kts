enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "AudioKodec-KMP"

// :audiokodec is the pure-Kotlin audio core (stdlib only). Container parsing,
// codecs, tag handling, the PCM model and the hostile-input guards all live
// here and port cleanly to every KMP target.
include(":audiokodec")

// :audiokodec-io is the optional kotlinx-io bridge: read a file as an
// AudioSource, write an encoder's output to a Sink. It keeps kotlinx-io off
// the classpath of consumers that already have their own byte plumbing. Its
// target list is kotlinx-io's, which is smaller than the core's.
include(":audiokodec-io")
