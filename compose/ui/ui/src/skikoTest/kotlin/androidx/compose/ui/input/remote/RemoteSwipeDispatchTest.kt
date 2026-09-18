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
import androidx.compose.foundation.layout.size
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.scene.dispatchRemoteSwipe
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalTestApi::class, InternalComposeUiApi::class)
class RemoteSwipeDispatchTest {
    private fun swipe() =
        RemoteSwipe(
            direction = RemoteSwipeDirection.Right,
            velocity = 0f,
            isTraverse = false,
            momentumSteps = 0,
            origin = RemoteSwipeOrigin.Center,
        )

    @Test
    fun nearestAncestorReceivesTheSwipe() = runSkikoComposeUiTest {
        val focusRequester = FocusRequester()
        var outer = 0
        var inner = 0
        setContent {
            Box(Modifier.remoteSwipe { outer++; true }) {
                Box(Modifier.remoteSwipe { inner++; true }) {
                    Box(Modifier.size(10.dp).focusRequester(focusRequester).focusable())
                }
            }
        }
        runOnIdle { focusRequester.requestFocus() }
        waitForIdle()

        assertNotNull(scene.dispatchRemoteSwipe(swipe()))
        assertEquals(0, outer)
        assertEquals(1, inner)
    }

    @Test
    fun modifierBelowFocusableOnTheSameElementReceivesTheSwipe() = runSkikoComposeUiTest {
        val focusRequester = FocusRequester()
        var received = 0
        setContent {
            Box(
                Modifier.size(10.dp)
                    .focusRequester(focusRequester)
                    .focusable()
                    .remoteSwipe { received++; true }
            )
        }
        runOnIdle { focusRequester.requestFocus() }
        waitForIdle()

        assertNotNull(scene.dispatchRemoteSwipe(swipe()))
        assertEquals(1, received)
    }

    @Test
    fun disabledModifierSwallowsTheSwipeWithoutCallingBack() = runSkikoComposeUiTest {
        val focusRequester = FocusRequester()
        var called = false
        setContent {
            Box(Modifier.remoteSwipe(enabled = false) { called = true; true }) {
                Box(Modifier.size(10.dp).focusRequester(focusRequester).focusable())
            }
        }
        runOnIdle { focusRequester.requestFocus() }
        waitForIdle()

        assertNotNull(scene.dispatchRemoteSwipe(swipe()))
        assertFalse(called)
    }

    @Test
    fun callbackReturningFalseLeavesTheSwipeToTheKeys() = runSkikoComposeUiTest {
        val focusRequester = FocusRequester()
        var received = 0
        setContent {
            Box(Modifier.remoteSwipe { received++; false }) {
                Box(Modifier.size(10.dp).focusRequester(focusRequester).focusable())
            }
        }
        runOnIdle { focusRequester.requestFocus() }
        waitForIdle()

        assertNull(scene.dispatchRemoteSwipe(swipe()))
        assertEquals(1, received)
    }

    @Test
    fun unhandledSwipeBubblesToTheNextModifierOut() = runSkikoComposeUiTest {
        val focusRequester = FocusRequester()
        var outer = 0
        var inner = 0
        setContent {
            Box(Modifier.remoteSwipe { outer++; true }) {
                Box(Modifier.remoteSwipe { inner++; false }) {
                    Box(Modifier.size(10.dp).focusRequester(focusRequester).focusable())
                }
            }
        }
        runOnIdle { focusRequester.requestFocus() }
        waitForIdle()

        assertNotNull(scene.dispatchRemoteSwipe(swipe()))
        assertEquals(1, inner)
        assertEquals(1, outer)
    }

    @Test
    fun swipeNoModifierHandlesLeavesItToTheKeys() = runSkikoComposeUiTest {
        val focusRequester = FocusRequester()
        var outer = 0
        var inner = 0
        setContent {
            Box(Modifier.remoteSwipe { outer++; false }) {
                Box(Modifier.remoteSwipe { inner++; false }) {
                    Box(Modifier.size(10.dp).focusRequester(focusRequester).focusable())
                }
            }
        }
        runOnIdle { focusRequester.requestFocus() }
        waitForIdle()

        assertNull(scene.dispatchRemoteSwipe(swipe()))
        assertEquals(1, inner)
        assertEquals(1, outer)
    }

    @Test
    fun disabledModifierKeepsTheSwipeFromTheOnesFurtherOut() = runSkikoComposeUiTest {
        val focusRequester = FocusRequester()
        var outer = 0
        setContent {
            Box(Modifier.remoteSwipe { outer++; true }) {
                Box(Modifier.remoteSwipe(enabled = false) { true }) {
                    Box(Modifier.size(10.dp).focusRequester(focusRequester).focusable())
                }
            }
        }
        runOnIdle { focusRequester.requestFocus() }
        waitForIdle()

        assertNotNull(scene.dispatchRemoteSwipe(swipe()))
        assertEquals(0, outer)
    }

    @Test
    fun modifiersAreCalledFromTheInsideOut() = runSkikoComposeUiTest {
        val focusRequester = FocusRequester()
        val order = mutableListOf<String>()
        setContent {
            Box(Modifier.remoteSwipe { order.add("outer"); false }) {
                Box(Modifier.remoteSwipe { order.add("inner"); false }) {
                    Box(Modifier.size(10.dp).focusRequester(focusRequester).focusable())
                }
            }
        }
        runOnIdle { focusRequester.requestFocus() }
        waitForIdle()

        assertNull(scene.dispatchRemoteSwipe(swipe()))
        assertEquals(listOf("inner", "outer"), order)
    }

    @Test
    fun noModifierLeavesTheSwipeToTheKeys() = runSkikoComposeUiTest {
        val focusRequester = FocusRequester()
        setContent { Box(Modifier.size(10.dp).focusRequester(focusRequester).focusable()) }
        runOnIdle { focusRequester.requestFocus() }
        waitForIdle()

        assertNull(scene.dispatchRemoteSwipe(swipe()))
    }
}
