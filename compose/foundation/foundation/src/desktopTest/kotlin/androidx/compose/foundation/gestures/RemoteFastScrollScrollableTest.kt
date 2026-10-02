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

package androidx.compose.foundation.gestures

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.remote.RemoteFastScrollModifierNode
import androidx.compose.ui.input.remote.RemoteFastScrollState
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.TraversableNode
import androidx.compose.ui.node.findNearestAncestor
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.dp
import java.lang.reflect.Method
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/**
 * Drives the ui node of the fast scroll mode the way the tvOS mediator does. Its members are
 * internal to the ui module, so they are called by reflection.
 */
@OptIn(
    ExperimentalTestApi::class,
    ExperimentalFoundationApi::class,
    ExperimentalComposeUiApi::class,
    InternalComposeUiApi::class,
)
class RemoteFastScrollScrollableTest {
    private val itemHeight = 40
    private val viewport = 300
    private val itemCount = 300

    private class FakeScrollableState : ScrollableState {
        val deltas = mutableListOf<Float>()
        var consumeFraction = 1f
        private val inner = ScrollableState { delta ->
            deltas.add(delta)
            delta * consumeFraction
        }

        override fun dispatchRawDelta(delta: Float) = inner.dispatchRawDelta(delta)

        override suspend fun scroll(
            scrollPriority: MutatePriority,
            block: suspend ScrollScope.() -> Unit,
        ) = inner.scroll(scrollPriority, block)

        override val isScrollInProgress get() = inner.isScrollInProgress
    }

    private class Driver(val test: SkikoComposeUiTest, val node: RemoteFastScrollModifierNode) {
        private fun Class<*>.method(prefix: String): Method =
            methods.first { it.name.startsWith(prefix) }

        private fun call(name: String, vararg args: Any?): Any? =
            node.javaClass.method(name).invoke(node, *args)

        private val runRemoteInput: Method =
            Class.forName("androidx.compose.ui.scene.BaseComposeScene_skikoKt").method("runRemoteInput")

        @Suppress("UNCHECKED_CAST")
        fun <T> input(block: () -> T): T =
            runRemoteInput.invoke(null, test.scene, { block() } as () -> Any?) as T

        fun begin(): Boolean = input { call("beginFastScroll") as Boolean }.also { test.waitForIdle() }

        fun scrollBy(delta: Float, velocity: Float = 1000f): Float =
            input { call("fastScrollBy", delta, velocity) as Float }

        fun abort() {
            call("abortFastScroll")
        }

        fun accepts(direction: String): Boolean {
            val c = Class.forName("androidx.compose.ui.input.remote.RemoteSwipeDirection")
            val value = c.enumConstants.first { (it as Enum<*>).name == direction }
            return call("accepts", value) as Boolean
        }

        fun end(anchor: String = "Center"): Boolean = input { endNoInput(anchor) }

        fun endNoInput(anchor: String = "Center"): Boolean {
            val anchorClass = Class.forName("androidx.compose.ui.input.remote.RemoteFastScrollExitAnchor")
            val value = anchorClass.enumConstants.first { (it as Enum<*>).name == anchor }
            return call("endFastScroll", value) as Boolean
        }
    }

    private class ProbeElement(val onFound: (RemoteFastScrollModifierNode) -> Unit) :
        ModifierNodeElement<ProbeNode>() {
        override fun create() = ProbeNode(onFound)

        override fun update(node: ProbeNode) {}

        override fun equals(other: Any?) = other === this

        override fun hashCode() = System.identityHashCode(this)
    }

    private class ProbeNode(val onFound: (RemoteFastScrollModifierNode) -> Unit) :
        Modifier.Node(), TraversableNode {
        override val traverseKey: Any = "RemoteFastScrollProbe"

        override fun onAttach() {
            val node =
                findNearestAncestor(RemoteFastScrollTraverseKey) as? RemoteFastScrollScrollableNode
            if (node != null) onFound(node.fastScrollNode)
        }
    }

    private fun SkikoComposeUiTest.driver(found: List<RemoteFastScrollModifierNode>) =
        Driver(this, assertNotNull(found.lastOrNull()))

    private val pivotSpec =
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(
                offset: Float,
                size: Float,
                containerSize: Float,
            ): Float {
                val initialTarget = 0.3f * containerSize
                val target =
                    if (size <= containerSize && containerSize - initialTarget < size) {
                        containerSize - size
                    } else {
                        initialTarget
                    }
                return offset - target
            }
        }

    private class Harness(
        val listState: LazyListState,
        val fastState: RemoteFastScrollState,
        val focused: MutableSet<Int>,
        val requesters: Map<Int, FocusRequester>,
        val found: MutableList<RemoteFastScrollModifierNode>,
        val composed: MutableSet<Int>,
    )

    private fun SkikoComposeUiTest.setList(
        enabled: Boolean = true,
        content: (Harness) -> Unit,
    ) {
        val focused = mutableSetOf<Int>()
        val requesters = HashMap<Int, FocusRequester>()
        val found = mutableListOf<RemoteFastScrollModifierNode>()
        val composed = mutableSetOf<Int>()
        val fastState = RemoteFastScrollState()
        lateinit var listState: LazyListState
        setContent {
            listState = rememberLazyListState()
            CompositionLocalProvider(LocalBringIntoViewSpec provides pivotSpec) {
                LazyColumn(
                    state = listState,
                    modifier =
                        Modifier.height(viewport.dp)
                            .remoteFastScroll(listState, Orientation.Vertical, fastState, enabled)
                            .then(ProbeElement { found.add(it) }),
                ) {
                    items(itemCount, key = { it }) { index ->
                        val requester = requesters.getOrPut(index) { FocusRequester() }
                        DisposableEffect(index) {
                            composed.add(index)
                            onDispose { composed.remove(index) }
                        }
                        Box(
                            Modifier.fillMaxWidth()
                                .height(itemHeight.dp)
                                .focusRequester(requester)
                                .onFocusChanged {
                                    if (it.isFocused) focused.add(index) else focused.remove(index)
                                }
                                .focusable()
                        )
                    }
                }
            }
        }
        waitForIdle()
        content(Harness(listState, fastState, focused, requesters, found, composed))
    }

    private fun SkikoComposeUiTest.requestFocus(h: Harness, index: Int) {
        runOnIdle { h.requesters.getValue(index).requestFocus() }
        waitForIdle()
    }

    /** Long fast scroll in steps, the last one followed by the exit in the same input call. */
    private fun Driver.longScrollAndEnd(totalPx: Int, stepPx: Int) {
        var left = totalPx
        while (left > stepPx) {
            scrollBy(stepPx.toFloat())
            left -= stepPx
        }
        input {
            scrollBy(left.toFloat())
            assertTrue(endNoInput())
        }
    }

    @Test
    fun scrollByCallsDispatchRawDelta() = runSkikoComposeUiTest {
        val state = FakeScrollableState()
        val found = mutableListOf<RemoteFastScrollModifierNode>()
        val fastState = RemoteFastScrollState()
        setContent {
            Box(
                Modifier.remoteFastScroll(state, Orientation.Vertical, fastState)
                    .then(ProbeElement { found.add(it) })
                    .focusable()
            )
        }
        waitForIdle()
        val driver = driver(found)
        assertTrue(driver.begin())

        assertEquals(30f, driver.scrollBy(30f))
        assertEquals(listOf(30f), state.deltas)
    }

    @Test
    fun reverseDirectionNegatesDeltaAndConsumed() = runSkikoComposeUiTest {
        val state = FakeScrollableState().apply { consumeFraction = 0.5f }
        val found = mutableListOf<RemoteFastScrollModifierNode>()
        val fastState = RemoteFastScrollState()
        setContent {
            Box(
                Modifier.remoteFastScroll(state, Orientation.Vertical, fastState, reverseDirection = true)
                    .then(ProbeElement { found.add(it) })
                    .focusable()
            )
        }
        waitForIdle()
        val driver = driver(found)
        assertTrue(driver.begin())

        // The state sees the negated delta and consumes half of it, the caller sees the sign restored.
        assertEquals(20f, driver.scrollBy(40f))
        assertEquals(listOf(-40f), state.deltas)
    }

    @Test
    fun sessionHoldsScrollMutexAndIsReleasedOnEnd() = runSkikoComposeUiTest {
        val state = FakeScrollableState()
        val found = mutableListOf<RemoteFastScrollModifierNode>()
        val fastState = RemoteFastScrollState()
        setContent {
            Box(
                Modifier.height(50.dp)
                    .remoteFastScroll(state, Orientation.Vertical, fastState)
                    .then(ProbeElement { found.add(it) })
                    .focusable()
            )
        }
        waitForIdle()
        val driver = driver(found)
        assertFalse(state.isScrollInProgress)
        assertTrue(driver.begin())
        assertTrue(state.isScrollInProgress)

        // A lower priority scroll is cancelled by the session.
        var defaultScrollRan = false
        val defaultScroll =
            CoroutineScopeHolder.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                state.scroll(MutatePriority.Default) { defaultScrollRan = true }
            }
        assertTrue(defaultScroll.isCancelled)
        assertFalse(defaultScrollRan)

        // Ending the mode releases the mutex, so a default scroll runs.
        driver.input { driver.abort() }
        waitForIdle()
        assertFalse(state.isScrollInProgress)
        var ranAfter = false
        val after =
            CoroutineScopeHolder.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                state.scroll(MutatePriority.Default) { ranAfter = true }
            }
        assertTrue(ranAfter)
        assertTrue(after.isCompleted)
    }

    @Test
    fun competingUserInputScrollAbortsTheMode() = runSkikoComposeUiTest {
        val state = FakeScrollableState()
        val found = mutableListOf<RemoteFastScrollModifierNode>()
        val fastState = RemoteFastScrollState()
        setContent {
            Box(
                Modifier.height(50.dp)
                    .remoteFastScroll(state, Orientation.Vertical, fastState)
                    .then(ProbeElement { found.add(it) })
                    .focusable()
            )
        }
        waitForIdle()
        val driver = driver(found)
        assertTrue(driver.begin())
        assertTrue(fastState.isActive)

        var competingRan = false
        CoroutineScopeHolder.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            state.scroll(MutatePriority.UserInput) { competingRan = true }
        }
        waitForIdle()

        assertTrue(competingRan)
        assertFalse(fastState.isActive)
        assertFalse(state.isScrollInProgress)
        var ranAfter = false
        CoroutineScopeHolder.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            state.scroll(MutatePriority.Default) { ranAfter = true }
        }
        assertTrue(ranAfter)
    }

    @Test
    fun disabledModifierIsInert() = runSkikoComposeUiTest {
        setList(enabled = false) { h ->
            requestFocus(h, 0)
            val driver = driver(h.found)
            // The platform only starts the mode on a node that accepts the swipe.
            assertFalse(driver.accepts("Down"))
            assertFalse(h.fastState.isActive)
            assertEquals(setOf(0), h.focused)
            assertFalse(h.listState.isScrollInProgress)
        }
    }

    @Test
    fun withoutTheModifierNothingHoldsTheMutex() = runSkikoComposeUiTest {
        lateinit var listState: LazyListState
        setContent {
            listState = rememberLazyListState()
            LazyColumn(state = listState, modifier = Modifier.height(viewport.dp)) {
                items(10) { Box(Modifier.height(itemHeight.dp).focusable()) }
            }
        }
        waitForIdle()
        assertFalse(listState.isScrollInProgress)
    }

    @Test
    fun lazyColumnPivotItemIsFocusedAndPlacedAfterLongScroll() = runSkikoComposeUiTest {
        setList { h ->
            requestFocus(h, 0)
            assertEquals(setOf(0), h.focused)
            val driver = driver(h.found)
            assertTrue(driver.begin())
            assertTrue(h.fastState.isActive)
            assertTrue(h.focused.isEmpty())

            driver.longScrollAndEnd(totalPx = 8000, stepPx = 100)
            // No idle wait between the last scroll and the exit: the same input call did both.
            waitForIdle()
            val chosen = h.focused.single()
            assertTrue(chosen in h.composed)
            assertFalse(h.fastState.isActive)

            // The item with the leading edge nearest to the pivot at 30 percent of the viewport.
            val pivot = 0.3f * viewport
            val nearest = (8000 + pivot) / itemHeight
            assertTrue(abs(chosen - nearest) <= 1f, "chosen=$chosen pivot item=$nearest")
        }
    }

    @Test
    fun exitSettlesTheFocusedItemAtTheBringIntoViewRestPosition() = runSkikoComposeUiTest {
        setList { h ->
            requestFocus(h, 0)
            val driver = driver(h.found)
            assertTrue(driver.begin())
            fun position() =
                h.listState.firstVisibleItemScrollOffset + h.listState.firstVisibleItemIndex * itemHeight
            var atExit = -1
            driver.input {
                var left = 8003
                while (left > 100) {
                    driver.scrollBy(100f)
                    left -= 100
                }
                driver.scrollBy(left.toFloat())
                assertTrue(driver.endNoInput())
                atExit = position()
            }
            waitForIdle()
            mainClock.advanceTimeBy(2000)
            waitForIdle()

            val chosen = h.focused.single()
            val info =
                assertNotNull(h.listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == chosen })
            val residual = abs(position() - atExit)
            println("fast scroll exit residual scroll: $residual px (item pitch $itemHeight px)")
            assertTrue(residual < itemHeight, "residual=$residual")
            assertTrue(
                abs(pivotSpec.calculateScrollDistance(info.offset.toFloat(), info.size.toFloat(), viewport.toFloat())) <= 1f
            )
            assertFalse(h.listState.isScrollInProgress)
            assertFalse(h.fastState.isActive)
        }
    }

    @Test
    fun previouslyFocusedItemIsReleased() = runSkikoComposeUiTest {
        setList { h ->
            requestFocus(h, 0)
            val driver = driver(h.found)
            assertTrue(0 in h.composed)
            assertTrue(driver.begin())
            // Not focused any more, and the container does not keep it pinned.
            assertFalse(0 in h.focused)

            driver.longScrollAndEnd(totalPx = 8000, stepPx = 100)
            waitForIdle()

            assertFalse(0 in h.focused)
            assertEquals(1, h.focused.size)
            // Item 0 is far outside the viewport and the prefetch window, so it is disposed.
            assertFalse(0 in h.composed)
            assertTrue(h.listState.firstVisibleItemIndex > 100)
        }
    }

    private object CoroutineScopeHolder {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
    }
}
