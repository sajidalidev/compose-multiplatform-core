/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.targets.js.ir.KotlinJsIrTarget

plugins {
    id("AndroidXComposePlugin")
    id("kotlin-multiplatform")
}

// Tizen and webOS both run applications as web apps, and neither TV's web engine has WasmGC:
// Tizen 8.0 ships Chromium 108 and webOS 24 ships Chromium 108, while Kotlin/Wasm needs 119+. So
// the demo targets Kotlin/JS, which runs Skiko on WebGL2 — available from Tizen 6.0 and from
// webOS 5.0 (Chromium 68); webOS 3.x, on Chromium 38, predates WebGL2 and cannot run it.
kotlin {
    js {
        outputModuleName = "compose-tv-demo"
        browser {
            commonWebpackConfig {
                outputFileName = "demo.js"
            }
        }
        binaries.executable()
    }

    // Single source set: the app targets one platform, and the TV-specific APIs it uses
    // (KeyEvent.isRepeat, ComposeTvViewport) live in the web/skiko source sets of
    // :compose:ui:ui, so there is nothing to share with a commonMain here.
    sourceSets {
        val jsMain by getting {
            dependencies {
                implementation(project(":compose:foundation:foundation"))
                implementation(project(":compose:foundation:foundation-layout"))
                implementation(project(":compose:material3:material3"))
                implementation(project(":compose:runtime:runtime"))
                implementation(project(":compose:ui:ui"))
                implementation(project(":compose:ui:ui-graphics"))
                implementation(project(":compose:ui:ui-text"))
                implementation(libs.kotlinCoroutinesCore)
                implementation(libs.skiko)
            }
        }
    }

    targets.withType<KotlinJsIrTarget>().all { configureSkikoWebRuntime(project, this) }
}

// One JS bundle serves both TVs — the platform is detected at runtime — so packaging differs only
// in the manifest laid next to it: config.xml for Tizen, appinfo.json for webOS. Both come from
// packaging/<platform>/ rather than src/jsMain/resources, so neither ends up inside the other's
// package.
val tizenAppDir = layout.buildDirectory.dir("tizen/app")
val webOsAppDir = layout.buildDirectory.dir("webos/app")

val assembleTizenApp by tasks.registering(Copy::class) {
    group = "tv"
    description = "Assembles the unsigned Tizen web app directory."
    from(tasks.named("jsBrowserDistribution"))
    from("packaging/tizen")
    into(tizenAppDir)
}

val packageTizenApp by tasks.registering(Zip::class) {
    group = "tv"
    description = "Packs the Tizen web app into an unsigned .wgt archive. " +
        "Installing on a real TV needs a signature: run `tizen package -t wgt -s <profile>` " +
        "on the output of assembleTizenApp instead. See README.md."
    dependsOn(assembleTizenApp)
    from(tizenAppDir)
    archiveFileName.set("ComposeTvDemo.wgt")
    destinationDirectory.set(layout.buildDirectory.dir("tizen"))
}

val assembleWebOsApp by tasks.registering(Copy::class) {
    group = "tv"
    description = "Assembles the webOS web app directory. Turn it into an .ipk with " +
        "`ares-package` from the webOS TV SDK — an .ipk is an ar archive, not a zip, so Gradle " +
        "cannot stand in for it. See README.md."
    from(tasks.named("jsBrowserDistribution"))
    from("packaging/webos")
    into(webOsAppDir)
}

/**
 * Kotlin/JS reaches Skiko through the global scope rather than through a module system, so the
 * bundler cannot pull `skiko.wasm`/`skiko.mjs` in by itself and they have to be unpacked into the
 * processed resources. Same mechanism as `compose/mpp/demo`.
 */
private fun configureSkikoWebRuntime(
    project: Project,
    target: KotlinJsIrTarget,
) {
    val titledTargetName = target.name.replaceFirstChar { it.titlecase() }
    val mainCompilation = target.compilations.findByName(KotlinCompilation.MAIN_COMPILATION_NAME)!!
    val runtimeDepsConfig =
        project.configurations.findByName(mainCompilation.runtimeDependencyConfigurationName)!!
    val skikoWebRuntimeJarFiles = runtimeDepsConfig.incoming.artifactView {
        @Suppress("UnstableApiUsage")
        withVariantReselection()
        attributes {
            runtimeDepsConfig.attributes.keySet().forEach {
                @Suppress("UNCHECKED_CAST")
                attribute(
                    it as Attribute<Any>,
                    runtimeDepsConfig.attributes.getAttribute(it) as Any
                )
            }
            attribute(Usage.USAGE_ATTRIBUTE, project.objects.named(Usage::class.java, "skiko-runtime"))
        }
    }.files
    val unpackedRuntimeDir = project.layout.buildDirectory.dir("compose/skiko-${target.name}-runtime")

    val unpackRuntime = project.tasks.register(
        "unpackSkikoRuntimeFor$titledTargetName",
        Copy::class.java
    ) {
        destinationDir = project.file(unpackedRuntimeDir)
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        from(skikoWebRuntimeJarFiles.map { artifact -> project.zipTree(artifact) })
    }

    target.compilations.all {
        project.tasks.named(processResourcesTaskName, ProcessResources::class.java) {
            from(unpackedRuntimeDir)
            dependsOn(unpackRuntime)
        }
    }
}
