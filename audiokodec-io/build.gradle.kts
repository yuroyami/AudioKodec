import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.vanniktech.publish)
    alias(libs.plugins.dokka)
}

/*
 * :audiokodec-io bridges the core to kotlinx-io. It turns a Path or a RawSource
 * into an AudioSource, and a Sink into an AudioSink, so callers can decode a
 * file without first loading it into a ByteArray.
 *
 * This module tracks kotlinx-io's target list, which is smaller than the core's.
 * A project that builds for a target kotlinx-io does not publish for resolves
 * :audiokodec and then fails to resolve this module, which is why the file
 * plumbing is not in the core.
 */
kotlin {
    explicitApi()
    jvmToolchain(21)

    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation {
        // Declaring the block is what switches tracking on.
    }

    android {
        namespace = "io.github.yuroyami.audiokodec.io"
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
            baseName = "AudioKodecIO"
            isStatic = false
        }
    }

    macosArm64()
    tvosArm64(); tvosSimulatorArm64()
    watchosArm32(); watchosArm64(); watchosDeviceArm64(); watchosSimulatorArm64()
    androidNativeArm32(); androidNativeArm64(); androidNativeX64(); androidNativeX86()
    linuxX64(); linuxArm64()
    mingwX64()
    @OptIn(ExperimentalWasmDsl::class)
    wasmWasi {
        nodejs()
    }
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    js {
        browser {
            testTask {
                useKarma { useChromeHeadless() }
                filter.excludeTestsMatching("*AudioFileTest*")
            }
        }
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
        nodejs()
    }
    jvm()

    sourceSets {
        commonMain.dependencies {
            api(projects.audiokodec)
            api(libs.kotlinx.io.core)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

tasks.withType<KotlinJsTest>().matching { it.name == "wasmWasiNodeTest" }.configureEach {
    val directory = layout.buildDirectory.dir("wasi-test-files")
    val preloader = layout.projectDirectory.file("test-tools/wasi-filesystem.cjs")
    inputs.file(preloader)
    nodeJsArgs.addAll(listOf("--require", preloader.asFile.absolutePath))
    environment("AUDIOKODEC_WASI_TEST_DIRECTORY", directory.get().asFile.absolutePath)
    doFirst { directory.get().asFile.mkdirs() }
}
