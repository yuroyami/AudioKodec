import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.vanniktech.publish)
    alias(libs.plugins.dokka)
}

/*
 * :audiokodec is the pure-Kotlin audio core. NO external runtime deps, only
 * kotlin-stdlib is on the classpath, exactly like :imagekodec, :archivekodec and
 * :kitepdf. Everything here is pure computation (no sockets, no threads, no
 * disk): container parsing, the codecs, the PCM model, the tag readers and
 * writers, and the malformed-input guards.
 *
 * The kotlinx-io bridge lives in :audiokodec-io so this core stays portable to
 * every target and kotlinx-io never reaches consumers who don't want it.
 */
/*
 * The allocation guard sits at 1 GiB of decoded PCM by default, and the fuzz
 * suite drives malformed headers right up to it. The test JVM therefore needs
 * enough heap that the *guard* is what rejects the input, rather than the
 * allocator failing first and masking which one fired.
 */
tasks.withType<Test>().configureEach {
    maxHeapSize = "3g"
    providers.gradleProperty("audiokodecFixtureTmp").orNull?.let { systemProperty("java.io.tmpdir", it) }
    // A cached green result cannot produce a fresh provenance recording.
    providers.environmentVariable("AUDIOKODEC_FIXTURE_RECORDING_DIR").orNull?.let {
        environment("AUDIOKODEC_FIXTURE_RECORDING_DIR", it)
        outputs.upToDateWhen { false }
    }
}

kotlin {
    explicitApi()
    jvmToolchain(21)

    // Public API tracking. `updateKotlinAbi` refreshes api/*.api, `checkKotlinAbi`
    // fails the build when the committed dump and the code disagree: with
    // explicitApi() on, that turns an accidental signature change into a review
    // conversation instead of a broken release.
    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation {
        // Declaring the block is what switches tracking on.
    }

    android {
        namespace = "io.github.yuroyami.audiokodec"
        compileSdk = 36
        minSdk = 21
        withHostTest {}
    }

    listOf(
        iosSimulatorArm64(),
        iosArm64(),
        iosX64(),
    ).forEach { target ->
        target.binaries.framework {
            baseName = "AudioKodec"
            isStatic = false
        }
    }

    // Apple-Silicon macOS (Intel is deprecated in Kotlin 2.3+).
    macosArm64()

    // The rest of the KMP matrix. The core is pure stdlib computation, so it
    // ships everywhere Kotlin does.
    tvosArm64(); tvosSimulatorArm64()
    watchosArm32(); watchosArm64(); watchosDeviceArm64(); watchosSimulatorArm64()
    androidNativeArm32(); androidNativeArm64(); androidNativeX64(); androidNativeX86()
    linuxX64(); linuxArm64()
    mingwX64()
    @OptIn(ExperimentalWasmDsl::class)
    wasmWasi {
        nodejs()
    }

    // Mocha's 2-second default is fine for unit tests and hopeless for the fuzz
    // suite, which drives thousands of malformed files through every decoder.
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    js {
        browser()
        nodejs {
            testTask {
                useMocha { timeout = "600s" }
            }
        }
        binaries.library()
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        nodejs {
            testTask {
                useMocha { timeout = "600s" }
            }
        }
    }

    jvm()

    sourceSets {
        all {
            languageSettings {
                optIn("kotlin.RequiresOptIn")
                optIn("kotlin.experimental.ExperimentalNativeApi")
            }
        }

        commonMain.dependencies {
            // Intentionally empty. AudioKodec core depends on kotlin-stdlib only.
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
