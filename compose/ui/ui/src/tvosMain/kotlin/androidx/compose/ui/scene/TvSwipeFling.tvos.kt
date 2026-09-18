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
import kotlin.math.floor
import kotlin.math.pow

// Momentum tuning for Siri Remote swipes. A swipe that passes the gates moves focus once, from
// wherever it started. Only a traverse straight across the ring keeps focus moving after that,
// the way native tvOS does: the momentum starts the moment the finger reaches the ring on the far
// side, and how far it carries follows how fast the finger was moving at that moment. Every value
// below is provisional pending device calibration.

/** Trailing window the velocity is measured over, in seconds. Provisional pending device calibration. */
internal const val FLING_VELOCITY_WINDOW_S = 0.05
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
 * Speed above [FLING_MIN_SPEED] that buys one more move, in clickpad units per second. A traverse
 * ending at 12 units per second earns three extra moves and one at 20 or more earns the cap.
 * Device-measured 2026-09-18, provisional.
 */
internal const val FLING_SPEED_PER_STEP = 4.0f
/**
 * Upper bound on the moves a single fling can add once the far side is reached, which keeps a
 * traverse to the four or five items native tvOS moves for one. Device-measured 2026-09-18,
 * provisional.
 */
internal const val FLING_MAX_EXTRA_MOVES = 4
/** Delay between the far ring being reached and the first extra move, in seconds. Provisional pending device calibration. */
internal const val FLING_FIRST_INTERVAL_S = 0.09
/** Factor each interval grows by, which is what makes the fling decelerate. Provisional pending device calibration. */
internal const val FLING_INTERVAL_DECAY = 1.35
/** Interval beyond which the fling reads as a stutter rather than momentum, in seconds. Provisional pending device calibration. */
internal const val FLING_MAX_INTERVAL_S = 0.45
/**
 * Total time a fling may span once the far ring is reached, in seconds, wide enough to hold the
 * full [FLING_MAX_EXTRA_MOVES] schedule. Provisional pending device calibration.
 */
internal const val FLING_MAX_DURATION_S = 1.35

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
    fun velocityAlong(horizontal: Boolean, windowS: Double): Float? {
        if (count < 2) return null
        return velocityEndingAt(count - 1, horizontal, windowS)
    }

    private fun velocityEndingAt(endLogical: Int, horizontal: Boolean, windowS: Double): Float? {
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
        if (span <= 0.0) return null
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

/**
 * The extra moves a fling adds once the far ring is reached, and how long to wait before each of
 * them.
 */
internal data class FlingPlan(val key: Key, val intervals: List<Double>) {
    val steps: Int
        get() = intervals.size
}

/**
 * Plans the moves that follow the swipe's own move, for a contact that reached the far ring while
 * still moving at [signedSpeedAlongKey], positive when that velocity points the same way as [key].
 * The traverse across the ring is the distance gate of a fling, so only the speed decides how far
 * the momentum carries. Returns `null` when the contact was no longer moving fast enough to earn
 * one.
 */
internal fun planFling(signedSpeedAlongKey: Float, key: Key): FlingPlan? {
    if (signedSpeedAlongKey < FLING_MIN_SPEED) return null
    val steps =
        (floor((signedSpeedAlongKey - FLING_MIN_SPEED) / FLING_SPEED_PER_STEP).toInt() + 1)
            .coerceIn(1, FLING_MAX_EXTRA_MOVES)
    val intervals = mutableListOf<Double>()
    var cumulative = 0.0
    for (k in 0 until steps) {
        val interval = FLING_FIRST_INTERVAL_S * FLING_INTERVAL_DECAY.pow(k)
        if (interval > FLING_MAX_INTERVAL_S) break
        if (cumulative + interval > FLING_MAX_DURATION_S) break
        cumulative += interval
        intervals.add(interval)
    }
    if (intervals.isEmpty()) return null
    return FlingPlan(key, intervals)
}
