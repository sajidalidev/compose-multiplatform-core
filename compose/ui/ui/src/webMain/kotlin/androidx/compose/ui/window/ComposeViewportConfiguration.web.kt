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