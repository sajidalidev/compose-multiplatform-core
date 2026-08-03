# Compose for Tizen TV demo

A Compose Multiplatform app packaged as a Tizen TV web app, driven entirely by a Samsung Smart
Remote: the four-way pad moves focus, OK opens a tile, BACK closes it, and BACK on the grid quits.
The strip along the bottom names the last remote button that reached Compose, which is the quickest
way to check that a button is mapped.

## Why Kotlin/JS and not Kotlin/Wasm

Tizen TV runs applications in its web engine, so a Compose app reaches the TV as a web app. The
engine is Chromium-based but lags: Tizen 8.0 ships Chromium 108, while Kotlin/Wasm needs WasmGC and
therefore Chromium 119+. Kotlin/JS runs Skiko on WebGL2 and works back to Tizen 6.0, so that is what
this module targets.

## Build

```bash
export ANDROIDX_JDK21="$JAVA_HOME"     # the build requires JDK 21
unset EXPECTED_AGP_VERSION             # build in fork mode, the default for a plain ./gradlew

./gradlew :demo-tizen:assembleTizenApp
```

The unsigned app lands in `demo-tizen/build/tizen/app`: the webpack bundle (`demo.js`), the Skiko
runtime (`skiko.wasm`, `skiko.mjs`), `index.html`, `icon.png`, and `config.xml`.

## Run in a desktop browser

`ComposeTizenTvViewport` skips the remote-key registration when the Tizen device APIs are absent and
applies the rest of its setup regardless, so the app renders identically in Chrome — arrow keys and
Enter stand in for the D-pad and OK. The TV-only buttons (coloured, playback, channel) do not exist
on a keyboard and are the one thing a desktop run cannot exercise.

```bash
./gradlew :demo-tizen:jsBrowserDevelopmentRun
```

## Install on a TV or the emulator

A `.wgt` has to be signed with a Samsung certificate profile before a TV will install it, and the
certificates are issued by Tizen Studio, so the signing step lives outside Gradle. With
[Tizen Studio](https://developer.samsung.com/smarttv/develop/tools.html) installed and a certificate
profile configured:

```bash
cd demo-tizen/build/tizen/app
tizen package -t wgt -s <your-certificate-profile> -- .
tizen install -n ComposeTizenDemo.wgt -t <emulator-or-device-id>
```

`./gradlew :demo-tizen:packageTizenApp` produces an **unsigned** `ComposeTizenDemo.wgt` in
`demo-tizen/build/tizen`. It is convenient for inspecting the archive layout, but a TV will reject
it — use `tizen package` for anything you intend to install.

Running on a real TV also requires putting the set into developer mode and registering the
development machine's IP; the emulator that ships with the Tizen TV extension needs neither.

## What the app exercises

| Tile | What it covers |
| --- | --- |
| Focus | D-pad traversal, including the very first press, when nothing holds focus yet |
| Remote | The Smart Remote's TV-specific buttons resolving to `Key` values |
| Density | The 10-foot density scale that gives a 1080p TV a 960x540 dp viewport |
| Back | `Key.Back` reaching the app, and quitting from the top-level screen |
| Repeat | `KeyEvent.isRepeat` telling a held button apart from the presses it repeats |
