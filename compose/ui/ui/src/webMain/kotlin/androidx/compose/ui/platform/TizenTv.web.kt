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

package androidx.compose.ui.platform

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.key.tizenTvRegisteredKeyNames
import kotlin.js.js
import kotlinx.browser.window

/**
 * `true` when the app is running inside a Tizen TV web runtime, i.e. the `tizen` device APIs are
 * present. `false` in an ordinary desktop or mobile browser, and also in the Tizen TV *emulator's*
 * browser preview, which does not inject the device APIs.
 */
@ExperimentalComposeUiApi
val isTizenTv: Boolean by lazy(LazyThreadSafetyMode.NONE) { detectTizenTv() }

/**
 * Asks the TV to route the Samsung Smart Remote's TV-specific buttons (playback, coloured, channel,
 * info, …) to this application instead of handling them itself. Until this is called, only the
 * four-way pad, OK, and Back reach the page.
 *
 * The four-way pad and OK arrive as ordinary `ArrowUp`/`Enter` key events and need no registration;
 * Back (key code `10009`) is always delivered.
 *
 * Safe to call on any platform and more than once: it is a no-op outside a Tizen TV.
 *
 * @return `true` if the keys were registered.
 */
@ExperimentalComposeUiApi
fun registerTizenTvRemoteKeys(): Boolean {
    if (!isTizenTv) return false
    return registerTizenTvKeyBatch(tizenTvRegisteredKeyNames.joinToString(","))
}

/**
 * The [androidx.compose.ui.window.ComposeViewportConfiguration.densityScale] that gives this TV a
 * dp viewport of roughly 960dp across — the width an Android TV app is laid out in, and the scale
 * a UI has to be drawn at to stay readable from a couch.
 *
 * Derived from the screen width, so a viewport reported as 1920 CSS pixels across gets `2` and one
 * reported as 3840 gets `4`. Below 1280 the formula stops making sense for a TV, so it falls back
 * to `2`.
 */
@ExperimentalComposeUiApi
val tizenTvDensityScale: Float
    get() {
        val screenWidth = window.screen.width
        return if (screenWidth >= 1280) (screenWidth / 960f) else 2f
    }

/**
 * Quits the application, the behaviour a TV user expects from Back on the top-level screen (and
 * what the platform certification requires). A no-op outside a Tizen TV.
 */
@ExperimentalComposeUiApi
fun exitTizenTvApplication() {
    if (!isTizenTv) return
    exitTizenApplication()
}

private fun detectTizenTv(): Boolean =
    js("typeof tizen !== 'undefined' && typeof tizen.tvinputdevice !== 'undefined'")

private fun registerTizenTvKeyBatch(commaSeparatedNames: String): Boolean =
    js(
        """(function() {
            try {
                tizen.tvinputdevice.registerKeyBatch(commaSeparatedNames.split(','));
                return true;
            } catch (e) {
                console.warn('Compose: could not register Tizen TV remote keys: ' + e);
                return false;
            }
        })()"""
    )

private fun exitTizenApplication(): Unit =
    js(
        """(function() {
            try {
                tizen.application.getCurrentApplication().exit();
            } catch (e) {
                console.warn('Compose: could not exit the Tizen application: ' + e);
            }
        })()"""
    )
