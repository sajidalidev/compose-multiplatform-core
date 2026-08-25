#!/bin/bash

## Publishes the tvOS fork's Compose Multiplatform artifacts to mavenLocal under the
## dev.sajidali.* coordinate root (see JetBrainsPublication.coordinateRoot /
## `-Ppublication.coordinateRoot`), so a redirect-consuming project can resolve them.
##
## This script only WRAPS the mavenLocal publish task; it does not run it automatically
## on its own — see the invocation below. It intentionally does not configure any
## `publish.maven.*` remote-repo or `publish.signing.*` PGP credentials.

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

# Versions extracted from libraryversions.toml at the repo root. That file is the
# source of truth for these; update the values below if it changes.
#   COMPOSE               = "1.12.0"  (CMP 1.12.0 release; toml still says 1.12.0-beta01)
#   COMPOSE_MATERIAL3     = "1.5.0-alpha22"
#   COMPOSE_MATERIAL3_ADAPTIVE = "1.3.0-beta02"
#   LIFECYCLE             = "2.11.0"
#   NAVIGATION            = "2.10.0-alpha05"
#   NAVIGATION3           = "1.2.0-alpha04"
#   NAVIGATIONEVENT       = "1.1.1"
#   SAVEDSTATE            = "1.5.0-alpha01"
#   WINDOW                = "1.6.0-alpha02"
#   TV_MATERIAL           = "1.1.0-alpha01"
#
# NOTE: the -Pjetbrains.publication.version.<LIB> property names below use the library
# keys registered in JetBrainsPublication.libraryToComponents (buildSrc/public/.../
# JetBrainsPublication.kt), which use underscores for NAVIGATION_3 and NAVIGATION_EVENT
# even though the toml keys above (NAVIGATION3 / NAVIGATIONEVENT) do not.
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
VERSION_COMPOSE="1.12.0"
VERSION_COMPOSE_MATERIAL3="1.5.0-alpha22"
VERSION_COMPOSE_MATERIAL3_ADAPTIVE="1.3.0-beta02"
VERSION_LIFECYCLE="2.11.0"
VERSION_NAVIGATION="2.10.0-alpha05"
VERSION_NAVIGATION_3="1.2.0-alpha04"
VERSION_NAVIGATION_EVENT="1.1.1"
VERSION_SAVEDSTATE="1.5.0-alpha01"
VERSION_WINDOW="1.6.0-alpha02"
VERSION_TV_MATERIAL="1.1.0-alpha01"

COORDINATE_ROOT="dev.sajidali"
LIBRARIES="${TVOS_PUBLISH_LIBRARIES:-COMPOSE}"
PLATFORMS="KotlinMultiplatform,TvosArm64,TvosSimulatorArm64"

echo "About to publish to mavenLocal with:"
echo "  coordinateRoot = $COORDINATE_ROOT"
echo "  platforms      = $PLATFORMS"
echo "  libraries      = $LIBRARIES"
echo "  versions:"
echo "    COMPOSE=$VERSION_COMPOSE"
echo "    COMPOSE_MATERIAL3=$VERSION_COMPOSE_MATERIAL3"
echo "    COMPOSE_MATERIAL3_ADAPTIVE=$VERSION_COMPOSE_MATERIAL3_ADAPTIVE"
echo "    LIFECYCLE=$VERSION_LIFECYCLE"
echo "    NAVIGATION=$VERSION_NAVIGATION"
echo "    NAVIGATION_3=$VERSION_NAVIGATION_3"
echo "    NAVIGATION_EVENT=$VERSION_NAVIGATION_EVENT"
echo "    SAVEDSTATE=$VERSION_SAVEDSTATE"
echo "    WINDOW=$VERSION_WINDOW"
echo "    TV_MATERIAL=$VERSION_TV_MATERIAL"

(
    cd "$ROOT_DIR"
    ./gradlew -p mpp publishComposeJbToMavenLocal \
        -Ppublication.coordinateRoot="$COORDINATE_ROOT" \
        "-Pcompose.platforms=$PLATFORMS" \
        -Pjetbrains.publication.libraries="$LIBRARIES" \
        -Pjetbrains.publication.version.COMPOSE="$VERSION_COMPOSE" \
        -Pjetbrains.publication.version.COMPOSE_MATERIAL3="$VERSION_COMPOSE_MATERIAL3" \
        -Pjetbrains.publication.version.COMPOSE_MATERIAL3_ADAPTIVE="$VERSION_COMPOSE_MATERIAL3_ADAPTIVE" \
        -Pjetbrains.publication.version.LIFECYCLE="$VERSION_LIFECYCLE" \
        -Pjetbrains.publication.version.NAVIGATION="$VERSION_NAVIGATION" \
        -Pjetbrains.publication.version.NAVIGATION_3="$VERSION_NAVIGATION_3" \
        -Pjetbrains.publication.version.NAVIGATION_EVENT="$VERSION_NAVIGATION_EVENT" \
        -Pjetbrains.publication.version.SAVEDSTATE="$VERSION_SAVEDSTATE" \
        -Pjetbrains.publication.version.WINDOW="$VERSION_WINDOW" \
        -Pjetbrains.publication.version.TV_MATERIAL="$VERSION_TV_MATERIAL"
)
