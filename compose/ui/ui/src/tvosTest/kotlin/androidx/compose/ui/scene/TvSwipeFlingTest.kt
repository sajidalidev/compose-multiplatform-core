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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TvSwipeFlingTest {
    @Test
    fun singleSampleHasNoVelocity() {
        val buffer = FlingSampleBuffer()
        assertNull(buffer.velocityAlong(horizontal = true, windowS = FLING_VELOCITY_WINDOW_S))
        buffer.add(0.1f, 0f, 1.0)
        assertNull(buffer.velocityAlong(horizontal = true, windowS = FLING_VELOCITY_WINDOW_S))
    }

    @Test
    fun peakSpeedIgnoresSamplesTooCloseInTime() {
        // A UIKit sample and a tick half a millisecond apart around a real movement.
        val buffer = FlingSampleBuffer()
        buffer.add(0.10f, 0f, 1.0000)
        buffer.add(0.15f, 0f, 1.0005)
        assertTrue(
            buffer.velocityAlong(horizontal = true, windowS = FLING_VELOCITY_WINDOW_S)!! > 50f
        )
        assertNull(
            buffer.velocityAlong(true, FLING_VELOCITY_WINDOW_S, minSpanS = PEAK_SPEED_MIN_SPAN_S)
        )
        // Once the window spans long enough, the speed is the real one.
        buffer.add(0.40f, 0f, 1.0300)
        val velocity =
            buffer.velocityAlong(true, FLING_VELOCITY_WINDOW_S, minSpanS = PEAK_SPEED_MIN_SPAN_S)!!
        assertEquals(10f, velocity, 0.01f)
    }

    @Test
    fun equalTimestampsHaveNoVelocity() {
        val buffer = FlingSampleBuffer()
        buffer.add(0f, 0f, 1.0)
        buffer.add(0.5f, 0f, 1.0)
        assertEquals(1, buffer.size)
        assertNull(buffer.velocityAlong(horizontal = true, windowS = FLING_VELOCITY_WINDOW_S))
    }

    @Test
    fun samplesThatGoBackInTimeAreDropped() {
        val buffer = FlingSampleBuffer()
        buffer.add(0f, 0f, 1.0)
        buffer.add(0.1f, 0f, 1.02)
        // The display link and UITouch stamp from the same clock but not from the same frame, so a
        // sample can arrive with a time the buffer has already passed.
        buffer.add(0.9f, 0f, 1.01)
        buffer.add(0.9f, 0f, 1.02)
        assertEquals(2, buffer.size)
        val velocity = buffer.velocityAlong(horizontal = true, windowS = 0.05)
        assertNotNull(velocity)
        assertEquals(5f, velocity, 1e-3f)
    }

    @Test
    fun onlyTrailingWindowSamplesCount() {
        val buffer = FlingSampleBuffer()
        buffer.add(0f, 0f, 0.0)
        buffer.add(0f, 0f, 1.0)
        buffer.add(0.5f, 0f, 1.02)
        val velocity = buffer.velocityAlong(horizontal = true, windowS = 0.05)
        assertNotNull(velocity)
        assertEquals(25f, velocity, 1e-3f)
    }

    @Test
    fun velocityKeepsItsSign() {
        val buffer = FlingSampleBuffer()
        buffer.add(0.5f, 0.5f, 1.0)
        buffer.add(0.4f, 0.4f, 1.02)
        val horizontal = buffer.velocityAlong(horizontal = true, windowS = 0.05)
        val vertical = buffer.velocityAlong(horizontal = false, windowS = 0.05)
        assertNotNull(horizontal)
        assertNotNull(vertical)
        assertEquals(-5f, horizontal, 1e-3f)
        assertEquals(-5f, vertical, 1e-3f)
    }

    @Test
    fun bufferKeepsOnlyTheNewestSamples() {
        val buffer = FlingSampleBuffer()
        for (i in 0 until 10) {
            buffer.add(i * 0.05f, 0f, i.toDouble())
        }
        assertEquals(8, buffer.size)
        // The two oldest samples are gone, so the span starts at t = 2 rather than at t = 0.
        val velocity = buffer.velocityAlong(horizontal = true, windowS = 100.0)
        assertNotNull(velocity)
        assertEquals(0.05f, velocity, 1e-3f)
    }

    @Test
    fun aStraightTraverseKeepsItsSpeed() {
        // A straight traverse of the clickpad, from the ring on one side to the ring on the other.
        val buffer = FlingSampleBuffer()
        buffer.add(-0.90f, 0f, 1.0)
        buffer.add(-0.60f, 0f, 1.02)
        buffer.add(-0.30f, 0f, 1.04)
        buffer.add(0f, 0f, 1.06)
        buffer.add(0.30f, 0f, 1.08)
        buffer.add(0.60f, 0f, 1.10)
        buffer.add(0.90f, 0f, 1.12)
        val velocity = buffer.velocityAlong(horizontal = true, windowS = FLING_VELOCITY_WINDOW_S)
        assertNotNull(velocity)
        assertEquals(15f, velocity, 0.05f)
    }

    @Test
    fun aRestOnTheEdgeHasNoVelocity() {
        // The window holds no second sample, so the finger was parked against the rail rather than
        // still travelling into it.
        val buffer = FlingSampleBuffer()
        buffer.add(0.30f, 0f, 1.0)
        buffer.add(0.80f, 0f, 1.0333)
        buffer.add(1.0f, 0f, 1.05)
        buffer.add(1.0f, 0f, 1.20)
        assertNull(buffer.velocityAlong(horizontal = true, windowS = FLING_VELOCITY_WINDOW_S))
    }

    @Test
    fun aRestInTheInteriorHasNoVelocity() {
        val buffer = FlingSampleBuffer()
        buffer.add(0.20f, 0f, 1.0)
        buffer.add(0.45f, 0f, 1.0167)
        buffer.add(0.60f, 0f, 1.0333)
        buffer.add(0.62f, 0f, 1.05)
        buffer.add(0.62f, 0f, 1.0667)
        buffer.add(0.62f, 0f, 1.0833)
        buffer.add(0.62f, 0f, 1.1167)
        val velocity = buffer.velocityAlong(horizontal = true, windowS = FLING_VELOCITY_WINDOW_S)
        assertNotNull(velocity)
        assertEquals(0f, velocity, 1e-3f)
    }

    @Test
    fun aReversalHasNegativeSignedSpeed() {
        val buffer = FlingSampleBuffer()
        buffer.add(0.20f, 0f, 1.0)
        buffer.add(0.50f, 0f, 1.0167)
        buffer.add(0.75f, 0f, 1.0333)
        buffer.add(0.70f, 0f, 1.05)
        buffer.add(0.60f, 0f, 1.0667)
        buffer.add(0.50f, 0f, 1.0833)
        val velocity = buffer.velocityAlong(horizontal = true, windowS = FLING_VELOCITY_WINDOW_S)
        assertNotNull(velocity)
        assertTrue(velocity < 0f, "the finger was moving back, velocity was $velocity")
    }

    @Test
    fun eachKeyTakesItsOwnSignOfTheAxisVelocity() {
        assertTrue(isHorizontalFlingKey(Key.DirectionLeft))
        assertTrue(isHorizontalFlingKey(Key.DirectionRight))
        assertFalse(isHorizontalFlingKey(Key.DirectionUp))
        assertFalse(isHorizontalFlingKey(Key.DirectionDown))
        // The oracle's x grows to the right and its y grows toward the top of the remote.
        assertEquals(4f, signedSpeedAlongKey(Key.DirectionRight, 4f))
        assertEquals(-4f, signedSpeedAlongKey(Key.DirectionRight, -4f))
        assertEquals(4f, signedSpeedAlongKey(Key.DirectionLeft, -4f))
        assertEquals(-4f, signedSpeedAlongKey(Key.DirectionLeft, 4f))
        assertEquals(4f, signedSpeedAlongKey(Key.DirectionUp, 4f))
        assertEquals(-4f, signedSpeedAlongKey(Key.DirectionUp, -4f))
        assertEquals(4f, signedSpeedAlongKey(Key.DirectionDown, -4f))
        assertEquals(-4f, signedSpeedAlongKey(Key.DirectionDown, 4f))
    }

    @Test
    fun aReversalOfTheContactEndsTheMomentum() {
        // The finger that started the momentum turned back on the far ring.
        val buffer = FlingSampleBuffer()
        buffer.add(0.90f, 0f, 1.0)
        buffer.add(0.80f, 0f, 1.02)
        buffer.add(0.70f, 0f, 1.04)
        val velocity = buffer.velocityAlong(horizontal = true, windowS = FLING_VELOCITY_WINDOW_S)
        assertNotNull(velocity)
        assertTrue(
            signedSpeedAlongKey(Key.DirectionRight, velocity) < -FLING_REVERSAL_SPEED,
            "the finger turned back, signed speed was $velocity",
        )
    }
}
