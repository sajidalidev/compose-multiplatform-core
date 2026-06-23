---
name: rebase-tvos-fork
description: Use when integrating upstream JetBrains compose-multiplatform-core changes into the tvOS fork — rebasing tvos-main onto upstream/jb-main, syncing the fork after upstream/jb-main moves ahead, or catching the tvOS fork up to upstream. Specific to the tvos-main / upstream jb-main rebase in this repository.
version: 1.0.0
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
- `build.gradle` of compose modules (fork source-set additions + `uiKitMain` wiring vs upstream version bumps) — keep upstream deps, re-add the tvos/`uiKitMain` wiring.
- `compose/ui/ui/src/skikoMain/.../node/RootNodeOwner.skiko.kt` (frame/scene changes).
- `settings.gradle` (fork's `:demo-tvos` include vs upstream stubs — additive, keep both).

## 4. Verify the fork's tvOS logic SURVIVED (do not skip — see Core principle)
The rebase must not change fork files except where a conflict forced it. Confirm:
```bash
# The fork scene files should be byte-identical to tvos-main after a clean rebase.
for f in ComposeContainer ComposeSceneMediator UIKitComposeSceneLayer; do
  p="compose/ui/ui/src/tvosMain/kotlin/androidx/compose/ui/scene/$f.tvos.kt"
  diff <(git show tvos-main:"$p") <(git show tvos-main-rebase-trial:"$p") && echo "OK $f" || echo "CHANGED $f — scrutinize"
done
# Density "10-foot" squaring must still be present on the scene-creation path:
grep -rn 'density.density \* density.density\|screenDensity.density \* screenDensity.density' \
  compose/ui/ui/src/tvosMain/kotlin/androidx/compose/ui/scene/
```
Then sanity-check fork behaviors against the original full fork branch `tvos` when an upstream
change touched the same area (frame model, key input). Key fork behaviors that must remain:
squared scene density, `FrameRecomposer` wiring (call site must match upstream's current
`PlatformLayersComposeScene(frameRecomposer, density, …)` signature), Siri Remote key mappings
(Menu→Back, D-pad focus), `KeyEvent.isRepeat`.

## 5. Compile-verify on the tvOS simulator target
```bash
./gradlew --console=plain \
  :compose:ui:ui:compileKotlinTvosSimulatorArm64 \
  :navigation:navigation-compose:compileKotlinTvosSimulatorArm64 \
  :navigation3:navigation3-ui:compileKotlinTvosSimulatorArm64 \
  :demo-tvos:compileKotlinTvosSimulatorArm64
```
`:demo-tvos` transitively pulls foundation/material3/ui-graphics/text/unit/util, so it is the
broadest single coverage check. Warnings are fine; errors are not.

## 6. Run the demo (proves it actually renders, not just compiles)
Build `demo-tvos/iosApp/iosApp.xcodeproj` (scheme `iosApp`, bundle `org.jetbrains.compose.demo.tvos`)
for a booted tvOS simulator with the JDK21 env exported, then `xcrun simctl install` + `launch`
+ `screenshot`. Look at the screenshot — a rendered card grid means success; a blank frame is a
launch failure. (The Xcode build runs `:demo-tvos:embedAndSignAppleFrameworkForXcode`, which
needs `JAVA_HOME`/`ANDROIDX_JDK21` in the environment.)

## 7. Promote or discard
```bash
# Promote the verified result:
git branch -f tvos-main tvos-main-rebase-trial && git branch -D tvos-main-rebase-trial
# (then force-push: git push --force-with-lease origin tvos-main)

# Or discard:
git switch tvos-main && git branch -D tvos-main-rebase-trial
```

# Common mistakes

| Mistake | Why it's wrong |
|---|---|
| `git pull` / merging instead of rebasing | The fork is a rebased history; merging creates duplicate-commit garbage. Always rebase. |
| Rebasing `tvos-main` directly | If it goes wrong you've corrupted the branch. Always use the throwaway branch. |
| Trusting "rebase succeeded" / "BUILD SUCCESSFUL" | Neither proves tvOS behavior survived an upstream rewrite. Run step 4 every time. |
| Compiling with JDK 17 | Build needs JDK 21 via `ANDROIDX_JDK21`. Sync/compile fails otherwise. |
| Compiling only — never running | Compile ≠ renders. Run the demo (step 6) for real integration proof. |
| Keeping the fork's old call site verbatim after upstream changed an API | A clean patch can keep stale calls that bind to a deprecated overload. Adapt the call to upstream's new signature while preserving fork intent (e.g. squared density). |
