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

import androidx.compose.ui.platform.TvPlatform

/*
 * A TV remote delivers its TV-specific buttons as plain `keydown`/`keyup` events, but only
 * KeyboardEvent.keyCode carries the button identity. The `key`/`code` strings are unusable: a real
 * TV aliases each button to the nearest PC keyboard key (on Tizen, Back is `key "XF86Back"` with
 * `code "Escape"`, and the coloured buttons are `"F1"`..`"F4"`), while an emulator leaves `code`
 * empty — so `KeyEvent.web.kt` resolves these tables before its string maps whenever it is running
 * on a TV.
 *
 * The four-way pad, OK, and the digits are ordinary keyboard codes (`ArrowUp`, `Enter`, `Digit0`,
 * …) whose key codes are absent from the Tizen table, so they keep resolving through the string
 * map. Each table holds the codes its vendor publishes for its TV web runtime; the Tizen Back and
 * coloured-button aliases were confirmed against a Tizen 9.0 set (UA43DU7000).
 */

/** Key codes of the Samsung Smart Remote's TV-specific buttons on Tizen TV. */
private val tizenKeyCodes: Map<Int, Key> = mapOf(
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
 * Names understood by `tizen.tvinputdevice.registerKeyBatch`. A Tizen TV only routes a remote
 * button to the application after it has been registered, so this list must stay in sync with
 * the Tizen half of [tvRemoteKeyFromKeyCode].
 *
 * `Exit`, the volume keys, and the power key are deliberately absent: Tizen reserves them for the
 * system and rejects an attempt to register them.
 */
internal val tizenRegisteredKeyNames: List<String> = listOf(
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
 * Key codes of the LG Magic Remote's TV-specific buttons on webOS TV.
 *
 * The playback and coloured buttons use the same codes as Tizen — they are the CEA/HTML5 media key
 * codes — but Back and the channel keys do not, which is the whole reason the table is per-platform.
 *
 * Note on channel keys: `33`/`34` are also PageUp/PageDown, and this table is consulted before the
 * string maps, so on a webOS TV a USB keyboard's PageUp/PageDown report [Key.ChannelUp] and
 * [Key.ChannelDown]. That is the one place a TV table shadows a real keyboard key — the remote
 * wins deliberately, since it is the input a TV app is built for. Tizen has no such collision.
 *
 * Unlike the Tizen table these codes have not been checked against real hardware; if a webOS set
 * turns out not to alias its buttons to keyboard strings the way Tizen does, only the ordering
 * matters here, not the codes themselves.
 */
private val webOsKeyCodes: Map<Int, Key> = mapOf(
    // Navigation.
    461 to Key.Back,

    // Playback.
    415 to Key.MediaPlay,
    19 to Key.MediaPause,
    413 to Key.MediaStop,
    412 to Key.MediaRewind,
    417 to Key.MediaFastForward,
    416 to Key.MediaRecord,

    // Coloured buttons.
    403 to Key.ProgramRed,
    404 to Key.ProgramGreen,
    405 to Key.ProgramYellow,
    406 to Key.ProgramBlue,

    // Channel and information.
    33 to Key.ChannelUp,
    34 to Key.ChannelDown,
    457 to Key.Info,
)

/**
 * Resolves a TV remote button from its numeric key code, for the remote [platform] ships with.
 *
 * Returns `null` when the code is not one of that remote's buttons — including always, off a TV —
 * so the caller keeps the result of the regular string-based mapping.
 */
internal fun tvRemoteKeyFromKeyCode(platform: TvPlatform, keyCode: Int): Key? =
    when (platform) {
        TvPlatform.Tizen -> tizenKeyCodes[keyCode]
        TvPlatform.WebOs -> webOsKeyCodes[keyCode]
        TvPlatform.None -> null
    }
