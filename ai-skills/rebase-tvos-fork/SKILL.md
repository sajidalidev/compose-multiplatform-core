---
name: rebase-tvos-fork
description: Use when integrating upstream JetBrains compose-multiplatform-core changes into the tvOS fork — rebasing tvos-main onto upstream/jb-main, syncing the fork after upstream/jb-main moves ahead, or catching the tvOS fork up to upstream. Specific to the tvos-main / upstream jb-main rebase in this repository.
version: 1.2.0
---

The tvOS fork (`tvos-main`) carries a small set of tvOS-specific commits on top of JetBrains
`upstream/jb-main`. Periodically upstream moves ahead and the fork must be rebased onto it.

# Core principle — a clean rebase is NOT proof of correctness

`git rebase` replays each fork commit as a textual patch. When upstream **rewrites the code
around** a fork change (e.g. the scene/frame architecture), git's 3-way merge can apply the
patch with **no conflict** yet land it in the wrong place, make it a no-op, or silently drop
fork behavior. "Rebase succeeded" and "BUILD SUCCESSFUL" do **not** mean the tvOS behavior
survived. You MUST explicitly verify the fork's tvOS logic is still present and on the live
code path after every rebase. That verification is the whole point of this skill.

# Prerequisites

- `upstream` remote = `JetBrains/compose-multiplatform-core` (branch `jb-main`); `origin` = the
  personal fork. Confirm with `git remote -v`.
- JDK 21 is required by the build (`org.gradle.java.installations.fromEnv=ANDROIDX_JDK21`). Set it:
  ```bash
  export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
  export ANDROIDX_JDK21="$JAVA_HOME"
  ```
- tvOS Kotlin targets are `tvosArm64` / `tvosSimulatorArm64` → compile task suffix
  `compileKotlinTvosSimulatorArm64`.

# Build modes (upstream #3064, #3265) — the tvOS wiring lives in the FORK-MODE files only

Since upstream PR #3064 ("Use a separate build structure for maintaining fork") the build runs
in one of two modes, selected by whether the `EXPECTED_AGP_VERSION` env var is set:

- **fork mode** — the DEFAULT for a plain `./gradlew` (and IntelliJ). `settings.gradle` early-returns
  into `settings-fork.gradle`, and every module builds from the first of `build-fork.gradle.kts`,
  `build-fork.gradle`, `build.gradle.kts`, `build.gradle` that exists in its directory.
- **AOSP mode** — used when `EXPECTED_AGP_VERSION` is set (i.e. `gradlew studio`). Uses the original
  `settings.gradle` + `build.gradle` files. Since upstream #3265 (2026-08-26, "Reset build.gradle to
  AOSP state") those files are byte-for-byte AOSP: no `JetBrainsAndroidXPlugin`, no `ios()`/`desktop()`
  targets, no redirects. AOSP mode builds NO multiplatform targets any more.

Consequence for the fork: ALL tvOS wiring lives in fork-mode files — `build-fork.gradle`,
`settings-fork.gradle`, `buildSrc-fork/` — plus the handful of JetBrains-owned `build.gradle` files
that have no `build-fork` counterpart and were deliberately NOT reset by #3265
(`compose/ui/ui-uikit`, `compose/ui/ui-skiko`, `compose/ui/ui-backhandler`, `compose/desktop/**`).
Fork-only modules follow the same split: `window/window-core/build-fork.gradle` and
`tv/tv-material/build-fork.gradle` carry the fork wiring, their `build.gradle` is upstream's AOSP file.
`settings.gradle` (AOSP mode) must equal upstream's. Never add tvOS wiring to a reset `build.gradle`:
it is dead in fork mode and guarantees a conflict on the next rebase. `demo-tvos` and
`compose/mpp/demo` use `build.gradle.kts`, which fork mode picks up via the fallback list above.

History note: fork commits older than 2026-09-02 still edit AOSP-mode `build.gradle` files. When they
replay onto a base that includes #3265 they conflict on every one of them; the resolution is always
"take upstream". Automate it for the duration of the rebase with a scoped merge driver:
```bash
git config merge.ours.driver true            # "ours" during a rebase = the upstream side
cat > .git/info/attributes <<'EOF'
**/build.gradle merge=ours
compose/ui/ui-uikit/build.gradle merge=text
compose/ui/ui-skiko/build.gradle merge=text
compose/ui/ui-backhandler/build.gradle merge=text
compose/desktop/**/build.gradle merge=text
EOF
# ... rebase ...
rm .git/info/attributes; git config --unset merge.ours.driver   # ALWAYS remove afterwards
```
The 2026-09-02 rebase went from ~10 expected build.gradle conflict stops to zero with this in place;
modify/delete and add/add conflicts still stop the rebase as usual. Verify the result in step 4.

# Procedure — perform steps exactly in order

## 1. Fetch and inspect the gap
```bash
git fetch upstream jb-main
git rev-list --left-right --count tvos-main...upstream/jb-main   # left=fork-only, right=upstream-only
```

## 2. Rebase in a THROWAWAY branch — never on tvos-main directly
```bash
git branch tvos-main-rebase-trial tvos-main   # tvos-main is never moved
git switch tvos-main-rebase-trial
git rebase upstream/jb-main
```
If it goes wrong: `git rebase --abort`. `tvos-main` stays untouched the entire time.

## 3. Resolve conflicts: keep upstream's structure, graft the tvOS additions on top
Resolution rule: take upstream's restructured/version-bumped code, then re-apply the fork's
tvOS intent on top of it — not the reverse. Typical conflict sites:
- `**/build-fork.gradle` (fork `tvos()` targets, `uiKitMain` wiring and project-reference pins vs upstream version bumps) — keep upstream deps, re-add the tvOS wiring. A conflict in a reset AOSP-mode `build.gradle` is resolved by taking upstream (see "Build modes").
- `compose/ui/ui/src/skikoMain/.../node/RootNodeOwner.skiko.kt` (frame/scene changes).
- `settings.gradle` (fork's `:demo-tvos` include vs upstream stubs — additive, keep both).

### 3b. When upstream RENAMES the iOS files the fork shares (#3309 "Align iOS naming")
Fork commit 1 is a pure directory move (`iosMain/**` -> `uiKitCommonMain/**`, later renamed to
`uiKitMain`) of ~70 files; when upstream renames some of those same files (2026-08-24 #3309 renamed
20 of them and their classes: `UIKitInteropContainer`->`IosInteropContainer`,
`UIKitComposeSceneLayer`->`IosComposeSceneLayer`, `PlatformWindowContext`->`WindowContext`,
`UIKitNativeTextInputContext`->`NativeTextInputContext`, ...) every fork commit that moves them
hits a rename/rename storm (commits 1, 4, 6, 18 in the 2026-08-25 rebase). Resolution rule:
**upstream's NEW file name at the fork's location.** Git already stages the right answer as `AU`
entries at the fork path (directory-rename detection); the old-named `UA`/`DD` entries are dropped
— but only after proving the fork commit merely moved the file:
```bash
# For each UA <path>: identical to the file in the fork commit's own parent => pure move => drop.
P=$(git rev-parse REBASE_HEAD^); diff -q <(git show "${P}:<old path>") <(git show "REBASE_HEAD:<path>")
```
Compare `REBASE_HEAD:<path>` (the fork commit's tree), NOT `:3:<path>` or the worktree file — git
writes a rename-merged blob into stage 3, so those already contain upstream's renamed content.
Four `platform/` files land as `AU` under `iosMain/` instead of the fork dir — `mv` them into
`uiKitMain/` by hand. (zsh gotcha: `"$P:compose/..."` eats the `:c`; write `"${P}:..."`.)
Commits that only EDIT the moved files apply cleanly afterwards via rename detection. Do NOT fix
symbol names mid-rebase; after the last commit, sweep tvosMain/uiKitMain/demo-tvos for the old
identifiers and port the scene copies with the 5b 3-way merge (it renames symbols for free),
renaming the tvOS files to mirror upstream (`IosComposeSceneLayer.tvos.kt`,
`ComposeSceneLayerView.tvos.kt`). Commit the whole sweep as one `[tvOS] Adapt ... (#NNNN)` commit.

## 4. Verify the fork-mode build wiring (see "Build modes")
The fork-mode wiring (`build-fork.gradle` edits + `settings-fork.gradle` includes) is tracked history
and is replayed by the rebase itself — do NOT blind-copy files around. VERIFY the replayed wiring:

```bash
# 1. AOSP-mode files: the only build.gradle deltas allowed are the un-reset JetBrains files.
git diff --stat upstream/jb-main..tvos-main-rebase-trial -- '**/build.gradle' settings.gradle
#    expected: compose/ui/ui-{uikit,skiko,backhandler}/build.gradle only. Anything else => restore
#    it with `git checkout upstream/jb-main -- <file>` (move genuine fork wiring to build-fork.gradle).
# 2. Fork-mode files: the delta must be exactly the tvOS wiring — tvos() targets, tvosMain/tvosTest
#    source sets, uiKitMain intermediates, project-reference pins, the fork's own includes.
git diff upstream/jb-main..tvos-main-rebase-trial -- '**/build-fork.gradle' settings-fork.gradle
grep -q 'includeProject(":demo-tvos")' settings-fork.gradle || echo "MISSING :demo-tvos include"
# 3. No project reference may point at a module fork mode no longer includes:
grep -rn 'project(":lifecycle\|project(":savedstate\|project(":navigationevent' \
  --include='build-fork.gradle' . | grep -v '^\(\./\)\?\(lifecycle\|savedstate\|navigationevent\)/'
#    (fork-mode files only; the AOSP build.gradle copies under navigationevent/ and */samples/ are inert)
```

Watch for upstream DELETING a build-fork.gradle (e.g. #3233 removed `ui-uikit/build-fork.gradle`,
2026-08 removed `ui-backhandler/build-fork.gradle`): settings-fork falls back to `build.gradle`, so
that module's single `build.gradle` serves fork mode — resolve the modify/delete conflict by accepting
the deletion and keeping the tvOS wiring in `build.gradle` only.

Watch for upstream REMOVING includes from `settings-fork.gradle`:
- #3314 ("Remove AOSP Android projects") deleted the `mpp/stub-project` block incl. `:window:window-core`;
  the fork re-adds the real `includeProject(":window:window-core")` (+ samples stub) and `:tv:tv-material`
  because it fork-builds them.
- #3357 ("Remove lifecycle, savedstate, navigationevent", 2026-08-31) removed every `:lifecycle:*`,
  `:savedstate:*` and `:navigationevent:*` include and their publication groups. The fork FOLLOWS
  upstream here (do not re-add them): `org.jetbrains.androidx.lifecycle:*:2.11.0`, `androidx.savedstate:*`
  and Google's `androidx.navigationevent:navigationevent-compose:1.1.1` all ship tvOS klibs, so the
  Maven pins resolve for tvOS. The one pin that does NOT is
  `org.jetbrains.androidx.navigationevent:navigationevent-compose:1.1.0` (no tvOS variants) — the fork
  replaces it with `"androidx.navigationevent:navigationevent-compose:$navigationEventVersion"`
  (`def navigationEventVersion = project.redirectVersions.get('androidx.navigationevent')`) in
  `compose/ui/ui`, `navigation-compose`, `navigation3-ui`, `adaptive-navigation3` (build-fork) and
  `ui-backhandler` (build.gradle). If upstream bumps that pin, re-apply the swap.
  `LIFECYCLE`/`NAVIGATION_EVENT`/`SAVEDSTATE` are gone from `scripts/publish-tvos-fork.sh` too.
  Project references stay only where the fork builds the module itself (`:navigation3:navigation3-ui`,
  `:window:window-core`, and the `project(":compose:...")` swaps for `org.jetbrains.compose.*` Maven
  pins, which have no tvOS variants).

Also sweep for NEW upstream modules the fork's tvOS deps now reach (e.g. #3126 added
`:compose:ui:ui-skiko`, an api dep of `:compose:ui:ui`): each needs `tvos()` added to its targets.
Commit any such additions as their own `[tvOS]` commit on the trial branch.

## 5. Verify the fork's tvOS logic SURVIVED (do not skip — see Core principle)
The rebase must not change fork files except where a conflict forced it. Confirm:
```bash
# The fork scene files should be byte-identical to tvos-main after a clean rebase.
for f in ComposeContainer ComposeSceneMediator IosComposeSceneLayer; do
  p="compose/ui/ui/src/tvosMain/kotlin/androidx/compose/ui/scene/$f.tvos.kt"
  diff <(git show tvos-main:"$p") <(git show tvos-main-rebase-trial:"$p") && echo "OK $f" || echo "CHANGED $f — scrutinize"
done
# Density "10-foot" squaring: since 2026-09-02 it has ONE owner, tvSceneDensity() in
# TvSceneDensity.tvos.kt, applied by ComposeSceneMediator.tvos.kt in the `scene` lazy initializer.
# The container/layer call sites pass the plain UIKit scale exactly like iOS (byte-identical), so
# upstream rewrites of those sites merge cleanly. Check the owner, and that nothing re-inlined it:
grep -n 'tvSceneDensity' compose/ui/ui/src/tvosMain/kotlin/androidx/compose/ui/scene/ComposeSceneMediator.tvos.kt \
  || echo "MISSING tvSceneDensity hook in mediator"
grep -rn 'screenScale \*' compose/ui/ui/src/tvosMain/ && echo "inline squaring crept back — move it to tvSceneDensity"
diff <(grep -A2 'Density(' compose/ui/ui/src/iosMain/kotlin/androidx/compose/ui/scene/ComposeContainer.ios.kt) \
     <(grep -A2 'Density(' compose/ui/ui/src/tvosMain/kotlin/androidx/compose/ui/scene/ComposeContainer.tvos.kt) \
  && echo "OK container density sites mirror iOS"
```
Then sanity-check fork behaviors against the original full fork branch `tvos` when an upstream
change touched the same area (frame model, key input). Key fork behaviors that must remain:
squared scene density (owned by `tvSceneDensity`, see above), `FrameRecomposer` wiring (call site must match upstream's current
`PlatformLayersComposeScene(frameRecomposer, density, …)` signature), Siri Remote key mappings
(Menu→Back, D-pad focus), `KeyEvent.isRepeat`.

Also check for NEW per-platform entry-point obligations upstream added since the last rebase
(#3306 Dynamic Type: `FontScaleProvider` feeds `Density(screenScale, fontScale)`; `FontScale.ios.kt`
was hoisted to uiKitMain and tvOS keeps `Density(screenScale * screenScale, fontScale)` for the root
scene AND every layer's `initialDensity` in `ComposeContainer.createComposeSceneLayer`): diff
`ComposeContainer.ios.kt` against the last base and mirror anything init-time into
`ComposeContainer.tvos.kt`. Example (#3126): every entry point must now call
`registerSkikoComposeImplementation()` (populates `PlatformGraphicsRegistry`/`PlatformTextRegistry`)
before scene creation — missing it compiles clean but crashes at launch with "Registered
implementation is null". This class of break is invisible to steps 5–6 and only surfaces in step 7.
Example (#3367, 2026-09): `PlatformContext.taskDispatchers` became an abstract member — the tvOS
mediator's `IosPlatformContext` copy must add the override (the 5b merge carries it; a missed one
fails compilation, which is the good case). Example (#2984 hosting-view sizing, 2026-09): the iOS
container gained `ComposeSceneSizing`, `rootForTestListener` plumbing and `view.onSizeThatFits`;
`ComposeSceneSizing.ios.kt` was hoisted to `uiKitMain` (pure move) and the tvOS mediator's
`measureSceneSize` rescales constraints/results between `screenDensity` and the squared
`composeSceneDensity`, because upstream's sizing bridge converts UIKit points with the *view*
density. Rule: after the 5b merge, grep the merged tvOS files for every symbol upstream added and
check where it is defined — anything only in `iosMain` must be hoisted (if platform-neutral) or
re-implemented for tvOS.

Also sweep tvOS counterpart files that are NOT content mirrors of their iOS siblings but must still
track their *behavioral contract* — an upstream change to the iOS file lands with no conflict and no
tvOS file change, so nothing in steps 5–6 flags it. Known case:
`navigation/navigation-compose/src/tvosMain/.../DefaultNavTransitions.tvos.kt` deliberately uses
TV-style fade/scale transitions (not iOS slides), but its `popEnterTransition`/`popExitTransition`
must follow the platform contract. Example (#3292): upstream made the iOS pop methods respect
user-overridden transitions; the tvOS copy still returned the hardcoded defaults, silently dropping
custom NavHost transitions. On tvOS the pop defaults equal the enter/exit defaults, so the correct
form is the Android/desktop one — return the parameter. Rule: whenever an upstream commit in the
rebase window touches an iosMain file that has a tvosMain counterpart (find them with
`git diff --name-only <old-base>..upstream/jb-main -- '*iosMain*'` cross-checked against
`git ls-tree -r tvos-main --name-only | grep tvosMain`), read the upstream change and decide whether
it is iOS *styling* (ignore) or a *contract/behavior* change (mirror the intent into the tvOS copy
as its own `[tvOS]` commit).

### 5b. When upstream REWRITES the iOS scene architecture: port via per-file 3-way merge
When an upstream PR restructures the iosMain scene stack (as #3212 did — per-UIWindowScene
`FrameChoreographer` replacing the per-mediator `FrameRecomposer`/`MetalRedrawer` render loop),
the tvosMain fork copies won't compile against the changed uiKitMain APIs. Do NOT hand-port
file by file. Replay upstream's own transformation onto each tvOS copy with a 3-way merge:
```bash
# base   = the iOS counterpart BEFORE the rebase (old tvos-main)
# theirs = the iOS counterpart at the rebased HEAD (carries the upstream rewrite)
# ours   = the tvOS fork copy
git show tvos-main:$ios > base; git show HEAD:$ios > theirs
git merge-file -p --diff3 tvosfile base theirs > merged
```
Conflicts then mark exactly the genuine tvOS deltas (BackNavigationEventInput, Tv*InputView,
squared density, dropped keyboard/text-input machinery) — resolve each by taking upstream's new
structure and re-applying the tvOS intent on top. After resolving, sweep tvosMain for stale
removed-API references that sat in ours-only regions (`grep -rE 'redrawer|setNeedsRedraw|...'`).

Post-#3212 state: `FrameChoreographer.ios.kt`, `LayoutInvalidationHandler.ios.kt`,
`CompositionContextAttachment.ios.kt` and (since 2026-09, #2984) `ComposeSceneSizing.ios.kt` live in `uiKitMain` (fork commit "[tvOS] Hoist
FrameChoreographer and scene helpers to uiKitMain") because tvosMain and the shared interop
views need them. Future upstream edits to these symbols (upstream keeps FrameChoreographer in
iosMain and the helpers inline in ComposeContainer.ios.kt) will surface as modify/delete or
add/add conflicts against the hoist commit — resolve by keeping the uiKitMain location with
upstream's new content. Same class of break to watch for: upstream adding a symbol in iosMain
that fork-shared uiKitMain files reference (that's what forced the hoist of
`attachedCompositionContext`).

## 6. Compile-verify on the tvOS simulator target — in FORK MODE (the default)
Run WITHOUT `EXPECTED_AGP_VERSION` so this exercises the fork-mode build files you fixed in step 4:
```bash
unset EXPECTED_AGP_VERSION
./gradlew --console=plain \
  :compose:ui:ui:compileKotlinTvosSimulatorArm64 \
  :navigation:navigation-compose:compileKotlinTvosSimulatorArm64 \
  :navigation3:navigation3-ui:compileKotlinTvosSimulatorArm64 \
  :demo-tvos:compileKotlinTvosSimulatorArm64
```
`:demo-tvos` transitively pulls foundation/material3/ui-graphics/text/unit/util, so it is the
broadest single coverage check. Warnings are fine; errors are not. (If you only need to prove the
Kotlin survived and haven't done step 4 yet, `EXPECTED_AGP_VERSION=8.12.0 ./gradlew …` compiles via
AOSP mode — but fork mode is the real target and MUST pass.)

## 7. Run the demo (proves it actually renders, not just compiles)
Build `demo-tvos/iosApp/iosApp.xcodeproj` (scheme `iosApp`, bundle `org.jetbrains.compose.demo.tvos`)
for a booted tvOS simulator with the JDK21 env exported (and `EXPECTED_AGP_VERSION` UNSET, so the
embedded Kotlin build uses fork mode), then `xcrun simctl install` + `launch` + `screenshot`. Look at
the screenshot — a rendered card grid means success; a blank frame is a launch failure. (The Xcode
build runs `:demo-tvos:embedAndSignAppleFrameworkForXcode`, which needs `JAVA_HOME`/`ANDROIDX_JDK21`
in the environment.)

## 8. Promote or discard
```bash
# Promote the verified result (dated backup first):
git branch tvos-main-old-$(date +%Y%m%d) tvos-main
git branch -f tvos-main tvos-main-rebase-trial && git branch -D tvos-main-rebase-trial
# (then force-push: git push --force-with-lease origin tvos-main)

# Or discard:
git switch tvos-main && git branch -D tvos-main-rebase-trial
```

# Cutting a release branch (release-X.Y-tvos) — the 1.11/1.12 pattern

Upstream `release/X.Y` forks from `jb-main` weeks before the tag, so today's `tvos-main` commits are
usually adapted to upstream changes the release branch never got (1.12: cut 2026-06-29, before
#3064/#3126/#3212/#3309). Never cherry-pick current `tvos-main` onto a release tag. Instead:
```bash
git merge-base upstream/release/X.Y upstream/jb-main          # the fork point, e.g. fca104ce5d4
# find the tvos-main backup whose upstream base IS that fork point:
for b in $(git branch --list 'tvos-main-old-*' 'tvos-publishing-old-*'); do
  echo "$b $(git merge-base $b upstream/jb-main)"; done       # 1.12: tvos-publishing-old-20260716
git branch release-X.Y-tvos-work <backup>; git worktree add ../core-release-X.Y release-X.Y-tvos-work
git -C ../core-release-X.Y rebase --onto vX.Y.0 <fork point> release-X.Y-tvos-work   # clean if bases match
```
Then run steps 5–6 there too, sweep `git diff --name-only <fork point> vX.Y.0 -- '*iosMain*'` for
tvOS counterparts (1.12: #3243's `withFrameGuard` in ComposeSceneMediator had to be mirrored), bump
`VERSION_COMPOSE` in `scripts/publish-tvos-fork.sh` + `scripts/stage-central-bundle.sh`, and
publish only the library groups JetBrains re-released (1.12.0: COMPOSE only — check the `.module`
files on repo1.maven.org; companions already on Central under dev.sajidali cannot be re-uploaded).
Keep dated backups (`tvos-main-old-YYYYMMDD`) before promoting — the publishing commits are part of
`tvos-main` since 2026-09, so there is no separate branch to catch up.

# Common mistakes

| Mistake | Why it's wrong |
|---|---|
| `git pull` / merging instead of rebasing | The fork is a rebased history; merging creates duplicate-commit garbage. Always rebase. |
| Rebasing `tvos-main` directly | If it goes wrong you've corrupted the branch. Always use the throwaway branch. |
| Trusting "rebase succeeded" / "BUILD SUCCESSFUL" | Neither proves tvOS behavior survived an upstream rewrite. Run step 5 every time. |
| Putting tvOS wiring in AOSP-mode `build.gradle`/`settings.gradle` | Since #3064 the default `./gradlew` uses `build-fork.gradle` + `settings-fork.gradle`, and since #3265 the AOSP files are pure AOSP: wiring there is dead code and a guaranteed conflict next rebase. Only the un-reset JetBrains files (ui-uikit, ui-skiko, ui-backhandler, desktop) carry fork wiring in `build.gradle`. |
| Compile-verifying only in AOSP mode (`EXPECTED_AGP_VERSION` set) | That hides a broken fork-mode build. Step 6 MUST pass with `EXPECTED_AGP_VERSION` unset. |
| Compiling with JDK 17 | Build needs JDK 21 via `ANDROIDX_JDK21`. Sync/compile fails otherwise. |
| Compiling only — never running | Compile ≠ renders. Run the demo (step 7) for real integration proof. |
| Keeping the fork's old call site verbatim after upstream changed an API | A clean patch can keep stale calls that bind to a deprecated overload. Adapt the call to upstream's new signature while preserving fork intent (e.g. squared density). |
| Comparing `:3:<path>` / the worktree file to detect a "pure move" during a rename storm | Stage 3 is already rename-merged with upstream content; compare `REBASE_HEAD:<path>` to the fork parent instead. |
