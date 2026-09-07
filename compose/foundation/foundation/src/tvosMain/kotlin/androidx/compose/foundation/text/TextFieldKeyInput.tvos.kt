/*
 * Copyright 2022 The Android Open Source Project
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

package androidx.compose.foundation.text

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint

/**
 * Unlike iOS, tvOS does not use the UITextInput protocol to receive characters from a
 * hardware (Bluetooth) keyboard: the hidden UITextField backing a Compose text field is only
 * made first responder while the system's on-screen keyboard overlay is presented, which is not
 * the case when a physical keyboard is attached. Instead, characters typed on a hardware
 * keyboard arrive as regular Compose [KeyEvent]s, so they must be treated as typed text here.
 *
 * UIKit reports non-character keys (arrows, escape, forward delete, function keys) with a
 * private-use character in `UIKey.characters`, so code points in the Unicode private use area
 * are rejected along with the ISO control codes, and the keys that are navigation or editing
 * commands rather than text are rejected by their [Key].
 */
internal actual val KeyEvent.isTypedEvent: Boolean
    get() = type == KeyEventType.KeyDown &&
        utf16CodePoint != 0 &&
        !isISOControl(utf16CodePoint) &&
        utf16CodePoint !in PRIVATE_USE_AREA &&
        key !in NonTypingKeys &&
        !isMetaPressed &&
        !isCtrlPressed

private fun isISOControl(codePoint: Int): Boolean =
    codePoint in 0x00..0x1F ||
    codePoint in 0x7F..0x9F

private val PRIVATE_USE_AREA = 0xE000..0xF8FF

private val NonTypingKeys = setOf(
    Key.DirectionUp,
    Key.DirectionDown,
    Key.DirectionLeft,
    Key.DirectionRight,
    Key.DirectionCenter,
    Key.Escape,
    Key.Delete,
    Key.Backspace,
    Key.Tab,
    Key.Enter,
    Key.NumPadEnter,
    Key.Menu,
    Key.Back,
    Key.MoveHome,
    Key.MoveEnd,
    Key.PageUp,
    Key.PageDown,
    Key.Insert,
)
