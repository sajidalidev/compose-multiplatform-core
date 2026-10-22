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
        // One row per 0.254 pad unit from two rows at the single-row limit, capped at seven
        assertEquals(2, momentumRowsFor(0.8f, 0.6f))
        assertEquals(2, momentumRowsFor(0.8f, 0.81f))
        assertEquals(3, momentumRowsFor(0.8f, 0.82f))
        assertEquals(3, momentumRowsFor(0.8f, 0.9f))
        assertEquals(4, momentumRowsFor(0.8f, 1.2f))
        assertEquals(6, momentumRowsFor(0.8f, 1.6f))
        assertEquals(7, momentumRowsFor(0.8f, 1.9f))
        assertEquals(7, momentumRowsFor(0.8f, 2.5f))
    }

    @Test
    fun startOffsetGatesMomentumAtItsThreshold() {
        val below = MOMENTUM_MIN_START_OFFSET - 0.01f
        assertEquals(1, momentumRowsFor(below, 1.2f))
        assertEquals(4, momentumRowsFor(MOMENTUM_MIN_START_OFFSET, 1.2f))
        assertEquals(1, momentumRowsFor(0.0f, 1.2f))
        assertEquals(1, momentumRowsFor(-0.5f, 1.2f))
        assertEquals(1, momentumRowsFor(MOMENTUM_MIN_START_OFFSET, 0.59f))
        assertEquals(2, momentumRowsFor(MOMENTUM_MIN_START_OFFSET, 0.6f))
    }

    @Test
    fun deviceStartOffsetsOfBothDirectionsEarnMomentum() {
        // Measured on an Apple TV 4K: downward swipes start about 0.45 above the centre, upward
        // ones about 0.9 below it. Both must keep their momentum.
        assertEquals(4, momentumRowsFor(0.45f, 1.2f))
        assertEquals(4, momentumRowsFor(0.9f, 1.2f))
    }

    @Test
    fun speedRowsClimbOneRowPerStepFromTheMinimum() {
        assertEquals(0, speedRowsFor(0f))
        assertEquals(0, speedRowsFor(MOMENTUM_SPEED_MIN - 0.1f))
        assertEquals(2, speedRowsFor(MOMENTUM_SPEED_MIN))
        assertEquals(3, speedRowsFor(MOMENTUM_SPEED_MIN + MOMENTUM_SPEED_PER_ROW))
        assertEquals(7, speedRowsFor(MOMENTUM_SPEED_MIN + 5 * MOMENTUM_SPEED_PER_ROW))
    }

    @Test
    fun fastFlickOutrunsItsTravel() {
        // A moderate swipe keeps the rows of its travel; a fast flick of the same length goes
        // further, capped at the single swipe maximum.
        assertEquals(2, momentumRowsFor(0.8f, 0.7f, peakSpeed = 6f))
        assertEquals(5, momentumRowsFor(0.8f, 0.7f, peakSpeed = 20f))
        assertEquals(MOMENTUM_MAX_ROWS, momentumRowsFor(0.8f, 1.6f, peakSpeed = 60f))
        // The travel still sets the floor of a slow long swipe.
        assertEquals(6, momentumRowsFor(0.8f, 1.6f, peakSpeed = 5f))
    }

    @Test
    fun speedDoesNotBypassTheMomentumGates() {
        assertEquals(1, momentumRowsFor(0.8f, 0.5f, peakSpeed = 30f))
        assertEquals(1, momentumRowsFor(0.1f, 1.2f, peakSpeed = 30f))
        assertEquals(1, momentumRowsFor(null, 1.2f, peakSpeed = 30f))
    }

    @Test
    fun fastSwipeThatKeepsAChainGoingNeedsLessTravel() {
        // Short, fast, started near the centre: one row alone, momentum within a chain.
        assertEquals(1, momentumRowsFor(0.1f, 0.5f, peakSpeed = 12f))
        assertEquals(3, momentumRowsFor(0.1f, 0.5f, peakSpeed = 12f, followsMomentumSwipe = true))
        // Too slow for the chain rule: the usual gates apply.
        assertEquals(1, momentumRowsFor(0.1f, 0.5f, peakSpeed = 6f, followsMomentumSwipe = true))
        // Still a floor on the travel.
        val short = CHAIN_SINGLE_ROW_TRAVEL - 0.01f
        assertEquals(1, momentumRowsFor(0.8f, short, peakSpeed = 20f, followsMomentumSwipe = true))
        assertEquals(1, momentumRowsFor(null, 0.5f, peakSpeed = 20f, followsMomentumSwipe = true))
    }

    @Test
    fun briefFlickKeepsAChainGoingWithoutAMeasuredSpeed() {
        // Sampled once before the pad went empty: no speed, a short known travel.
        assertEquals(
            2,
            momentumRowsFor(0.1f, 0.22f, followsMomentumSwipe = true, quickContact = true),
        )
        assertEquals(1, momentumRowsFor(0.1f, 0.22f, quickContact = true))
        assertEquals(1, momentumRowsFor(0.1f, 0.22f, peakSpeed = 5f, followsMomentumSwipe = true))
        val short = CHAIN_SINGLE_ROW_TRAVEL - 0.01f
        assertEquals(
            1,
            momentumRowsFor(0.1f, short, followsMomentumSwipe = true, quickContact = true),
        )
    }

    @Test
    fun seriesTakesASwipeUpToFortyFiveDegreesOffItsAxis() {
        val up = Key.DirectionUp
        // Oracle space: y is positive toward the top of the remote.
        assertEquals(up, seriesSwipeKey(up, dx = 0.35f, dy = 0.40f))
        assertEquals(up, seriesSwipeKey(up, dx = -0.40f, dy = 0.40f))
        assertEquals(null, seriesSwipeKey(up, dx = 0.50f, dy = 0.40f))
        // A stroke back against the series is not taken.
        assertEquals(null, seriesSwipeKey(up, dx = 0.10f, dy = -0.60f))
        assertEquals(null, seriesSwipeKey(null, dx = 0.0f, dy = 0.60f))
        assertEquals(Key.DirectionLeft, seriesSwipeKey(Key.DirectionLeft, dx = -0.5f, dy = 0.3f))
        assertEquals(0.4f, displacementAlongKey(down, dx = 0.1f, dy = -0.4f))
    }

    @Test
    fun seriesContinuesOnlyWithinTheChainGap() {
        val t = TvSwipeChainTracker()
        assertEquals(null, t.continuingKey(1.0))
        t.onSwipeDispatched(down, 1.0)
        assertEquals(null, t.continuingKey(1.1))
        t.onLift(1.2)
        assertEquals(down, t.continuingKey(1.5))
        assertEquals(null, t.continuingKey(1.2 + CHAIN_MAX_GAP_S + 0.01))
    }

    @Test
    fun chainKnowsWhetherItsPreviousSwipeHadMomentum() {
        val t = TvSwipeChainTracker()
        t.onSwipeDispatched(down, 1.0)
        assertFalse(t.followsMomentumSwipe)
        t.onSwipeRows(4)
        t.onLift(1.1)
        t.onSwipeDispatched(down, 1.4)
        assertTrue(t.followsMomentumSwipe)
        // A single-row swipe does not carry it to the next one.
        t.onSwipeRows(1)
        t.onLift(1.5)
        t.onSwipeDispatched(down, 1.8)
        assertFalse(t.followsMomentumSwipe)
        t.onSwipeRows(5)
        t.onLift(1.9)
        // Direction change and long gap both start a new chain.
        t.onSwipeDispatched(Key.DirectionUp, 2.0)
        assertFalse(t.followsMomentumSwipe)
        t.onSwipeRows(5)
        t.onLift(2.1)
        t.onSwipeDispatched(Key.DirectionUp, 2.1 + CHAIN_MAX_GAP_S + 0.1)
        assertFalse(t.followsMomentumSwipe)
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
            assertEquals(3, momentumRowsFor(swipeStartOffset(key, origin), 0.9f))
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
    fun chainBoostLeavesSingleRowSwipesAlone() {
        assertTrue(CHAIN_BOOST > 1f)
        assertEquals(1, boostedRows(1, CHAIN_BOOST))
        assertTrue(boostedRows(5, CHAIN_BOOST) > 5)
    }

    @Test
    fun liftDistanceStaysBelowTheArmingGate() {
        assertTrue(SWIPE_LIFT_DISTANCE < 0.30f)
    }

    @Test
    fun tailStepsNeverWaitLongerThanTheMaximumInterval() {
        val b = TvMomentumBurst(down, 0.0, rows = 4, tau = MOMENTUM_CHAIN_TAU_S)
        var now = 0.0
        var last = 0.0
        var longest = 0.0
        while (!b.isFinished) {
            now += 0.001
            repeat(b.stepsDue(now, last)) {
                b.onStepEmitted()
                longest = maxOf(longest, now - last)
                last = now
            }
        }
        assertTrue(longest <= MOMENTUM_MAX_STEP_INTERVAL_S + 0.0015, "longest $longest")
        // Without the cap the last step trails the previous one by about 175 ms.
        assertTrue(now < 0.28, "finished at $now")
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
    fun stepPaidByTravelRaisesRowsAheadOfTheCurve() {
        // A dragging finger pays for moves the curve never planned: the burst follows.
        val b = burst(1)
        assertTrue(b.isFinished)
        b.onStepEmitted()
        assertEquals(2, b.emitted)
        assertEquals(2, b.rows)
        assertTrue(b.isFinished)
        assertEquals(0, b.stepsDue(b.startedAt + 1.0))
        b.raiseRows(1)
        assertEquals(2, b.rows)
    }

    @Test
    fun carriedRowsAreOwedOnTopOfTheSwipesOwn() {
        val b = TvMomentumBurst(down, 10.0, rows = 3, carried = 2)
        assertEquals(5, b.rows)
        b.raiseRows(4)
        assertEquals(6, b.rows)
        b.raiseRows(2)
        assertEquals(6, b.rows)
        assertEquals(5, b.remaining)
    }

    @Test
    fun chainTauSpreadsTheSameRowsOverLonger() {
        fun lastStepAt(tau: Double): Double {
            val b = TvMomentumBurst(down, 0.0, rows = 4, tau = tau)
            var now = 0.0
            while (!b.isFinished) {
                now += 0.001
                repeat(b.stepsDue(now)) { b.onStepEmitted() }
            }
            return now
        }
        val single = lastStepAt(MOMENTUM_TAU_S)
        val chained = lastStepAt(MOMENTUM_CHAIN_TAU_S)
        assertTrue(single in 0.12..0.16, "single $single")
        assertTrue(chained in 0.28..0.34, "chained $chained")
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
        assertEquals(CHAIN_BOOST, t.currentBoost())
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

    /** Dispatches a swipe at [at] of [rows] rows, counts its [moves] moves and lifts it 0.2 s later. */
    private fun TvSwipeChainTracker.swipe(at: Double, rows: Int, moves: Int = rows): Boolean {
        onSwipeDispatched(down, at)
        onSwipeRows(rows)
        onFocusMoved(moves)
        onLift(at + 0.2)
        return shouldEnterFastMode()
    }

    @Test
    fun singleRowSwipesNeverEnterFastMode() {
        val t = TvSwipeChainTracker()
        for (i in 0 until 20) assertFalse(t.swipe(1.0 + i * 0.5, 1))
        assertEquals(20, t.focusMoves)
        assertEquals(0, t.momentumMoves)
    }

    @Test
    fun momentumSwipesEnterAtLiftOfThird() {
        val t = TvSwipeChainTracker()
        assertFalse(t.swipe(1.0, 5))
        assertFalse(t.swipe(1.5, 5))
        assertEquals(10, t.momentumMoves)
        assertTrue(t.swipe(2.0, 5))
        assertEquals(15, t.momentumMoves)
    }

    @Test
    fun twoSixRowSwipesDoNotEnterButThirdDoes() {
        val t = TvSwipeChainTracker()
        assertFalse(t.swipe(1.0, 6))
        assertFalse(t.swipe(1.5, 6))
        assertEquals(12, t.momentumMoves)
        assertEquals(2, t.momentumSwipes)
        assertTrue(t.swipe(2.0, 6))
    }

    @Test
    fun threeThreeRowSwipesDoNotEnterButFourthDoes() {
        val t = TvSwipeChainTracker()
        assertFalse(t.swipe(1.0, 3))
        assertFalse(t.swipe(1.5, 3))
        assertFalse(t.swipe(2.0, 3))
        assertEquals(9, t.momentumMoves)
        assertTrue(t.swipe(2.5, 3))
        assertEquals(12, t.momentumMoves)
    }

    @Test
    fun growingSwipeCountsAsOneMomentumSwipe() {
        val t = TvSwipeChainTracker()
        t.onSwipeDispatched(down, 1.0)
        t.onSwipeRows(1)
        assertEquals(0, t.momentumSwipes)
        t.onSwipeRows(2)
        t.onSwipeRows(4)
        t.onSwipeRows(6)
        assertEquals(1, t.momentumSwipes)
    }

    @Test
    fun singleRowSwipesDoNotCountOrResetMomentumSwipes() {
        val t = TvSwipeChainTracker()
        t.swipe(1.0, 6)
        t.swipe(1.5, 1)
        assertEquals(1, t.momentumSwipes)
        t.swipe(2.0, 6)
        t.swipe(2.5, 1)
        assertEquals(2, t.momentumSwipes)
        assertTrue(t.swipe(3.0, 6))
        assertEquals(3, t.momentumSwipes)
    }

    @Test
    fun directionChangeTimeoutAndResetClearMomentumSwipes() {
        val t = TvSwipeChainTracker()
        t.swipe(1.0, 6)
        t.swipe(1.5, 6)
        t.onSwipeDispatched(Key.DirectionUp, 2.0)
        assertEquals(0, t.momentumSwipes)
        t.swipe(3.0, 6)
        t.swipe(3.5, 6)
        assertFalse(t.swipe(3.7 + CHAIN_MAX_GAP_S + 0.5, 6))
        assertEquals(1, t.momentumSwipes)
        t.reset()
        assertEquals(0, t.momentumSwipes)
    }

    @Test
    fun singleSwipesBetweenMomentumSwipesNeitherCountNorReset() {
        val t = TvSwipeChainTracker()
        assertFalse(t.swipe(1.0, 5))
        assertFalse(t.swipe(1.5, 1))
        assertEquals(5, t.momentumMoves)
        assertFalse(t.swipe(2.0, 5))
        assertFalse(t.swipe(2.5, 1))
        assertEquals(10, t.momentumMoves)
        assertTrue(t.swipe(3.0, 5))
        assertEquals(17, t.focusMoves)
        assertEquals(5, t.swipes)
    }

    @Test
    fun singleSwipeDoesNotEnterEvenOverThreshold() {
        val t = TvSwipeChainTracker()
        t.swipe(1.0, 5)
        t.swipe(1.5, 5)
        t.swipe(2.0, 5)
        assertFalse(t.swipe(2.5, 1))
        assertEquals(15, t.momentumMoves)
    }

    @Test
    fun directionChangeResetsMomentumMoves() {
        val t = TvSwipeChainTracker()
        t.swipe(1.0, 5)
        t.swipe(1.5, 5)
        t.onSwipeDispatched(Key.DirectionUp, 2.0)
        assertEquals(0, t.momentumMoves)
        t.onSwipeRows(5)
        t.onFocusMoved(5)
        assertFalse(t.shouldEnterFastMode())
        assertEquals(5, t.momentumMoves)
    }

    @Test
    fun chainTimeoutResetsMomentumMoves() {
        val t = TvSwipeChainTracker()
        t.swipe(1.0, 5)
        t.swipe(1.5, 5)
        assertFalse(t.swipe(1.7 + CHAIN_MAX_GAP_S + 0.5, 5))
        assertEquals(5, t.momentumMoves)
    }

    @Test
    fun swipeThatGrowsBeforeLiftCountsAllItsMoves() {
        val t = TvSwipeChainTracker()
        t.onSwipeDispatched(down, 1.0)
        t.onSwipeRows(1)
        t.onFocusMoved()
        assertEquals(0, t.momentumMoves)
        t.onSwipeRows(4)
        assertEquals(1, t.momentumMoves)
        t.onFocusMoved(3)
        assertEquals(4, t.momentumMoves)
        assertEquals(4, t.focusMoves)
    }

    @Test
    fun resetClearsMomentumMoves() {
        val t = TvSwipeChainTracker()
        t.swipe(1.0, 5)
        t.reset()
        assertEquals(0, t.momentumMoves)
        assertFalse(t.shouldEnterFastMode())
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
