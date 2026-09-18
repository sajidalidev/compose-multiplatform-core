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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RemoteSwipeTest {
    private fun swipe(
        direction: RemoteSwipeDirection = RemoteSwipeDirection.Right,
        velocity: Float = 12f,
        isTraverse: Boolean = true,
        momentumSteps: Int = 3,
        origin: RemoteSwipeOrigin = RemoteSwipeOrigin.Ring,
    ) = RemoteSwipe(direction, velocity, isTraverse, momentumSteps, origin)

    @Test
    fun equalSwipesAreEqual() {
        assertEquals(swipe(), swipe())
        assertEquals(swipe().hashCode(), swipe().hashCode())
    }

    @Test
    fun swipesDifferingInOneValueAreNotEqual() {
        assertNotEquals(swipe(), swipe(direction = RemoteSwipeDirection.Left))
        assertNotEquals(swipe(), swipe(velocity = 13f))
        assertNotEquals(swipe(), swipe(isTraverse = false))
        assertNotEquals(swipe(), swipe(momentumSteps = 2))
        assertNotEquals(swipe(), swipe(origin = RemoteSwipeOrigin.Center))
    }

    @Test
    fun toStringNamesEveryValue() {
        val text = swipe().toString()
        assertTrue(text.contains("direction=Right"), text)
        assertTrue(text.contains("velocity=12.0"), text)
        assertTrue(text.contains("isTraverse=true"), text)
        assertTrue(text.contains("momentumSteps=3"), text)
        assertTrue(text.contains("origin=Ring"), text)
    }
}
