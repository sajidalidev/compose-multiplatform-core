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

// tvOS fork: binds the remote fast scrolling mode of the ui layer to a ScrollableState.

package androidx.compose.foundation.gestures

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.MutatePriority
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.remote.RemoteFastScrollAxis
import androidx.compose.ui.input.remote.RemoteFastScrollModifierNode
import androidx.compose.ui.input.remote.RemoteFastScrollState
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.TraversableNode
import androidx.compose.ui.platform.InspectorInfo
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch

/**
 * Lets a platform with a remote scroll this container quickly, with the touch surface or the
 * D-pad. The mode is started and driven by the platform, never by this modifier: on platforms with
 * no remote it is inert, and without this modifier no scroll container changes.
 *
 * While the mode runs, focus rests on the container and [fastScrollState] is updated. When it ends,
 * focus lands on the item at the centre of the viewport, and the container does not scroll to bring
 * that item into view. The scroll mutex of [scrollableState] is held at [MutatePriority.UserInput]
 * for the duration of the mode, so animations such as bring into view do not fight the scroll.
 *
 * Sample usage:
 * ```
 * val listState = rememberLazyListState()
 * val fastScrollState = rememberRemoteFastScrollState()
 * LazyColumn(
 *     state = listState,
 *     modifier = Modifier.remoteFastScroll(listState, Orientation.Vertical, fastScrollState),
 * ) {
 *     items(500) { Text("Row $it", Modifier.focusable()) }
 * }
 * ```
 *
 * @param scrollableState the state that is scrolled.
 * @param orientation the orientation the container scrolls in.
 * @param fastScrollState receives the observable state of the mode.
 * @param enabled when `false` the mode cannot start.
 * @param reverseDirection `true` if the container lays out its content in reverse, so that
 *   scrolling forward means a negative delta.
 */
@ExperimentalFoundationApi
@OptIn(ExperimentalComposeUiApi::class)
public fun Modifier.remoteFastScroll(
    scrollableState: ScrollableState,
    orientation: Orientation,
    fastScrollState: RemoteFastScrollState,
    enabled: Boolean = true,
    reverseDirection: Boolean = false,
): Modifier =
    this then
        RemoteFastScrollScrollableElement(
            scrollableState = scrollableState,
            orientation = orientation,
            fastScrollState = fastScrollState,
            enabled = enabled,
            reverseDirection = reverseDirection,
        )

/** Creates and remembers a [RemoteFastScrollState] for [Modifier.remoteFastScroll]. */
@ExperimentalFoundationApi
@OptIn(ExperimentalComposeUiApi::class)
@Composable
public fun rememberRemoteFastScrollState(): RemoteFastScrollState = remember {
    RemoteFastScrollState()
}

@OptIn(ExperimentalComposeUiApi::class)
private class RemoteFastScrollScrollableElement(
    val scrollableState: ScrollableState,
    val orientation: Orientation,
    val fastScrollState: RemoteFastScrollState,
    val enabled: Boolean,
    val reverseDirection: Boolean,
) : ModifierNodeElement<RemoteFastScrollScrollableNode>() {
    override fun create() =
        RemoteFastScrollScrollableNode(
            scrollableState,
            orientation,
            fastScrollState,
            enabled,
            reverseDirection,
        )

    override fun update(node: RemoteFastScrollScrollableNode) {
        node.update(scrollableState, orientation, fastScrollState, enabled, reverseDirection)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "remoteFastScroll"
        properties["scrollableState"] = scrollableState
        properties["orientation"] = orientation
        properties["fastScrollState"] = fastScrollState
        properties["enabled"] = enabled
        properties["reverseDirection"] = reverseDirection
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RemoteFastScrollScrollableElement) return false

        if (scrollableState != other.scrollableState) return false
        if (orientation != other.orientation) return false
        if (fastScrollState != other.fastScrollState) return false
        if (enabled != other.enabled) return false
        if (reverseDirection != other.reverseDirection) return false

        return true
    }

    override fun hashCode(): Int {
        var result = scrollableState.hashCode()
        result = 31 * result + orientation.hashCode()
        result = 31 * result + fastScrollState.hashCode()
        result = 31 * result + enabled.hashCode()
        result = 31 * result + reverseDirection.hashCode()
        return result
    }
}

internal const val RemoteFastScrollTraverseKey = "androidx.compose.foundation.gestures.RemoteFastScrollScrollableNode"

@OptIn(ExperimentalComposeUiApi::class)
internal class RemoteFastScrollScrollableNode(
    private var scrollableState: ScrollableState,
    private var orientation: Orientation,
    private var fastScrollState: RemoteFastScrollState,
    private var enabled: Boolean,
    private var reverseDirection: Boolean,
) : DelegatingNode(), TraversableNode {
    // Lets tests find the node from a descendant modifier.
    override val traverseKey: Any = RemoteFastScrollTraverseKey

    internal var fastScrollNode: RemoteFastScrollModifierNode = createFastScrollNode()
        private set

    private var sessionJob: Job? = null

    init {
        delegate(fastScrollNode)
    }

    fun update(
        scrollableState: ScrollableState,
        orientation: Orientation,
        fastScrollState: RemoteFastScrollState,
        enabled: Boolean,
        reverseDirection: Boolean,
    ) {
        val recreate =
            this.scrollableState !== scrollableState ||
                this.orientation != orientation ||
                this.fastScrollState !== fastScrollState ||
                this.enabled != enabled
        this.scrollableState = scrollableState
        this.reverseDirection = reverseDirection
        if (recreate) {
            this.orientation = orientation
            this.fastScrollState = fastScrollState
            this.enabled = enabled
            recreateFastScrollNode()
        }
    }

    private fun recreateFastScrollNode() {
        // Detaching the old node ends a running mode, which releases the scroll mutex.
        undelegate(fastScrollNode)
        fastScrollNode = createFastScrollNode()
        delegate(fastScrollNode)
    }

    private fun createFastScrollNode(): RemoteFastScrollModifierNode =
        RemoteFastScrollModifierNode(
            state = fastScrollState,
            axis =
                if (orientation == Orientation.Vertical) RemoteFastScrollAxis.Vertical
                else RemoteFastScrollAxis.Horizontal,
            enabled = enabled,
            onStart = ::startSession,
            onEnd = ::endSession,
            onScrollBy = ::scrollBy,
        )

    private fun startSession() {
        endSession()
        val state = scrollableState
        val job =
            coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
                state.scroll(MutatePriority.UserInput) { awaitCancellation() }
            }
        sessionJob = job
        job.invokeOnCompletion {
            if (sessionJob === job) {
                // Another scroll took the mutex while the mode runs: the mode must not keep
                // dispatching deltas without it. Detaching the ui node aborts the mode.
                sessionJob = null
                if (isAttached) recreateFastScrollNode()
            }
        }
    }

    private fun endSession() {
        val job = sessionJob
        sessionJob = null
        job?.cancel()
    }

    private fun scrollBy(delta: Float): Float {
        val signed = if (reverseDirection) -delta else delta
        val consumed = scrollableState.dispatchRawDelta(signed)
        return if (reverseDirection) -consumed else consumed
    }

    override fun onDetach() {
        endSession()
    }
}
