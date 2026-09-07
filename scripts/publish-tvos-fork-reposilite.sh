#!/bin/bash

## Publishes the tvOS fork's Compose Multiplatform artifacts to a remote Reposilite
## repository under the dev.sajidali.* coordinate root, stamped with a per-build dev
## qualifier so a consumer project can resolve a snapshot of this working tree without
## touching Maven Central.
##
## Version scheme: <exact JetBrains version><DEV_SUFFIX>, e.g. 1.12.0-dev.20260907.1.
## The JetBrains identity is preserved verbatim so the redirect plugin's same-version
## convention still describes what the artifact IS; the qualifier marks it as a dev build.
## Dev versions are IMMUTABLE: never republish one, bump the trailing .N instead.
##
## Credentials are passed to Gradle through the MAVEN_URL / MAVEN_USERNAME / MAVEN_PASSWORD
## environment variables that MavenUploadHelper.kt reads, and to curl through a `--config -`
## document fed on stdin, so the token never appears in a command line (and therefore never in
## `ps` output).
##
## The version stamps and LIBRARIES list come from scripts/tvos-versions.sh.

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

NO_SUFFIX=0
SKIP_LOCAL_AUDIT=0
DRY_RUN=0

usage() {
    cat <<EOF
Usage: $(basename "$0") [--no-suffix] [--skip-local-audit] [--dry-run] [-h|--help]

Required environment:
  REPOSILITE_URL     full repository URL, e.g. https://maven.example.com/releases
  REPOSILITE_USER    Reposilite user name
  REPOSILITE_TOKEN   Reposilite token (never echoed)

Optional environment:
  DEV_SUFFIX         version qualifier appended to every pin. When unset, the first
                     free -dev.\$(date +%Y%m%d).<N> (N in 1..50) is picked by probing
                     the server; when set explicitly, an already-published version is
                     refused before the build starts.

Flags:
  --no-suffix          publish the exact pinned versions with no dev qualifier.
                       Only allowed on a release-*-tvos* or tvos-main branch.
  --skip-local-audit   skip the mavenLocal rehearsal + closure audit.
  --dry-run            print the Gradle command lines (token masked) and exit.
EOF
}

while [ $# -gt 0 ]; do
    case "$1" in
        --no-suffix) NO_SUFFIX=1 ;;
        --skip-local-audit) SKIP_LOCAL_AUDIT=1 ;;
        --dry-run) DRY_RUN=1 ;;
        -h|--help) usage; exit 0 ;;
        *) echo "ERROR: unknown argument \"$1\"" >&2; usage >&2; exit 1 ;;
    esac
    shift
done

# ---------------------------------------------------------------- (a) guards

# JDK 21 is required by the build (org.gradle.java.installations.fromEnv=ANDROIDX_JDK21).
# Fail fast rather than let Gradle fall back to an unsupported JDK. It's not enough for the
# env var to merely be set -- verify the java binary it points at actually reports major
# version 21.
JDK21_HOME="${ANDROIDX_JDK21:-${JAVA_HOME:-}}"
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

for var in REPOSILITE_URL REPOSILITE_USER REPOSILITE_TOKEN; do
    if [ -z "${!var:-}" ]; then
        echo "ERROR: $var is required but not set." >&2
        usage >&2
        exit 1
    fi
done

# Strip a trailing slash so the verification URLs below concatenate cleanly.
REPOSILITE_URL="${REPOSILITE_URL%/}"

BRANCH="$(cd "$ROOT_DIR" && git rev-parse --abbrev-ref HEAD 2>/dev/null || echo "")"

if [ "$NO_SUFFIX" = "1" ]; then
    case "$BRANCH" in
        release-*-tvos*|tvos-main) ;;
        *)
            echo "ERROR: --no-suffix publishes the exact pinned release versions, which are" >&2
            echo "       immutable coordinates shared with the Central release. It is only" >&2
            echo "       allowed from a release-*-tvos* or tvos-main branch; current branch is" >&2
            echo "       \"$BRANCH\". Drop --no-suffix to publish a -dev.<date>.<n> build." >&2
            exit 1
            ;;
    esac
fi

if [ -n "$(cd "$ROOT_DIR" && git status --porcelain 2>/dev/null)" ]; then
    echo "WARNING: working tree is dirty; the published artifacts will not correspond to a commit." >&2
fi

# ------------------------------------------------- (b) versions + dev suffix

source "$SCRIPT_DIR/tvos-versions.sh"

COORDINATE_ROOT="dev.sajidali"
PLATFORMS="KotlinMultiplatform,TvosArm64,TvosSimulatorArm64"

# The token is written into a curl config document on stdin, never onto a command line.
reposilite_curl() {
    curl --config - "$@" <<CURLRC
user = "$REPOSILITE_USER:$REPOSILITE_TOKEN"
CURLRC
}

# Echoes the HTTP status of a HEAD request, or 000 when the server is unreachable.
remote_status() {
    local status
    status="$(reposilite_curl -s -o /dev/null -I -w '%{http_code}' --max-time 10 "$1" || true)"
    echo "${status:-000}"
}

runtime_pom_url() {
    local version="${VERSION_COMPOSE}$1"
    echo "$REPOSILITE_URL/dev/sajidali/compose/runtime/runtime/$version/runtime-$version.pom"
}

if [ "$NO_SUFFIX" = "1" ]; then
    DEV_SUFFIX=""
elif [ -n "${DEV_SUFFIX:-}" ]; then
    # Explicit suffix: a Reposilite release repository answers 409 on redeploy, which would
    # only surface after the (long) build. Refuse up front instead.
    STATUS="$(remote_status "$(runtime_pom_url "$DEV_SUFFIX")")"
    if [ "$STATUS" = "200" ]; then
        echo "ERROR: ${VERSION_COMPOSE}${DEV_SUFFIX} is already published at $REPOSILITE_URL." >&2
        echo "       Dev versions are immutable; bump the trailing .N in DEV_SUFFIX." >&2
        exit 1
    fi
    if [ "$STATUS" != "404" ] && [ "$STATUS" != "000" ]; then
        echo "ERROR: unexpected HTTP $STATUS probing $(runtime_pom_url "$DEV_SUFFIX")" >&2
        exit 1
    fi
    if [ "$STATUS" = "000" ]; then
        echo "WARNING: $REPOSILITE_URL is unreachable; skipping the collision check." >&2
    fi
else
    DATE_STAMP="$(date +%Y%m%d)"
    DEV_SUFFIX=""
    for n in $(seq 1 50); do
        CANDIDATE="-dev.${DATE_STAMP}.${n}"
        STATUS="$(remote_status "$(runtime_pom_url "$CANDIDATE")")"
        if [ "$STATUS" = "404" ]; then
            DEV_SUFFIX="$CANDIDATE"
            echo "Selected dev suffix $DEV_SUFFIX (first free version on $REPOSILITE_URL)."
            break
        fi
        if [ "$STATUS" = "000" ]; then
            if [ "$DRY_RUN" = "1" ]; then
                DEV_SUFFIX="-dev.${DATE_STAMP}.1"
                echo "NOTE: $REPOSILITE_URL is unreachable; assuming dev suffix $DEV_SUFFIX for this dry run."
                break
            fi
            echo "ERROR: $REPOSILITE_URL is unreachable; cannot pick a free dev version." >&2
            exit 1
        fi
        if [ "$STATUS" != "200" ]; then
            echo "ERROR: unexpected HTTP $STATUS probing $(runtime_pom_url "$CANDIDATE")" >&2
            exit 1
        fi
    done
    if [ -z "$DEV_SUFFIX" ]; then
        echo "ERROR: -dev.${DATE_STAMP}.1 through .50 are all published at $REPOSILITE_URL." >&2
        exit 1
    fi
fi

DEV_VERSION_COMPOSE="${VERSION_COMPOSE}${DEV_SUFFIX}"
DEV_VERSION_COMPOSE_MATERIAL3="${VERSION_COMPOSE_MATERIAL3}${DEV_SUFFIX}"
DEV_VERSION_COMPOSE_MATERIAL3_ADAPTIVE="${VERSION_COMPOSE_MATERIAL3_ADAPTIVE}${DEV_SUFFIX}"
DEV_VERSION_NAVIGATION="${VERSION_NAVIGATION}${DEV_SUFFIX}"
DEV_VERSION_NAVIGATION_3="${VERSION_NAVIGATION_3}${DEV_SUFFIX}"
DEV_VERSION_WINDOW="${VERSION_WINDOW}${DEV_SUFFIX}"
DEV_VERSION_TV_MATERIAL="${VERSION_TV_MATERIAL}${DEV_SUFFIX}"

# Every one of the seven version properties must always be passed: a missing one makes the
# internal POM edges fall back to 9999.0.0-SNAPSHOT.
VERSION_PROPS=(
    -Pjetbrains.publication.version.COMPOSE="$DEV_VERSION_COMPOSE"
    -Pjetbrains.publication.version.COMPOSE_MATERIAL3="$DEV_VERSION_COMPOSE_MATERIAL3"
    -Pjetbrains.publication.version.COMPOSE_MATERIAL3_ADAPTIVE="$DEV_VERSION_COMPOSE_MATERIAL3_ADAPTIVE"
    -Pjetbrains.publication.version.NAVIGATION="$DEV_VERSION_NAVIGATION"
    -Pjetbrains.publication.version.NAVIGATION_3="$DEV_VERSION_NAVIGATION_3"
    -Pjetbrains.publication.version.WINDOW="$DEV_VERSION_WINDOW"
    -Pjetbrains.publication.version.TV_MATERIAL="$DEV_VERSION_TV_MATERIAL"
)

# --no-configuration-cache is REQUIRED here, not an optimisation: fork-mode publishing state
# (JetBrainsPublication.coordinateRoot above all) is assigned during configuration and a reused
# configuration-cache entry never re-runs configuration. See publish-tvos-fork.sh for the
# full explanation.
COMMON_ARGS=(
    --no-configuration-cache
    -Ppublication.coordinateRoot="$COORDINATE_ROOT"
    "-Pcompose.platforms=$PLATFORMS"
    -Pjetbrains.publication.libraries="$LIBRARIES"
    -Ppublish.signing.key=
    "${VERSION_PROPS[@]}"
)

echo "About to publish to Reposilite with:"
echo "  repository     = $REPOSILITE_URL"
echo "  user           = $REPOSILITE_USER"
echo "  coordinateRoot = $COORDINATE_ROOT"
echo "  platforms      = $PLATFORMS"
echo "  libraries      = $LIBRARIES"
echo "  dev suffix     = ${DEV_SUFFIX:-<none>}"
echo "  versions:"
echo "    COMPOSE=$DEV_VERSION_COMPOSE"
echo "    COMPOSE_MATERIAL3=$DEV_VERSION_COMPOSE_MATERIAL3"
echo "    COMPOSE_MATERIAL3_ADAPTIVE=$DEV_VERSION_COMPOSE_MATERIAL3_ADAPTIVE"
echo "    NAVIGATION=$DEV_VERSION_NAVIGATION"
echo "    NAVIGATION_3=$DEV_VERSION_NAVIGATION_3"
echo "    WINDOW=$DEV_VERSION_WINDOW"
echo "    TV_MATERIAL=$DEV_VERSION_TV_MATERIAL"

if [ "$DRY_RUN" = "1" ]; then
    echo
    echo "DRY RUN: Gradle would be invoked as (credentials go through MAVEN_* env vars):"
    echo
    echo "  MAVEN_URL=$REPOSILITE_URL MAVEN_USERNAME=$REPOSILITE_USER MAVEN_PASSWORD=***"
    echo
    if [ "$SKIP_LOCAL_AUDIT" = "1" ]; then
        echo "  (c) skipped (--skip-local-audit)"
    else
        echo "  (c) ./gradlew -p mpp publishComposeJbToMavenLocal ${COMMON_ARGS[*]}"
        echo "      python3 scripts/audit-tvos-closure.py --group-prefix dev/sajidali"
    fi
    echo
    echo "  (d) ./gradlew -p mpp publishComposeJbToRemote ${COMMON_ARGS[*]}"
    echo
    exit 0
fi

# Signing is applied only when publish.signing.key / SIGNING_KEY is non-blank, and the Gradle
# property wins over the environment variable. Dev builds are not signed, so clear the env vars
# here and pass an empty -Ppublish.signing.key (see COMMON_ARGS) to neutralise a key left in
# ~/.gradle/gradle.properties by a Central release session.
unset SIGNING_KEY SIGNING_PASSWORD

# --------------------------------------------- (c) local rehearsal + audit

if [ "$SKIP_LOCAL_AUDIT" = "1" ]; then
    echo "Skipping the mavenLocal rehearsal and closure audit (--skip-local-audit)."
else
    echo
    echo "Step 1/3: publishing the dev versions to mavenLocal as a rehearsal..."
    (
        cd "$ROOT_DIR"
        ./gradlew -p mpp publishComposeJbToMavenLocal "${COMMON_ARGS[@]}"
    )

    echo
    echo "Step 2/3: auditing the local dependency closure..."
    (
        cd "$ROOT_DIR"
        python3 scripts/audit-tvos-closure.py --group-prefix dev/sajidali
    )
fi

# ------------------------------------------------------------ (d) publish

echo
echo "Step 3/3: publishing to $REPOSILITE_URL ..."
(
    cd "$ROOT_DIR"
    MAVEN_URL="$REPOSILITE_URL" \
    MAVEN_USERNAME="$REPOSILITE_USER" \
    MAVEN_PASSWORD="$REPOSILITE_TOKEN" \
        ./gradlew -p mpp publishComposeJbToRemote "${COMMON_ARGS[@]}"
)

# -------------------------------------------------------------- (e) verify

# One representative module per published library, so a library that silently failed to upload
# cannot hide behind a readable compose runtime.
VERIFY_TARGETS=(
    "COMPOSE|dev/sajidali/compose/runtime|runtime|$DEV_VERSION_COMPOSE"
    "COMPOSE_MATERIAL3|dev/sajidali/compose/material3|material3|$DEV_VERSION_COMPOSE_MATERIAL3"
    "COMPOSE_MATERIAL3_ADAPTIVE|dev/sajidali/compose/material3/adaptive|adaptive|$DEV_VERSION_COMPOSE_MATERIAL3_ADAPTIVE"
    "NAVIGATION|dev/sajidali/androidx/navigation|navigation-runtime|$DEV_VERSION_NAVIGATION"
    "NAVIGATION_3|dev/sajidali/androidx/navigation3|navigation3-ui|$DEV_VERSION_NAVIGATION_3"
    "WINDOW|dev/sajidali/androidx/window|window-core|$DEV_VERSION_WINDOW"
    "TV_MATERIAL|dev/sajidali/androidx/tv|tv-material|$DEV_VERSION_TV_MATERIAL"
)

echo
echo "Verifying one published module per library is readable..."
for target in "${VERIFY_TARGETS[@]}"; do
    IFS='|' read -r lib group_path artifact version <<<"$target"
    base="$REPOSILITE_URL/$group_path/$artifact/$version/$artifact-$version"
    for ext in pom module; do
        if reposilite_curl -fsS -o /dev/null "$base.$ext"; then
            echo "  OK  $base.$ext"
        else
            echo "  ERROR: $lib is missing: could not fetch $base.$ext" >&2
            exit 1
        fi
    done
done

# ---------------------------------------------------- (f) consumer snippet

cat <<EOF

Published. Consumer setup:

settings.gradle.kts (repositories block): keep mavenCentral() as well, the fork's artifacts
depend on upstream Kotlin/kotlinx and androidx coordinates that only Central serves:

    maven {
        name = "tvosDev"
        url = uri("$REPOSILITE_URL")
        credentials(PasswordCredentials::class)
        content { includeGroupByRegex("dev\\\\.sajidali.*") }
    }

Gradle reads the tvosDev credentials from tvosDevUsername / tvosDevPassword in
~/.gradle/gradle.properties (or ORG_GRADLE_PROJECT_tvosDevUsername / ORG_GRADLE_PROJECT_tvosDevPassword
in the environment); the repository requires credentials for reads as well as publishing.

build.gradle.kts / settings.gradle.kts (redirect plugin): map each JetBrains release version
onto this dev build. Dev versions are immutable: to pick up a new build, bump the trailing .N
in DEV_SUFFIX and update these mappings.

    composeTvos {
        manifestUrl.set("")
        versionMappings.put("org.jetbrains.compose:$VERSION_COMPOSE", "$DEV_VERSION_COMPOSE")
        versionMappings.put("org.jetbrains.compose.material3:$VERSION_COMPOSE_MATERIAL3", "$DEV_VERSION_COMPOSE_MATERIAL3")
        versionMappings.put("org.jetbrains.compose.material3.adaptive:$VERSION_COMPOSE_MATERIAL3_ADAPTIVE", "$DEV_VERSION_COMPOSE_MATERIAL3_ADAPTIVE")
        versionMappings.put("org.jetbrains.androidx.navigation:$VERSION_NAVIGATION", "$DEV_VERSION_NAVIGATION")
        versionMappings.put("org.jetbrains.androidx.navigation3:$VERSION_NAVIGATION_3", "$DEV_VERSION_NAVIGATION_3")
        versionMappings.put("org.jetbrains.androidx.window:$VERSION_WINDOW", "$DEV_VERSION_WINDOW")
        versionMappings.put("androidx.tv:$VERSION_TV_MATERIAL", "$DEV_VERSION_TV_MATERIAL")
    }

To always resolve the newest dated build instead of a fixed one, use a dynamic mapping value
such as "1.12.0-dev.+"; the plugin passes the value verbatim to Gradle. Gradle caches dynamic
versions for 24 hours unless resolutionStrategy.cacheDynamicVersionsFor(0, "seconds") is set.

EOF
