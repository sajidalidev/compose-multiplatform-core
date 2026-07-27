---
name: publish-tvos-fork
description: Use when publishing the tvOS fork's Compose Multiplatform artifacts (dev.sajidali.* coordinate root) — mavenLocal smoke-testing, the mandatory closure audit, staging a signed Maven Central Portal bundle, or diagnosing a publish failure on the `tvos-publishing` branch. Specific to the dev.sajidali coordinate-root publish flow in this repository.
version: 1.1.0
---

This skill publishes the tvOS fork's `dev.sajidali.*`-coordinate Compose Multiplatform
artifacts, in three stages: mavenLocal (fast, local, safe to repeat), the closure audit
(mandatory gate before trusting a publish), and a staged/signed Central Portal bundle
(dry-run preparation only — no upload). It never uploads anything itself.

# Core principle — a `BUILD SUCCESSFUL` mavenLocal publish is NOT proof the fork is releasable

`publishComposeJbToMavenLocal` succeeding only means Gradle didn't crash. It does not mean
every tvOS-relevant module's dependency graph is actually satisfiable (internally, via the
redirect group rewrite, or via genuine upstream tvOS support). That is what
`scripts/audit-tvos-closure.py` checks, and it is a **mandatory** gate, not an optional
sanity check — do not treat a green mavenLocal publish as release-ready without also running
it and getting exit 0.

# Prerequisites

- JDK 21 is required by the build (`org.gradle.java.installations.fromEnv=ANDROIDX_JDK21`).
  Set it before running anything below:
  ```bash
  export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
  export ANDROIDX_JDK21="$JAVA_HOME"
  ```
- Branch: `tvos-publishing` (carries the coordinate-root/publish-wrapper/audit commits on top
  of `tvos-main`). Confirm `git branch --show-current` before running any publish — these
  scripts are not meant to run against plain `tvos-main` or `jb-main`.
- Clean tree: `git status --short` should be empty (or only contain files you intend to
  publish from) before a publish you plan to trust. An uncommitted local edit silently
  becomes part of what gets published.
- Python 3 (for `audit-tvos-closure.py`) with no extra dependencies (stdlib only).

# Stage 1 — mavenLocal publish (`scripts/publish-tvos-fork.sh`)

```bash
./scripts/publish-tvos-fork.sh
```

What it does: wraps `./gradlew -p mpp publishComposeJbToMavenLocal` with the fork's fixed
property set —
- `-Ppublication.coordinateRoot=dev.sajidali` (rewrites `org.jetbrains.compose.*` /
  `org.jetbrains.androidx.*` group roots to `dev.sajidali.*`; everything after the root
  segment is untouched).
- `-Pcompose.platforms=KotlinMultiplatform,TvosArm64,TvosSimulatorArm64`.
- `-Pjetbrains.publication.libraries=COMPOSE,COMPOSE_MATERIAL3,COMPOSE_MATERIAL3_ADAPTIVE,LIFECYCLE,NAVIGATION,NAVIGATION_3,NAVIGATION_EVENT,SAVEDSTATE,WINDOW,TV_MATERIAL`
  (10 libraries; see "The 11th library" below).
- A per-library `-Pjetbrains.publication.version.<LIB>=<version>` pin, hardcoded in the
  script and manually kept in sync with `libraryversions.toml`. The script's header comments
  are the authoritative per-library rationale — read them when the set looks surprising.

**The library set.** `COMPOSE_MATERIAL3_ADAPTIVE` was originally excluded (its `adaptive`
module imports `androidx.window.core.layout.WindowSizeClass`, and upstream
`androidx.window:window-core` publishes no Kotlin/Native klib), but task 18a made
`:window:window-core` fork-buildable with real tvOS klib variants — its android variant wraps
in `redirect("androidx.window") { ... }` so Android still resolves to the real androidx
artifact — and task 18b re-included `COMPOSE_MATERIAL3_ADAPTIVE` and lifted the
`material3-adaptive-navigation-suite` trim from `COMPOSE_MATERIAL3`'s component list.
`WINDOW` must therefore always be published alongside adaptive (the
`project(":window:window-core")` reference depends on it). `TV_MATERIAL` (task 23a) publishes
the in-tree `androidx.tv:tv-material` port the same redirect-wrapped way; `tv-foundation` was
deliberately not ported (tv-material does not depend on it).

**The stability-gate behavior.** `JetBrainsVerifyDependencyVersionsTask` (AndroidX's
"a beta artifact may not depend on an alpha artifact" rule) fails by default here, because
this fork's version pins are intentionally mixed release-phases (`COMPOSE=1.12.0-beta01` but
e.g. `NAVIGATION=2.10.0-alpha05`, `SAVEDSTATE=1.5.0-alpha01`) — those pins simply republish
upstream's own existing version combination under a different coordinate root; the
beta-on-alpha shape is upstream's state, not something this fork introduced. The gate is
scoped off for exactly this case in
`buildSrc/private/.../JetBrainsVerifyDependencyVersionsTask.kt`:
```kotlin
task.onlyIf { JetBrainsPublication.coordinateRoot == "org.jetbrains" }
```
i.e. the check still runs (and still matters) for ordinary `org.jetbrains` publishes; it is
skipped only when `-Ppublication.coordinateRoot` overrides the root. Expect to see many
`jbVerifyDependencyVersions SKIPPED` lines in the log — that is this gate working as intended,
not a problem.

**The 11th library.** `dev.sajidali.compose:compose-gradle-plugin` (the tvOS-patched
`org.jetbrains.compose` Gradle plugin fork that `compose-tvos-redirect`'s plugin-marker
interception substitutes to) is built and published from a **different** repository — this
one (`compose-multiplatform-core`) has no `compose-gradle-plugin` subproject. Do not expect
`publish-tvos-fork.sh` to produce it; it must be published separately, from whichever repo
owns `gradle-plugins/compose`.

# Stage 2 — closure audit (`scripts/audit-tvos-closure.py`) — MANDATORY

```bash
python3 scripts/audit-tvos-closure.py
echo "exit=$?"   # must be 0
```

What it does: walks every tvOS-relevant variant (native-target attribute starting `tvos_`, or
a `-tvos*`-suffixed platform-split module) of every `.module` under
`~/.m2/repository/dev/sajidali/**`, and classifies each declared dependency:

| Classification | Meaning |
|---|---|
| `OK-INTERNAL` | dependency is under the audited prefix and its module directory exists locally at that exact version |
| `OK-EXTERNAL-TVOS` | not under the audited prefix, but upstream's own `.module` (Central/Google Maven) already advertises a `tvos_` variant |
| `OK-EXTERNAL-TVOS-ASSUMED` | `org.jetbrains.kotlin`/`org.jetbrains.kotlinx` — resolved via the Kotlin/Native toolchain's own klib distribution, not per-target Gradle variants; never flagged FAIL for lacking a `tvos_`-tagged `.module` variant |
| `COVERED-BY-REDIRECT` | dependency's group is one `compose-tvos-redirect` rewrites, and the rewritten twin exists locally at the exact same version |
| `WARN` | same as above, but the twin exists locally at a **different** version — a version-manifest gap (see `compose-tvos-redirect/manifest/compose-tvos-versions.json`), not a hard failure |
| `UNKNOWN` | external network lookup failed/timed out |
| `FAIL` | none of the above — a real gap |

**Exit-0 is required** before treating a publish as trustworthy: exit is non-zero if there is
at least one `FAIL` or `UNKNOWN`. `WARN` entries are expected and informational — they are
exactly the version-mismatch findings that feed `compose-tvos-redirect`'s manifest
(`manifest/compose-tvos-versions.json` `mappings`), not a gate failure. `--group-prefix` and
`--repo-root` are overridable if auditing a non-default coordinate root or repo location.

# Stage 3 — staged Central Portal bundle (`scripts/stage-central-bundle.sh`)

```bash
export PUBLISH_SIGNING_KEY="$(cat path/to/private-key.asc)"
export PUBLISH_SIGNING_PASSWORD="..."
./scripts/stage-central-bundle.sh
```

**This script never uploads anything, under any flag** — `--upload`/`--publish`/`--push` all
hard-refuse with exit 1. It only prepares a local, signed bundle zip and prints the (inert,
commented-out) `curl` command a human would run manually.

Required env vars (both optional — see "the known issue" below for what happens when unset):
- `PUBLISH_SIGNING_KEY` / `PUBLISH_SIGNING_PASSWORD` — in-memory ASCII-armored PGP key +
  passphrase, forwarded as `-Ppublish.signing.key`/`-Ppublish.signing.password` to the same
  `publishComposeJbToMavenLocal` task Stage 1 uses (signing applies regardless of which
  repository task publishes — real `~/.m2` output is genuinely signed when these are set).

What it does, in order:
1. **Publish** (same task/property set as `publish-tvos-fork.sh`, plus signing props) to real
   `~/.m2`, then copy only the newly-published `dev/sajidali/**` subtree into an isolated
   `build/central-staging-repo/` — never bundling unrelated pre-existing `~/.m2` content.
2. **Validate completeness**: every module needs a sibling `-sources.jar` (hard failure if
   missing — the build already produces these) and a sibling `-javadoc.jar` (Central checks
   presence only, not content; the script generates a trivial stub for any module missing
   one — this is expected for every native/resource-only module, since `MavenUploadHelper.kt`
   has no javadoc-generation logic). Every artifact file needs a sibling `.asc` signature —
   this **warns** (doesn't fail) when unsigned, since an unsigned dry run is a valid, expected
   mode of this script (see below).
3. **Zip** the staging repo root as-is (Central's expected repo-root-relative path layout).
4. **Print** the (never-executed) upload `curl` command as the final "Manual next step".

**The known issue (from `.superpowers/sdd/task-11-prep-report.md`): Step 1 failed once with a
suspected stale config-cache.** A session that had a warm Gradle configuration cache from
earlier, unrelated builds hit `IncompatibleComposeRuntimeVersionException` /
`commonizeCInterop` / `compileKotlinTvos{Arm64,SimulatorArm64}` failures after 256 of 673
actionable tasks — distinct from any of `publish-tvos-fork.sh`'s three documented attempts
(task-8c-report.md), and the log showed `"Configuration cache entry reused"`, pointing at
stale incremental/config-cache state rather than a code regression. Clean-session workaround,
in order of preference:
```bash
./gradlew --stop                                   # kill any running daemon
rm -rf .gradle/configuration-cache \
       buildSrc/.gradle/configuration-cache \
       androidx-settings-plugins/.gradle/configuration-cache
```
or, more simply, a fresh checkout / fresh `GRADLE_USER_HOME`. There is no
`--no-configuration-cache` flag baked into the scripts themselves (they don't pass
`--configuration-cache` explicitly either way — whatever the ambient `gradle.properties`
default is applies); clearing the on-disk cache directories forces a from-scratch
configuration regardless of that default.

**Update (task 12): validated clean.** A retry with the daemon stopped, all three
`configuration-cache` directories above removed, and `PUBLISH_SIGNING_KEY`/
`PUBLISH_SIGNING_PASSWORD` unset (unsigned validation run) completed
`BUILD SUCCESSFUL in 12m 54s` (1594 actionable tasks: 458 executed, 23 from cache, 1113
up-to-date), followed by Step 2 (`0` missing sources jars, `121` stub javadoc jars generated,
`741` missing `.asc` signatures — expected/unsigned) and Step 3 (an 81M
`build/central-bundle.zip`) both completing normally. This is the first confirmed clean,
end-to-end success of this exact script in a genuinely cold configuration-cache session —
treat it as validated for the unsigned dry-run path. A real signed run (with
`PUBLISH_SIGNING_KEY`/`PUBLISH_SIGNING_PASSWORD` set) still has not been exercised end-to-end
and should get its own clean-session run before being trusted for an actual Central upload.

# Post-rebase note

`rebase-tvos-fork`'s procedure rebases `tvos-main` via a throwaway
`tvos-main-rebase-trial` branch, then promotes it (`git branch -f tvos-main
tvos-main-rebase-trial`). Once a rebase promotes a new `tvos-main`, `tvos-publishing` needs
its own rebase onto it. Two hard-won specifics (2026-07 rebase):

- **Always use `--onto` with the OLD `tvos-main` tip as the cut point.** Promoting rewrites
  every `tvos-main` commit hash, so `merge-base(tvos-publishing, tvos-main)` falls back to
  the old *upstream* base and a plain `git rebase tvos-main` replays ALL fork commits (not
  just the publishing ones) against themselves — conflicts everywhere. Recover the old tip
  from the force-push log line (`+ <old>...<new> tvos-main -> tvos-main`), then:
  ```bash
  git branch tvos-publishing-rebase-trial tvos-publishing
  git rebase --onto tvos-main <old-tvos-main-tip> tvos-publishing-rebase-trial
  ```
- **Verify the four buildSrc publish files with their REAL paths** — they are exactly where
  an upstream restructure is most likely to produce a silent, clean-but-wrong merge (per
  `rebase-tvos-fork`'s own "Core principle"):
  `buildSrc/private/src/main/kotlin/org/jetbrains/androidx/build/{JetBrainsAndroidXRootImplPlugin,JetBrainsVerifyDependencyVersionsTask,MavenUploadHelper}.kt`
  and `buildSrc/public/src/main/kotlin/org/jetbrains/androidx/build/JetBrainsPublication.kt`.
  (Note the `org/jetbrains/androidx/build` package — an `androidx/build/MavenUploadHelper.kt`
  twin also exists but carries no publishing delta. A pathspec guess that matches nothing
  makes the diff-comparison vacuously pass, so confirm each side's diff is NON-EMPTY, e.g.
  `git diff tvos-main..trial -- <file> | grep -c '^[+-]'` before comparing old vs new.)
  Also confirm the stability-gate guard survived:
  `grep 'coordinateRoot == "org.jetbrains"' .../JetBrainsVerifyDependencyVersionsTask.kt`.

Then re-run Stage 1 + Stage 2 (mandatory audit) before trusting the result, and keep a dated
backup of the old branch tip (`git branch tvos-publishing-old-YYYYMMDD tvos-publishing`)
before promoting.

# Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `jbVerifyDependencyVersions` fails: "Project with version X may not take a dependency on less-stable artifact Y" | The stability gate is firing for an `org.jetbrains`-root publish (expected — do NOT scope it off further), or the `onlyIf { JetBrainsPublication.coordinateRoot == "org.jetbrains" }` guard in `JetBrainsVerifyDependencyVersionsTask.kt` was lost in a merge/rebase. Confirm with `grep -c "jbVerifyDependencyVersions SKIPPED"` in the publish log — should be non-zero for a `dev.sajidali` publish. |
| `compose:material3:adaptive:adaptive:compileCommonMainKotlinMetadata` fails: `Unresolved reference 'window'` | The fork-built `:window:window-core` (task 18a) is missing or lost its tvOS wiring — adaptive resolves `WindowSizeClass` from it, not from upstream `androidx.window` (which still has no tvOS klib). Check `window/window-core/build.gradle` still has tvos targets and `WINDOW` is still in the libraries list. |
| The `org.jetbrains.compose:org.jetbrains.compose.gradle.plugin` marker publication ends up depending on a `dev.sajidali.compose:compose-gradle-plugin` coordinate | This is the plugin-marker-suppression issue from the `compose-gradle-plugin` fork build (a **different** repo than this one — `gradle-plugins/` doesn't exist here). `java-gradle-plugin`'s auto-generated marker publication is keyed off the plugin-id string, not `project.group`, so a coordinate-root override on the implementation artifact does NOT automatically move the marker — it must be explicitly suppressed (`onlyIf` on tasks matching `*PluginMarkerMavenPublication*`, gated behind the same coordinate/group-override property check) in that other repo's `gradle-plugins/build.gradle.kts`. `compose-tvos-redirect`'s settings plugin relies on this marker staying under `org.jetbrains` and does its own substitution via `pluginManagement.resolutionStrategy.eachPlugin` — never on this marker pointing at the fork directly. |
| Publish looks successful but a `dev.sajidali` artifact you expect is missing from `~/.m2` | Check `-Pjetbrains.publication.libraries` actually lists it (10 libraries by default here — see "The 11th library" above), and re-run the closure audit; a `FAIL` finding will name the exact missing coordinate. |
| Closure audit reports many `WARN` entries | Expected and informational — these are version-mismatch findings (the twin exists locally, just at a different version than requested) that should feed `compose-tvos-redirect/manifest/compose-tvos-versions.json`'s `mappings`, not something to "fix" in this repo. |
| `stage-central-bundle.sh` reports missing `.asc` signatures | Expected when `PUBLISH_SIGNING_KEY` is unset (unsigned dry-run mode) — re-run with real signing credentials before treating the bundle as upload-ready. |
| `stage-central-bundle.sh`'s Step 1 fails partway through with klib/runtime version errors | See "The known issue" above — retry in a clean session (`./gradlew --stop` + clear configuration-cache dirs) before assuming a real regression. |
