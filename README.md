# Compose Multiplatform core — tvOS fork

This is a fork of [JetBrains/compose-multiplatform-core](https://github.com/JetBrains/compose-multiplatform-core)
(the androidx-based sources of Compose Multiplatform: runtime, ui, foundation, material, material3,
lifecycle, navigation, and friends) that adds Apple tvOS as a Kotlin/Native target.
The upstream project's own README is [here](https://github.com/JetBrains/compose-multiplatform-core#readme).

It is an unofficial community fork. It is not affiliated with or endorsed by JetBrains or Google.

## Status / maintenance

I maintain only the tvOS port, and only as far as I need it for my own tvOS app. I do not track
every upstream release; I republish roughly once per Compose Multiplatform stable line. There are
no support commitments and no release schedule.

**PRs are welcome** — bug fixes, additional targets or modules, and help keeping the fork up to
date with upstream are all appreciated.

## How to use it

Do not depend on this repository directly. Apply the
[compose-tvos](https://github.com/sajidalidev/compose-tvos) Gradle settings plugin and keep your
stock `org.jetbrains.compose.*` / `org.jetbrains.androidx.*` coordinates:

```kotlin
// settings.gradle.kts
plugins {
    id("dev.sajidali.compose-tvos") version "1.3.0"
}
```

Add `tvosArm64()` / `tvosSimulatorArm64()` targets to your Kotlin Multiplatform module as usual.
At dependency-resolution time the plugin attaches tvOS variants from this fork's artifacts, which
are published to Maven Central under the `dev.sajidali.*` group prefix (for example
`dev.sajidali.compose.ui:ui`). Non-tvOS targets keep resolving the official JetBrains artifacts.

Versions follow upstream (the "same-version convention"): a request for
`org.jetbrains.compose.foundation:foundation:1.12.0` resolves the tvOS variant from
`dev.sajidali.compose.foundation:foundation:1.12.0`. The current published line is **1.12.0**.
Companion libraries that JetBrains did not re-release for 1.12.0 (material3, lifecycle,
navigation, ...) are mapped through the plugin's version manifest; see
[Supported versions](https://sajidalidev.github.io/compose-tvos/supported-versions.html).

Full documentation: https://sajidalidev.github.io/compose-tvos/ — and [TVOS.md](TVOS.md) in this repo.

## What's in this fork

### Branches

| Branch | Contents |
|---|---|
| `tvos-main` | upstream `jb-main` + the tvOS commits (prefixed `[tvOS]`), including the `dev.sajidali` publishing tooling; rebased onto upstream periodically |
| `release-1.12-tvos` | upstream `v1.12.0` + the same tvOS commits; the 1.12.0 artifacts were built from here |

### Targets

`tvosArm64` and `tvosSimulatorArm64`. `tvosX64` (Intel simulator) is not built.

### Published module groups (`dev.sajidali.*`)

| Group | Version |
|---|---|
| `compose.{runtime,ui,foundation,animation,material}` (63 modules incl. the KMP umbrella) | 1.12.0 |
| `compose.material3` (`material3`, `material3-window-size-class`, `material3-adaptive-navigation-suite`) | 1.5.0-alpha22 |
| `compose.material3.adaptive` | 1.3.0-beta02 |
| `androidx.lifecycle` | 2.11.0 |
| `androidx.navigation` | 2.10.0-alpha05 |
| `androidx.navigation3` | 1.2.0-alpha04 |
| `androidx.navigationevent` | 1.1.1 |
| `androidx.savedstate` | 1.5.0-alpha01 |
| `androidx.window` (`window-core`) | 1.6.0-alpha02 |
| `androidx.tv` (`tv-material`) | 1.1.0-alpha01 |

Only the compose group was republished for 1.12.0 because JetBrains re-released only that group;
the companions above were built from the same `release/1.12` fork point and are unchanged.

### Notable tvOS-specific work

- tvOS UIKit scene integration under `compose/ui/ui/src/tvosMain/.../scene/`
  (`ComposeSceneMediator.tvos.kt`, `ComposeHostingViewController.tvos.kt`,
  `ComposeLayersViewController.tvos.kt`, ...) and `ComposeUIViewController.tvos.kt`, sharing the
  `FrameChoreographer` architecture with the iOS scene stack.
- Siri Remote input: D-pad focus traversal, swipe-to-focus, Menu button routed to `Key.Back`,
  and discrimination of clickpad press from swipe by hardware timestamp
  (`ComposeSceneMediator.tvos.kt`).
- Squared "10-foot" scene density.
- tvOS text input (`TvOSTextInputService.tvos.kt`): keyboard presented on the Select press that
  starts an input session.
- Focus handling when overlay layers (dialogs, popups) close.
- `androidx.tv:tv-material` ported to Compose Multiplatform with a tvOS source set.
- A real tvOS build of `window-core` so `material3-adaptive` works without stubs.
- tvOS `actual`s for foundation (clipboard, magnifier, text selection), `ui-text` font resolution,
  navigation-compose default transitions and navigation3 `NavDisplay`.
- `:demo-tvos` sample module, built and run on the Apple TV 4K simulator.

## Building / publishing locally

The build needs JDK 21:

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
export ANDROIDX_JDK21="$JAVA_HOME"
```

Fork mode is switched on with `-Ppublication.coordinateRoot=dev.sajidali`, which publishes every
module under `dev.sajidali.*` instead of `org.jetbrains.*` / `androidx.*`. The publishing
tooling:

- `scripts/publish-tvos-fork.sh` — publishes the tvOS artifact set to mavenLocal
  (`./gradlew -p mpp publishComposeJbToMavenLocal --no-configuration-cache ...`).
- `scripts/audit-tvos-closure.py` — dependency-closure audit over `~/.m2`; run it before trusting
  a publish.
- `scripts/stage-central-bundle.sh` — signs and stages a Maven Central Portal bundle. It never
  uploads.
- `ai-skills/rebase-tvos-fork/SKILL.md` and `ai-skills/publish-tvos-fork/SKILL.md` — the rebase
  and release runbooks.

## Related repositories

- [sajidalidev/compose-tvos](https://github.com/sajidalidev/compose-tvos) — the settings plugin,
  version manifest and docs. Start here.
- [sajidalidev/compose-multiplatform](https://github.com/sajidalidev/compose-multiplatform)
  — fork of the Compose Gradle plugin and `components-resources`.
- [sajidalidev/koin](https://github.com/sajidalidev/koin) — Koin with tvOS targets.
- [sajidalidev/coil](https://github.com/sajidalidev/coil) — Coil 3 with tvOS targets.
- [sajidalidev/jetstream-tvos](https://github.com/sajidalidev/jetstream-tvos) — sample app
  (Google's JetStream) running on Apple TV.

## License

Same as upstream: [Apache License 2.0](LICENSE.txt). Copyright for the upstream code remains with
the Android Open Source Project and JetBrains s.r.o.
