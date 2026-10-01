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
import kotlin.math.sign

/** How far a resting finger may drift from its anchor, in pad units, and still count as resting. */
private const val FAST_REST_TOLERANCE = 0.02f
/** Shortfall in dp beyond which a scroll the container could not fully consume marks the end of the content. */
private const val FAST_END_SLACK_DP = 0.5f

/**
 * The motion of fast scrolling, in scene dp and seconds. Forward is toward the bottom or the right
 * of the screen. It coasts with exponential decay after a swipe or a flick, accelerates while a
 * directional key is held, brakes when it is released, and follows a finger on the pad.
 */
internal class TvFastScrollPhysics {
    var velocity: Float = 0f
        private set
    var hitEnd: Boolean = false
        private set

    private var tau = FAST_TAU_S
    private var held = false
    private var heldSign = 1f
    private var fingerDown = false
    private var stopped = false
    private var lastStepAt = Double.NaN
    private var pendingPan = 0f
    private var lastPosition = Float.NaN
    private var anchorPosition = Float.NaN
    private var anchorSince = 0.0
    private var samples = FlingSampleBuffer()

    /** No finger down, not held, and either slower than [FAST_EXIT_SPEED_DP_S], stopped or at the end. */
    val shouldExit: Boolean
        get() = !fingerDown && !held && (abs(velocity) < FAST_EXIT_SPEED_DP_S || stopped || hitEnd)

    fun startFromSwipe(now: Double, forward: Boolean) {
        begin(now)
        velocity = (if (forward) 1f else -1f) * FAST_SWIPE_ENTRY_VELOCITY_DP_S
        tau = FAST_TAU_S
        held = false
    }

    fun startFromHold(now: Double, forward: Boolean) {
        begin(now)
        heldSign = if (forward) 1f else -1f
        velocity = heldSign * FAST_HOLD_START_VELOCITY_DP_S
        held = true
    }

    fun onHoldReleased(now: Double) {
        advanceClock(now)
        held = false
        tau = FAST_HOLD_BRAKE_TAU_S
    }

    fun onTouchDown(now: Double) {
        fingerDown = true
        lastPosition = Float.NaN
        anchorPosition = Float.NaN
        samples = FlingSampleBuffer()
    }

    /** [forwardPosition] is the contact position along the scroll axis in pad units, positive forward. */
    fun onTouchSample(forwardPosition: Float, now: Double) {
        if (!lastPosition.isNaN()) {
            pendingPan += FAST_PAN_GAIN_DP_PER_UNIT * (forwardPosition - lastPosition)
        }
        lastPosition = forwardPosition
        samples.add(forwardPosition, 0f, now)
        if (anchorPosition.isNaN() || abs(forwardPosition - anchorPosition) > FAST_REST_TOLERANCE) {
            anchorPosition = forwardPosition
            anchorSince = now
        } else if (held) {
            // A finger resting while the drive key is held must not stop the drive.
            anchorSince = now
        } else if (now - anchorSince >= FAST_REST_STOP_S) {
            stop()
        }
    }

    fun onTouchUp(now: Double) {
        fingerDown = false
        val speed = samples.velocityAlong(horizontal = true, windowS = FLING_VELOCITY_WINDOW_S)
        samples = FlingSampleBuffer()
        lastPosition = Float.NaN
        anchorPosition = Float.NaN
        if (speed == null || abs(speed) < FAST_FLICK_MIN_SPEED) return
        val flick = FAST_PAN_GAIN_DP_PER_UNIT * speed
        velocity = if (velocity == 0f || sign(flick) == sign(velocity)) velocity + flick else flick
        velocity = capped(velocity)
        tau = FAST_TAU_S
        held = false
        stopped = false
    }

    fun stop() {
        velocity = 0f
        stopped = true
        held = false
    }

    /** Advances to [now] and returns the dp to scroll since the previous call (coast or hold drive plus pending pan). */
    fun step(now: Double): Float {
        val dt = if (lastStepAt.isNaN()) 0.0 else (now - lastStepAt).coerceIn(0.0, FAST_MAX_FRAME_DT_S)
        lastStepAt = now
        var delta: Float
        if (held) {
            val a = heldSign * FAST_HOLD_ACCEL_DP_S2
            val t = dt.toFloat()
            delta = velocity * t + 0.5f * a * t * t
            velocity = capped(velocity + a * t)
        } else {
            val decay = exp(-dt / tau)
            delta = (velocity * tau * (1.0 - decay)).toFloat()
            velocity = (velocity * decay).toFloat()
        }
        delta += pendingPan
        pendingPan = 0f
        return delta
    }

    fun onConsumed(requested: Float, consumed: Float) {
        if (abs(requested - consumed) > FAST_END_SLACK_DP) {
            hitEnd = true
            velocity = 0f
        }
    }

    private fun begin(now: Double) {
        lastStepAt = now
        stopped = false
        hitEnd = false
    }

    private fun advanceClock(now: Double) {
        if (lastStepAt.isNaN()) lastStepAt = now
    }

    private fun capped(v: Float): Float = v.coerceIn(-FAST_MAX_VELOCITY_DP_S, FAST_MAX_VELOCITY_DP_S)
}
