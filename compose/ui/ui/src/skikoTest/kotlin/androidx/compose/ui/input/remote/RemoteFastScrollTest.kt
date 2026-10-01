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

package androidx.compose.ui.input.remote

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.scene.findRemoteFastScrollNode
import androidx.compose.ui.scene.runRemoteInput
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class, InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
class RemoteFastScrollTest {
    private val vertical = RemoteSwipeDirection.Down

    private fun SkikoComposeUiTest.node(direction: RemoteSwipeDirection = vertical) =
        scene.findRemoteFastScrollNode(direction)

    private fun SkikoComposeUiTest.begin(): Boolean {
        val node = assertNotNull(node())
        val result = scene.runRemoteInput { node.beginFastScroll() }
        waitForIdle()
        return result
    }

    private fun SkikoComposeUiTest.end(
        anchor: RemoteFastScrollExitAnchor = RemoteFastScrollExitAnchor.Center
    ): Boolean {
        val node = assertNotNull(node())
        val result = scene.runRemoteInput { node.endFastScroll(anchor) }
        waitForIdle()
        return result
    }

    private fun SkikoComposeUiTest.focus(requester: FocusRequester) {
        runOnIdle { requester.requestFocus() }
        waitForIdle()
    }

    @Test
    fun nodeIsFoundFromFocusedDescendant() = runSkikoComposeUiTest {
        val requester = FocusRequester()
        val state = RemoteFastScrollState()
        setContent {
            Box(Modifier.remoteFastScroll(state, RemoteFastScrollAxis.Vertical) { it }) {
                Box(Modifier.size(10.dp).focusRequester(requester).focusable())
            }
        }
        focus(requester)

        assertNotNull(node())
        assertNull(node(RemoteSwipeDirection.Left))
    }

    @Test
    fun nearestMatchingAxisWins() = runSkikoComposeUiTest {
        val requester = FocusRequester()
        val outer = RemoteFastScrollState()
        val inner = RemoteFastScrollState()
        val horizontal = RemoteFastScrollState()
        setContent {
            Box(Modifier.remoteFastScroll(outer, RemoteFastScrollAxis.Vertical) { it }) {
                Box(Modifier.remoteFastScroll(inner, RemoteFastScrollAxis.Vertical) { it }) {
                    Box(Modifier.remoteFastScroll(horizontal, RemoteFastScrollAxis.Horizontal) { it }) {
                        Box(Modifier.size(10.dp).focusRequester(requester).focusable())
                    }
                }
            }
        }
        focus(requester)

        assertSame(inner, node()?.state)
        assertSame(horizontal, node(RemoteSwipeDirection.Right)?.state)
    }

    @Test
    fun disabledNodeIsSkipped() = runSkikoComposeUiTest {
        val requester = FocusRequester()
        val outer = RemoteFastScrollState()
        val inner = RemoteFastScrollState()
        setContent {
            Box(Modifier.remoteFastScroll(outer, RemoteFastScrollAxis.Vertical) { it }) {
                Box(Modifier.remoteFastScroll(inner, RemoteFastScrollAxis.Vertical, enabled = false) { it }) {
                    Box(Modifier.size(10.dp).focusRequester(requester).focusable())
                }
            }
        }
        focus(requester)

        assertSame(outer, node()?.state)
    }

    @Test
    fun noNodeWithoutFocus() = runSkikoComposeUiTest {
        val state = RemoteFastScrollState()
        setContent {
            Box(Modifier.remoteFastScroll(state, RemoteFastScrollAxis.Vertical) { it }) {
                Box(Modifier.size(10.dp).focusable())
            }
        }
        waitForIdle()

        assertNull(node())
    }

    @Test
    fun nodeDelegatedByAnotherNodeIsFound() = runSkikoComposeUiTest {
        val requester = FocusRequester()
        val state = RemoteFastScrollState()
        setContent {
            Box(Modifier.then(DelegatingElement(state))) {
                Box(Modifier.size(10.dp).focusRequester(requester).focusable())
            }
        }
        focus(requester)

        assertSame(state, node()?.state)
        assertTrue(begin())
        assertTrue(state.isActive)
        // The container holds focus now, and the node is still found from there.
        assertSame(state, node()?.state)
    }

    @Test
    fun beginMovesFocusToContainer() = runSkikoComposeUiTest {
        val requester = FocusRequester()
        val state = RemoteFastScrollState()
        var itemFocused = false
        var started = 0
        setContent {
            Box(Modifier.testFastScroll(state, onStart = { started++ })) {
                Box(
                    Modifier.size(10.dp)
                        .focusRequester(requester)
                        .onFocusChanged { itemFocused = it.isFocused }
                        .focusable()
                )
            }
        }
        focus(requester)
        assertTrue(itemFocused)

        assertTrue(begin())

        assertFalse(itemFocused)
        assertTrue(state.isActive)
        assertEquals(1, started)
    }

    @Test
    fun containerIsNotFocusableOutsideTheMode() = runSkikoComposeUiTest {
        val requesters = List(3) { FocusRequester() }
        val focused = BooleanArray(3)
        lateinit var focusManager: FocusManager
        setContent {
            focusManager = LocalFocusManager.current
            Column(Modifier.remoteFastScroll(RemoteFastScrollState(), RemoteFastScrollAxis.Vertical) { it }) {
                requesters.forEachIndexed { i, r ->
                    Box(
                        Modifier.size(10.dp)
                            .focusRequester(r)
                            .onFocusChanged { focused[i] = it.isFocused }
                            .focusable()
                    )
                }
            }
        }
        focus(requesters[0])

        for (expected in 1..2) {
            runOnIdle { focusManager.moveFocus(FocusDirection.Down) }
            waitForIdle()
            assertEquals(1, focused.count { it })
            assertTrue(focused[expected])
        }
    }

    @Test
    fun scrollByForwardsDeltaAndPublishesVelocity() = runSkikoComposeUiTest {
        val requester = FocusRequester()
        val state = RemoteFastScrollState()
        val deltas = mutableListOf<Float>()
        setContent {
            Box(
                Modifier.remoteFastScroll(state, RemoteFastScrollAxis.Vertical) {
                    deltas.add(it)
                    it / 2f
                }
            ) {
                Box(Modifier.size(10.dp).focusRequester(requester).focusable())
            }
        }
        focus(requester)
        val node = assertNotNull(node())

        assertEquals(0f, node.fastScrollBy(40f, 900f))
        assertTrue(deltas.isEmpty())

        assertTrue(begin())
        assertEquals(20f, node.fastScrollBy(40f, 900f))
        assertEquals(listOf(40f), deltas)
        assertEquals(900f, state.velocity)
    }

    @Test
    fun endFocusesChildUnderCentre() = runSkikoComposeUiTest {
        val requesters = List(3) { FocusRequester() }
        val focused = BooleanArray(3)
        val state = RemoteFastScrollState()
        var ended = 0
        setContent {
            Column(Modifier.height(90.dp).width(50.dp).testFastScroll(state, onEnd = { ended++ })) {
                requesters.forEachIndexed { i, r ->
                    Box(
                        Modifier.size(50.dp, 30.dp)
                            .focusRequester(r)
                            .onFocusChanged { focused[i] = it.isFocused }
                            .focusable()
                    )
                }
            }
        }
        focus(requesters[0])
        assertTrue(begin())

        assertTrue(end())

        assertEquals(listOf(false, true, false), focused.toList())
        assertFalse(state.isActive)
        assertEquals(1, ended)
    }

    @Test
    fun endAtEndAnchorFocusesLastChild() = runSkikoComposeUiTest {
        val requesters = List(5) { FocusRequester() }
        val focused = BooleanArray(5)
        val state = RemoteFastScrollState()
        setContent {
            Column(Modifier.height(90.dp).width(50.dp).testFastScroll(state)) {
                requesters.forEachIndexed { i, r ->
                    Box(
                        Modifier.size(50.dp, 30.dp)
                            .focusRequester(r)
                            .onFocusChanged { focused[i] = it.isFocused }
                            .focusable()
                    )
                }
            }
        }
        focus(requesters[0])
        assertTrue(begin())

        assertTrue(end(RemoteFastScrollExitAnchor.End))

        // Only the first three are inside the 90 px viewport, the fifth is placed beyond it.
        assertEquals(listOf(false, false, true, false, false), focused.toList())
    }

    @Test
    fun endKeepsColumnInGrid() = runSkikoComposeUiTest {
        val requesters = List(6) { FocusRequester() }
        val focused = BooleanArray(6)
        val state = RemoteFastScrollState()
        setContent {
            Column(Modifier.height(90.dp).width(100.dp).testFastScroll(state)) {
                repeat(3) { row ->
                    Row {
                        repeat(2) { col ->
                            val i = row * 2 + col
                            Box(
                                Modifier.size(50.dp, 30.dp)
                                    .focusRequester(requesters[i])
                                    .onFocusChanged { focused[i] = it.isFocused }
                                    .focusable()
                            )
                        }
                    }
                }
            }
        }
        focus(requesters[1])
        assertTrue(begin())

        assertTrue(end())

        assertEquals(listOf(false, false, false, true, false, false), focused.toList())
    }

    @Test
    fun endSkipsNonFocusableChildUnderCentre() = runSkikoComposeUiTest {
        val a = FocusRequester()
        val c = FocusRequester()
        var aFocused = false
        var cFocused = false
        val state = RemoteFastScrollState()
        setContent {
            Column(Modifier.height(90.dp).width(50.dp).testFastScroll(state)) {
                Box(
                    Modifier.size(50.dp, 20.dp)
                        .focusRequester(a)
                        .onFocusChanged { aFocused = it.isFocused }
                        .focusable()
                )
                Box(Modifier.size(50.dp, 30.dp))
                Box(
                    Modifier.size(50.dp, 40.dp)
                        .focusRequester(c)
                        .onFocusChanged { cFocused = it.isFocused }
                        .focusable()
                )
            }
        }
        focus(a)
        assertTrue(begin())

        // The centre, at 45 px, lies in the plain box, and the last child is the nearest.
        assertTrue(end())

        assertFalse(aFocused)
        assertTrue(cFocused)
    }

    @Test
    fun endReturnsFalseWithoutChildren() = runSkikoComposeUiTest {
        val requester = FocusRequester()
        val state = RemoteFastScrollState()
        var showItem by mutableStateOf(true)
        var ended = 0
        setContent {
            Box(Modifier.size(50.dp).testFastScroll(state, onEnd = { ended++ })) {
                if (showItem) Box(Modifier.size(10.dp).focusRequester(requester).focusable())
            }
        }
        focus(requester)
        assertTrue(begin())
        runOnIdle { showItem = false }
        waitForIdle()

        assertFalse(end())

        assertTrue(state.isActive)
        assertEquals(0, ended)
    }

    @Test
    fun focusStolenDuringModeAborts() = runSkikoComposeUiTest {
        val inside = FocusRequester()
        val outside = FocusRequester()
        val state = RemoteFastScrollState()
        var ended = 0
        setContent {
            Box {
                Box(Modifier.testFastScroll(state, onEnd = { ended++ })) {
                    Box(Modifier.size(10.dp).focusRequester(inside).focusable())
                }
                Box(Modifier.size(10.dp).focusRequester(outside).focusable())
            }
        }
        focus(inside)
        assertTrue(begin())

        focus(outside)

        assertFalse(state.isActive)
        assertEquals(1, ended)
    }

    @Test
    fun replacingTheStateDuringModeResetsTheOldOne() = runSkikoComposeUiTest {
        val requester = FocusRequester()
        val first = RemoteFastScrollState()
        val second = RemoteFastScrollState()
        var state by mutableStateOf(first)
        setContent {
            Box(Modifier.remoteFastScroll(state, RemoteFastScrollAxis.Vertical) { it }) {
                Box(Modifier.size(10.dp).focusRequester(requester).focusable())
            }
        }
        focus(requester)
        assertTrue(begin())
        val node = assertNotNull(node())
        runOnIdle { node.fastScrollBy(10f, 500f) }
        assertTrue(first.isActive)

        runOnIdle { state = second }
        waitForIdle()

        assertFalse(first.isActive)
        assertEquals(0f, first.velocity)
        assertFalse(second.isActive)
        assertFalse(node.isFastScrolling)
    }

    @Test
    fun detachDuringModeResetsState() = runSkikoComposeUiTest {
        val requester = FocusRequester()
        val state = RemoteFastScrollState()
        var show by mutableStateOf(true)
        var ended = 0
        setContent {
            if (show) {
                Box(Modifier.testFastScroll(state, onEnd = { ended++ })) {
                    Box(Modifier.size(10.dp).focusRequester(requester).focusable())
                }
            }
        }
        focus(requester)
        assertTrue(begin())
        val node = assertNotNull(node())
        runOnIdle { node.fastScrollBy(10f, 500f) }
        assertEquals(500f, state.velocity)

        runOnIdle { show = false }
        waitForIdle()

        assertFalse(state.isActive)
        assertEquals(0f, state.velocity)
        assertEquals(1, ended)
    }

    private fun Modifier.testFastScroll(
        state: RemoteFastScrollState,
        onStart: () -> Unit = {},
        onEnd: () -> Unit = {},
    ): Modifier = this then DelegatingElement(state, onStart, onEnd)

    private class DelegatingElement(
        val state: RemoteFastScrollState,
        val onStart: () -> Unit = {},
        val onEnd: () -> Unit = {},
    ) : ModifierNodeElement<DelegatingTestNode>() {
        override fun create() = DelegatingTestNode(state, onStart, onEnd)

        override fun update(node: DelegatingTestNode) {}

        override fun equals(other: Any?) = other is DelegatingElement && other.state === state

        override fun hashCode() = state.hashCode()
    }

    private class DelegatingTestNode(
        state: RemoteFastScrollState,
        onStart: () -> Unit,
        onEnd: () -> Unit,
    ) : DelegatingNode() {
        val fastScroll =
            delegate(
                RemoteFastScrollModifierNode(
                    state = state,
                    axis = RemoteFastScrollAxis.Vertical,
                    onStart = onStart,
                    onEnd = onEnd,
                    onScrollBy = { it },
                )
            )
    }
}
