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
  `androidx.*`. Modules JetBrains already ships for tvOS (runtime, runtime-saveable, lifecycle,
  navigation-common/-runtime, savedstate, window-core; the list, with the exact upstream
  coordinates, is `JetBrainsPublication.upstreamTvosModules`) get no tvOS target in this build
  and are never republished. tvOS compilations take the upstream artifact in their place, and
  the published modules' metadata points at the same `org.jetbrains.*` coordinates.

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

## Keyboard and focus scrolling

While the native keyboard is visible, remote input stays in UIKit. Compose's
mediator does not forward those events to the text field or focus navigation
behind the keyboard; it still consumes the paired Select event that opened it.

Foundation uses a 30% focus pivot on tvOS, matching Android TV. Focused items
scroll away from the viewport edge, including when returning toward the first
row. Other Skiko platforms retain minimum-distance scrolling. Applications may
still override `LocalBringIntoViewSpec` for a different layout policy.

## Root Back / Menu forwarding

The tvOS input bridge forwards the real Menu Began as soon as Compose leaves a
KeyDown unhandled, while still offering the KeyUp to Compose. UIKit only arms
its Home gesture on a Began delivered while the press is live, so a Began
replayed later from `pressesEnded` is ignored on the physical Apple TV: the
forward has to happen at KeyDown time, and the press stays pending until its
KeyUp is resolved.

An unconsumed KeyUp completes the pending press by forwarding its real Ended,
and the app goes Home. A KeyUp that an in-app Back handler consumes forwards
the press as Cancelled instead, from inside that same live call, so UIKit sees
Began then Cancelled and disarms the gesture rather than exiting. In-app Back
handlers may consume either KeyDown or KeyUp; consuming either phase keeps the
press in the app. The common pattern of acting on KeyUp alone therefore needs
no KeyDown handler. Native cancellations are forwarded as Cancelled too. Both
the overlay input view and hosting controller use the shared dispatch log to
avoid delivering forwarded presses to Compose twice.

Applications must leave root Back unconsumed when they want tvOS to return Home.
An always-consuming no-op exit callback prevents this system behavior.
