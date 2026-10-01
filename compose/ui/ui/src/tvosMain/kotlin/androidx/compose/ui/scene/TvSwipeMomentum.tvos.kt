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
import kotlin.math.exp
import kotlin.math.floor

/** Rows a swipe that travelled [travel] pad units along its axis moves focus, its own move included. */
internal fun momentumRowsForTravel(travel: Float): Int {
    if (travel < MOMENTUM_SINGLE_ROW_TRAVEL) return 1
    val rows = floor(MOMENTUM_ROWS_INTERCEPT + MOMENTUM_ROWS_PER_TRAVEL * travel + 0.5f).toInt()
    return rows.coerceIn(2, MOMENTUM_MAX_ROWS)
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
) {
    var rows: Int = rows
        private set
    var emitted: Int = 1
        private set

    /** Raises the row count to [rows]. Never lowers it and never goes below [emitted]. */
    fun raiseRows(rows: Int) {
        this.rows = maxOf(this.rows, rows, emitted)
    }

    fun position(now: Double): Double {
        val n = rows + MOMENTUM_ROW_BIAS
        return n - (n - 1.0) * exp(-(now - startedAt) / tau)
    }

    fun stepsDue(now: Double): Int =
        (floor(position(now)).toInt().coerceAtMost(rows) - emitted).coerceAtLeast(0)

    fun onStepEmitted() {
        emitted++
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
    var focusMoves: Int = 0
        private set
    private var lastLiftAt: Double? = null
    private var currentGap: Double = Double.MAX_VALUE

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
            currentGap = Double.MAX_VALUE
        } else {
            currentGap = gap
        }
        lastLiftAt = null
        swipes++
        return swipes
    }

    /** CHAIN_BOOST when the current swipe is at least the second of the chain and its gap was at most CHAIN_BOOST_MAX_GAP_S, else 1f. */
    fun currentBoost(): Float =
        if (swipes >= 2 && currentGap <= CHAIN_BOOST_MAX_GAP_S) CHAIN_BOOST else 1f

    fun onFocusMoved(count: Int = 1) {
        focusMoves += count
    }

    fun onLift(now: Double) {
        lastLiftAt = now
    }

    fun shouldEnterFastMode(): Boolean = focusMoves >= FAST_ENTRY_FOCUS_MOVES

    fun reset() {
        key = null
        swipes = 0
        focusMoves = 0
        lastLiftAt = null
        currentGap = Double.MAX_VALUE
    }
}

/** Delay before repeat number [repeatIndex] (0 based) of a held directional key: 500, 200, 150, then 100 ms. */
internal fun tvDirectionalKeyRepeatDelayMs(repeatIndex: Int): Long =
    if (repeatIndex in KEY_REPEAT_DELAYS_MS.indices) KEY_REPEAT_DELAYS_MS[repeatIndex] else KEY_REPEAT_STEADY_MS
