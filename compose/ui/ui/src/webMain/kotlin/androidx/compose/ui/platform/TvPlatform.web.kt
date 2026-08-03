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
import androidx.compose.ui.input.key.tizenRegisteredKeyNames
import kotlin.js.js
import kotlinx.browser.window

/**
 * The TV web runtime the app is running in, if any.
 *
 * A TV runs applications in a Chromium-based web engine and delivers its remote as ordinary key
 * events, so the differences worth branching on are small: which key codes the remote's TV-specific
 * buttons use, whether those buttons have to be claimed before the TV routes them to the app at
 * all, and how the app quits.
 */
@ExperimentalComposeUiApi
enum class TvPlatform {
    /** Not a TV: an ordinary desktop or mobile browser. */
    None,

    /** Samsung Tizen TV, driven by a Samsung Smart Remote. */
    Tizen,

    /** LG webOS TV, driven by a Magic Remote. */
    WebOs,
}

/**
 * The TV web runtime hosting this app, or [TvPlatform.None] in an ordinary browser.
 *
 * Detected from what the TV injects into the page, so it is also [TvPlatform.None] in a TV
 * emulator's *browser preview*, which injects nothing.
 */
@ExperimentalComposeUiApi
val currentTvPlatform: TvPlatform by lazy(LazyThreadSafetyMode.NONE) {
    when (detectTvPlatformId()) {
        "tizen" -> TvPlatform.Tizen
        "webos" -> TvPlatform.WebOs
        else -> TvPlatform.None
    }
}

/**
 * Asks the TV to route the remote's TV-specific buttons (playback, coloured, channel, info, …) to
 * this application instead of acting on them itself.
 *
 * Only Tizen needs this: until `tizen.tvinputdevice.registerKeyBatch` has run, only the four-way
 * pad, OK and Back reach the page. webOS delivers the whole remote to the app unconditionally.
 *
 * Safe to call on any platform and more than once.
 *
 * @return `true` if a registration was performed.
 */
@ExperimentalComposeUiApi
fun registerTvRemoteKeys(): Boolean = when (currentTvPlatform) {
    TvPlatform.Tizen -> registerTizenKeyBatch(tizenRegisteredKeyNames.joinToString(","))
    TvPlatform.WebOs, TvPlatform.None -> false
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
val tvDensityScale: Float
    get() {
        val screenWidth = window.screen.width
        return if (screenWidth >= 1280) (screenWidth / 960f) else 2f
    }

/**
 * Quits the application, the behaviour a TV user expects from Back on the top-level screen. A no-op
 * in an ordinary browser, where a page cannot close itself.
 */
@ExperimentalComposeUiApi
fun exitTvApplication() {
    when (currentTvPlatform) {
        TvPlatform.Tizen -> exitTizenApplication()
        // webOS closes the app's window; `disableBackHistoryAPI` in appinfo.json is what stops the
        // system from treating Back as history navigation before the app ever sees it.
        TvPlatform.WebOs -> window.close()
        TvPlatform.None -> Unit
    }
}

private fun detectTvPlatformId(): String =
    js(
        """(function() {
            try {
                if (typeof tizen !== 'undefined' && typeof tizen.tvinputdevice !== 'undefined') {
                    return 'tizen';
                }
                // PalmSystem is injected into every webOS app; the webOS global only exists once
                // webOSTV.js has been loaded, and the user agent is the fallback for neither.
                if (typeof PalmSystem !== 'undefined') return 'webos';
                if (typeof webOS !== 'undefined' && webOS.platform && webOS.platform.tv) return 'webos';
                if (typeof navigator !== 'undefined' && /web0s/i.test(navigator.userAgent)) {
                    return 'webos';
                }
            } catch (e) {
                // Reading a device API can throw when its privilege is missing. Not that TV, then.
            }
            return '';
        })()"""
    )

private fun registerTizenKeyBatch(commaSeparatedNames: String): Boolean =
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
