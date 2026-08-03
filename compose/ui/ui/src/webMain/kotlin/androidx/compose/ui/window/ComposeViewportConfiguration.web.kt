/*
 * Copyright 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.compose.ui.window

import androidx.compose.ui.ComposeUiFlags
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.isClearFocusOnMouseDownEnabled

/**
 * Configuration of [ComposeViewport] behavior.
 */
@ExperimentalComposeUiApi
class ComposeViewportConfiguration internal constructor() {

    /**
     * Indicates whether accessibility (a11y) is enabled for the associated Compose viewport.
     * When it's enabled, the Compose Viewport will maintain a DOM tree mirroring the Compose semantics nodes.
     * That DOM tree is visibly hidden, but reachable by the accessibility tools.
     * It can be disabled to avoid the overhead of maintaining the DOM tree.
     * By default, it is set to `true`.
     *
     * Note: This API is experimental and subject to change in the future.
     */
    @ExperimentalComposeUiApi
    var isA11YEnabled: Boolean = true

    /**
     * Controls whether a mouse clicks on an unfocused element clears focus.
     * It's clearing focus on mouse down by default.
     */
    @ExperimentalComposeUiApi
    var isClearFocusOnMouseDownEnabled: Boolean = ComposeUiFlags.isClearFocusOnMouseDownEnabled

    /**
     * Controls whether the Compose scene handles system window insets (status bar, navigation bar,
     * IME keyboard) and exposes them via [androidx.compose.foundation.layout.WindowInsets] APIs
     * such as `WindowInsets.safeDrawing`, `WindowInsets.ime`, etc.
     *
     * When set to `true`, the scene reads safe area insets from the browser using CSS
     * `env(safe-area-inset-*)` environment variables, and tracks IME (virtual keyboard) geometry.
     *
     * **Prerequisite**: the page must opt in to edge-to-edge rendering by including
     * `viewport-fit=cover` in the viewport meta tag:
     * ```html
     * <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover" />
     * ```
     * Without `viewport-fit=cover`, the browser applies safe area padding automatically and all
     * `env(safe-area-inset-*)` variables return `0px`, so insets will always be zero.
     *
     * By default, this is `false` and the scene reports zero insets.
     *
     * **Scrollable containers:** insets are re-read on `window resize` and keyboard geometry events,
     * but not on page scroll. If the [composeScene] is inside a scrollable page, its viewport position
     * changes as the user scrolls, so the insets may become invalid. In that case
     * it is recommended to disable inset handling entirely (`enableBrowserWindowInsets = false`) and
     * manage padding manually.
     *
     * Note: This API is experimental and subject to change in the future.
     */
    @ExperimentalComposeUiApi
    var enableBrowserWindowInsets: Boolean = false

    /**
     * Multiplies the density the scene lays out with, without changing the rasterization
     * resolution: `1.dp` covers [densityScale] times as many CSS pixels, and the viewport reports
     * proportionally fewer dp of space.
     *
     * This is what makes a "10-foot" UI possible on a TV, where the browser reports a device pixel
     * ratio of `1` for a 1080p screen and a Compose UI authored at phone scale ends up unreadable
     * from across the room. A scale of `2` gives a 1920x1080 TV a 960x540 dp viewport, matching the
     * dp space an Android TV app is laid out in. See [ComposeTizenTvViewport], which sets it from
     * the screen resolution.
     *
     * Defaults to `1`, i.e. `1.dp` == 1 CSS pixel, which is the behaviour of a plain
     * [ComposeViewport].
     *
     * Note: drag-and-drop and [enableBrowserWindowInsets] still measure in CSS pixels and are not
     * corrected for this scale. Neither applies to a TV, which is what the option exists for.
     *
     * Note: This API is experimental and subject to change in the future.
     */
    @ExperimentalComposeUiApi
    var densityScale: Float = 1f
        set(value) {
            require(value > 0f) { "densityScale must be positive, but was $value" }
            field = value
        }

    /**
     * Multiplies the resolution of the canvas' backing store, i.e. the raster resolution Skia
     * rasterizes into, without changing the dp space the scene lays out in or the canvas'
     * on-screen (CSS) size: the `<canvas>` element is styled `width: 100%; height: 100%` of its
     * container (see [ComposeViewport]), so a backing store smaller than that box is simply
     * upscaled by the browser, the same way it would upscale a low-resolution `<img>`.
     *
     * This is a raw performance lever for a GPU-constrained TV: GPU cost scales with the number
     * of backing pixels rasterized every frame, and a couch-distance 10-foot UI on a 43"+ set
     * does not need every physical pixel of a 1080p (or higher) panel to look sharp. Rendering at
     * a reduced backing resolution - e.g. 720p on a 1080p screen - and letting CSS stretch the
     * canvas back up can meaningfully cut per-frame GPU work on lower-end (e.g. Mali-class) TV
     * GPUs. See [androidx.compose.ui.platform.tizenTvBackingScale] for a suggested TV value.
     *
     * Defaults to `1`, i.e. the backing store is rasterized at native resolution, which is the
     * behaviour of a plain [ComposeViewport]. Values less than `1` render fewer backing pixels
     * than the canvas' CSS box and get upscaled; values greater than `1` (supersampling) are also
     * allowed but rarely useful on a TV.
     *
     * Note: like [densityScale], drag-and-drop and [enableBrowserWindowInsets] still measure in
     * CSS pixels and are not corrected for this scale.
     *
     * Note: This API is experimental and subject to change in the future.
     */
    @ExperimentalComposeUiApi
    var backingScale: Float = 1f
        set(value) {
            require(value > 0f) { "backingScale must be positive, but was $value" }
            field = value
        }

    /**
     * Focuses the viewport's `<canvas>` as soon as it is attached, so that key events reach Compose
     * without the user clicking the page first.
     *
     * A TV has no pointer to click with: unless the canvas holds DOM focus, every remote button
     * press goes to `<body>` and the app looks frozen. Defaults to `false` to leave the focus
     * behaviour of pages that embed Compose alongside other content untouched.
     *
     * Note: This API is experimental and subject to change in the future.
     */
    @ExperimentalComposeUiApi
    var requestFocusOnStart: Boolean = false
}