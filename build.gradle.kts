import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.GenerateMavenPom
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.jetbrains.kotlin.gradle.tasks.KotlinNativeLink

plugins {
    // Declared here with apply false so the publish plugin's shared build service
    // is loaded by one classloader for the whole build. Without this, applying it
    // to sibling modules makes publishAndReleaseToMavenCentral fail.
    alias(libs.plugins.vanniktech.publish).apply(false)
    alias(libs.plugins.kotlin.multiplatform).apply(false)
    alias(libs.plugins.android.kmp.library).apply(false)
    // Applied (not deferred) at the root so `dokkaGenerate` aggregates every
    // library module into one API site at build/dokka/html.
    alias(libs.plugins.dokka)
}

allprojects {
    group = "io.github.yuroyami"
    version = "0.1.0"
}

// Attribution accompanies every Maven archive, including native/JS/Wasm KLIBs,
// source archives and Android AARs. Keep the canonical texts in the repo root.
subprojects {
    val attribution = rootProject.files("LICENSE", "NOTICE")
    tasks.withType<AbstractArchiveTask>().configureEach {
        from(attribution) { into("META-INF") }
    }
    tasks.withType<Copy>().matching { it.name.endsWith("LibraryDistribution") }.configureEach {
        from(attribution)
    }
    tasks.withType<KotlinNativeLink>().configureEach {
        inputs.files(attribution).withPropertyName("distributionAttribution")
        doLast {
            for (directory in outputs.files.files.filter { it.isDirectory }) {
                for (framework in directory.listFiles().orEmpty().filter { it.name.endsWith(".framework") }) {
                    copy {
                        from(attribution)
                        into(framework.resolve("Resources"))
                    }
                }
            }
        }
    }
    plugins.withId("maven-publish") {
        extensions.configure<PublishingExtension> {
            publications.withType<MavenPublication>().configureEach {
                pom.licenses {
                    license {
                        name.set("BSD 3-Clause License (translated libopus components)")
                        url.set("https://opensource.org/license/bsd-3-clause")
                        distribution.set("repo")
                    }
                }
            }
        }
    }
}

tasks.register("generateDistributionPoms") {
    dependsOn(subprojects.map { it.tasks.withType<GenerateMavenPom>() })
}

dependencies {
    dokka(projects.audiokodec)
    dokka(projects.audiokodecIo)
}

dokka {
    moduleName.set("AudioKodec")
}

// Shared Kite theme. Sources live in ../_kite-docs; ./_kite-docs/sync.sh copies
// them here, so this repo still builds standalone from a fresh clone.
//
// This has to be applied to every project that has Dokka, not just the root:
// under aggregation the root only renders the "all modules" landing page, and
// each module renders its own pages from its own configuration. Configuring
// only the root leaves every actual API page on the stock theme.
allprojects {
    plugins.withId("org.jetbrains.dokka") {
        extensions.configure<org.jetbrains.dokka.gradle.DokkaExtension> {
            pluginsConfiguration.html {
                val themeCss = rootProject.layout.projectDirectory.file("docs/api-theme/kite.css")
                if (themeCss.asFile.exists()) {
                    customStyleSheets.from(themeCss)
                }
                val templates = rootProject.layout.projectDirectory.dir("dokka-templates")
                if (templates.asFile.exists()) {
                    templatesDir.set(templates)
                }
                footerMessage.set("Apache-2.0 · AudioKodec is part of the Kite family.")
            }

            // A module with a Module.md gets its description onto the aggregated
            // "all modules" landing page, which is otherwise a bare list of names.
            dokkaSourceSets.configureEach {
                val moduleDoc = layout.projectDirectory.file("Module.md")
                if (moduleDoc.asFile.exists()) {
                    includes.from(moduleDoc)
                }
            }
        }
    }
}
