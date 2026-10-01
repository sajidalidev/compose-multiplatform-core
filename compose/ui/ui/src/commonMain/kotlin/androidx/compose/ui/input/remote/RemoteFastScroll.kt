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

// tvOS fork: opt-in fast scrolling of a container with the touch surface or the D-pad of a remote.

package androidx.compose.ui.input.remote

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.focus.FocusTargetNode
import androidx.compose.ui.focus.Focusability
import androidx.compose.ui.focus.findAccessibleChildNearest
import androidx.compose.ui.focus.focusRect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.requireOwner
import androidx.compose.ui.platform.InspectorInfo
import kotlin.js.JsName

/** The axis a fast scroll runs along. */
@ExperimentalComposeUiApi
public enum class RemoteFastScrollAxis {
    Horizontal,
    Vertical,
}

/**
 * Observable state of the fast scrolling mode of one container, for app drawn indicators.
 *
 * While the mode runs, focus leaves the focused item and rests on the container, so an item that
 * reads its focus state sees a blur when the mode starts and focus on another item when it ends.
 */
@ExperimentalComposeUiApi
@Stable
public class RemoteFastScrollState {
    /** Whether the container is in the fast scrolling mode. Snapshot state. */
    public var isActive: Boolean by mutableStateOf(false)
        internal set

    /** Signed speed in pixels per second, positive toward the bottom or the right. Snapshot state. */
    public var velocity: Float by mutableFloatStateOf(0f)
        internal set
}

/**
 * Marker for the node created by [RemoteFastScrollModifierNode], so other modules can delegate to
 * it.
 */
@ExperimentalComposeUiApi public sealed interface RemoteFastScrollModifierNode : DelegatableNode

/**
 * Creates a [RemoteFastScrollModifierNode] that a custom modifier node can delegate to. See
 * [Modifier.remoteFastScroll] for the parameters.
 *
 * @param onStart invoked when the mode starts, after focus moved to the container.
 * @param onEnd invoked when the mode ends for any reason.
 */
@ExperimentalComposeUiApi
@JsName("funRemoteFastScrollModifierNode")
public fun RemoteFastScrollModifierNode(
    state: RemoteFastScrollState,
    axis: RemoteFastScrollAxis,
    enabled: Boolean = true,
    onStart: () -> Unit = {},
    onEnd: () -> Unit = {},
    onScrollBy: (delta: Float) -> Float,
): RemoteFastScrollModifierNode =
    RemoteFastScrollNode(
        state = state,
        axis = axis,
        enabled = enabled,
        onStart = onStart,
        onEnd = onEnd,
        onScrollBy = onScrollBy,
    )

/**
 * Low level modifier that lets a platform with a remote scroll this element quickly. The caller
 * scrolls its own content in [onScrollBy] and returns what it consumed.
 *
 * The mode is started and driven by the platform, never by this modifier: on platforms with no
 * remote it is inert. While it runs, focus rests on the element carrying this modifier and
 * [state] is updated. The element is not focusable outside the mode.
 *
 * @param state receives the observable state of the mode.
 * @param axis the axis the content scrolls along.
 * @param enabled when `false` the mode cannot start.
 * @param onScrollBy scrolls the content by a delta in pixels, positive toward the bottom or the
 *   right, and returns the part of it that was consumed.
 */
@ExperimentalComposeUiApi
public fun Modifier.remoteFastScroll(
    state: RemoteFastScrollState,
    axis: RemoteFastScrollAxis,
    enabled: Boolean = true,
    onScrollBy: (delta: Float) -> Float,
): Modifier =
    this then
        RemoteFastScrollElement(
            state = state,
            axis = axis,
            enabled = enabled,
            onScrollBy = onScrollBy,
        )

private class RemoteFastScrollElement(
    val state: RemoteFastScrollState,
    val axis: RemoteFastScrollAxis,
    val enabled: Boolean,
    val onScrollBy: (Float) -> Float,
) : ModifierNodeElement<RemoteFastScrollNode>() {
    override fun create() =
        RemoteFastScrollNode(
            state = state,
            axis = axis,
            enabled = enabled,
            onStart = {},
            onEnd = {},
            onScrollBy = onScrollBy,
        )

    override fun update(node: RemoteFastScrollNode) {
        // The old state would stay active with its last velocity: end the mode before the swap.
        if (node.state !== state) node.abortFastScroll()
        node.state = state
        node.axis = axis
        node.enabled = enabled
        node.onScrollBy = onScrollBy
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "remoteFastScroll"
        properties["state"] = state
        properties["axis"] = axis
        properties["enabled"] = enabled
        properties["onScrollBy"] = onScrollBy
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RemoteFastScrollElement) return false

        if (state != other.state) return false
        if (axis != other.axis) return false
        if (enabled != other.enabled) return false
        if (onScrollBy !== other.onScrollBy) return false

        return true
    }

    override fun hashCode(): Int {
        var result = state.hashCode()
        result = 31 * result + axis.hashCode()
        result = 31 * result + enabled.hashCode()
        result = 31 * result + onScrollBy.hashCode()
        return result
    }
}

/** Where the focus lands when the mode ends, relative to the viewport of the container. */
internal enum class RemoteFastScrollExitAnchor {
    Center,
    Start,
    End,
}

internal class RemoteFastScrollNode(
    var state: RemoteFastScrollState,
    var axis: RemoteFastScrollAxis,
    var enabled: Boolean,
    var onStart: () -> Unit,
    var onEnd: () -> Unit,
    var onScrollBy: (Float) -> Float,
) : DelegatingNode(), RemoteFastScrollModifierNode {
    // Not focusable outside the mode, so the focus search never stops on the container.
    private val focusTarget =
        delegate(
            FocusTargetNode(
                focusability = Focusability.Never,
                onFocusChange = ::onContainerFocusChange,
            )
        )

    private var crossAxisHint = Float.NaN
    private var isEnding = false

    var isFastScrolling: Boolean = false
        private set

    private val isVertical: Boolean
        get() = axis == RemoteFastScrollAxis.Vertical

    fun accepts(direction: RemoteSwipeDirection): Boolean {
        if (!enabled || !isAttached) return false
        return when (direction) {
            RemoteSwipeDirection.Up,
            RemoteSwipeDirection.Down -> isVertical
            RemoteSwipeDirection.Left,
            RemoteSwipeDirection.Right -> !isVertical
        }
    }

    /** Moves focus from the focused item to the container. Returns `false` if that failed. */
    fun beginFastScroll(): Boolean {
        if (isFastScrolling) return true
        if (!isAttached) return false

        val focused = requireOwner().focusOwner.activeFocusTargetNode?.focusRect()
        crossAxisHint =
            when {
                focused == null -> Float.NaN
                isVertical -> focused.center.x
                else -> focused.center.y
            }
        focusTarget.focusability = Focusability.Always
        if (!focusTarget.requestFocus(FocusDirection.Enter)) {
            focusTarget.focusability = Focusability.Never
            return false
        }
        isFastScrolling = true
        state.isActive = true
        onStart()
        return true
    }

    fun fastScrollBy(deltaPx: Float, velocityPx: Float): Float {
        if (!isFastScrolling) return 0f
        val consumed = onScrollBy(deltaPx)
        state.velocity = velocityPx
        return consumed
    }

    /** Focuses the child at [anchor] and ends the mode. Returns `false` if no child was found. */
    fun endFastScroll(anchor: RemoteFastScrollExitAnchor): Boolean {
        if (!isFastScrolling) return false
        val rect = focusTarget.focusRect()
        val anchorPoint =
            when (anchor) {
                RemoteFastScrollExitAnchor.Center -> rect.center
                RemoteFastScrollExitAnchor.Start ->
                    if (isVertical) Offset(rect.center.x, rect.top)
                    else Offset(rect.left, rect.center.y)
                RemoteFastScrollExitAnchor.End ->
                    if (isVertical) Offset(rect.center.x, rect.bottom)
                    else Offset(rect.right, rect.center.y)
            }
        val child =
            focusTarget.findAccessibleChildNearest(anchorPoint, isVertical, crossAxisHint)
                ?: return false

        isEnding = true
        val focused =
            try {
                child.requestFocus(FocusDirection.Enter)
            } finally {
                isEnding = false
            }
        if (!focused) return false

        focusTarget.focusability = Focusability.Never
        finishFastScroll()
        return true
    }

    /** Ends the mode without any focus work: resets the state and calls `onEnd`. */
    fun abortFastScroll() {
        if (!isFastScrolling) return
        finishFastScroll()
    }

    private fun finishFastScroll() {
        isFastScrolling = false
        state.isActive = false
        state.velocity = 0f
        onEnd()
    }

    private fun onContainerFocusChange(previous: FocusState, current: FocusState) {
        if (!previous.isFocused || current.isFocused) return
        // The app took focus while the mode ran. Ending it here is not a restore of focus.
        if (isFastScrolling && !isEnding) abortFastScroll()
        focusTarget.focusability = Focusability.Never
    }

    override fun onDetach() {
        abortFastScroll()
    }
}
