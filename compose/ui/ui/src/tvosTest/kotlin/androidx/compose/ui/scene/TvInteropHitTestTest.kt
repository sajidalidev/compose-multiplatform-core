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

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.toDpRect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvInteropHitTestTest {
    // An interop view laid out at these root pixels.
    private val interopRect = Rect(left = 400f, top = 200f, right = 800f, bottom = 400f)

    @Test
    fun hitTestPointMapsBackToPlacedInteropView() {
        for (scale in listOf(1f, 2f)) {
            val screenDensity = Density(scale)
            // UIKitInteropElementHolder places the native view at root pixels / UIKit scale.
            val frame = interopRect.toDpRect(screenDensity)
            val center = DpOffset((frame.left + frame.right) / 2, (frame.top + frame.bottom) / 2)

            val position = uiKitPointToScenePixels(center, screenDensity)

            assertEquals(interopRect.center, position)
            assertTrue(interopRect.contains(position))
        }
    }

    @Test
    fun sceneDensityWouldMissThePlacedInteropView() {
        for (scale in listOf(1f, 2f)) {
            val screenDensity = Density(scale)
            val frame = interopRect.toDpRect(screenDensity)
            val center = DpOffset((frame.left + frame.right) / 2, (frame.top + frame.bottom) / 2)

            val position =
                uiKitPointToScenePixels(center, tvSceneDensity(screenDensity, fontScale = 1f))

            assertFalse(interopRect.contains(position))
        }
    }
}
