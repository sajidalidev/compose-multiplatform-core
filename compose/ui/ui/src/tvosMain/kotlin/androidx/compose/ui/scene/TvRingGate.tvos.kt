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
import kotlin.math.hypot

// Where a Siri Remote contact started, and what a contact that started on the outer ring of arrow
// buttons has to do before it counts as a swipe. Native tvOS moves focus several items for a
// straight swipe across the ring, keeps a circular drag around the ring for its own jog gesture,
// and never swipes for a click made with the finger resting on the ring.

/**
 * Radius, in the absolute clickpad space reported by [SiriRemoteTouchOracle] (|x| and |y| in
 * [0, 1]), beyond which a contact started on the outer ring of arrow buttons rather than on the
 * centre pad. Ring contacts between 0.60 and this radius are not gated by the origin but by the
 * press latch and by the distance gate, since they travel at most 0.25.
 */
internal const val CENTER_PAD_RADIUS = 0.75f

/**
 * Radius a contact that started on the ring has to reach before it may arm a swipe: a straight
 * ring to ring swipe passes through the centre, while a circular ring drag never drops below the
 * ring. This crossing radius is a provisional value pending device calibration: a ring click's
 * roll is expected to stay above it and below the distance gate, and the press latch covers the
 * rest. Measured on a second-generation Siri Remote: ring drags stay at r >= 0.94, centre swipe
 * origins start at r <= 0.33.
 */
internal const val RING_CROSSING_RADIUS = 0.50f

/**
 * Whether the contact at ([x], [y]) started on the outer ring of a remote that [hasRing]. A remote
 * without a ring has no such origin, so every contact of it is a plain swipe candidate.
 */
internal fun isRingOrigin(x: Float, y: Float, hasRing: Boolean): Boolean =
    hasRing && hypot(x, y) >= CENTER_PAD_RADIUS

/**
 * Whether a contact that started on the ring crossed into the centre pad, [minRadius] being the
 * smallest radius it was seen at since its origin was last anchored. Only a contact that crossed
 * may arm a swipe.
 */
internal fun ringOriginMayArm(minRadius: Float): Boolean = minRadius <= RING_CROSSING_RADIUS

/**
 * How far past the centre on the opposite side the last reported position of a contact has to be
 * for a traverse to count. The controller stream lags and snaps to (0, 0) at lift, so the far ring
 * itself is rarely observed: a real ring to ring traverse is last seen well inside the pad.
 * Device-measured 2026-09-18, provisional.
 */
internal const val FAR_SIDE_RADIUS = 0.30f

/**
 * Whether a contact that started on the ring at ([originX], [originY]) has travelled to the far side
 * of the pad at ([x], [y]), having crossed the centre pad on the way there, [minRadius] being the
 * smallest radius it was seen at since its origin was last anchored and [horizontal] telling which
 * axis its swipe went along. That traverse is the whole of a ring to ring swipe, so it is what earns
 * momentum; a drag around the ring keeps its radius and a swipe that stops in the centre never
 * arrives.
 */
internal fun hasReachedFarRing(
    originX: Float,
    originY: Float,
    x: Float,
    y: Float,
    minRadius: Float,
    horizontal: Boolean,
): Boolean {
    if (!ringOriginMayArm(minRadius)) return false
    val originAxis = if (horizontal) originX else originY
    val axis = if (horizontal) x else y
    if (originAxis * axis >= 0f) return false
    return abs(axis) >= FAR_SIDE_RADIUS
}
