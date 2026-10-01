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

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvSwipeMomentumTest {
    private val down = Key.DirectionDown

    private fun burst(rows: Int, startedAt: Double = 10.0) = TvMomentumBurst(down, startedAt, rows)

    @Test
    fun shortTravelMovesOneRow() {
        assertEquals(1, momentumRowsFor(0.9f, 0.3f))
        assertEquals(1, momentumRowsFor(0.9f, 0.59f))
    }

    @Test
    fun rowsGrowWithTravelAndCap() {
        assertEquals(3, momentumRowsFor(0.8f, 0.6f))
        assertEquals(4, momentumRowsFor(0.8f, 0.9f))
        assertEquals(5, momentumRowsFor(0.8f, 1.2f))
        assertEquals(7, momentumRowsFor(0.8f, 2.5f))
    }

    @Test
    fun startOffsetGatesMomentumAtItsThreshold() {
        assertEquals(1, momentumRowsFor(0.59f, 1.2f))
        assertEquals(5, momentumRowsFor(0.6f, 1.2f))
        assertEquals(1, momentumRowsFor(0.0f, 1.2f))
        assertEquals(1, momentumRowsFor(-0.5f, 1.2f))
        assertEquals(1, momentumRowsFor(0.6f, 0.59f))
        assertEquals(3, momentumRowsFor(0.6f, 0.6f))
    }

    @Test
    fun unknownStartOffsetMovesOneRow() {
        assertEquals(1, momentumRowsFor(null, 2.5f))
    }

    @Test
    fun startOffsetIsMeasuredBehindTheCentreOnAllFourSides() {
        // The oracle's y is positive toward the top, so a Down swipe starts behind the centre at y > 0.
        assertEquals(0.8f, swipeStartOffset(Key.DirectionDown, 0.8f))
        assertEquals(0.8f, swipeStartOffset(Key.DirectionUp, -0.8f))
        assertEquals(0.8f, swipeStartOffset(Key.DirectionRight, -0.8f))
        assertEquals(0.8f, swipeStartOffset(Key.DirectionLeft, 0.8f))
        assertEquals(-0.8f, swipeStartOffset(Key.DirectionDown, -0.8f))
        assertEquals(-0.8f, swipeStartOffset(Key.DirectionUp, 0.8f))
        assertEquals(-0.8f, swipeStartOffset(Key.DirectionRight, 0.8f))
        assertEquals(-0.8f, swipeStartOffset(Key.DirectionLeft, -0.8f))
    }

    @Test
    fun fourQuadrantsOfRows() {
        // Far start and long travel earn momentum on each side; the near side of the pad never does.
        for ((key, origin) in listOf(
            Key.DirectionDown to 0.8f,
            Key.DirectionUp to -0.8f,
            Key.DirectionRight to -0.8f,
            Key.DirectionLeft to 0.8f,
        )) {
            assertEquals(4, momentumRowsFor(swipeStartOffset(key, origin), 0.9f))
            assertEquals(1, momentumRowsFor(swipeStartOffset(key, -origin), 0.9f))
        }
    }

    @Test
    fun liftArmNeedsDistanceDominanceAndTime() {
        val max = 0.25
        assertFalse(isLiftArmSwipe(0f, -0.19f, 0.1, max, 2f))
        assertTrue(isLiftArmSwipe(0f, -0.21f, 0.1, max, 2f))
        assertTrue(isLiftArmSwipe(0.25f, 0f, 0.25, max, 2f))
        assertFalse(isLiftArmSwipe(0f, 0.3f, 0.26, max, 2f))
        assertFalse(isLiftArmSwipe(0.3f, 0.2f, 0.1, max, 2f))
        assertTrue(isLiftArmSwipe(0.3f, 0.15f, 0.1, max, 2f))
    }

    @Test
    fun chainBoostIsOffByDefault() {
        assertEquals(1f, CHAIN_BOOST)
        assertTrue(SWIPE_LIFT_DISTANCE < 0.30f)
    }

    @Test
    fun burstStartsWithOwnMoveEmitted() {
        val b = burst(5)
        assertEquals(0, b.stepsDue(b.startedAt))
        assertEquals(1, b.emitted)
    }

    @Test
    fun burstOwesAllRowsEventually() {
        val b = burst(5)
        val due = b.stepsDue(b.startedAt + 1.0)
        repeat(due) { b.onStepEmitted() }
        assertEquals(5, b.emitted)
        assertTrue(b.isFinished)
    }

    @Test
    fun burstFrontLoaded() {
        val b = burst(5)
        assertEquals(2, b.stepsDue(b.startedAt + 0.06))
        var now = b.startedAt
        var lastStepAt = now
        while (now < b.startedAt + 1.0) {
            now += 0.001
            val due = b.stepsDue(now)
            if (due > 0) {
                repeat(due) { b.onStepEmitted() }
                lastStepAt = now
            }
        }
        assertEquals(5, b.emitted)
        val last = lastStepAt - b.startedAt
        assertTrue(last in 0.12..0.2, "last step at $last")
    }

    @Test
    fun lateTickCatchesUp() {
        val b = burst(5)
        assertEquals(4, b.stepsDue(b.startedAt + 0.5))
    }

    @Test
    fun raiseRowsNeverLowers() {
        val b = burst(5)
        b.raiseRows(3)
        assertEquals(5, b.rows)
        b.raiseRows(7)
        assertEquals(7, b.rows)
    }

    @Test
    fun raiseRowsMidBurstOwesMore() {
        val b = burst(3)
        repeat(b.stepsDue(b.startedAt + 1.0)) { b.onStepEmitted() }
        assertTrue(b.isFinished)
        b.raiseRows(6)
        assertFalse(b.isFinished)
        assertEquals(3, b.stepsDue(b.startedAt + 1.0))
    }

    @Test
    fun stopFreezesBurst() {
        val b = burst(6)
        b.onStepEmitted()
        b.stop()
        assertEquals(2, b.rows)
        assertTrue(b.isFinished)
        assertEquals(0, b.stepsDue(b.startedAt + 1.0))
    }

    @Test
    fun chainContinuesWithinGap() {
        val t = TvSwipeChainTracker()
        assertEquals(1, t.onSwipeDispatched(down, 1.0))
        t.onLift(1.5)
        assertEquals(2, t.onSwipeDispatched(down, 2.0))
        t.onLift(2.4)
        assertEquals(3, t.onSwipeDispatched(down, 3.0))
        assertEquals(down, t.key)
        assertEquals(3, t.swipes)
    }

    @Test
    fun chainResetsOnLongGap() {
        val t = TvSwipeChainTracker()
        t.onSwipeDispatched(down, 1.0)
        t.onFocusMoved(4)
        t.onLift(1.5)
        assertEquals(1, t.onSwipeDispatched(down, 1.5 + CHAIN_MAX_GAP_S + 0.1))
        assertEquals(0, t.focusMoves)
    }

    @Test
    fun chainResetsOnDirectionChange() {
        val t = TvSwipeChainTracker()
        t.onSwipeDispatched(down, 1.0)
        t.onLift(1.5)
        assertEquals(1, t.onSwipeDispatched(Key.DirectionUp, 1.8))
        assertEquals(Key.DirectionUp, t.key)
    }

    @Test
    fun chainResetsWithoutRecordedLift() {
        val t = TvSwipeChainTracker()
        t.onSwipeDispatched(down, 1.0)
        assertEquals(1, t.onSwipeDispatched(down, 1.2))
    }

    @Test
    fun chainBoostOnlyForShortGap() {
        val t = TvSwipeChainTracker()
        t.onSwipeDispatched(down, 1.0)
        assertEquals(1f, t.currentBoost())
        t.onLift(1.5)
        t.onSwipeDispatched(down, 1.8)
        assertEquals(1f, t.currentBoost())
        assertEquals(1.3f, t.currentBoost(1.3f))
        t.onLift(2.0)
        t.onSwipeDispatched(down, 2.8)
        assertEquals(1f, t.currentBoost(1.3f))
    }

    @Test
    fun chainCountsFocusMovesAcrossSwipes() {
        val t = TvSwipeChainTracker()
        t.onSwipeDispatched(down, 1.0)
        t.onFocusMoved(3)
        t.onLift(1.5)
        t.onSwipeDispatched(down, 1.8)
        t.onFocusMoved()
        assertEquals(4, t.focusMoves)
        t.reset()
        assertEquals(0, t.focusMoves)
        assertEquals(0, t.swipes)
    }

    @Test
    fun chainEntersFastModeAtElevenMoves() {
        val t = TvSwipeChainTracker()
        t.onSwipeDispatched(down, 1.0)
        t.onFocusMoved(10)
        assertFalse(t.shouldEnterFastMode())
        t.onFocusMoved()
        assertTrue(t.shouldEnterFastMode())
    }

    @Test
    fun boostedRowsRoundsAndCaps() {
        assertEquals(7, boostedRows(5, 1.3f))
        assertEquals(8, boostedRows(7, 1.3f))
        assertEquals(4, boostedRows(4, 1f))
    }

    @Test
    fun repeatDelaysMatchNative() {
        assertEquals(
            listOf(500L, 200L, 150L, 100L, 100L),
            (0..4).map { tvDirectionalKeyRepeatDelayMs(it) }
        )
    }
}
