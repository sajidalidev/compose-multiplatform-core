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

import androidx.compose.ui.unit.Density
import kotlin.test.Test
import kotlin.test.assertEquals

class TvSceneDensityTest {
    @Test
    fun fullHdScreenUsesTvDensity() {
        val density = tvSceneDensity(Density(1f), fontScale = 1.25f)
        assertEquals(960f, 1920f / density.density)
        assertEquals(540f, 1080f / density.density)
        assertEquals(1.25f, density.fontScale)
    }

    @Test
    fun ultraHdScreenKeepsTvDensity() {
        val density = tvSceneDensity(Density(2f), fontScale = 1.5f)
        assertEquals(960f, 3840f / density.density)
        assertEquals(540f, 2160f / density.density)
        assertEquals(1.5f, density.fontScale)
    }
}
