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

import kotlin.math.abs
import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvFastScrollPhysicsTest {
    private val frame = 1.0 / 60.0

    private fun swiping(forward: Boolean = true) =
        TvFastScrollPhysics().also { it.startFromSwipe(0.0, forward) }

    /** A fresh instance coasting at about 1000 dp/s, started by a flick of a finger from rest. */
    private fun coastingAt1000(): TvFastScrollPhysics {
        val p = TvFastScrollPhysics()
        flick(p, 1.0, 0f, 1000f / FAST_PAN_GAIN_DP_PER_UNIT * 0.04f)
        return p
    }

    private fun holdUntil(p: TvFastScrollPhysics, velocity: Float, start: Double): Double {
        var t = start
        while (p.velocity < velocity) {
            t += 0.05
            p.step(t)
        }
        return t
    }

    @Test
    fun stopDuringHoldEndsTheDriveAndAllowsExit() {
        val p = TvFastScrollPhysics()
        p.startFromHold(0.0, forward = true)
        var t = 0.0
        repeat(10) {
            t += frame
            p.step(t)
        }
        assertTrue(p.velocity > 0f)
        assertFalse(p.shouldExit)
        p.stop()
        repeat(10) {
            t += frame
            p.step(t)
            assertEquals(0f, p.velocity)
        }
        assertTrue(p.shouldExit)
    }

    @Test
    fun restingFingerDoesNotStopAHeldDrive() {
        val p = TvFastScrollPhysics()
        p.startFromHold(0.0, forward = true)
        p.onTouchDown(0.0)
        var t = 0.0
        repeat(30) {
            t += frame
            p.onTouchSample(0.5f, t)
            p.step(t)
        }
        assertTrue(p.velocity > FAST_HOLD_START_VELOCITY_DP_S)
        p.onHoldReleased(t)
        t += frame
        p.onTouchSample(0.5f, t)
        assertTrue(p.step(t + frame) > 0f)
    }

    @Test
    fun coastDecaysWithFastTau() {
        val tau = FAST_TAU_S.toFloat()
        val schedules = listOf(
            List(31) { frame },
            listOf(0.1, 0.1, 0.1, 0.1, 0.1, 0.021),
        )
        for (steps in schedules) {
            val p = coastingAt1000()
            val v0 = p.velocity
            assertEquals(1000f, v0, 1f)
            var now = 1.04
            p.step(now)
            var sum = 0f
            for (dt in steps) {
                now += dt
                sum += p.step(now)
            }
            val k = (steps.sum() / FAST_TAU_S).toFloat()
            assertEquals(v0 * exp(-k), p.velocity, v0 * 0.001f)
            assertEquals(v0 * tau * (1f - exp(-k)), sum, v0 * 0.001f)
        }
        val p = coastingAt1000()
        var now = 1.04
        p.step(now)
        var sum = 0f
        for (dt in listOf(0.1, 0.1, 0.1, 0.1, 0.1, 0.021)) {
            now += dt
            sum += p.step(now)
        }
        assertEquals(1000f / exp(1f), p.velocity, 1000f / exp(1f) * 0.01f)
        assertEquals(1000f * FAST_TAU_S.toFloat() * (1f - 1f / exp(1f)), sum, 1000f * 0.01f)
    }

    @Test
    fun swipeEntryUsesEntryVelocityAndDirection() {
        assertEquals(FAST_SWIPE_ENTRY_VELOCITY_DP_S, swiping(forward = true).velocity)
        assertEquals(-FAST_SWIPE_ENTRY_VELOCITY_DP_S, swiping(forward = false).velocity)
        assertTrue(swiping(forward = false).step(0.1) < 0f)
    }

    @Test
    fun holdAccelerates_startsAtHoldVelocityInDirection() {
        val p = TvFastScrollPhysics()
        p.startFromHold(0.0, forward = false)
        assertEquals(-FAST_HOLD_START_VELOCITY_DP_S, p.velocity)
    }

    @Test
    fun holdAcceleratesLinearly() {
        val p = TvFastScrollPhysics()
        p.startFromHold(0.0, forward = true)
        var distance = 0f
        for (i in 1..20) distance += p.step(i * 0.05)
        assertEquals(FAST_HOLD_START_VELOCITY_DP_S + FAST_HOLD_ACCEL_DP_S2, p.velocity, 0.5f)
        assertEquals(900f + 0.5f * 1200f, distance, 0.5f)
    }

    @Test
    fun holdReleaseBrakesWithShortTau() {
        val p = TvFastScrollPhysics()
        p.startFromHold(0.0, forward = true)
        var t = holdUntil(p, 5000f, 0.0)
        val v0 = p.velocity
        p.onHoldReleased(t)
        for (dt in listOf(0.05, 0.05, 0.005)) {
            t += dt
            p.step(t)
        }
        assertEquals(v0 / exp(1f), p.velocity, v0 / exp(1f) * 0.01f)
    }

    @Test
    fun holdReleaseExitsWithinOneSecond() {
        val p = TvFastScrollPhysics()
        p.startFromHold(0.0, forward = true)
        var t = holdUntil(p, 5000f, 0.0)
        assertFalse(p.shouldExit)
        p.onHoldReleased(t)
        val released = t
        while (!p.shouldExit && t - released < 5.0) {
            t += frame
            p.step(t)
        }
        assertTrue(p.shouldExit)
        assertTrue(t - released < 1.0, "exit took ${t - released} s")
    }

    @Test
    fun exitsBelowExitSpeed() {
        val p = swiping()
        var t = 0.0
        while (abs(p.velocity) >= FAST_EXIT_SPEED_DP_S) {
            assertFalse(p.shouldExit)
            t += frame
            p.step(t)
        }
        assertTrue(p.shouldExit)
    }

    @Test
    fun noExitWhileFingerDownOrHeld() {
        val finger = swiping()
        finger.onTouchDown(0.0)
        finger.stop()
        assertFalse(finger.shouldExit)
        finger.onTouchUp(0.1)
        assertTrue(finger.shouldExit)

        val key = TvFastScrollPhysics()
        key.startFromHold(0.0, forward = true)
        assertFalse(key.shouldExit)
        key.onHoldReleased(0.1)
        key.stop()
        assertTrue(key.shouldExit)
    }

    @Test
    fun panFollowsFinger() {
        val p = swiping()
        p.onTouchDown(0.0)
        p.onTouchSample(0.3f, 0.0)
        p.onTouchSample(0.5f, 0.01)
        assertEquals(90f, p.step(0.0), 0.01f)
        assertEquals(0f, p.step(0.0), 0.01f)
    }

    @Test
    fun flickCompoundsSameDirection() {
        val p = swiping()
        flick(p, 1.0, 0f, 0.2f)
        assertEquals(FAST_SWIPE_ENTRY_VELOCITY_DP_S + FAST_PAN_GAIN_DP_PER_UNIT * 5f, p.velocity, 1f)
    }

    @Test
    fun oppositeFlickReplacesVelocity() {
        val p = swiping()
        flick(p, 1.0, 0.2f, 0f)
        assertEquals(-FAST_PAN_GAIN_DP_PER_UNIT * 5f, p.velocity, 1f)
    }

    @Test
    fun slowReleaseAddsNothing() {
        val p = swiping()
        flick(p, 1.0, 0f, 0.01f)
        assertEquals(FAST_SWIPE_ENTRY_VELOCITY_DP_S, p.velocity)
    }

    @Test
    fun touchDownDoesNotStop() {
        val p = swiping()
        p.onTouchDown(0.0)
        assertEquals(FAST_SWIPE_ENTRY_VELOCITY_DP_S, p.velocity)
        assertTrue(p.step(0.05) > 0f)
        assertTrue(p.velocity > 0f)
    }

    @Test
    fun restingFingerStops() {
        val p = swiping()
        p.onTouchDown(1.0)
        p.onTouchSample(0.5f, 1.0)
        p.onTouchSample(0.51f, 1.03)
        assertTrue(p.velocity > 0f)
        p.onTouchSample(0.5f, 1.06)
        assertEquals(0f, p.velocity)
        p.onTouchUp(1.07)
        assertTrue(p.shouldExit)
    }

    @Test
    fun movingFingerDoesNotStop() {
        val p = swiping()
        p.onTouchDown(1.0)
        for (i in 0..10) p.onTouchSample(0.5f + 0.05f * i, 1.0 + 0.03 * i)
        assertTrue(p.velocity > 0f)
    }

    @Test
    fun stopZeroesVelocityAndExits() {
        val p = swiping()
        p.stop()
        assertEquals(0f, p.velocity)
        assertTrue(p.shouldExit)
        assertEquals(0f, p.step(0.1), 0.001f)
    }

    /** Steps one frame at [t] and reports that the container consumed [gotFraction] of the request. */
    private fun frameWith(p: TvFastScrollPhysics, t: Double, gotFraction: Float): Float {
        val dp = p.step(t)
        p.onConsumed(dp, dp * gotFraction)
        return dp
    }

    @Test
    fun shortFrameStartsEdgeWaitInsteadOfEnding() {
        val p = swiping()
        p.onConsumed(100f, 99.7f)
        assertFalse(p.isWaitingAtEdge)
        assertEquals(FAST_SWIPE_ENTRY_VELOCITY_DP_S, p.velocity)
        p.onConsumed(100f, 40f)
        assertTrue(p.isWaitingAtEdge)
        assertFalse(p.hitEnd)
        assertEquals(FAST_SWIPE_ENTRY_VELOCITY_DP_S, p.velocity)
        assertFalse(p.shouldExit)
    }

    @Test
    fun consumptionResumingContinuesWithDecayedVelocity() {
        val p = swiping()
        var t = 0.0
        t += frame
        frameWith(p, t, 0f)
        assertTrue(p.isWaitingAtEdge)
        val waitStart = t
        val v0 = p.velocity
        while (t - waitStart < 0.1) {
            t += frame
            frameWith(p, t, 0f)
        }
        t += frame
        frameWith(p, t, 1f)
        assertFalse(p.isWaitingAtEdge)
        assertFalse(p.hitEnd)
        val expected = v0 * exp(-(t - waitStart) / FAST_TAU_S).toFloat()
        assertEquals(expected, p.velocity, expected * 0.02f)
        assertTrue(p.step(t + frame) > 0f)
    }

    @Test
    fun waitLongerThanTimeoutMakesTheEndFinal() {
        val p = swiping()
        var t = 0.0
        while (!p.hitEnd && t < 2.0) {
            t += frame
            frameWith(p, t, 0f)
        }
        assertTrue(p.hitEnd)
        assertFalse(p.isWaitingAtEdge)
        assertEquals(0f, p.velocity)
        assertTrue(p.shouldExit)
        assertTrue(t <= FAST_EDGE_WAIT_S + 3 * frame)
        assertTrue(t >= FAST_EDGE_WAIT_S)
    }

    @Test
    fun requestWhileWaitingIsCapped() {
        val p = TvFastScrollPhysics()
        p.startFromHold(0.0, forward = true)
        var t = 0.0
        repeat(120) {
            t += frame
            p.step(t)
        }
        assertTrue(p.velocity > 1000f)
        t += frame
        frameWith(p, t, 0f)
        assertTrue(p.isWaitingAtEdge)
        t += frame
        val dp = p.step(t)
        assertEquals(FAST_EDGE_WAIT_MAX_DELTA_DP, dp, 0.001f)
    }

    @Test
    fun oppositeFlickLeavesTheWait() {
        val p = swiping()
        frameWith(p, frame, 0f)
        assertTrue(p.isWaitingAtEdge)
        flick(p, 0.1, 0f, -1000f / FAST_PAN_GAIN_DP_PER_UNIT * 0.04f)
        assertFalse(p.isWaitingAtEdge)
        assertFalse(p.hitEnd)
        assertTrue(p.velocity < 0f)
        assertTrue(p.step(0.2) < 0f)
    }

    @Test
    fun oppositePanLeavesTheWait() {
        val p = swiping()
        frameWith(p, frame, 0f)
        p.onTouchDown(0.05)
        p.onTouchSample(0.5f, 0.05)
        p.onTouchSample(0.4f, 0.06)
        assertTrue(p.step(0.07) < 0f)
        assertFalse(p.isWaitingAtEdge)
    }

    @Test
    fun stopDuringWaitEndsAsStop() {
        val p = swiping()
        frameWith(p, frame, 0f)
        assertTrue(p.isWaitingAtEdge)
        p.stop()
        assertFalse(p.isWaitingAtEdge)
        assertFalse(p.hitEnd)
        assertEquals(0f, p.velocity)
        assertTrue(p.shouldExit)
    }

    @Test
    fun heldDriveResumesAfterShortWaitAndExitsAfterFinalEnd() {
        val p = TvFastScrollPhysics()
        p.startFromHold(0.0, forward = true)
        var t = 0.0
        t += frame
        frameWith(p, t, 0f)
        repeat(6) {
            t += frame
            frameWith(p, t, 0f)
        }
        assertTrue(p.isWaitingAtEdge)
        t += frame
        frameWith(p, t, 1f)
        assertFalse(p.isWaitingAtEdge)
        assertFalse(p.hitEnd)
        assertTrue(p.velocity > FAST_HOLD_START_VELOCITY_DP_S)
        assertFalse(p.shouldExit)

        frameWith(p, t + frame, 0f)
        t += frame
        p.onHoldReleased(t)
        assertFalse(p.shouldExit)
        while (!p.hitEnd && t < 3.0) {
            t += frame
            frameWith(p, t, 0f)
        }
        assertTrue(p.hitEnd)
        assertTrue(p.shouldExit)
    }

    @Test
    fun velocityIsCapped() {
        val p = swiping()
        flick(p, 1.0, 0f, 1f, span = 0.01)
        assertEquals(FAST_MAX_VELOCITY_DP_S, p.velocity)
        val q = TvFastScrollPhysics()
        q.startFromHold(0.0, forward = true)
        var t = 0.0
        repeat(300) {
            t += 0.1
            q.step(t)
        }
        assertEquals(FAST_MAX_VELOCITY_DP_S, q.velocity)
    }

    @Test
    fun stalledFrameIsClamped() {
        val stalled = swiping()
        val clamped = swiping()
        assertEquals(clamped.step(FAST_MAX_FRAME_DT_S), stalled.step(10.0), 0.001f)
        assertEquals(clamped.velocity, stalled.velocity, 0.001f)
    }

    /** One finger contact from [from] to [to] pad units over [span] seconds starting at [start], then lift. */
    private fun flick(
        p: TvFastScrollPhysics,
        start: Double,
        from: Float,
        to: Float,
        span: Double = 0.04,
    ) {
        p.onTouchDown(start)
        p.onTouchSample(from, start)
        p.onTouchSample((from + to) / 2f, start + span / 2)
        p.onTouchSample(to, start + span)
        p.onTouchUp(start + span)
    }
}
