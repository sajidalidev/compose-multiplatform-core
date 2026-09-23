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
package androidx.compose.foundation.gestures

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class TvBringIntoViewSpecTest {
    @Test
    fun defaultKeepsFocusedCardAwayFromViewportEdge() = runTest {
        val recomposer = Recomposer(coroutineContext)
        val composition = Composition(UnitApplier(), recomposer)
        try {
            lateinit var spec: BringIntoViewSpec
            composition.setContent { spec = LocalBringIntoViewSpec.current }
            // A 150px card at the far right of a 1000px row should move to the TV pivot.
            assertEquals(550f, spec.calculateScrollDistance(850f, 150f, 1000f))
            // Moving back to an already visible first row should still scroll toward the top.
            assertEquals(-250f, spec.calculateScrollDistance(50f, 200f, 1000f))
            // Large cards must remain fully visible rather than extend past the far edge.
            assertEquals(100f, spec.calculateScrollDistance(200f, 900f, 1000f))
        } finally {
            composition.dispose()
            recomposer.cancel()
        }
    }
}

private class UnitApplier : AbstractApplier<Unit>(Unit) {
    override fun insertTopDown(index: Int, instance: Unit) {}

    override fun insertBottomUp(index: Int, instance: Unit) {}

    override fun remove(index: Int, count: Int) {}

    override fun move(from: Int, to: Int, count: Int) {}

    override fun onClear() {}
}
