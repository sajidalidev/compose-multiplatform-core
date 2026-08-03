/*
 * Copyright 2026 The Android Open Source Project
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

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.registerTvRemoteKeys
import androidx.compose.ui.platform.tvDensityScale
import org.w3c.dom.Element

/**
 * EXPERIMENTAL! Might be deleted or changed in the future!
 *
 * Creates a Compose viewport set up for a TV application: a "10-foot" density scale, the remote's
 * TV buttons routed to the app, and DOM focus on the canvas so the remote reaches Compose without
 * a pointer to click with.
 *
 * The TV it is running on is detected at runtime
 * ([androidx.compose.ui.platform.currentTvPlatform]), so one build serves every supported platform.
 *
 * It is otherwise a [ComposeViewport] and takes the same [ComposeViewportConfiguration], so any of
 * the defaults below can be overridden from [configure]:
 * - [ComposeViewportConfiguration.densityScale] is set from the screen resolution
 *   ([androidx.compose.ui.platform.tvDensityScale]), giving a 1080p TV a 960x540 dp viewport — the
 *   dp space an Android TV app is laid out in.
 * - [ComposeViewportConfiguration.requestFocusOnStart] is enabled.
 * - [ComposeViewportConfiguration.isClearFocusOnMouseDownEnabled] is disabled: losing focus to a
 *   stray pointer event would leave the remote with nothing to move focus from.
 *
 * Off a TV (a desktop browser, an emulator's browser preview) the remote key registration is
 * skipped and the rest still applies, so the app can be developed in Chrome and look the same.
 *
 * Focus is what drives a TV UI, so make the content focusable and give it an initial focus target
 * with `Modifier.focusRequester(…)`. The four-way pad then moves focus, OK activates
 * `Modifier.clickable`, and Back is dispatched through the navigation event dispatcher (so
 * `BackHandler`/`NavHost` handle it) — see
 * [androidx.compose.ui.platform.exitTvApplication] for quitting on the top-level
 * screen, which a TV user expects Back to do.
 *
 * @param viewportContainerId The id of an HTML element which would host the Compose Viewport.
 * If it's null, then `<body>` will be used as a container.
 * @param configure A lambda for Compose Viewport configuration, applied on top of the TV defaults.
 * @param content The Composable content to be rendered on the `<canvas>` element.
 */
@ExperimentalComposeUiApi
fun ComposeTvViewport(
    viewportContainerId: String? = null,
    configure: ComposeViewportConfiguration.() -> Unit = {},
    content: @Composable () -> Unit = { }
) {
    registerTvRemoteKeys()
    ComposeViewport(
        viewportContainerId = viewportContainerId,
        configure = {
            applyTvDefaults()
            configure.invoke(this)
        },
        content = content
    )
}

/**
 * EXPERIMENTAL! Might be deleted or changed in the future!
 *
 * [ComposeTvViewport] hosted in the given [viewportContainer] element.
 */
@ExperimentalComposeUiApi
fun ComposeTvViewport(
    viewportContainer: Element,
    configure: ComposeViewportConfiguration.() -> Unit = {},
    content: @Composable () -> Unit = { }
) {
    registerTvRemoteKeys()
    ComposeViewport(
        viewportContainer = viewportContainer,
        configure = {
            applyTvDefaults()
            configure.invoke(this)
        },
        content = content
    )
}

@ExperimentalComposeUiApi
private fun ComposeViewportConfiguration.applyTvDefaults() {
    densityScale = tvDensityScale
    requestFocusOnStart = true
    isClearFocusOnMouseDownEnabled = false
}
