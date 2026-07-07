# tvOS Support (Fork)

This is a fork of [JetBrains/compose-multiplatform-core](https://github.com/JetBrains/compose-multiplatform-core)
that adds Apple tvOS as a first-class Compose Multiplatform target.

## What this fork adds

- **`tvosArm64`/`tvosSimulatorArm64` Kotlin/Native targets** across Compose runtime, ui,
  foundation, material3 (including `material3-adaptive`), navigation, navigation3, navigationevent,
  lifecycle, savedstate, and related modules.
- **Siri Remote / focus / back-navigation behavior**: D-pad focus traversal, swipe-to-focus,
  `KeyEvent.isRepeat`, the Siri Remote Menu button routed through `Key.Back`, and squared "10-foot"
  scene density. See the commits prefixed `[tvOS]` in the git history for the full list.
- **A real tvOS build of `window-core`**, needed for `material3-adaptive`'s `WindowSizeClass`
  dependency (rather than stubbing or excluding it).
- **`coordinateRoot` publishing** (`-Ppublication.coordinateRoot=dev.sajidali`), which republishes
  this fork's artifacts under `dev.sajidali.*` coordinates instead of `org.jetbrains.*`/
  `androidx.*`.

## For consumers

You should never need to depend on this repository directly. Use the compose-tvos Gradle settings
plugin instead — it transparently redirects the official Compose Multiplatform artifacts to this
fork's tvOS builds, with no changes to your `dependencies {}` blocks:

```kotlin
// settings.gradle.kts
plugins {
    id("dev.sajidali.compose-tvos") version "1.1.0"
}
```

Canonical docs: **https://sajidalidev.github.io/compose-tvos/** (site is being built in parallel;
until it's live, see the [compose-tvos](https://github.com/sajidalidev/compose-tvos) repository).

## For contributors

- **Branch model.** `tvos-main` is the canonical tvOS development branch, rebased onto upstream
  `jb-main` periodically (see `ai-skills/rebase-tvos-fork/SKILL.md` for the rebase-and-verify
  procedure). `tvos-publishing` carries the release-engineering commits (coordinate-root override,
  publish scripts, dependency-closure audit) on top of `tvos-main` and is the branch that actually
  gets published.
- **Runbooks:** `ai-skills/rebase-tvos-fork/SKILL.md` (integrating upstream changes) and
  `ai-skills/publish-tvos-fork/SKILL.md` (publishing a release).
- **Scripts:** `scripts/publish-tvos-fork.sh` (mavenLocal publish under the `dev.sajidali`
  coordinate root), `scripts/audit-tvos-closure.py` (mandatory dependency-closure audit that must
  pass before trusting a publish), `scripts/stage-central-bundle.sh` (signed local staging and
  Maven Central Portal bundle preparation — it never uploads anything itself).
- The rest of the tvOS ecosystem lives in:
  - [compose-multiplatform](https://github.com/sajidalidev/compose-multiplatform-tvos) — the
    Compose Gradle plugin and Compose Resources fork (tvOS resource packaging).
  - [compose-tvos](https://github.com/sajidalidev/compose-tvos) — the settings plugin, version
    manifest, and canonical docs.

## Release cadence

This fork publishes once per Compose Multiplatform **stable** line rather than for every
intermediate upstream alpha/beta; consumer requests for those intermediate versions are mapped
onto the published fork version via the compose-tvos plugin's remote version manifest. This
cadence exists because Maven Central's publishing quota makes publishing every upstream
pre-release impractical for a single-maintainer fork.
