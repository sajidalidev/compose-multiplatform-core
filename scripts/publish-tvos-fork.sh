#!/bin/bash

## Publishes the tvOS fork's Compose Multiplatform artifacts to mavenLocal under the
## dev.sajidali.* coordinate root (see JetBrainsPublication.coordinateRoot /
## `-Ppublication.coordinateRoot`), so a redirect-consuming project can resolve them.
##
## Ledger-driven and per-module. Versions come ONLY from the release ledger
## (compose-tvos-redirect/tools/release/release-ledger/<version>.json, `--ledger` or
## $TVOS_LEDGER); nothing here types a version. The module set comes from
## scripts/publish_set.py (ledger artifacts with no usable upstream tvOS klibs, minus those
## whose dev.sajidali twin is already on Central, minus its NEVER_PUBLISH list), and each
## module is published by invoking its own publish<Platform>PublicationToMavenLocal tasks
## rather than the library-group aggregate `publishComposeJbToMavenLocal`, which is what
## `-Pjetbrains.publication.libraries` selects and which cannot express a single module.
##
## This script only WRAPS the mavenLocal publish tasks; it intentionally does not configure
## any `publish.maven.*` remote-repo or `publish.signing.*` PGP credentials.
##
## Usage:
##   publish-tvos-fork.sh [--ledger <json>] [--only-group <org.jetbrains group>[,...]]
##                        [--modules <:path>[,<:path>...]] [--force-modules]
##                        [--include-tests] [--include-tooling-preview] [--ignore-central]
##                        [--dry-run]
##   --modules must be a subset of the derived publish set; --force-modules overrides that
##   check (loudly). --dry-run prints the exact Gradle command and runs nothing.

set -e

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SCRIPT_DIR="$ROOT_DIR/scripts"

LEDGER="${TVOS_LEDGER:-}"
MODULES_OVERRIDE=""
FORCE_MODULES=0
DRY_RUN=0
PSET_ARGS=()
while [ $# -gt 0 ]; do
    case "$1" in
        --ledger) LEDGER="$2"; shift 2 ;;
        --ledger=*) LEDGER="${1#--ledger=}"; shift ;;
        --only-group)
            for g in ${2//,/ }; do PSET_ARGS+=(--only-group "$g"); done; shift 2 ;;
        --only-group=*)
            v="${1#--only-group=}"
            for g in ${v//,/ }; do PSET_ARGS+=(--only-group "$g"); done; shift ;;
        --modules) MODULES_OVERRIDE="$2"; shift 2 ;;
        --modules=*) MODULES_OVERRIDE="${1#--modules=}"; shift ;;
        --force-modules) FORCE_MODULES=1; shift ;;
        --include-tests|--include-tooling-preview|--ignore-central) PSET_ARGS+=("$1"); shift ;;
        --dry-run) DRY_RUN=1; shift ;;
        -h|--help) sed -n '/^## Usage:/,/^$/p' "$0" | sed 's/^## \{0,1\}//'; exit 0 ;;
        *) echo "ERROR: unknown argument: $1" >&2; exit 2 ;;
    esac
done

if [ -z "$LEDGER" ]; then
    echo "ERROR: no ledger. Pass --ledger <json> or export TVOS_LEDGER." >&2
    echo "  (compose-tvos-redirect/tools/release/release-ledger/<version>.json)" >&2
    exit 1
fi
if [ ! -f "$LEDGER" ]; then
    echo "ERROR: ledger not found: $LEDGER" >&2
    exit 1
fi

# JDK 21 is required by the build (org.gradle.java.installations.fromEnv=ANDROIDX_JDK21).
# Fail fast rather than let Gradle fall back to an unsupported JDK. It's not enough for the
# env var to merely be set -- verify the java binary it points at actually reports major
# version 21. A --dry-run runs no Gradle, so there it only warns.
jdk_problem=""
JDK21_HOME="${ANDROIDX_JDK21:-${JAVA_HOME:-}}"
if [ -z "$JDK21_HOME" ]; then
    jdk_problem="JDK 21 is required. Export ANDROIDX_JDK21 (and/or JAVA_HOME) pointing at a JDK 21 install."
elif [ ! -x "$JDK21_HOME/bin/java" ]; then
    jdk_problem="No java executable found at \"$JDK21_HOME/bin/java\". Check ANDROIDX_JDK21/JAVA_HOME."
elif ! "$JDK21_HOME/bin/java" -version 2>&1 | grep -q 'version "21'; then
    jdk_problem="\"$JDK21_HOME/bin/java\" is not a JDK 21 install: $("$JDK21_HOME/bin/java" -version 2>&1 | head -1)"
fi
if [ -n "$jdk_problem" ]; then
    if [ "$DRY_RUN" -eq 1 ]; then
        echo "WARNING (dry-run, not fatal): $jdk_problem" >&2
    else
        echo "ERROR: $jdk_problem" >&2
        echo "Example:" >&2
        echo "  export JAVA_HOME=\"\$(/usr/libexec/java_home -v 21)\"" >&2
        echo "  export ANDROIDX_JDK21=\"\$JAVA_HOME\"" >&2
        exit 1
    fi
fi

# Derive the publish set. publish_set.py exits non-zero (and prints why) when any library
# key the build registers has no version in the ledger; the eval below then never runs.
echo "=== Publish set (from ledger) ==="
python3 "$SCRIPT_DIR/publish_set.py" --ledger "$LEDGER" --format table ${PSET_ARGS[@]+"${PSET_ARGS[@]}"}
shell_out="$(python3 "$SCRIPT_DIR/publish_set.py" --ledger "$LEDGER" --format shell ${PSET_ARGS[@]+"${PSET_ARGS[@]}"})" || exit 1
eval "$shell_out"
# eval defines: LEDGER_FILE LEDGER_CMP_VERSION LEDGER_KOTLIN VERSION_PROPS[] PUBLISH_LIBRARIES
#               PUBLISH_MODULES[] PUBLISH_COORDS[] UNBUILDABLE_COORDS[]

MODULES=("${PUBLISH_MODULES[@]}")
if [ -n "$MODULES_OVERRIDE" ]; then
    MODULES=()
    for m in ${MODULES_OVERRIDE//,/ }; do
        in_set=0
        for p in "${PUBLISH_MODULES[@]}"; do [ "$p" = "$m" ] && in_set=1; done
        if [ "$in_set" -eq 0 ]; then
            if [ "$FORCE_MODULES" -eq 1 ]; then
                echo "!!! WARNING: $m is NOT in the ledger-derived publish set; publishing it anyway" >&2
                echo "!!!          because --force-modules was given. Its upstream either already ships" >&2
                echo "!!!          usable tvOS klibs, or its dev.sajidali twin is already on Central," >&2
                echo "!!!          or it is on the NEVER_PUBLISH list. Central will reject a re-upload." >&2
            else
                echo "ERROR: --modules entry $m is not in the derived publish set (see table above)." >&2
                echo "       Pass --force-modules to override, or --ignore-central for a rebuild view." >&2
                exit 1
            fi
        fi
        MODULES+=("$m")
    done
fi
if [ "${#MODULES[@]}" -eq 0 ]; then
    echo "ERROR: nothing to publish: every ledger artifact is either usable upstream, already" >&2
    echo "       on Central as dev.sajidali, or on NEVER_PUBLISH. Use --ignore-central to see" >&2
    echo "       the full rebuild set, or --modules with --force-modules." >&2
    exit 1
fi

COORDINATE_ROOT="dev.sajidali"
PLATFORMS="KotlinMultiplatform,TvosArm64,TvosSimulatorArm64"
# Per-module task set: mirrors what ComposePublishingTask.publishMultiplatform wires into the
# aggregate (publish<Platform>PublicationToMavenLocal for KotlinMultiplatform plus each
# requested target, then jbVerifyDependencyVersions), just for the modules chosen here.
TASKS=()
for m in "${MODULES[@]}"; do
    for p in ${PLATFORMS//,/ }; do
        TASKS+=("$m:publish${p}PublicationToMavenLocal")
    done
    TASKS+=("$m:jbVerifyDependencyVersions")
done

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
GRADLE_CMD=(./gradlew -p mpp "${TASKS[@]}" --no-configuration-cache
    -Ppublication.coordinateRoot="$COORDINATE_ROOT"
    "-Pcompose.platforms=$PLATFORMS"
    -Pjetbrains.publication.libraries="$PUBLISH_LIBRARIES"
    "${VERSION_PROPS[@]}")

echo
echo "About to publish to mavenLocal with:"
echo "  ledger         = $LEDGER_FILE (CMP $LEDGER_CMP_VERSION, Kotlin $LEDGER_KOTLIN)"
echo "  coordinateRoot = $COORDINATE_ROOT"
echo "  platforms      = $PLATFORMS"
echo "  modules        = ${MODULES[*]}"
echo "  versions:"
for v in "${VERSION_PROPS[@]}"; do echo "    ${v#-Pjetbrains.publication.version.}"; done
echo
echo "Gradle command (cwd $ROOT_DIR):"
printf '  %q' "${GRADLE_CMD[@]}"; echo

if [ "$DRY_RUN" -eq 1 ]; then
    echo
    echo "=== DRY RUN: no Gradle invoked ==="
    exit 0
fi

(
    cd "$ROOT_DIR"
    "${GRADLE_CMD[@]}"
)
