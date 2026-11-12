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

// tvOS fork: tells the directional key events of a touch-surface swipe from those of a press.

package androidx.compose.ui.input.remote

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.internal

/**
 * The swipe on the touch surface of a remote that a directional [KeyEvent] was made from. Every
 * step of one finger contact, its momentum included, carries the same instance.
 */
@ExperimentalComposeUiApi
public class RemoteSwipe internal constructor(
    /** Identifies the finger contact on the touch surface, unique within the process. */
    public val contactId: Long,
)

/**
 * The touch-surface swipe this key event was made from, or `null` for a key from a press,
 * including a click on the clickpad of a Siri Remote.
 */
@ExperimentalComposeUiApi
public val KeyEvent.remoteSwipe: RemoteSwipe?
    get() = internal.nativeEvent as? RemoteSwipe

private var lastContactId = 0L

/** A swipe for a new finger contact. Contacts only begin on the main thread. */
internal fun newRemoteSwipe(): RemoteSwipe = RemoteSwipe(++lastContactId)
