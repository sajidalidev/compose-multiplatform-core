# Compose for TV demo

A Compose Multiplatform app packaged for **Samsung Tizen TV** and **LG webOS TV**, driven entirely
by the remote: the four-way pad moves focus, OK opens a tile, BACK closes it, and BACK on the grid
quits. The badge next to the title names the detected platform, and the strip along the bottom names
the last remote button that reached Compose — the two quickest checks that a set is wired up.

One Kotlin/JS bundle serves both TVs. `ComposeTvViewport` detects the platform at runtime, so the
only thing that differs between the two packages is the manifest laid beside the bundle.

## Why Kotlin/JS and not Kotlin/Wasm

Both platforms run applications in their web engine, so a Compose app reaches the TV as a web app.
Both engines are Chromium-based but lag: Tizen 8.0 and webOS 24 ship Chromium 108, while Kotlin/Wasm
needs WasmGC and therefore Chromium 119+. Kotlin/JS runs Skiko on WebGL2 and works from Tizen 6.0
and webOS 5.0 (Chromium 68). webOS 3.x, on Chromium 38, predates WebGL2 and cannot run it at all.

## Build

```bash
export ANDROIDX_JDK21="$JAVA_HOME"     # the build requires JDK 21
unset EXPECTED_AGP_VERSION             # build in fork mode, the default for a plain ./gradlew

./gradlew :demo-tv:assembleTizenApp    # -> demo-tv/build/tizen/app
./gradlew :demo-tv:assembleWebOsApp    # -> demo-tv/build/webos/app
```

Each directory holds the webpack bundle (`demo.js`), the Skiko runtime (`skiko.wasm`, `skiko.mjs`),
`index.html`, `icon.png`, and that platform's manifest — `config.xml` for Tizen, `appinfo.json` for
webOS, both from `packaging/<platform>/`.

## Run in a desktop browser

`ComposeTvViewport` applies its TV setup regardless of platform and only skips the parts a browser
has no equivalent for, so the app renders identically in Chrome — arrow keys and Enter stand in for
the D-pad and OK, and the badge reads `None`. The TV-only buttons (coloured, playback, channel) do
not exist on a keyboard and are the one thing a desktop run cannot exercise.

```bash
./gradlew :demo-tv:jsBrowserDevelopmentRun
```

## Install on a Tizen TV or emulator

A `.wgt` has to be signed with a Samsung certificate profile before a TV will install it, and the
certificates are issued by Tizen Studio, so the signing step lives outside Gradle. With
[Tizen Studio](https://developer.samsung.com/smarttv/develop/tools.html) installed and a certificate
profile configured:

```bash
cd demo-tv/build/tizen/app
tizen package -t wgt -s <your-certificate-profile> -- .
tizen install -n ComposeTvDemo.wgt -t <emulator-or-device-id>
```

`./gradlew :demo-tv:packageTizenApp` produces an **unsigned** `ComposeTvDemo.wgt` in
`demo-tv/build/tizen`. It is convenient for inspecting the archive layout, but a TV will reject it —
use `tizen package` for anything you intend to install.

## Install on a webOS TV or emulator

webOS needs no certificate, but an `.ipk` is an ar archive rather than a zip, so Gradle does not
stand in for `ares-package`. With the
[webOS TV SDK](https://webostv.developer.lge.com/develop/tools/sdk-introduction) CLI on `PATH`:

```bash
ares-package demo-tv/build/webos/app -o demo-tv/build/webos
ares-install demo-tv/build/webos/org.jetbrains.compose.demo.tv_1.0.0_all.ipk -d <device-or-emulator>
ares-launch org.jetbrains.compose.demo.tv -d <device-or-emulator>
```

Installing on a real set needs the Developer Mode app running on the TV and the machine registered
with `ares-setup-device`; the emulator that ships with the SDK needs neither.

`disableBackHistoryAPI` in `appinfo.json` is what stops webOS from treating BACK as browser history
navigation before the app ever sees the key — without it, BACK never reaches Compose.

## What the app exercises

| Tile | What it covers |
| --- | --- |
| Focus | D-pad traversal, including the very first press, when nothing holds focus yet |
| Remote | The remote's TV-specific buttons resolving to `Key` values on either platform |
| Density | The 10-foot density scale that gives a 1080p TV a 960x540 dp viewport |
| Back | `Key.Back` reaching the app (10009 on Tizen, 461 on webOS), and quitting from the top |
| Repeat | `KeyEvent.isRepeat` telling a held button apart from the presses it repeats |

The icon is a single 117x117 `icon.png` shared by both manifests; a production app should ship each
platform's exact icon sizes instead.
