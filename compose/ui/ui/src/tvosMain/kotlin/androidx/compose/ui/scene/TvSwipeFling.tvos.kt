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

// Finger velocity helpers for Siri Remote swipes: the reported swipe velocity and the check for a
// finger that turned back against its momentum. Every value below is provisional pending device
// calibration.

/** Trailing window the velocity is measured over, in seconds. Provisional pending device calibration. */
internal const val FLING_VELOCITY_WINDOW_S = 0.05
/**
 * Shortest span of samples the peak speed of a swipe is measured over, in seconds: two display
 * frames at 60 Hz. Shorter spans are dominated by the offset between the two timebases of the
 * samples; on device they produced speeds up to 10 000 units per second.
 */
internal const val PEAK_SPEED_MIN_SPAN_S = 0.03
/**
 * Shortest time since a swipe started over which its average speed stands in for its peak speed, in
 * seconds. Over that long the offset between the two timebases of the samples is negligible.
 */
internal const val AVERAGE_SPEED_MIN_ELAPSED_S = 0.02
/**
 * Minimum speed along the dispatched axis, in clickpad units per second. A traverse ends between 9
 * and 29 units per second on the device, so this sits below the slowest of them. Device-measured
 * 2026-09-18, provisional.
 */
internal const val FLING_MIN_SPEED = 4.0f
/**
 * Speed against the dispatched direction, in clickpad units per second, at which the finger that
 * started the momentum is taken to have turned back, which ends it. Half of [FLING_MIN_SPEED], so
 * the wobble of a finger coming to a stop on the far ring is not read as a reversal. Device-measured
 * 2026-09-18, provisional.
 */
internal const val FLING_REVERSAL_SPEED = FLING_MIN_SPEED / 2f

/**
 * The trailing positions of one indirect contact, in the absolute clickpad space of
 * [SiriRemoteTouchOracle], used to measure how fast the finger is moving.
 */
internal class FlingSampleBuffer {
    private val xs = FloatArray(CAPACITY)
    private val ys = FloatArray(CAPACITY)
    private val timestamps = DoubleArray(CAPACITY)
    private var count = 0
    private var next = 0

    val size: Int
        get() = count

    fun add(x: Float, y: Float, t: Double) {
        // Samples come both from the display link, stamped with CACurrentMediaTime, and from UIKit,
        // stamped with UITouch.timestamp, so one can hand back a time the buffer has already passed.
        if (count > 0 && t <= timestamps[indexOf(count - 1)]) return
        xs[next] = x
        ys[next] = y
        timestamps[next] = t
        next = (next + 1) % CAPACITY
        if (count < CAPACITY) count++
    }

    /**
     * The signed velocity along one axis over the [windowS] seconds that precede the newest sample,
     * or `null` when that window holds no second sample, which is what a finger that has been
     * resting for longer than the window leaves behind.
     */
    fun velocityAlong(horizontal: Boolean, windowS: Double, minSpanS: Double = 0.0): Float? {
        if (count < 2) return null
        return velocityEndingAt(count - 1, horizontal, windowS, minSpanS)
    }

    /**
     * The velocity over the samples of the [windowS] seconds ending at [endLogical], `null` when
     * they span less than [minSpanS]. The samples come from UIKit callbacks stamped with the
     * hardware time of the touch and from display-link ticks stamped with the time of the tick, so
     * two of them can lie a fraction of a millisecond apart around a real movement, which reads as
     * thousands of units per second.
     */
    private fun velocityEndingAt(
        endLogical: Int,
        horizontal: Boolean,
        windowS: Double,
        minSpanS: Double,
    ): Float? {
        if (endLogical < 1) return null
        val end = indexOf(endLogical)
        val endTime = timestamps[end]
        var oldest = -1
        for (i in 0 until endLogical) {
            val index = indexOf(i)
            if (endTime - timestamps[index] <= windowS) {
                oldest = index
                break
            }
        }
        if (oldest < 0) return null
        val span = endTime - timestamps[oldest]
        if (span <= 0.0 || span < minSpanS) return null
        val delta = if (horizontal) xs[end] - xs[oldest] else ys[end] - ys[oldest]
        return (delta / span).toFloat()
    }

    private fun indexOf(logical: Int): Int =
        (((next - count + logical) % CAPACITY) + CAPACITY) % CAPACITY

    private companion object {
        const val CAPACITY = 8
    }
}

/** Whether [key] moves focus along the x axis of the clickpad rather than along its y axis. */
internal fun isHorizontalFlingKey(key: Key): Boolean =
    key == Key.DirectionLeft || key == Key.DirectionRight

/**
 * The velocity [axisVelocity] of the dominant axis, signed so that it is positive when the finger
 * was moving the way [key] points. The oracle's y is positive toward the top of the remote, so up
 * and right keep the measured sign while down and left invert it.
 */
internal fun signedSpeedAlongKey(key: Key, axisVelocity: Float): Float =
    when (key) {
        Key.DirectionRight,
        Key.DirectionUp -> axisVelocity
        else -> -axisVelocity
    }
