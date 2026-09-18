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

// tvOS fork: swipes on the touch surface of a remote, which no upstream platform reports.

package androidx.compose.ui.input.remote

import androidx.compose.ui.Modifier
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo

/**
 * Receives the swipes made on the touch surface of a remote, such as the clickpad of the Siri
 * Remote, on the element this modifier is on, on any focused descendant of it, or on any element
 * below it that is focused.
 *
 * Such a swipe is normally turned into a directional key event, so it moves focus the way the D-pad
 * does. When this modifier is on the focused element or on one of its ancestors, the swipe is
 * delivered to [onSwipe] instead, starting with the innermost such modifier. Returning `true`
 * claims it, so no key event is sent for it; returning `false` passes it to the next `remoteSwipe`
 * further out, as if this modifier were not there. Only when no modifier consumes it does the
 * default key conversion run, which is also what happens without this modifier anywhere above the
 * focused element.
 *
 * Presses are unaffected: clicks and arrow presses keep producing key events, and so do swipes that
 * are not recognised as one of the four directions.
 *
 * A swipe straight across the pad ([RemoteSwipe.isTraverse]) is the one the default handling
 * carries with momentum. It may arrive as two callbacks: the move itself, and then, once the finger
 * reaches the far side, a second [RemoteSwipe] with [RemoteSwipe.isTraverse] set and the speed the
 * momentum would have been sized from. The result of the first callback decides the whole contact:
 * the momentum callback only runs when the first one claimed the swipe, and its own result is
 * ignored.
 *
 * On platforms with no remote touch surface this modifier is inert.
 *
 * @param enabled when `false` the swipes are swallowed: the swipe is consumed without calling
 *   [onSwipe], so neither a `remoteSwipe` further out nor the default key conversion runs for it.
 * @param onSwipe invoked for every swipe that reaches this modifier. Returns whether the swipe was
 *   handled, which keeps it from being turned into key events.
 */
public fun Modifier.remoteSwipe(
    enabled: Boolean = true,
    onSwipe: (RemoteSwipe) -> Boolean,
): Modifier = this then RemoteSwipeElement(enabled = enabled, onSwipe = onSwipe)

/**
 * A [Modifier.Node][androidx.compose.ui.Modifier.Node] that receives the swipes made on the touch
 * surface of a remote when it is on the focused element or on one of its ancestors, and no such
 * node further in claimed them.
 */
internal interface RemoteSwipeModifierNode : DelegatableNode {
    /** Returns whether [event] was claimed, which keeps it from being turned into key events. */
    fun onRemoteSwipe(event: RemoteSwipe): Boolean
}

private class RemoteSwipeElement(val enabled: Boolean, val onSwipe: (RemoteSwipe) -> Boolean) :
    ModifierNodeElement<RemoteSwipeNode>() {
    override fun create() = RemoteSwipeNode(enabled = enabled, onSwipe = onSwipe)

    override fun update(node: RemoteSwipeNode) {
        node.enabled = enabled
        node.onSwipe = onSwipe
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "remoteSwipe"
        properties["enabled"] = enabled
        properties["onSwipe"] = onSwipe
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RemoteSwipeElement) return false

        if (enabled != other.enabled) return false
        if (onSwipe !== other.onSwipe) return false

        return true
    }

    override fun hashCode(): Int {
        var result = enabled.hashCode()
        result = 31 * result + onSwipe.hashCode()
        return result
    }
}

private class RemoteSwipeNode(var enabled: Boolean, var onSwipe: (RemoteSwipe) -> Boolean) :
    RemoteSwipeModifierNode, Modifier.Node() {
    // A disabled modifier swallows the swipe rather than letting it fall back to the key
    // conversion, so it claims it without asking the callback.
    override fun onRemoteSwipe(event: RemoteSwipe): Boolean {
        if (!enabled) return true
        return onSwipe(event)
    }
}
