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
import androidx.compose.ui.input.remote.RemoteSwipe
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor

/**
 * Rows a swipe moves focus, its own move included. [startOffset] is how far behind the centre the
 * origin lies along the swipe axis, against the swipe direction (negative when it lies past the
 * centre), and `null` when it is unknown, as on the relative fallback path, which earns one row.
 *
 * A swipe earns multi-row momentum when it starts at least [MOMENTUM_MIN_START_OFFSET] behind the
 * centre and travels at least [MOMENTUM_SINGLE_ROW_TRAVEL], or, when it [followsMomentumSwipe] in
 * its chain and is quick (at least [MOMENTUM_SPEED_MIN] fast, or a [quickContact]), when it travels
 * at least [CHAIN_SINGLE_ROW_TRAVEL] wherever it starts. Every other swipe moves one row. A swipe
 * with momentum moves the larger of the rows its [travel] and its [peakSpeed] (pad units per second
 * along the swipe axis, 0 when unknown) give, from two to [MOMENTUM_MAX_ROWS].
 */
internal fun momentumRowsFor(
    startOffset: Float?,
    travel: Float,
    peakSpeed: Float = 0f,
    followsMomentumSwipe: Boolean = false,
    quickContact: Boolean = false,
): Int {
    if (startOffset == null) return 1
    if (followsMomentumSwipe && (peakSpeed >= MOMENTUM_SPEED_MIN || quickContact)) {
        // A quick swipe that keeps a chain of momentum swipes going: the finger repeats shorter
        // strokes from wherever the previous one left it, so only a shorter travel is asked of it.
        if (travel < CHAIN_SINGLE_ROW_TRAVEL) return 1
    } else {
        if (startOffset < MOMENTUM_MIN_START_OFFSET) return 1
        if (travel < MOMENTUM_SINGLE_ROW_TRAVEL) return 1
    }
    val travelRows =
        floor(MOMENTUM_ROWS_INTERCEPT + MOMENTUM_ROWS_PER_TRAVEL * travel + 0.5f).toInt()
    return maxOf(travelRows, speedRowsFor(peakSpeed)).coerceIn(2, MOMENTUM_MAX_ROWS)
}

/** Rows a swipe earns from its peak speed alone, 0 below [MOMENTUM_SPEED_MIN]. */
internal fun speedRowsFor(peakSpeed: Float): Int =
    if (peakSpeed < MOMENTUM_SPEED_MIN) {
        0
    } else {
        2 + floor((peakSpeed - MOMENTUM_SPEED_MIN) / MOMENTUM_SPEED_PER_ROW).toInt()
    }

/**
 * The displacement [dx], [dy] along the axis of [key], positive toward [key], in the oracle's space
 * where y is positive toward the top of the remote.
 */
internal fun displacementAlongKey(key: Key, dx: Float, dy: Float): Float =
    signedSpeedAlongKey(key, if (isHorizontalFlingKey(key)) dx else dy)

/**
 * The key a contact displaced by [dx], [dy] swipes in when it continues a series of swipes in
 * [seriesKey], in the oracle's space: [seriesKey] when the displacement runs toward it along its
 * axis by at least [SERIES_AXIS_DOMINANCE] times the displacement across, `null` otherwise or
 * without a series.
 */
internal fun seriesSwipeKey(seriesKey: Key?, dx: Float, dy: Float): Key? {
    if (seriesKey == null) return null
    val along = displacementAlongKey(seriesKey, dx, dy)
    val across = abs(if (isHorizontalFlingKey(seriesKey)) dy else dx)
    return if (along > 0f && along >= SERIES_AXIS_DOMINANCE * across) seriesKey else null
}

/**
 * The start offset of a contact whose origin component along the axis of [key] is [originAxis], in
 * the oracle's space where y is positive toward the top of the remote.
 */
internal fun swipeStartOffset(key: Key, originAxis: Float): Float =
    -signedSpeedAlongKey(key, originAxis)

/**
 * Whether a contact that never armed is a swipe at lift: its displacement from the origin, [dx] and
 * [dy], reaches [SWIPE_LIFT_DISTANCE] on the dominant axis, that axis is at least [dominance] times
 * the other, and [elapsedS] is within [maxDurationS].
 */
internal fun isLiftArmSwipe(
    dx: Float,
    dy: Float,
    elapsedS: Double,
    maxDurationS: Double,
    dominance: Float,
): Boolean {
    val absDx = kotlin.math.abs(dx)
    val absDy = kotlin.math.abs(dy)
    val dominant = maxOf(absDx, absDy)
    val other = minOf(absDx, absDy)
    return dominant >= SWIPE_LIFT_DISTANCE && dominant >= dominance * other && elapsedS <= maxDurationS
}

/** Applies the chain boost: round(rows * boost).coerceAtMost(MOMENTUM_MAX_ROWS_CHAINED), never below rows. */
internal fun boostedRows(rows: Int, boost: Float): Int {
    val boosted = floor(rows * boost + 0.5f).toInt().coerceAtMost(MOMENTUM_MAX_ROWS_CHAINED)
    return maxOf(rows, boosted)
}

/**
 * The step schedule of one swipe. Step 1 is the swipe's own move at [startedAt]; the position is
 * p(t) = n - (n - 1) * exp(-(t - startedAt) / tau) with n = rows + MOMENTUM_ROW_BIAS, so p(startedAt) = 1,
 * and a step is owed each time p crosses an integer up to rows.
 */
internal class TvMomentumBurst(
    val key: Key,
    val startedAt: Double,
    rows: Int,
    private val tau: Double = MOMENTUM_TAU_S,
    /** Rows left unemitted by the burst this one replaced, owed on top of this swipe's own. */
    val carried: Int = 0,
    /** What the key events of the burst carry: the swipe of the contact that started it. */
    val swipe: RemoteSwipe? = null,
) {
    var rows: Int = rows + carried
        private set
    var emitted: Int = 1
        private set

    /**
     * Raises the row count to [rows] of this swipe plus [carried]. Never lowers it and never goes
     * below [emitted].
     */
    fun raiseRows(rows: Int) {
        this.rows = maxOf(this.rows, rows + carried, emitted)
    }

    /** The rows still owed. */
    val remaining: Int
        get() = (rows - emitted).coerceAtLeast(0)

    fun position(now: Double): Double {
        val n = rows + MOMENTUM_ROW_BIAS
        return n - (n - 1.0) * exp(-(now - startedAt) / tau)
    }

    fun stepsDue(now: Double): Int =
        (floor(position(now)).toInt().coerceAtMost(rows) - emitted).coerceAtLeast(0)

    /**
     * Like [stepsDue], but a step still owed goes out once [MOMENTUM_MAX_STEP_INTERVAL_S] passed
     * since [lastStepAt], the last step out, however slowly the tail of the curve runs.
     */
    fun stepsDue(now: Double, lastStepAt: Double): Int {
        val due = stepsDue(now)
        return if (due == 0 && remaining > 0 && now - lastStepAt >= MOMENTUM_MAX_STEP_INTERVAL_S) {
            1
        } else {
            due
        }
    }

    /**
     * Counts one more step as emitted. A step the finger paid for by travelling can go out ahead of
     * the curve, so the rows follow the emitted count rather than capping it.
     */
    fun onStepEmitted() {
        emitted++
        if (rows < emitted) rows = emitted
    }

    /** Freezes the burst at the steps already emitted. */
    fun stop() {
        rows = emitted
    }

    val isFinished: Boolean
        get() = emitted >= rows
}

/** Same direction swipes whose lift to touch gap stays under CHAIN_MAX_GAP_S. Survives new contacts. */
internal class TvSwipeChainTracker {
    var key: Key? = null
        private set
    var swipes: Int = 0
        private set
    /** Every focus move of the chain, logged only. */
    var focusMoves: Int = 0
        private set
    /** Focus moves of the momentum swipes of the chain, the count that enters fast scrolling. */
    var momentumMoves: Int = 0
        private set
    /** Momentum swipes of the chain, each counted once when it becomes a momentum swipe. */
    var momentumSwipes: Int = 0
        private set
    private var currentIsMomentum = false
    private var currentUncommittedMoves = 0
    /** Whether the current swipe continues the chain right after a momentum swipe. */
    var followsMomentumSwipe: Boolean = false
        private set

    private var lastLiftAt: Double? = null
    /** Seconds between the previous lift and the touch of the current swipe, [Double.MAX_VALUE] when it starts a chain. */
    var currentGap: Double = Double.MAX_VALUE
        private set

    /**
     * Call when a swipe dispatches. Starts a new chain if the key differs, no lift is recorded, or
     * the gap is too long. Returns the 1 based index of the swipe in the chain.
     */
    fun onSwipeDispatched(key: Key, touchBeganAt: Double): Int {
        val liftAt = lastLiftAt
        val gap = if (liftAt == null) Double.MAX_VALUE else touchBeganAt - liftAt
        if (this.key != key || liftAt == null || gap > CHAIN_MAX_GAP_S) {
            this.key = key
            swipes = 0
            focusMoves = 0
            momentumMoves = 0
            momentumSwipes = 0
            currentGap = Double.MAX_VALUE
            followsMomentumSwipe = false
        } else {
            currentGap = gap
            followsMomentumSwipe = currentIsMomentum
        }
        lastLiftAt = null
        currentIsMomentum = false
        currentUncommittedMoves = 0
        swipes++
        return swipes
    }

    /**
     * The direction of the chain a contact that began at [touchBeganAt] would continue, `null` when
     * its gap to the last lift is too long or no swipe of the chain lifted yet.
     */
    fun continuingKey(touchBeganAt: Double): Key? {
        val liftAt = lastLiftAt ?: return null
        return if (touchBeganAt - liftAt <= CHAIN_MAX_GAP_S) key else null
    }

    /** CHAIN_BOOST when the current swipe is at least the second of the chain and its gap was at most CHAIN_BOOST_MAX_GAP_S, else 1f. */
    fun currentBoost(boost: Float = CHAIN_BOOST): Float =
        if (swipes >= 2 && currentGap <= CHAIN_BOOST_MAX_GAP_S) boost else 1f

    /** Counts moves of the current swipe: toward the momentum count only once it is a momentum swipe. */
    fun onFocusMoved(count: Int = 1) {
        focusMoves += count
        if (currentIsMomentum) momentumMoves += count else currentUncommittedMoves += count
    }

    /**
     * Call with the planned rows of the current swipe whenever they change. Once they exceed one the
     * swipe is a momentum swipe, and the moves it made as a single row so far count as well.
     */
    fun onSwipeRows(rows: Int) {
        if (rows <= 1 || currentIsMomentum) return
        currentIsMomentum = true
        momentumSwipes++
        momentumMoves += currentUncommittedMoves
        currentUncommittedMoves = 0
    }

    fun onLift(now: Double) {
        lastLiftAt = now
    }

    /** True when the current swipe is a momentum swipe and the chain's momentum swipes and moves reach their thresholds. */
    fun shouldEnterFastMode(): Boolean =
        currentIsMomentum &&
            momentumSwipes >= FAST_ENTRY_MOMENTUM_SWIPES &&
            momentumMoves >= FAST_ENTRY_MOMENTUM_MOVES

    fun reset() {
        key = null
        swipes = 0
        focusMoves = 0
        momentumMoves = 0
        momentumSwipes = 0
        currentIsMomentum = false
        currentUncommittedMoves = 0
        lastLiftAt = null
        currentGap = Double.MAX_VALUE
        followsMomentumSwipe = false
    }
}

/** Delay before repeat number [repeatIndex] (0 based) of a held directional key: 500, 200, 150, then 100 ms. */
internal fun tvDirectionalKeyRepeatDelayMs(repeatIndex: Int): Long =
    if (repeatIndex in KEY_REPEAT_DELAYS_MS.indices) KEY_REPEAT_DELAYS_MS[repeatIndex] else KEY_REPEAT_STEADY_MS
