#!/bin/bash
#
## Stages a SIGNED, LOCAL Maven Central Portal upload bundle for the dev.sajidali tvOS
## Compose Multiplatform fork -- DRY-RUN PREPARATION ONLY.
##
## THIS SCRIPT NEVER UPLOADS ANYTHING TO MAVEN CENTRAL. It stops after producing a bundle
## zip on local disk and printing (as inert, commented-out text) the curl command that
## WOULD upload it. There is no flag, environment variable, or code path in this script
## that performs the upload -- see the hard refusal check right after argument parsing.
## Actually uploading is a separate, explicit, human-run step (see "Manual next step"
## printed at the end).
##
## Ledger-driven and per-module, exactly like scripts/publish-tvos-fork.sh: versions come
## ONLY from the release ledger (`--ledger` or $TVOS_LEDGER), the module list comes from
## scripts/publish_set.py, and the bundle is assembled from that explicit module list (the
## umbrella plus one leaf per tvOS target, at the ledger version) rather than from whatever
## happens to sit in ~/.m2 at a version directory name.
##
## Usage:
##   stage-central-bundle.sh [--ledger <json>] [--only-group <org.jetbrains group>[,...]]
##                           [--modules <:path>[,<:path>...]] [--force-modules]
##                           [--include-tests] [--include-tooling-preview] [--ignore-central]
##                           [--clean] [--dry-run]
##   --clean deletes a previous build/central-staging-repo + central-bundle.zip first;
##   without it a previous bundle is left alone and the script refuses to overwrite it.
##   --dry-run prints the Gradle command and the intended bundle contents, runs nothing.
##
## ---------------------------------------------------------------------------------------
## Prerequisites (the user provides these; this script only READS them, never stores them):
##   PUBLISH_SIGNING_KEY        In-memory ASCII-armored PGP private key (the same property
##                               MavenUploadHelper.kt already wires up via
##                               `publish.signing.key` / `SIGNING_KEY`).
##   PUBLISH_SIGNING_PASSWORD   Passphrase for the key above (`publish.signing.password` /
##                               `SIGNING_PASSWORD`).
##   CENTRAL_TOKEN               Base64 "username:password" (or the token Central Portal's
##                               "Generate User Token" page issues) for the account this
##                               fork will publish under. ONLY used to print the (inert)
##                               upload command -- never sent anywhere by this script.
## None of these are required to run the staging/validation/zip steps in "check" mode
## (the default); they matter once a human runs the actual upload.
## ---------------------------------------------------------------------------------------
##
## ADDITIVE-PLATFORMS POLICY (read before ever uploading -- documented per task-11-prep,
## not implemented/enforced by this script):
##
## `-Pcompose.platforms=KotlinMultiplatform,TvosArm64,TvosSimulatorArm64` (as used by
## scripts/publish-tvos-fork.sh) does NOT restrict a published umbrella module's variant Set
## to just those three targets -- confirmed directly (task-8c) by inspecting published
## `.module` files: every umbrella (e.g. `dev.sajidali.compose.ui:ui`) still carries full,
## resolvable variants for the complete default JetBrains target set (android, desktop,
## iosArm64, iosSimulatorArm64, js, macosArm64, wasmJs) IN ADDITION TO the two tvOS targets.
## `-Pcompose.platforms` is additive (it adds tvOS on top of the default target set), not a
## restriction that removes non-tvOS targets from what gets built/published.
##
## Chosen policy for release one: SHIP AS-IS. This is harmless for the actual tvOS-redirect
## use case: a non-tvOS consumer (iOS/Android/Desktop/etc.) NEVER sees a `dev.sajidali`
## coordinate at all, because the `compose-tvos-redirect` plugin only ever substitutes
## dependencies for configurations belonging to a tvOS Kotlin target -- see
## ComposeTvosRedirectSettingsPlugin/TvosVariantInjectionRule in that repo. The non-tvOS
## variants these `dev.sajidali` umbrella modules carry are simply unreachable dead weight
## from this project's perspective: they cost extra bytes in the published bundle and extra
## variants in the umbrella `.module` file, nothing else.
##
## Option (not implemented here, noted for future consideration if bundle size or Central's
## own review process ever makes it worth the build-logic change): investigate whether
## AndroidX's `KotlinTarget`/`AndroidXComposeMultiplatformExtension` wiring has (or could
## gain) a genuinely EXCLUSIVE platform-restriction flag, as opposed to `compose.platforms`'
## current additive behavior, so a `-Ppublication.coordinateRoot=dev.sajidali` build could
## produce umbrella modules containing ONLY the tvOS + common variants. This would need
## upstream buildSrc changes well beyond this script's scope (this script only stages what
## `publish-tvos-fork.sh`'s underlying Gradle task set already produces) and was
## deliberately NOT attempted here per this task's "no build-logic changes" boundary.
##
## ---------------------------------------------------------------------------------------
##
## What Central Portal actually requires (established from direct inspection of this
## fork's already-published mavenLocal tree -- see step 2 below -- plus Central Portal's
## published bundle-validation rules):
##   - Every artifact file (.pom, .module, main artifact, -sources.jar, -javadoc.jar) needs
##     a detached PGP signature (`<file>.asc`) sitting next to it in the bundle.
##   - Every published component needs a `-sources.jar`. CONFIRMED already produced by the
##     existing build for every module this script stages (see MavenUploadHelper.kt's KMP
##     anchor-publication `sourcesComponents` wiring) -- nothing to add here.
##   - Every published component ALSO needs a `-javadoc.jar`, even a non-Java one (Central's
##     validator checks for the file's PRESENCE, not its content, for non-Java/Kotlin/Native
##     artifacts). CONFIRMED MISSING: direct inspection of this fork's already-published
##     mavenLocal tree (`~/.m2/repository/dev/sajidali/compose/ui/ui/1.12.0-beta01/` and
##     `.../ui-tvosarm64/1.12.0-beta01/`) shows `.jar`/`.klib`, `-sources.jar`, `.module`,
##     `.pom` -- and NO `-javadoc.jar` anywhere, for either the JVM-style umbrella artifact
##     or the native tvOS variant artifact. `MavenUploadHelper.kt` (read directly for this
##     task) has no javadoc-jar generation logic at all. Step 2 below generates a trivial,
##     empty (single placeholder text file) javadoc jar for every module directory found to
##     be missing one -- this is the standard, Sonatype-sanctioned workaround for
##     non-Java-doc-able artifacts (native klibs, resource-only modules, etc.); it satisfies
##     Central's presence check without fabricating fake documentation content.
##   - MD5/SHA1 checksums: Gradle's Maven-repository publish tasks generate these
##     automatically for every artifact published to ANY `MavenArtifactRepository`,
##     including a `file://` one (confirmed: `find <staging-repo> -name '*.jar.sha1'` finds
##     entries after step 1 below runs) -- no extra step needed here.
##
## ---------------------------------------------------------------------------------------

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SCRIPT_DIR="$ROOT_DIR/scripts"
STAGING_REPO_DIR="$ROOT_DIR/build/central-staging-repo"
JAVADOC_STAGING_DIR="$ROOT_DIR/build/central-staging-javadoc-stubs"
BUNDLE_ZIP="$ROOT_DIR/build/central-bundle.zip"

LEDGER="${TVOS_LEDGER:-}"
MODULES_OVERRIDE=""
FORCE_MODULES=0
DRY_RUN=0
CLEAN=0
PSET_ARGS=()
while [ $# -gt 0 ]; do
    case "$1" in
        ## --- Hard refusal: this script NEVER uploads, no matter what flag is passed. -----
        --upload|--publish|--push)
            echo "REFUSED: stage-central-bundle.sh does not perform uploads under any flag." >&2
            echo "This is intentional (Phase 5 gate: Central Portal upload requires explicit," >&2
            echo "separate human action with real credentials). See the 'Manual next step'" >&2
            echo "section this script prints on a successful run for the actual command." >&2
            exit 1
            ;;
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
        --clean) CLEAN=1; shift ;;
        --dry-run) DRY_RUN=1; shift ;;
        -h|--help) sed -n '/^## Usage:/,/^##$/p' "$0" | sed 's/^## \{0,1\}//'; exit 0 ;;
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

COORDINATE_ROOT="dev.sajidali"
PLATFORMS="KotlinMultiplatform,TvosArm64,TvosSimulatorArm64"
M2_REPO="${HOME}/.m2/repository"

echo "=== Step 0: preconditions ==="
if [ ! -x "$ROOT_DIR/gradlew" ]; then
    echo "ERROR: $ROOT_DIR/gradlew not found/executable." >&2
    exit 1
fi
JDK21_HOME="${ANDROIDX_JDK21:-${JAVA_HOME:-}}"
if [ -z "$JDK21_HOME" ] || [ ! -x "$JDK21_HOME/bin/java" ] || ! "$JDK21_HOME/bin/java" -version 2>&1 | grep -q 'version "21'; then
    if [ "$DRY_RUN" -eq 1 ]; then
        echo "WARNING (dry-run, not fatal): JDK 21 required (ANDROIDX_JDK21/JAVA_HOME)." >&2
    else
        echo "ERROR: JDK 21 required (ANDROIDX_JDK21/JAVA_HOME). See publish-tvos-fork.sh's own check." >&2
        exit 1
    fi
fi
if [ -z "${PUBLISH_SIGNING_KEY:-}" ]; then
    echo "WARNING: PUBLISH_SIGNING_KEY is not set -- Step 1 will publish UNSIGNED artifacts" >&2
    echo "         to the local staging repo (MavenUploadHelper.kt only applies the signing" >&2
    echo "         plugin when a key is present). That is fine for a dry run of the bundle" >&2
    echo "         layout/completeness logic below, but Central will reject an unsigned" >&2
    echo "         bundle -- re-run with PUBLISH_SIGNING_KEY/PUBLISH_SIGNING_PASSWORD set" >&2
    echo "         before treating a successful run of this script as upload-ready." >&2
fi

# A previous bundle is only removed on --clean; otherwise it is left untouched and this run
# refuses to build on top of (or silently replace) it.
previous_outputs=""
for f in "$STAGING_REPO_DIR" "$JAVADOC_STAGING_DIR" "$BUNDLE_ZIP"; do
    [ -e "$f" ] && previous_outputs="$previous_outputs $f"
done
if [ -n "$previous_outputs" ]; then
    if [ "$CLEAN" -eq 1 ]; then
        echo "  --clean: removing previous outputs:$previous_outputs"
        [ "$DRY_RUN" -eq 1 ] || rm -rf "$STAGING_REPO_DIR" "$JAVADOC_STAGING_DIR" "$BUNDLE_ZIP"
    else
        echo "ERROR: previous bundle outputs exist:$previous_outputs" >&2
        echo "       Re-run with --clean to delete them, or move them aside first." >&2
        [ "$DRY_RUN" -eq 1 ] || exit 1
        echo "       (dry-run: continuing anyway)" >&2
    fi
fi

# Derive the publish set. publish_set.py exits non-zero (and prints why) when any library
# key the build registers has no version in the ledger; the eval below then never runs.
echo "=== Step 0b: publish set (from ledger) ==="
python3 "$SCRIPT_DIR/publish_set.py" --ledger "$LEDGER" --format table ${PSET_ARGS[@]+"${PSET_ARGS[@]}"}
shell_out="$(python3 "$SCRIPT_DIR/publish_set.py" --ledger "$LEDGER" --format shell ${PSET_ARGS[@]+"${PSET_ARGS[@]}"})" || exit 1
eval "$shell_out"
# eval defines: LEDGER_FILE LEDGER_CMP_VERSION LEDGER_KOTLIN VERSION_PROPS[] PUBLISH_LIBRARIES
#               PUBLISH_MODULES[] PUBLISH_COORDS[] UNBUILDABLE_COORDS[]

# MODULES and COORDS stay index-aligned: COORDS[i] is the org.jetbrains coordinate that
# MODULES[i] publishes (group rewritten to $COORDINATE_ROOT below when locating output).
MODULES=(${PUBLISH_MODULES[@]+"${PUBLISH_MODULES[@]}"})
COORDS=(${PUBLISH_COORDS[@]+"${PUBLISH_COORDS[@]}"})
if [ -n "$MODULES_OVERRIDE" ]; then
    MODULES=()
    COORDS=()
    for m in ${MODULES_OVERRIDE//,/ }; do
        found=""
        i=0
        for p in ${PUBLISH_MODULES[@]+"${PUBLISH_MODULES[@]}"}; do
            [ "$p" = "$m" ] && found="${PUBLISH_COORDS[$i]}"
            i=$((i + 1))
        done
        if [ -z "$found" ]; then
            if [ "$FORCE_MODULES" -eq 1 ]; then
                echo "!!! WARNING: $m is NOT in the ledger-derived publish set; staging it anyway" >&2
                echo "!!!          because --force-modules was given. Its upstream either already ships" >&2
                echo "!!!          usable tvOS klibs, or its dev.sajidali twin is already on Central," >&2
                echo "!!!          or it is on the NEVER_PUBLISH list. Central will reject a re-upload." >&2
                # No ledger coordinate for a forced module: derive it from the project path the
                # same way JetBrainsPublication.mavenGroupFor does, with the library version
                # looked up by asking publish_set.py for the ignore-central view.
                found="$(python3 "$SCRIPT_DIR/publish_set.py" --ledger "$LEDGER" --format json --ignore-central --include-tests --include-tooling-preview \
                    | python3 -c 'import json,sys; m=sys.argv[1]; r=json.load(sys.stdin); print(next(("%s:%s:%s"%(p["group"],p["artifact"],p["version"]) for p in r["publish"] if p["project"]==m), ""))' "$m")"
                if [ -z "$found" ]; then
                    echo "ERROR: $m has no coordinate in the ledger at all; cannot stage it." >&2
                    exit 1
                fi
            else
                echo "ERROR: --modules entry $m is not in the derived publish set (see table above)." >&2
                echo "       Pass --force-modules to override, or --ignore-central for a rebuild view." >&2
                exit 1
            fi
        fi
        MODULES+=("$m")
        COORDS+=("$found")
    done
fi
if [ "${#MODULES[@]}" -eq 0 ]; then
    echo "ERROR: nothing to stage: every ledger artifact is either usable upstream, already" >&2
    echo "       on Central as dev.sajidali, or on NEVER_PUBLISH. Use --ignore-central to see" >&2
    echo "       the full rebuild set, or --modules with --force-modules." >&2
    exit 1
fi

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

# Intended bundle contents: for each module, the umbrella directory plus one leaf directory
# per native target (<artifact>-<target lowercased>), all at the ledger version, under the
# $COORDINATE_ROOT-rewritten group. This list, not a `find -name <version>` over ~/.m2, is
# what Step 1b copies: ~/.m2/dev/sajidali also holds every previously released version and
# other fork groups, and Central rejects any coordinate it has already published.
BUNDLE_DIRS=()
i=0
for c in "${COORDS[@]}"; do
    group="${c%%:*}"; rest="${c#*:}"; artifact="${rest%%:*}"; version="${rest#*:}"
    group="${group/#org.jetbrains./$COORDINATE_ROOT.}"
    group_path="${group//.//}"
    BUNDLE_DIRS+=("$group_path/$artifact/$version")
    for p in ${PLATFORMS//,/ }; do
        [ "$p" = "KotlinMultiplatform" ] && continue
        leaf="$(printf '%s' "$p" | tr '[:upper:]' '[:lower:]')"
        BUNDLE_DIRS+=("$group_path/$artifact-$leaf/$version")
    done
    i=$((i + 1))
done

# --- Step 1 mechanism note ---
# MavenUploadHelper.kt's `configureMavenArtifactUpload` registers exactly two named
# repositories per project: an unnamed default ("...ToMavenRepository" task suffix,
# pointed at AndroidX's own internal out/repository staging directory -- not something
# this script's -Ppublish.maven.url can redirect, since that property only ever adds a
# SEPARATE, named "Remote" repository) and MavenLocal (via the standard maven-publish
# plugin mechanism, task suffix "ToMavenLocal"). There is no pre-registered publish task
# wired to a custom "Remote"/file:// URL, and adding one would be a build-logic change
# outside this script's scope.
#
# So: run the same per-module ...ToMavenLocal tasks scripts/publish-tvos-fork.sh uses, with
# signing properties added (signing applies to every MavenPublication regardless of which
# repository task publishes it, so real ~/.m2 output IS signed when PUBLISH_SIGNING_KEY is
# set). Step 1b below then copies ONLY the explicit module directories out of ~/.m2 into an
# isolated staging directory -- this both gives us the same "local file:// repo, ready to
# zip" shape the Central Portal bundle needs AND avoids ever bundling unrelated,
# pre-existing ~/.m2 content.
#
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
SIGNING_ARGS=()
SIGNING_ARGS_SHOWN=()
if [ -n "${PUBLISH_SIGNING_KEY:-}" ]; then
    SIGNING_ARGS+=(-Ppublish.signing.key="$PUBLISH_SIGNING_KEY")
    SIGNING_ARGS_SHOWN+=('-Ppublish.signing.key=<PUBLISH_SIGNING_KEY>')
fi
if [ -n "${PUBLISH_SIGNING_PASSWORD:-}" ]; then
    SIGNING_ARGS+=(-Ppublish.signing.password="$PUBLISH_SIGNING_PASSWORD")
    SIGNING_ARGS_SHOWN+=('-Ppublish.signing.password=<PUBLISH_SIGNING_PASSWORD>')
fi

echo
echo "Plan:"
echo "  ledger         = $LEDGER_FILE (CMP $LEDGER_CMP_VERSION, Kotlin $LEDGER_KOTLIN)"
echo "  coordinateRoot = $COORDINATE_ROOT"
echo "  platforms      = $PLATFORMS"
echo "  modules        = ${MODULES[*]}"
echo "  signing        = $([ -n "${PUBLISH_SIGNING_KEY:-}" ] && echo yes || echo no)"
echo "  versions:"
for v in "${VERSION_PROPS[@]}"; do echo "    ${v#-Pjetbrains.publication.version.}"; done
echo
echo "Gradle command (cwd $ROOT_DIR):"
printf '  %q' "${GRADLE_CMD[@]}" ${SIGNING_ARGS_SHOWN[@]+"${SIGNING_ARGS_SHOWN[@]}"}; echo
echo
echo "Intended bundle contents ($STAGING_REPO_DIR, ${#BUNDLE_DIRS[@]} module dirs):"
for d in "${BUNDLE_DIRS[@]}"; do
    if [ -d "$M2_REPO/$d" ]; then state="present in ~/.m2"; else state="not yet in ~/.m2"; fi
    echo "  $d  ($state)"
done

if [ "$DRY_RUN" -eq 1 ]; then
    echo
    echo "=== DRY RUN: no Gradle invoked, nothing copied, no bundle written ==="
    exit 0
fi

mkdir -p "$STAGING_REPO_DIR" "$JAVADOC_STAGING_DIR"

echo "=== Step 1: signed publish to mavenLocal (real ~/.m2, same tasks as publish-tvos-fork.sh) ==="
(
    cd "$ROOT_DIR"
    "${GRADLE_CMD[@]}" ${SIGNING_ARGS[@]+"${SIGNING_ARGS[@]}"}
)

echo "=== Step 1b: copy the explicit module directories out of ~/.m2 into an isolated staging repo ==="
echo "  from: $M2_REPO"
echo "  to:   $STAGING_REPO_DIR"
missing_dirs=0
for d in "${BUNDLE_DIRS[@]}"; do
    if [ ! -d "$M2_REPO/$d" ]; then
        echo "  MISSING after publish: $M2_REPO/$d" >&2
        missing_dirs=$((missing_dirs + 1))
        continue
    fi
    mkdir -p "$STAGING_REPO_DIR/$(dirname "$d")"
    cp -R "$M2_REPO/$d" "$STAGING_REPO_DIR/$d"
done
if [ "$missing_dirs" -gt 0 ]; then
    echo "ERROR: $missing_dirs expected module directory(ies) were not produced by Step 1." >&2
    exit 1
fi
echo "  module dirs staged: ${#BUNDLE_DIRS[@]}"

echo "=== Step 2: validate bundle completeness (+ generate stub javadoc jars) ==="
missing_sources=0
missing_signatures=0
generated_javadoc=0

# One iteration per published module directory (identified by its .pom file) --
# a module directory holds one version of one artifact, e.g.
# dev/sajidali/compose/ui/ui-tvosarm64/1.12.0/.
while IFS= read -r -d '' pom_file; do
    module_dir="$(dirname "$pom_file")"
    base="${pom_file%.pom}"          # .../ui-tvosarm64-1.12.0
    artifact_base="$(basename "$base")"

    # pom-packaging components (e.g. Gradle plugin markers) are POM-only by design;
    # Central's sources/javadoc requirements apply to jar-packaging components only.
    # (Their POM still needs an .asc signature -- the signature loop below runs for them.)
    if grep -q "<packaging>pom</packaging>" "$pom_file"; then
        pom_only=1
    else
        pom_only=0
    fi

    if [ "$pom_only" -eq 0 ] && [ ! -f "$base-sources.jar" ]; then
        echo "  MISSING sources jar: $base-sources.jar"
        missing_sources=$((missing_sources + 1))
    fi

    if [ "$pom_only" -eq 0 ] && [ ! -f "$base-javadoc.jar" ]; then
        stub_readme="$JAVADOC_STAGING_DIR/README-$artifact_base.txt"
        cat > "$stub_readme" <<STUB
No API documentation is generated for this Kotlin/Native or resource-only artifact.
This placeholder javadoc jar exists solely to satisfy Maven Central Portal's bundle
validation, which requires a -javadoc.jar to be present for every published component.
STUB
        jar cf "$base-javadoc.jar" -C "$JAVADOC_STAGING_DIR" "README-$artifact_base.txt"
        echo "  Generated stub javadoc jar: $base-javadoc.jar"
        generated_javadoc=$((generated_javadoc + 1))
    fi

    # Every artifact file next to this .pom (main artifact of whatever extension, plus
    # .module/.pom/-sources.jar/-javadoc.jar) needs a sibling .asc detached signature.
    while IFS= read -r -d '' artifact_file; do
        case "$artifact_file" in
            *.asc|*.md5|*.sha1|*.sha256|*.sha512|*.module.asc) continue ;;
        esac
        if [ ! -f "$artifact_file.asc" ]; then
            missing_signatures=$((missing_signatures + 1))
        fi
    done < <(find "$module_dir" -maxdepth 1 -type f -name "$artifact_base*" -print0)
done < <(find "$STAGING_REPO_DIR" -type f -name "*.pom" -print0)

echo "  Stub javadoc jars generated: $generated_javadoc"
echo "  Missing sources jars: $missing_sources"
echo "  Missing .asc signatures: $missing_signatures"
if [ "$missing_sources" -gt 0 ]; then
    echo "ERROR: one or more modules have no sources jar -- Central will reject the bundle." >&2
    exit 1
fi
if [ "$missing_signatures" -gt 0 ]; then
    if [ -z "${PUBLISH_SIGNING_KEY:-}" ]; then
        echo "NOTE: $missing_signatures missing .asc signature(s) -- expected, since this run" >&2
        echo "      had no PUBLISH_SIGNING_KEY (see the Step 0 warning above). Re-run with" >&2
        echo "      signing credentials set before treating this bundle as upload-ready." >&2
    else
        # The unsigned files are (by construction) the stub javadoc jars generated in this
        # step, AFTER Gradle's signed publish ran -- Gradle never saw them. Sign them here
        # with the same key, imported into a throwaway GNUPGHOME so this stays self-contained
        # and never touches the operator's default keyring state.
        echo "=== Step 2b: sign the $missing_signatures artifact(s) Gradle didn't see (stub javadocs) ==="
        SIGN_HOME="$(mktemp -d)"
        chmod 700 "$SIGN_HOME"
        printf '%s\n' "$PUBLISH_SIGNING_KEY" | GNUPGHOME="$SIGN_HOME" gpg --batch --quiet --import
        signed_now=0
        while IFS= read -r -d '' artifact_file; do
            case "$artifact_file" in
                *.asc|*.md5|*.sha1|*.sha256|*.sha512) continue ;;
            esac
            if [ ! -f "$artifact_file.asc" ]; then
                GNUPGHOME="$SIGN_HOME" gpg --batch --quiet --pinentry-mode loopback \
                    --passphrase "$PUBLISH_SIGNING_PASSWORD" \
                    --detach-sign --armor --output "$artifact_file.asc" "$artifact_file"
                signed_now=$((signed_now + 1))
            fi
        done < <(find "$STAGING_REPO_DIR" -type f -print0)
        rm -rf "$SIGN_HOME"
        echo "  Signed in step 2b: $signed_now"
        # Re-count: anything STILL unsigned is a genuine failure.
        still_missing=0
        while IFS= read -r -d '' artifact_file; do
            case "$artifact_file" in
                *.asc|*.md5|*.sha1|*.sha256|*.sha512) continue ;;
            esac
            [ -f "$artifact_file.asc" ] || still_missing=$((still_missing + 1))
        done < <(find "$STAGING_REPO_DIR" -type f -print0)
        if [ "$still_missing" -gt 0 ]; then
            echo "ERROR: $still_missing artifact(s) still unsigned after step 2b -- investigate." >&2
            exit 1
        fi
    fi
fi

# Central Portal bundles require .md5 and .sha1 checksums for every artifact file.
# Gradle generates them when publishing to a maven { url = ... } repository but NOT for
# publishToMavenLocal (~/.m2), which is what Step 1 uses -- so generate any missing ones
# here. (.asc signature files themselves do not need checksums.)
echo "=== Step 2c: generate missing .md5/.sha1 checksums ==="
generated_checksums=0
while IFS= read -r -d '' artifact_file; do
    case "$artifact_file" in
        *.asc|*.md5|*.sha1|*.sha256|*.sha512|*maven-metadata-local.xml) continue ;;
    esac
    if [ ! -f "$artifact_file.md5" ]; then
        md5 -q "$artifact_file" > "$artifact_file.md5"
        generated_checksums=$((generated_checksums + 1))
    fi
    if [ ! -f "$artifact_file.sha1" ]; then
        shasum -a 1 "$artifact_file" | cut -d' ' -f1 > "$artifact_file.sha1"
        generated_checksums=$((generated_checksums + 1))
    fi
done < <(find "$STAGING_REPO_DIR" -type f -print0)
echo "  Checksum files generated: $generated_checksums"

# maven-metadata-local.xml files are mavenLocal-only bookkeeping; they must not ship in a
# Central bundle.
find "$STAGING_REPO_DIR" -name 'maven-metadata-local.xml*' -delete

echo "=== Step 3: zip bundle (Central Portal layout: repo-root-relative paths) ==="
(
    cd "$STAGING_REPO_DIR"
    zip -q -r "$BUNDLE_ZIP" . -x "*.DS_Store"
)
echo "  Bundle written: $BUNDLE_ZIP ($(du -h "$BUNDLE_ZIP" | cut -f1))"

echo
echo "=== DONE (dry run only -- nothing was uploaded) ==="
echo
echo "Manual next step (NOT performed by this script -- requires a human with real"
echo "Central Portal credentials to run it deliberately):"
echo
echo "# curl --request POST \\"
echo "#   --header \"Authorization: Bearer \$CENTRAL_TOKEN\" \\"
echo "#   --form bundle=@\"$BUNDLE_ZIP\" \\"
echo "#   \"https://central.sonatype.com/api/v1/publisher/upload?name=dev.sajidali-compose-tvos-fork-$LEDGER_CMP_VERSION&publishingType=USER_MANAGED\""
echo
echo "(USER_MANAGED means the upload lands in Central Portal's review UI for manual"
echo "'Publish' confirmation rather than auto-publishing on validation success -- an extra,"
echo "deliberate human checkpoint before this fork's artifacts become permanently public"
echo "and irreversible on Central. Switch to AUTOMATIC only once this staged/signed flow"
echo "has been exercised successfully at least once.)"
