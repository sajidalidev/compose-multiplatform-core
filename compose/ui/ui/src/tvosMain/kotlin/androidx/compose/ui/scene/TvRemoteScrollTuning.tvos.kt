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

// Tuning values for Siri Remote swipe momentum, directional key repeat and fast scrolling. The
// behaviour they model was measured on native tvOS lists. Distances are in scene dp (a 960x540 dp
// canvas, so one tvOS point is 0.5 dp), times in seconds, and pad units are the clickpad's
// normalised coordinates. Values marked provisional are guesses or rough fits pending device
// calibration.

/** Decay time constant of the momentum position curve. Fitted on UITableView, provisional. */
internal const val MOMENTUM_TAU_S = 0.07
/** Bias added to the row count to get the asymptote of the momentum curve (n = rows + bias). */
internal const val MOMENTUM_ROW_BIAS = 0.5
/** Travel in pad units below which a swipe moves exactly one row, whatever its start. Measured. */
internal const val MOMENTUM_SINGLE_ROW_TRAVEL = 0.6f
/**
 * How far behind the pad centre, along the swipe axis and against the swipe direction, a contact
 * must start for its swipe to earn more than one row, in pad units. Provisional: this is the
 * conservative end of a range (0.04 to 0.58 could not be separated in the native data) and must be
 * tuned on device.
 */
internal const val MOMENTUM_MIN_START_OFFSET = 0.60f
/**
 * Travel along the dominant axis at which a contact that never armed still swipes once, one row, at
 * lift. Provisional.
 */
internal const val SWIPE_LIFT_DISTANCE = 0.20f
/** Intercept of the rows to travel fit, in rows. Fitted, provisional. */
internal const val MOMENTUM_ROWS_INTERCEPT = 0.31f
/** Slope of the rows to travel fit, in rows per pad unit. Fitted, provisional. */
internal const val MOMENTUM_ROWS_PER_TRAVEL = 3.93f
/** Most rows a single swipe moves focus. Measured range is 3 to 7. */
internal const val MOMENTUM_MAX_ROWS = 7
/** Most rows a chained swipe moves focus once the chain boost is applied. Measured maximum. */
internal const val MOMENTUM_MAX_ROWS_CHAINED = 8
/** Most momentum steps emitted on one tick. Provisional, drop to 1 if steps get lost. */
internal const val MOMENTUM_MAX_STEPS_PER_TICK = 2
/** Retries of a momentum step when the scene is busy. Provisional guess. */
internal const val MOMENTUM_BUSY_RETRIES = 1

/** Longest gap between a lift and the next touch for swipes to still chain. Fitted. */
internal const val CHAIN_MAX_GAP_S = 1.0
/**
 * Row multiplier of a chained swipe that follows quickly. Native evidence is about 1.05x, so the
 * boost stays off (1.0) until it is re-measured; the code path and the chain tracker remain.
 */
internal const val CHAIN_BOOST = 1.0f
/** Longest lift to touch gap that earns [CHAIN_BOOST]. Measured, low confidence. */
internal const val CHAIN_BOOST_MAX_GAP_S = 0.45

/**
 * Focus moves of the momentum swipes (more than one row) within one chain that switch to fast
 * scrolling. Single row swipes do not count. Measured and fitted.
 */
internal const val FAST_ENTRY_MOMENTUM_MOVES = 11
/**
 * Momentum swipes within one same direction chain needed before swipe entry to fast scrolling, on
 * top of [FAST_ENTRY_MOMENTUM_MOVES]. Native tvOS entered on the third full swipe in device measurements.
 */
internal const val FAST_ENTRY_MOMENTUM_SWIPES = 3
/** Key repeat focus moves of a hold that switch to fast scrolling, the mode replacing the next tick. Measured. */
internal const val FAST_HOLD_ENTRY_MOVES = 10

/**
 * Whether a held directional key repeats on the native cadence, [KEY_REPEAT_DELAYS_MS] then
 * [KEY_REPEAT_STEADY_MS]. When `false` it repeats after 500 ms and then every 50 ms, as before.
 */
internal const val KEY_REPEAT_NATIVE_CADENCE = true
/** Delays before the first three repeats of a held directional key, in milliseconds. Measured 510, 210, 140. */
internal val KEY_REPEAT_DELAYS_MS = longArrayOf(500L, 200L, 150L)
/** Delay between later repeats of a held directional key, in milliseconds. Measured. */
internal const val KEY_REPEAT_STEADY_MS = 100L

/** Decay time constant of fast scroll coasting. Fitted, high confidence. */
internal const val FAST_TAU_S = 0.521
/** Velocity fast scroll starts at when entered by a swipe, in dp per second. Provisional, middle of the measured range. */
internal const val FAST_SWIPE_ENTRY_VELOCITY_DP_S = 750f
/** Velocity fast scroll starts at when entered by a hold, in dp per second. Measured. */
internal const val FAST_HOLD_START_VELOCITY_DP_S = 900f
/** Acceleration while a directional key stays held in fast scroll, in dp per second squared. Measured. */
internal const val FAST_HOLD_ACCEL_DP_S2 = 1200f
/** Decay time constant after a held key is released in fast scroll. Measured. */
internal const val FAST_HOLD_BRAKE_TAU_S = 0.105
/** Scroll distance per pad unit of finger travel in fast scroll, in dp. Fitted roughly, provisional. */
internal const val FAST_PAN_GAIN_DP_PER_UNIT = 450f
/** Minimum finger speed for a flick release to add velocity, in pad units per second. Provisional guess. */
internal const val FAST_FLICK_MIN_SPEED = 2.0f
/** Cap on the fast scroll velocity, in dp per second. Provisional, native shows no cap. */
internal const val FAST_MAX_VELOCITY_DP_S = 25000f
/** Longest a held key may drive fast scroll before the hold is released, in seconds, in case its release never arrives. Provisional guess. */
internal const val FAST_HOLD_MAX_S = 30.0
/** Speed below which fast scroll exits, in dp per second. Fitted. */
internal const val FAST_EXIT_SPEED_DP_S = 5f
/** How long a finger must rest on its anchor to stop fast scroll. Provisional. */
internal const val FAST_REST_STOP_S = 0.05
/** Largest frame time fast scroll integrates in one step. Provisional guess. */
internal const val FAST_MAX_FRAME_DT_S = 0.1
/** Frames spent retrying to focus an item when fast scroll exits. Provisional guess. */
internal const val FAST_EXIT_FOCUS_RETRY_FRAMES = 3
/** Frames spent retrying the lookup of a fast scroll container at the lift of a swipe, while focus invalidation is pending. Provisional guess. */
internal const val FAST_ENTRY_LOOKUP_RETRY_FRAMES = 3

/** Scroll distance per pad unit used when the scene gives no better figure, in dp. Derived from the existing gates, 40 dp over 0.30. */
internal const val FALLBACK_DP_PER_PAD_UNIT = 133f
