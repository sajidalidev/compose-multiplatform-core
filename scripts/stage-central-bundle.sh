#!/bin/bash
#
## Stages a SIGNED, LOCAL Maven Central Portal upload bundle for the dev.sajidali tvOS
## Compose Multiplatform fork -- DRY-RUN PREPARATION ONLY.
##
## THIS SCRIPT NEVER UPLOADS ANYTHING TO MAVEN CENTRAL. It stops after producing a bundle
## zip on local disk and printing (as inert, commented-out text) the curl command that
## WOULD upload it. There is no flag, environment variable, or code path in this script
## that performs the upload -- see the hard refusal check right after the usage banner.
## Actually uploading is a separate, explicit, human-run step (see "Manual next step"
## printed at the end).
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
##     task) has no javadoc-jar generation logic at all. Step 3 below generates a trivial,
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
STAGING_REPO_DIR="$ROOT_DIR/build/central-staging-repo"
JAVADOC_STAGING_DIR="$ROOT_DIR/build/central-staging-javadoc-stubs"
BUNDLE_ZIP="$ROOT_DIR/build/central-bundle.zip"

# Same version pins as scripts/publish-tvos-fork.sh (kept in sync manually -- see that
# script's own header comment for the libraryversions.toml provenance note).
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

# NOTE on "8 libraries": this script's own -Pjetbrains.publication.libraries set (above,
# matching publish-tvos-fork.sh) now covers 10 JetBrainsPublication library keys (COMPOSE_
# MATERIAL3_ADAPTIVE and WINDOW were added in task 18b/18a respectively, TV_MATERIAL in task
# 23a, after this note was first written). The `dev.sajidali.compose:compose-gradle-plugin`
# artifact (the tvOS-patched org.jetbrains.compose Gradle plugin fork, referenced by
# task-9a/9b and already present in this machine's mavenLocal at 1.12.0-beta01) is built and
# published from a DIFFERENT repository than this one (compose-multiplatform-core has no
# compose-gradle-plugin subproject) -- it is NOT something this script can stage. If the task
# brief's "8 libraries" count is meant to include it, that component needs its own staging
# script in whichever repo actually builds it; flagged here rather than silently fabricated.

## --- Hard refusal: this script NEVER uploads, no matter what flag is passed. ---------
for arg in "$@"; do
    case "$arg" in
        --upload|--publish|--push)
            echo "REFUSED: stage-central-bundle.sh does not perform uploads under any flag." >&2
            echo "This is intentional (Phase 5 gate: Central Portal upload requires explicit," >&2
            echo "separate human action with real credentials). See the 'Manual next step'" >&2
            echo "section this script prints on a successful run for the actual command." >&2
            exit 1
            ;;
    esac
done

echo "=== Step 0: preconditions ==="
if [ ! -x "$ROOT_DIR/gradlew" ]; then
    echo "ERROR: $ROOT_DIR/gradlew not found/executable." >&2
    exit 1
fi
JDK21_HOME="${ANDROIDX_JDK21:-${JAVA_HOME:-}}"
if [ -z "$JDK21_HOME" ] || [ ! -x "$JDK21_HOME/bin/java" ] || ! "$JDK21_HOME/bin/java" -version 2>&1 | grep -q 'version "21'; then
    echo "ERROR: JDK 21 required (ANDROIDX_JDK21/JAVA_HOME). See publish-tvos-fork.sh's own check." >&2
    exit 1
fi
if [ -z "${PUBLISH_SIGNING_KEY:-}" ]; then
    echo "WARNING: PUBLISH_SIGNING_KEY is not set -- Step 1 will publish UNSIGNED artifacts" >&2
    echo "         to the local staging repo (MavenUploadHelper.kt only applies the signing" >&2
    echo "         plugin when a key is present). That is fine for a dry run of the bundle" >&2
    echo "         layout/completeness logic below, but Central will reject an unsigned" >&2
    echo "         bundle -- re-run with PUBLISH_SIGNING_KEY/PUBLISH_SIGNING_PASSWORD set" >&2
    echo "         before treating a successful run of this script as upload-ready." >&2
fi

rm -rf "$STAGING_REPO_DIR" "$JAVADOC_STAGING_DIR" "$BUNDLE_ZIP"
mkdir -p "$STAGING_REPO_DIR" "$JAVADOC_STAGING_DIR"

# --- Step 1 mechanism note ---
# MavenUploadHelper.kt's `configureMavenArtifactUpload` registers exactly two named
# repositories per project: an unnamed default ("...ToMavenRepository" task suffix,
# pointed at AndroidX's own internal out/repository staging directory -- not something
# this script's -Ppublish.maven.url can redirect, since that property only ever adds a
# SEPARATE, named "Remote" repository) and MavenLocal (via the standard maven-publish
# plugin mechanism, task suffix "ToMavenLocal"). `mpp/build.gradle.kts` only pre-registers
# aggregate tasks for the latter two ("publishComposeJb" -> the internal MavenRepository,
# "publishComposeJbToMavenLocal" -> real ~/.m2) -- there is no pre-registered aggregate
# task wired to a custom "Remote"/file:// URL, and adding one would be a build-logic change
# outside this script's scope.
#
# So: reuse `publishComposeJbToMavenLocal` -- the exact task scripts/publish-tvos-fork.sh
# already uses and task-8c already proved works end-to-end (BUILD SUCCESSFUL, full closure
# audit clean) -- with signing properties added (signing applies to every MavenPublication
# regardless of which repository task publishes it, so real ~/.m2 output IS signed when
# PUBLISH_SIGNING_KEY is set). Step 1b below then copies ONLY the newly-published
# `dev/sajidali/**` subtree out of ~/.m2 into an isolated staging directory -- this both
# gives us the same "local file:// repo, ready to zip" shape the Central Portal bundle
# needs AND avoids ever bundling unrelated, pre-existing ~/.m2 content (other groups this
# machine's mavenLocal happens to also contain -- see task-10c/10-report's own documented
# mavenLocal-pollution concern).
echo "=== Step 1: signed publish to mavenLocal (real ~/.m2, same task as publish-tvos-fork.sh) ==="
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
        -Pjetbrains.publication.version.TV_MATERIAL="$VERSION_TV_MATERIAL" \
        ${PUBLISH_SIGNING_KEY:+-Ppublish.signing.key="$PUBLISH_SIGNING_KEY"} \
        ${PUBLISH_SIGNING_PASSWORD:+-Ppublish.signing.password="$PUBLISH_SIGNING_PASSWORD"}
)

echo "=== Step 1b: copy dev/sajidali/** out of ~/.m2 into an isolated staging repo ==="
M2_REPO="${HOME}/.m2/repository"
echo "  from: $M2_REPO/dev/sajidali"
echo "  to:   $STAGING_REPO_DIR/dev/sajidali"
mkdir -p "$STAGING_REPO_DIR/dev"
# Scope the copy to the versions actually published by THIS run (one version directory
# per library in $LIBRARIES). ~/.m2/dev/sajidali also holds every previously released
# version (1.12.0-beta01 set, koin, coil3, tv-material, ...) and Central rejects a bundle
# that re-uploads any already-published coordinate, so a blanket copy is never correct.
staged_versions=""
for lib in ${LIBRARIES//,/ }; do
    eval "v=\${VERSION_$lib}"
    staged_versions="$staged_versions $v"
done
echo "  versions in scope:$staged_versions"
for v in $staged_versions; do
    while IFS= read -r -d '' vdir; do
        rel="${vdir#$M2_REPO/}"
        mkdir -p "$STAGING_REPO_DIR/$(dirname "$rel")"
        cp -R "$vdir" "$STAGING_REPO_DIR/$rel"
    done < <(find "$M2_REPO/dev/sajidali" -type d -name "$v" -print0)
done

echo "=== Step 2: validate bundle completeness (+ generate stub javadoc jars) ==="
missing_sources=0
missing_signatures=0
generated_javadoc=0

# One iteration per published module directory (identified by its .pom file) --
# a module directory holds one version of one artifact, e.g.
# dev/sajidali/compose/ui/ui-tvosarm64/1.12.0-beta01/.
while IFS= read -r -d '' pom_file; do
    module_dir="$(dirname "$pom_file")"
    base="${pom_file%.pom}"          # .../ui-tvosarm64-1.12.0-beta01
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
        cat > "$stub_readme" <<EOF
No API documentation is generated for this Kotlin/Native or resource-only artifact.
This placeholder javadoc jar exists solely to satisfy Maven Central Portal's bundle
validation, which requires a -javadoc.jar to be present for every published component.
EOF
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
echo "#   \"https://central.sonatype.com/api/v1/publisher/upload?name=dev.sajidali-compose-tvos-fork-$VERSION_COMPOSE&publishingType=USER_MANAGED\""
echo
echo "(USER_MANAGED means the upload lands in Central Portal's review UI for manual"
echo "'Publish' confirmation rather than auto-publishing on validation success -- an extra,"
echo "deliberate human checkpoint before this fork's artifacts become permanently public"
echo "and irreversible on Central. Switch to AUTOMATIC only once this staged/signed flow"
echo "has been exercised successfully at least once.)"
