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

package androidx.compose.ui.input.key

/**
 * The Samsung Smart Remote delivers its TV-specific buttons as plain `keydown`/`keyup` events, but
 * only [org.w3c.dom.events.KeyboardEvent.keyCode] carries the button identity: `key` is `"Unidentified"`
 * and `code` is empty, so the string-based lookup in `KeyEvent.web.kt` cannot resolve them.
 *
 * The four-way pad, OK, and the digits are ordinary keyboard codes (`ArrowUp`, `Enter`, `Digit0`, …)
 * and keep resolving through the string map; only the entries below need the numeric fallback.
 *
 * Key codes are the ones published by Samsung for the TV web runtime.
 */
internal val tizenTvKeyCodes: Map<Int, Key> = mapOf(
    // Navigation.
    10009 to Key.Back,
    10182 to Key.MediaClose, // "Exit" — quits the application on a real TV.
    10135 to Key.Menu, // "Tools"

    // Playback.
    415 to Key.MediaPlay,
    19 to Key.MediaPause,
    10252 to Key.MediaPlayPause,
    413 to Key.MediaStop,
    412 to Key.MediaRewind,
    417 to Key.MediaFastForward,
    416 to Key.MediaRecord,
    10233 to Key.MediaNext,
    10232 to Key.MediaPrevious,

    // Coloured buttons.
    403 to Key.ProgramRed,
    404 to Key.ProgramGreen,
    405 to Key.ProgramYellow,
    406 to Key.ProgramBlue,

    // Channel and information.
    427 to Key.ChannelUp,
    428 to Key.ChannelDown,
    10190 to Key.Tv, // "Previous channel"
    457 to Key.Info,
    458 to Key.Guide,
    10221 to Key.Captions,
    10225 to Key.Search,
    10072 to Key.TvInput, // "Source"

    // Volume keys are normally swallowed by the TV, but they are delivered when the application
    // registers them explicitly.
    447 to Key.VolumeUp,
    448 to Key.VolumeDown,
    449 to Key.VolumeMute,
)

/**
 * Names understood by `tizen.tvinputdevice.registerKeyBatch`. A TV only routes a remote button to
 * the application after it has been registered, so this list must stay in sync with
 * [tizenTvKeyCodes].
 *
 * `Exit`, the volume keys, and the power key are deliberately absent: Tizen reserves them for the
 * system and rejects an attempt to register them.
 */
internal val tizenTvRegisteredKeyNames: List<String> = listOf(
    "MediaPlay",
    "MediaPause",
    "MediaPlayPause",
    "MediaStop",
    "MediaRewind",
    "MediaFastForward",
    "MediaRecord",
    "MediaTrackNext",
    "MediaTrackPrevious",
    "ColorF0Red",
    "ColorF1Green",
    "ColorF2Yellow",
    "ColorF3Blue",
    "ChannelUp",
    "ChannelDown",
    "PreviousChannel",
    "Info",
    "Guide",
    "Caption",
    "Search",
    "Source",
    "Tools",
)

/**
 * Resolves a Samsung Smart Remote button from its numeric key code.
 *
 * Returns `null` when the code is not a TV remote button, so the caller can fall back to the
 * regular string-based mapping.
 */
internal fun tizenTvKeyFromKeyCode(keyCode: Int): Key? = tizenTvKeyCodes[keyCode]
