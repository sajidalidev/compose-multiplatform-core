/*
 * Copyright 2025 The Android Open Source Project
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

package org.jetbrains.androidx.build

import androidx.build.Version
import org.gradle.api.Project
import org.gradle.api.artifacts.DependencySubstitutions

fun Project.changeMavenCoordinatesToJetBrains() {
    val component = JetBrainsPublication.projectPathToComponent[path]
    val versions = JetBrainsVersionsService.versions(project)

    val group = JetBrainsPublication.mavenGroupFor(path)
    val publishedVersion = versions.versionOf(component?.library())
    // A module upstream already ships for tvOS is not republished, so its coordinates, and with
    // them every dependency edge on it, stay upstream's.
    val version = Version(
        if (JetBrainsPublication.usesUpstreamArtifact(path)) {
            JetBrainsPublication.upstreamVersionOf(publishedVersion)
        } else {
            publishedVersion
        }
    )
    this.group = group
    this.version = version

    afterEvaluate {
        check(this.group == group) {
            "The $path group is changed after evaluation from $group to ${this.group}. Check if it is overridden inside build.gradle and remove it"
        }
        check(this.version == version) {
            "The $path version is changed after evaluation from $version to ${this.version}. Check if it is overridden inside build.gradle and remove it"
        }
    }
}

/**
 * Resolves every project dependency on a [JetBrainsPublication.upstreamTvosModules] entry to
 * upstream's artifact when publishing under a custom coordinate root, so the fork never builds
 * those modules and compiles against exactly what its consumers will resolve. The modules stay
 * in the build (upstream's own build files reference them by project path) but none of their
 * tasks run.
 */
fun Project.substituteUpstreamTvosModules() {
    if (path in JetBrainsPublication.upstreamTvosModules) return
    val upstreamPaths =
        JetBrainsPublication.upstreamTvosModules.filter(JetBrainsPublication::usesUpstreamArtifact)
    if (upstreamPaths.isEmpty()) return
    val versions = JetBrainsVersionsService.versions(project)
    val coordinates = upstreamPaths.associateWith { upstreamPath ->
        val library = requireNotNull(JetBrainsPublication.projectPathToLibrary[upstreamPath]) {
            "$upstreamPath is not registered in JetBrainsPublication"
        }
        val version = JetBrainsPublication.upstreamVersionOf(versions.versionOf(library))
        "${JetBrainsPublication.mavenGroupFor(upstreamPath)}:" +
            "${upstreamPath.substringAfterLast(":")}:$version"
    }
    configurations.configureEach { configuration ->
        configuration.resolutionStrategy.dependencySubstitution { substitutions ->
            substitutions.useUpstreamArtifacts(coordinates)
        }
    }
}

private fun DependencySubstitutions.useUpstreamArtifacts(coordinates: Map<String, String>) {
    for ((projectPath, module) in coordinates) {
        substitute(project(projectPath))
            .using(module(module))
            .because("upstream already publishes $module with tvOS variants")
    }
}
