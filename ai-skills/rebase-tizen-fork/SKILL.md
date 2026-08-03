---
name: rebase-tizen-fork
description: Use when integrating upstream JetBrains compose-multiplatform-core changes into the Tizen TV fork — rebasing the Tizen branch onto upstream/jb-main, syncing after upstream moves ahead, or catching the Tizen fork up to upstream. Specific to the Tizen TV branch / upstream jb-main rebase in this repository.
version: 1.0.0
---

The Tizen TV fork carries a small set of commits on top of JetBrains `upstream/jb-main` that make
Compose usable from a Samsung Smart Remote on a Tizen TV. Periodically upstream moves ahead and the
fork must be rebased onto it.

# Core principle — a clean rebase is NOT proof of correctness

`git rebase` replays each fork commit as a textual patch. When upstream **rewrites the code around**
a fork change (and the web viewport is rewritten often), git's 3-way merge can apply the patch with
**no conflict** yet land it in the wrong place, make it a no-op, or silently drop fork behaviour.
"Rebase succeeded" and "BUILD SUCCESSFUL" do **not** mean the TV behaviour survived. Verify the fork
logic is still on the live code path after every rebase. That verification is the point of this
skill.

# What the fork actually changes

Unlike the tvOS fork, this one adds **no new Kotlin target**. A Tizen TV runs applications in its
web engine, so the app is the existing `js` target packaged as a Tizen web app. That means no
per-module `build.gradle` / `build-fork.gradle` source-set wiring to maintain — the whole surface is
five behavioural edits plus a demo module:

| Area | File | What it does |
| --- | --- | --- |
| D-pad focus | `compose/ui/ui/src/skikoMain/…/node/RootNodeOwner.skiko.kt` | maps `Key.Direction*` to the matching `FocusDirection` |
| First press | `compose/ui/ui/src/commonMain/…/focus/FocusTraversal.kt` | `Enter` search falls back to `this` when nothing is focused |
| Back | `compose/ui/ui/src/skikoMain/…/navigationevent/BackNavigationEventInput.kt` | `Key.Back` dispatches back, like `Key.Escape` |
| Key repeat | `compose/ui/ui/src/skikoMain/…/input/key/KeyEvent.skiko.kt` | `InternalKeyEvent.isRepeat` + the public `KeyEvent.isRepeat` |
| Scene focus | `compose/ui/ui/src/skikoMain/…/scene/ComposeSceneFocusManager.skiko.kt` | `moveFocus` |
| Remote keys | `compose/ui/ui/src/webMain/…/input/key/TizenTvKeys.web.kt`, `KeyEvent.web.kt` | numeric `keyCode` fallback for the remote's TV buttons |
| Device APIs | `compose/ui/ui/src/webMain/…/platform/TizenTv.web.kt` | `isTizenTv`, `registerTizenTvRemoteKeys`, `tizenTvDensityScale`, `exitTizenTvApplication` |
| 10-foot scale | `compose/ui/ui/src/webMain/…/window/ComposeWindowInternal.web.kt`, `ComposeViewportConfiguration.web.kt` | `densityScale` / `requestFocusOnStart`, and the `contentScale` vs `density` split |
| Entry point | `compose/ui/ui/src/webMain/…/window/ComposeTizenTvViewport.web.kt` | TV defaults on top of `ComposeViewport` |
| Demo | `demo-tizen/`, `settings.gradle`, `settings-fork.gradle` | Kotlin/JS app + `.wgt` packaging |

# Two build modes (upstream #3064)

The build runs in one of two modes, selected by whether `EXPECTED_AGP_VERSION` is set:

- **fork mode** — the default for a plain `./gradlew` (and IntelliJ). `settings.gradle` early-returns
  into `settings-fork.gradle`, and modules build from `build-fork.gradle`.
- **AOSP mode** — used when `EXPECTED_AGP_VERSION` is set (`gradlew studio`).

The fork's only build-file edit is the `:demo-tizen` include, which is carried in **both**
`settings.gradle` and `settings-fork.gradle`. `demo-tizen` itself uses `build.gradle.kts`, which
fork mode picks up through its build-file fallback, so it needs no `build-fork` counterpart. If a
rebase drops the `settings-fork.gradle` line, the default `./gradlew` reports `:demo-tizen` as
"project not found" while AOSP mode still works.

# Procedure — perform steps exactly in order

## 1. Fetch and inspect the gap
```bash
git fetch upstream jb-main
git rev-list --left-right --count <tizen-branch>...upstream/jb-main   # left=fork-only, right=upstream-only
```

## 2. Rebase in a THROWAWAY branch — never on the Tizen branch directly
```bash
git branch tizen-rebase-trial <tizen-branch>
git switch tizen-rebase-trial
git rebase upstream/jb-main
```
If it goes wrong: `git rebase --abort`. The real branch stays untouched the entire time.

## 3. Resolve conflicts: keep upstream's structure, graft the TV additions on top
Take upstream's restructured code, then re-apply the fork's intent on top of it — not the reverse.
Likely conflict sites, in the order they matter:

- **`ComposeWindowInternal.web.kt`** — by far the most fragile. Upstream owns this file and reworks
  it regularly. See step 4.
- `KeyEvent.web.kt` — upstream extends `codeMap`; the fork appends a numeric fallback *after* the
  string lookup. Both are additive; keep both, and keep the fallback last.
- `RootNodeOwner.skiko.kt` / `BackNavigationEventInput.kt` — small `when` additions, usually clean.
- `settings.gradle` / `settings-fork.gradle` — additive, keep both sides.

## 4. Verify the density split survived (the silent-breakage hot spot)
The fork splits one number into two in `ComposeWindow`:

- `contentScale` — the browser's `devicePixelRatio`. Every **CSS-pixel <-> scene-pixel** conversion
  must use it: canvas sizing in `resize()`, `toScenePointerEvent(…)`, `MouseEvent.offset`,
  `getNewGeometryForBackingInput`.
- `density` — `contentScale * configuration.densityScale`, the **dp** scale. Used for
  `scene.density`, `touchSlop`, and `containerDpSize`.

At the default `densityScale` of 1 the two are numerically equal, so **a regression here is
invisible in a desktop browser and invisible in tests** — it only shows up on a TV as pointer
coordinates or a canvas resolution off by the scale factor. Check every new or moved use of
`density` in that file and classify it before accepting the rebase:

```bash
grep -n 'density\|contentScale\|cssDensity' \
  compose/ui/ui/src/webMain/kotlin/androidx/compose/ui/window/ComposeWindowInternal.web.kt
```

Any conversion that starts from a DOM measurement (a `clientX`, an `offsetX`, a `clientWidth`) or
produces one must be on `cssDensity`/`contentScale`. If upstream added a new pointer or geometry
path, it will have been written against `density` and needs the same treatment.

Known gaps, deliberately left on `density`: `WebDragAndDropManager` and `WebWindowInsetsManager`.
Neither applies to a TV. If a rebase makes one of them reachable on TV, fix it then.

## 5. Verify the rest of the fork logic is still on the live path
```bash
# D-pad -> FocusDirection, Back -> back navigation, key repeat, TV key table.
grep -n 'Key.Direction\(Up\|Down\|Left\|Right\) ->' \
  compose/ui/ui/src/skikoMain/kotlin/androidx/compose/ui/node/RootNodeOwner.skiko.kt
grep -n 'Key.Back' \
  compose/ui/ui/src/skikoMain/kotlin/androidx/compose/ui/navigationevent/BackNavigationEventInput.kt
grep -n 'isRepeat = repeat' \
  compose/ui/ui/src/webMain/kotlin/androidx/compose/ui/input/key/KeyEvent.web.kt
grep -n 'tizenTvKeyFromKeyCode' \
  compose/ui/ui/src/webMain/kotlin/androidx/compose/ui/input/key/KeyEvent.web.kt
grep -n 'includeProject(":demo-tizen")' settings.gradle settings-fork.gradle
```

Also check whether upstream added a new obligation to the web entry point: diff
`ComposeWindow.web.kt` and `ComposeWindowInternal.web.kt` against the previous base and mirror
anything init-time into the TV path. This class of break compiles clean and only surfaces at
launch — `registerSkikoComposeImplementation()` was exactly that on the tvOS side.

## 6. Keep the new API out of the ABI dump
`jbApiCheck` validates `compose/ui/ui/api/ui.klib.api`, which covers the `js` and `wasmJs` targets
the fork's code lives in. Declarations marked `@ExperimentalComposeUiApi` or `@InternalComposeUiApi`
are excluded, which is why every public declaration the fork adds carries one. If a rebase or a new
addition drops the annotation, `jbApiCheck` fails with a dump diff — add the annotation rather than
regenerating the dump, so the fork keeps contributing nothing to the public ABI.

## 7. Compile-verify in FORK MODE (the default)
```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"   # macOS; the build requires JDK 21
export ANDROIDX_JDK21="$JAVA_HOME"
unset EXPECTED_AGP_VERSION

./gradlew --console=plain :demo-tizen:compileKotlinJs
```
`:demo-tizen` transitively pulls foundation/material3/ui-graphics/text/unit/util, so it is the
broadest single coverage check. Warnings are fine; errors are not.

## 8. Run it (compiling is not rendering)
Fastest loop, and enough for focus, Back and density:
```bash
./gradlew :demo-tizen:jsBrowserDevelopmentRun   # arrow keys and Enter stand in for the D-pad and OK
```
The remote's TV-specific buttons have no keyboard equivalent, so confirming those needs the Tizen TV
emulator or a real set:
```bash
./gradlew :demo-tizen:assembleTizenApp
cd demo-tizen/build/tizen/app
tizen package -t wgt -s <certificate-profile> -- .
tizen install -n ComposeTizenDemo.wgt -t <emulator-or-device-id>
```
Watch the readout at the bottom of the demo: it names the last remote button that reached Compose,
so an unmapped button shows up as "unmapped" instead of failing silently. See `demo-tizen/README.md`.

## 9. Promote or discard
```bash
git branch -f <tizen-branch> tizen-rebase-trial && git branch -D tizen-rebase-trial
# then: git push --force-with-lease origin <tizen-branch>

# or discard:
git switch <tizen-branch> && git branch -D tizen-rebase-trial
```

# Common mistakes

| Mistake | Why it's wrong |
|---|---|
| `git pull` / merging instead of rebasing | The fork is a rebased history; merging creates duplicate-commit garbage. Always rebase. |
| Rebasing the Tizen branch directly | If it goes wrong you've corrupted the branch. Always use the throwaway branch. |
| Trusting "rebase succeeded" / "BUILD SUCCESSFUL" | Neither proves the TV behaviour survived an upstream rewrite. Run steps 4-5 every time. |
| Accepting a new `density` use in `ComposeWindowInternal.web.kt` without classifying it | At `densityScale = 1` the wrong choice is invisible everywhere except on a TV. |
| Adding a public declaration without an experimental/internal marker | `jbApiCheck` fails, and the fork starts contributing to the public ABI. |
| Fixing only `settings.gradle` and not `settings-fork.gradle` | The default `./gradlew` uses fork mode and would lose `:demo-tizen`. |
| Targeting Kotlin/Wasm instead of Kotlin/JS | Kotlin/Wasm needs WasmGC (Chromium 119+); Tizen 8.0 ships Chromium 108. It will not load on any shipping TV. |
| Compiling only — never running | Compile != renders, and focus bugs are only visible when a remote is driving. |
