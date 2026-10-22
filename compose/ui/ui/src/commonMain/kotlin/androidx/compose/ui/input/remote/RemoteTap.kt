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

// tvOS fork: taps on the touch surface of a remote, sent around the focused item like keys.

package androidx.compose.ui.input.remote

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo

/**
 * Calls [onTap] when a finger rests on the touch surface of a remote and lifts without swiping or
 * clicking, while this node or one of its descendants has focus. Only the nearest such node around
 * the focused item is called, the way UIKit gives the touches of a Siri Remote to the focused view.
 */
@ExperimentalComposeUiApi
public fun Modifier.onRemoteTap(onTap: () -> Unit): Modifier = this then RemoteTapElement(onTap)

private class RemoteTapElement(val onTap: () -> Unit) : ModifierNodeElement<RemoteTapNode>() {
    override fun create() = RemoteTapNode(onTap)

    override fun update(node: RemoteTapNode) {
        node.onTap = onTap
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "onRemoteTap"
        properties["onTap"] = onTap
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is RemoteTapElement && onTap === other.onTap)

    override fun hashCode(): Int = onTap.hashCode()
}

internal class RemoteTapNode(var onTap: () -> Unit) : Modifier.Node()
