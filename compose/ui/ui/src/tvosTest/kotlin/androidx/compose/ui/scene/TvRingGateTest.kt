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

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvRingGateTest {
    @Test
    fun aContactOnTheRingStartedOnTheRing() {
        assertTrue(isRingOrigin(0.90f, 0f, hasRing = true))
        // The boundary belongs to the ring.
        assertTrue(isRingOrigin(CENTER_PAD_RADIUS, 0f, hasRing = true))
        assertTrue(isRingOrigin(0.60f, 0.60f, hasRing = true))
    }

    @Test
    fun aContactOnTheCentrePadDidNot() {
        assertFalse(isRingOrigin(0.33f, 0f, hasRing = true))
        assertFalse(isRingOrigin(CENTER_PAD_RADIUS - 0.01f, 0f, hasRing = true))
        assertFalse(isRingOrigin(0f, 0f, hasRing = true))
    }

    @Test
    fun aRemoteWithoutARingHasNoRingOrigin() {
        assertFalse(isRingOrigin(0.99f, 0.99f, hasRing = false))
    }

    @Test
    fun aRingContactThatCrossedTheCentreMayArm() {
        // A straight swipe across the ring passes through the centre pad.
        assertTrue(ringOriginMayArm(0.33f))
        assertTrue(ringOriginMayArm(RING_CROSSING_RADIUS))
    }

    @Test
    fun aRingContactThatStayedOnTheRingMayNot() {
        // A circular drag around the ring never drops below it.
        assertFalse(ringOriginMayArm(0.94f))
        assertFalse(ringOriginMayArm(RING_CROSSING_RADIUS + 0.01f))
    }

    @Test
    fun aTraverseToTheOppositeRingArrives() {
        assertTrue(
            hasReachedFarRing(
                originX = -0.90f,
                originY = 0f,
                x = 0.95f,
                y = 0f,
                minRadius = 0.10f,
                horizontal = true,
            )
        )
        // The stream lags at lift, so the far side is usually last seen well inside the pad. The
        // boundary counts as reached.
        assertTrue(
            hasReachedFarRing(
                originX = -0.90f,
                originY = 0f,
                x = FAR_SIDE_RADIUS,
                y = 0f,
                minRadius = 0.10f,
                horizontal = true,
            )
        )
        assertTrue(
            hasReachedFarRing(
                originX = 0f,
                originY = 0.90f,
                x = 0f,
                y = -0.90f,
                minRadius = 0.05f,
                horizontal = false,
            )
        )
    }

    @Test
    fun theRingOnTheSameSideIsNotTheFarRing() {
        assertFalse(
            hasReachedFarRing(
                originX = -0.90f,
                originY = 0f,
                x = -0.80f,
                y = 0f,
                minRadius = 0.10f,
                horizontal = true,
            )
        )
    }

    @Test
    fun aDragAroundTheRingNeverArrives() {
        // It reaches the opposite side of the ring, but by going around rather than across.
        assertFalse(
            hasReachedFarRing(
                originX = -0.90f,
                originY = 0f,
                x = 0.90f,
                y = 0f,
                minRadius = 0.94f,
                horizontal = true,
            )
        )
    }

    @Test
    fun aSwipeThatStopsInTheCentreNeverArrives() {
        assertFalse(
            hasReachedFarRing(
                originX = -0.90f,
                originY = 0f,
                x = 0f,
                y = 0f,
                minRadius = 0.10f,
                horizontal = true,
            )
        )
        assertFalse(
            hasReachedFarRing(
                originX = -0.90f,
                originY = 0f,
                x = FAR_SIDE_RADIUS - 0.01f,
                y = 0f,
                minRadius = 0.10f,
                horizontal = true,
            )
        )
    }
}
