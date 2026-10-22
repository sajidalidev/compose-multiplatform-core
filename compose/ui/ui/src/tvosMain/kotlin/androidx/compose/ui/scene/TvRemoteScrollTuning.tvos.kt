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
/**
 * Decay time constant of the momentum of a swipe that follows a momentum swipe in its chain. With
 * [MOMENTUM_TAU_S] a burst delivers its rows in about 150 ms while the swipes of a quick run come
 * every 390 ms or so on device: focus stood still two thirds of the time, felt as a hitch at every
 * swipe. Spread over about 300 ms, the rows of one swipe run until the next one takes over, with
 * what is left carried over to it. The native UI keeps a run moving by entering fast scrolling,
 * which only runs on containers with Modifier.remoteFastScroll. Provisional.
 */
internal const val MOMENTUM_CHAIN_TAU_S = 0.16
/** Bias added to the row count to get the asymptote of the momentum curve (n = rows + bias). */
internal const val MOMENTUM_ROW_BIAS = 0.5
/** Travel in pad units below which a swipe moves exactly one row, whatever its start. Measured. */
internal const val MOMENTUM_SINGLE_ROW_TRAVEL = 0.6f
/**
 * How far behind the pad centre, along the swipe axis and against the swipe direction, a contact
 * must start for its swipe to earn more than one row, in pad units. The native data could not
 * separate a threshold anywhere between 0.04 and 0.58. On an Apple TV 4K with a first generation
 * Siri Remote, downward swipes start about 0.45 above the centre and upward ones about 0.9 below
 * it, and the native UI gives both momentum, so the threshold sits below both.
 */
internal const val MOMENTUM_MIN_START_OFFSET = 0.25f
/**
 * Pad travel along the swipe axis for each further focus move while the finger stays down after the
 * first move, in pad units. The native focus engine follows a gliding finger item by item; the
 * distance per item scales with the item's size there, this is a single figure, equal to the gate
 * of the first move. Provisional.
 */
internal const val DRAG_STEP_TRAVEL = 0.30f
/**
 * Travel back from the furthest point a dispatched contact reached, in pad units, at which the
 * finger is taken to have turned: the contact starts over from that furthest point, in the opposite
 * direction, as the native focus engine does under a finger that comes back. Provisional.
 */
internal const val DRAG_REVERSAL_TRAVEL = 0.15f
/**
 * How far ahead of the tick the momentum curve is read, in seconds: half a frame at 60 Hz, so a
 * step due just after a tick goes out on that tick instead of a whole frame later. Read on the tick
 * itself, the steps trail the fitted curve by a steady 15 to 25 ms on device.
 */
internal const val MOMENTUM_TICK_LOOKAHEAD_S = 0.008
/**
 * Travel along the dominant axis at which a contact that never armed still swipes once, one row, at
 * lift. Provisional.
 */
internal const val SWIPE_LIFT_DISTANCE = 0.20f
/**
 * Intercept of the rows to travel fit, in rows. The travel alone runs the ladder 2, 3, 4… from
 * [MOMENTUM_SINGLE_ROW_TRAVEL] on, one row per 0.25 pad unit as the fitted slope says: a moderate
 * swipe moves two or three rows, as on the native UI side by side on device. The native fit itself
 * (0.31) gives three rows at the single-row limit, which moves more items than the native UI for a
 * moderate swipe; what a fast flick earns beyond that comes from its speed ([MOMENTUM_SPEED_MIN]).
 * Provisional.
 */
internal const val MOMENTUM_ROWS_INTERCEPT = -0.69f
/** Slope of the rows to travel fit, in rows per pad unit. Fitted, provisional. */
internal const val MOMENTUM_ROWS_PER_TRAVEL = 3.93f
/**
 * Peak finger speed along the swipe axis, in pad units per second, from which the speed of a swipe
 * sizes its momentum: a swipe this fast earns two rows, and one more row for every
 * [MOMENTUM_SPEED_PER_ROW] beyond, up to [MOMENTUM_MAX_ROWS]. A swipe earns the larger of the rows
 * its travel and its speed give. The native UI carries a fast flick much further than a moderate
 * swipe of the same length, which the travel alone cannot tell apart. A traverse ends between 9 and
 * 29 units per second on the device, so the scale spans that range. Provisional, pending a
 * calibration against the speeds the MOMENTUM plan debug line reports.
 */
internal const val MOMENTUM_SPEED_MIN = 8f
/** Peak speed in pad units per second for each row beyond two. See [MOMENTUM_SPEED_MIN]. */
internal const val MOMENTUM_SPEED_PER_ROW = 4f
/** Most rows a single swipe moves focus. Measured range is 3 to 7. */
internal const val MOMENTUM_MAX_ROWS = 7
/**
 * Most rows a chained swipe earns once the chain boost is applied. Measured maximum. The rows
 * carried over from the burst it replaces come on top.
 */
internal const val MOMENTUM_MAX_ROWS_CHAINED = 8
/**
 * Longest time between two steps of a burst, in seconds: the tail of the curve runs slower, most of
 * all for [MOMENTUM_CHAIN_TAU_S]. Kept under the spacing that tells the steps of one run from
 * separate presses, as an app may time them (the steady key repeat is [KEY_REPEAT_STEADY_MS],
 * separate presses come about 250 ms apart): on device a twelfth of the steps of a chained burst
 * came up to 250 ms apart without this. Provisional.
 */
internal const val MOMENTUM_MAX_STEP_INTERVAL_S = 0.12
/** Most momentum steps emitted on one tick. Provisional, drop to 1 if steps get lost. */
internal const val MOMENTUM_MAX_STEPS_PER_TICK = 2
/** Retries of a momentum step when the scene is busy. Provisional guess. */
internal const val MOMENTUM_BUSY_RETRIES = 1

/** Longest gap between a lift and the next touch for swipes to still chain. Fitted. */
internal const val CHAIN_MAX_GAP_S = 1.0
/**
 * Ratio of the displacement along the axis of a series of swipes to the displacement across it at
 * which a contact that continues the series, toward its direction, swipes in that direction: up to
 * 45 degrees off the axis. An isolated swipe still needs SWIPE_AXIS_DOMINANCE. Quick flicks of a
 * run drift off the axis: on device a fifth of them, 27 to 45 degrees off, never armed under the
 * isolated rule. A stroke back against the series, the finger returning between two flicks, keeps
 * the isolated rule. Provisional.
 */
internal const val SERIES_AXIS_DOMINANCE = 1f
/**
 * Travel in pad units from which a quick swipe that follows a momentum swipe in its chain earns
 * momentum, wherever it starts: the distance at which a contact that never armed still swipes at
 * lift ([SWIPE_LIFT_DISTANCE]). Repeated quick flicks are short strokes that start wherever the
 * previous one left the finger, and the briefest are sampled once or twice before the pad goes
 * empty, so their travel is known only up to that last sample. Held to the gates of an isolated
 * swipe, a fifth of them moved one row in the middle of a run of seven or eight, felt on device as
 * a hitch. Provisional.
 */
internal const val CHAIN_SINGLE_ROW_TRAVEL = 0.20f
/**
 * Longest contact, from its BEGAN to its last sample, that counts as quick in a chain of momentum
 * swipes whatever its measured speed: the briefest flicks are sampled too sparsely for a speed. The
 * quick flicks of a run last 30 to 75 ms on device. Only a swipe that follows a momentum swipe is
 * judged by it. Provisional.
 */
internal const val CHAIN_QUICK_CONTACT_S = 0.08
/**
 * Row multiplier of a momentum swipe that follows the previous one in the same direction within
 * [CHAIN_BOOST_MAX_GAP_S], up to [MOMENTUM_MAX_ROWS_CHAINED]; a single-row swipe stays one row. On
 * the native UI the rows of each swipe barely grow along a chain (about 1.05x measured): a chain
 * goes far because its third swipe enters fast scrolling, which only runs on containers with
 * Modifier.remoteFastScroll. The boost lets a quick chain carry further where fast scrolling does
 * not run. Provisional, felt on device.
 */
internal const val CHAIN_BOOST = 1.3f
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
/** How long fast scroll waits for more content after the container delivered less than asked, before the end is final, in seconds. Provisional. */
internal const val FAST_EDGE_WAIT_S = 0.4
/** Cap on the dp requested per frame while fast scroll waits at an edge, so no backlog builds up. Provisional. */
internal const val FAST_EDGE_WAIT_MAX_DELTA_DP = 40f
