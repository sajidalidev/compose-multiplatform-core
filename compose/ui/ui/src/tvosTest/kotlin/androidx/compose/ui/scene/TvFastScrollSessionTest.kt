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
package androidx.compose.ui.scene

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.remote.RemoteFastScrollExitAnchor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvFastScrollSessionTest {
    @Test
    fun directionalKeysAreTheFourArrows() {
        assertTrue(isDirectionalKey(Key.DirectionUp))
        assertTrue(isDirectionalKey(Key.DirectionRight))
        assertFalse(isDirectionalKey(Key.DirectionCenter))
        assertFalse(isDirectionalKey(Key.Back))
    }

    @Test
    fun downAndRightAreForward() {
        assertTrue(isForwardKey(Key.DirectionDown))
        assertTrue(isForwardKey(Key.DirectionRight))
        assertFalse(isForwardKey(Key.DirectionUp))
        assertFalse(isForwardKey(Key.DirectionLeft))
    }

    @Test
    fun arrowsAndSelectAreSwallowedButMenuIsNot() {
        assertTrue(isSwallowedInFastScroll(Key.DirectionDown))
        assertTrue(isSwallowedInFastScroll(Key.DirectionCenter))
        assertFalse(isSwallowedInFastScroll(Key.Back))
        assertFalse(isSwallowedInFastScroll(Key.MediaPlayPause))
    }

    @Test
    fun forwardPositionFlipsTheOracleYForVertical() {
        assertEquals(-0.4f, fastScrollForwardPosition(Offset(0.1f, 0.4f), vertical = true))
        assertEquals(0.1f, fastScrollForwardPosition(Offset(0.1f, 0.4f), vertical = false))
    }

    @Test
    fun exitReasonDistinguishesEndStopAndSpeed() {
        assertEquals("end", fastScrollExitReason(hitEnd = true, velocity = 0f))
        assertEquals("stop", fastScrollExitReason(hitEnd = false, velocity = 0f))
        assertEquals("speed", fastScrollExitReason(hitEnd = false, velocity = 3f))
    }

    @Test
    fun exitAnchorIsCentreUnlessTheContentEnded() {
        assertEquals(RemoteFastScrollExitAnchor.Center, fastScrollExitAnchor(false, 120f))
        assertEquals(RemoteFastScrollExitAnchor.End, fastScrollExitAnchor(true, 120f))
        assertEquals(RemoteFastScrollExitAnchor.Start, fastScrollExitAnchor(true, -120f))
    }

    @Test
    fun retryBudgetHandsOutItsLimitThenRefuses() {
        val budget = TvRetryBudget(2)
        assertTrue(budget.tryConsume())
        assertTrue(budget.tryConsume())
        assertFalse(budget.tryConsume())
        budget.reset()
        assertTrue(budget.tryConsume())
    }

    @Test
    fun directionalHoldRepeatsOnTheNativeCadence() {
        val hold = TvHoldRepeatTracker(directional = true, nativeCadence = true)
        val delays = mutableListOf<Long>()
        repeat(5) {
            delays += hold.nextDelayMs()
            hold.onRepeatDispatched(consumed = true)
        }
        assertEquals(listOf(500L, 200L, 150L, 100L, 100L), delays)
    }

    @Test
    fun directionalHoldKeepsTheOldCadenceWhenTheFlagIsOff() {
        val hold = TvHoldRepeatTracker(directional = true, nativeCadence = false)
        val delays = mutableListOf<Long>()
        repeat(4) {
            delays += hold.nextDelayMs()
            hold.onRepeatDispatched(consumed = true)
        }
        assertEquals(listOf(500L, 50L, 50L, 50L), delays)
    }

    @Test
    fun otherKeysAlwaysKeepTheOldCadence() {
        val hold = TvHoldRepeatTracker(directional = false, nativeCadence = true)
        assertEquals(500L, hold.nextDelayMs())
        hold.onRepeatDispatched(consumed = true)
        assertEquals(50L, hold.nextDelayMs())
    }

    @Test
    fun fastEntryReplacesTheEleventhMove() {
        val hold = TvHoldRepeatTracker(directional = true)
        hold.onInitialPress(consumed = true)
        repeat(8) {
            assertFalse(hold.shouldTryFastEntry())
            hold.onRepeatDispatched(consumed = true)
        }
        assertEquals(9, hold.moves)
        assertFalse(hold.shouldTryFastEntry())
        hold.onRepeatDispatched(consumed = true)
        assertEquals(10, hold.moves)
        assertTrue(hold.shouldTryFastEntry())
    }

    @Test
    fun unconsumedPressesAndRepeatsDoNotCountAsMoves() {
        val hold = TvHoldRepeatTracker(directional = true)
        hold.onInitialPress(consumed = false)
        repeat(12) { hold.onRepeatDispatched(consumed = false) }
        assertEquals(0, hold.moves)
        assertFalse(hold.shouldTryFastEntry())
    }

    @Test
    fun nonDirectionalHoldNeverTriesFastEntry() {
        val hold = TvHoldRepeatTracker(directional = false)
        repeat(30) { hold.onRepeatDispatched(consumed = true) }
        assertFalse(hold.shouldTryFastEntry())
    }
}
