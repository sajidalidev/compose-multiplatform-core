#!/bin/bash

## Publishes the tvOS fork's Compose Multiplatform artifacts to mavenLocal under the
## dev.sajidali.* coordinate root (see JetBrainsPublication.coordinateRoot /
## `-Ppublication.coordinateRoot`), so a redirect-consuming project can resolve them.
##
## This script only WRAPS the mavenLocal publish task; it does not run it automatically
## on its own — see the invocation below. It intentionally does not configure any
## `publish.maven.*` remote-repo or `publish.signing.*` PGP credentials. For a remote (Reposilite)
## dev publish see publish-tvos-fork-reposilite.sh.
##
## The published version stamps (VERSION_*) and the LIBRARIES list are sourced from
## scripts/tvos-versions.sh, the single source of truth shared with the Reposilite flow.

set -e

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"

# JDK 21 is required by the build (org.gradle.java.installations.fromEnv=ANDROIDX_JDK21).
# Fail fast rather than let Gradle fall back to an unsupported JDK. It's not enough for the
# env var to merely be set -- verify the java binary it points at actually reports major
# version 21.
JDK21_HOME="${ANDROIDX_JDK21:-$JAVA_HOME}"
if [ -z "$JDK21_HOME" ]; then
    echo "ERROR: JDK 21 is required. Export ANDROIDX_JDK21 (and/or JAVA_HOME) pointing at a JDK 21 install." >&2
    echo "Example:" >&2
    echo "  export JAVA_HOME=\"\$(/usr/libexec/java_home -v 21)\"" >&2
    echo "  export ANDROIDX_JDK21=\"\$JAVA_HOME\"" >&2
    exit 1
fi

if [ ! -x "$JDK21_HOME/bin/java" ]; then
    echo "ERROR: No java executable found at \"$JDK21_HOME/bin/java\". Check ANDROIDX_JDK21/JAVA_HOME." >&2
    exit 1
fi

if ! "$JDK21_HOME/bin/java" -version 2>&1 | grep -q 'version "21'; then
    echo "ERROR: \"$JDK21_HOME/bin/java\" is not a JDK 21 install. Got:" >&2
    "$JDK21_HOME/bin/java" -version 2>&1 | sed 's/^/  /' >&2
    echo "Export ANDROIDX_JDK21 (and/or JAVA_HOME) pointing at a JDK 21 install." >&2
    exit 1
fi

# VERSION_* below are the PUBLISHED VERSION IDENTITY each artifact is stamped with -- the
# coordinate a consumer actually requests -- via -Pjetbrains.publication.version.<LIB>
# (JetBrainsVersionsService.kt parses the property; JetBrainsMavenCoordinatesChanger.kt sets
# group+version from it). For COMPOSE, COMPOSE_MATERIAL3 and NAVIGATION this MUST be
# JetBrains' own org.jetbrains.* release version for that module, NOT the in-tree androidx
# number from libraryversions.toml: the Compose Multiplatform Gradle plugin's redirect
# mechanism substitutes dev.sajidali.* for org.jetbrains.* only when the consumer-requested
# version STRING matches (the "same-version convention"). Stamping the androidx number (e.g.
# material3 1.5.0-alpha22, navigation 2.10.0-alpha05) produces a coordinate no real consumer
# ever asks for and no JetBrains release ever published, which is why every consumer needed a
# manual `versionMappings` workaround.
#
# Determined 2026-09-03 on the jb-version-stamping branch:
#   - COMPOSE = "1.12.0": JetBrains' final released tag (v1.12.0 = f29d2f99f3b on
#     release/1.12); this fork tree's compose source is the same fork point, and that release
#     re-published only the COMPOSE group (see commit ad725033bc1's own investigation).
#   - COMPOSE_MATERIAL3 = "1.12.0-alpha03": JetBrains' LATEST published material3 (confirmed
#     via Central's maven-metadata.xml <latest>/<release>). Its own POM depends on
#     org.jetbrains.compose.{runtime,ui,foundation}:1.12.0-beta01 -- exactly this tree's
#     libraryversions.toml COMPOSE value (1.12.0-beta01) -- i.e. it's the material3 release
#     built from the same androidx material3 1.5.0-alpha22 source drop this tree carries.
#     1.11.0-alpha07 (the OTHER version the old manifest wrongly also claimed) depends on
#     compose 1.11.0-beta03 instead, confirmed via its POM, and does NOT match.
#   - NAVIGATION = "2.10.0-alpha02": JetBrains has never published a navigation-compose
#     release compatible with the 1.12.0 compose line. Per Central's maven-metadata.xml,
#     2.10.0-alpha02 is the latest (and last) version JetBrains has ever published for
#     org.jetbrains.androidx.navigation:navigation-compose; its POM depends on compose
#     1.10.0, stale relative to this tree. 2.10.0-alpha03/04/05 do not exist upstream. There
#     is no exact content match available, so the topping-out published version -- the safer,
#     most-defensible choice -- is used rather than the androidx number nothing upstream ever
#     shipped.
#
# COMPOSE_MATERIAL3_ADAPTIVE, NAVIGATION_3 and WINDOW below are still pinned to the in-tree
# libraryversions.toml/androidx number (out of scope for this stamping pass). TV_MATERIAL is
# correctly an androidx number: androidx.tv has no JetBrains counterpart at all.
#
# Versions extracted from libraryversions.toml at the repo root. That file is the
# source of truth for the androidx-numbered entries; update the values below if it changes.
#   COMPOSE               = "1.12.0-beta01"  (in-tree; stamped as JetBrains' "1.12.0" above)
#   COMPOSE_MATERIAL3     = "1.5.0-alpha22"  (in-tree; stamped as JetBrains' "1.12.0-alpha03" above)
#   COMPOSE_MATERIAL3_ADAPTIVE = "1.3.0-beta02"
#   NAVIGATION            = "2.10.0-alpha05"  (in-tree; stamped as JetBrains' "2.10.0-alpha02" above)
#   NAVIGATION3           = "1.2.0-alpha04"
#   WINDOW                = "1.6.0-alpha02"
#   TV_MATERIAL           = "1.1.0-alpha01"
#
# NOTE: the -Pjetbrains.publication.version.<LIB> property names below use the library
# keys registered in JetBrainsPublication.libraryToComponents (buildSrc/public/.../
# JetBrainsPublication.kt), which use an underscore for NAVIGATION_3 even though the toml key
# above (NAVIGATION3) does not.
#
# LIFECYCLE, NAVIGATION_EVENT and SAVEDSTATE were dropped on 2026-09-02 when tvos-main was rebased
# past upstream #3357 ("Remove lifecycle, savedstate, navigationevent"): fork mode no longer
# includes those projects. Nothing is lost for tvOS consumers -- org.jetbrains.androidx.lifecycle
# 2.11.0, androidx.savedstate 1.4.0/1.5.0 and androidx.navigationevent 1.1.1 all ship tvosArm64 /
# tvosSimulatorArm64 variants and the fork's build files now depend on those coordinates directly.
# The dev.sajidali.androidx.{lifecycle,navigationevent,savedstate} artifacts published for the
# 1.12.0 line stay on Central but will not be re-released.
#
# TV_MATERIAL (:tv:tv-material) was added in task 23a: its androidLibrary target is wrapped
# in redirect("androidx.tv") { ... } (see tv/tv-material/build.gradle), so its android variant
# redirects to the real androidx.tv:tv-material:1.1.0-alpha01 artifact while tvOS is fork-built
# from this repo's in-tree AOSP copy (androidTest was excluded from this port -- see
# task-23a-report.md). tv-material has no dependency on :tv:tv-foundation (verified empty
# grep across its main source; the one androidTestImplementation reference to it was dropped
# along with the rest of the excluded androidTest source set), so tv-foundation itself was
# NOT ported and is not part of this library group.
#
# WINDOW (:window:window-core) was added in task 18a and is now a PERMANENT part of this
# release (not a one-off): its androidLibrary target is wrapped in
# redirect("androidx.window") { ... } (see window/window-core/build.gradle), so its android
# variant redirects to the real androidx.window:window-core:1.5.0 artifact while
# tvOS/iOS/etc. are fork-built from this repo's in-tree AOSP copy. COMPOSE_MATERIAL3_ADAPTIVE
# (below) depends on it via a project(":window:window-core") reference, so WINDOW must always
# be published alongside it.
#
# COMPOSE_MATERIAL3_ADAPTIVE was RE-INCLUDED in task 18b, lifting the earlier task-8c-attempt-3
# exclusion. That exclusion was based on the (incorrect) assumption that
# compose:material3:adaptive:adaptive depended on an external, tvOS-less upstream
# androidx.window:window-core artifact; task 18a's investigation found it's actually a
# project(":window:window-core") reference, and task 18a fork-built window-core with real tvOS
# klib variants (see the WINDOW note above). Task 18b verified
# `:compose:material3:adaptive:{adaptive,adaptive-layout,adaptive-navigation,
# adaptive-navigation3}:compileKotlinTvosArm64` all succeed, and additionally verified
# `:compose:material3:material3-adaptive-navigation-suite:compileKotlinTvosArm64` (the
# COMPOSE_MATERIAL3 consumer of adaptive) succeeds too, so the navigation-suite exclusion in
# JetBrainsPublication.kt was removed as well.
# The VERSION_* pins and LIBRARIES documented above live in scripts/tvos-versions.sh.
source "$(dirname "$0")/tvos-versions.sh"

COORDINATE_ROOT="dev.sajidali"
PLATFORMS="KotlinMultiplatform,TvosArm64,TvosSimulatorArm64"

echo "About to publish to mavenLocal with:"
echo "  coordinateRoot = $COORDINATE_ROOT"
echo "  platforms      = $PLATFORMS"
echo "  libraries      = $LIBRARIES"
echo "  versions:"
echo "    COMPOSE=$VERSION_COMPOSE"
echo "    COMPOSE_MATERIAL3=$VERSION_COMPOSE_MATERIAL3"
echo "    COMPOSE_MATERIAL3_ADAPTIVE=$VERSION_COMPOSE_MATERIAL3_ADAPTIVE"
echo "    NAVIGATION=$VERSION_NAVIGATION"
echo "    NAVIGATION_3=$VERSION_NAVIGATION_3"
echo "    WINDOW=$VERSION_WINDOW"
echo "    TV_MATERIAL=$VERSION_TV_MATERIAL"

(
    cd "$ROOT_DIR"
    # --no-configuration-cache is REQUIRED here, not an optimisation. Several fork-mode
    # publishing decisions live in mutable state on Kotlin `object`s that is assigned during
    # configuration -- JetBrainsPublication.coordinateRoot most importantly, which
    # jbVerifyDependencyVersions reads from an execution-time onlyIf. A build that REUSES a
    # configuration cache entry in a fresh daemon never re-runs configuration, so those objects
    # hold their defaults ("org.jetbrains") and the publish fails with e.g.
    #   Project with version 1.12.0 may not take a dependency on less-stable artifact
    #   dev.sajidali.androidx.navigation:navigation-compose:2.10.0-alpha05
    # which is exactly the verbatim upstream pin a fork republish is meant to carry. A warm
    # daemon hides it (the object still holds the previously configured value), so this only
    # bites on the first publish after a daemon restart. Reproduced and confirmed 2026-09-01.
    ./gradlew -p mpp publishComposeJbToMavenLocal --no-configuration-cache \
        -Ppublication.coordinateRoot="$COORDINATE_ROOT" \
        "-Pcompose.platforms=$PLATFORMS" \
        -Pjetbrains.publication.libraries="$LIBRARIES" \
        -Pjetbrains.publication.version.COMPOSE="$VERSION_COMPOSE" \
        -Pjetbrains.publication.version.COMPOSE_MATERIAL3="$VERSION_COMPOSE_MATERIAL3" \
        -Pjetbrains.publication.version.COMPOSE_MATERIAL3_ADAPTIVE="$VERSION_COMPOSE_MATERIAL3_ADAPTIVE" \
        -Pjetbrains.publication.version.NAVIGATION="$VERSION_NAVIGATION" \
        -Pjetbrains.publication.version.NAVIGATION_3="$VERSION_NAVIGATION_3" \
        -Pjetbrains.publication.version.WINDOW="$VERSION_WINDOW" \
        -Pjetbrains.publication.version.TV_MATERIAL="$VERSION_TV_MATERIAL"
)
