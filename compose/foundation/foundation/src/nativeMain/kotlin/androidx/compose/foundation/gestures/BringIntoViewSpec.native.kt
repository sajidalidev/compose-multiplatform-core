/*
 * Copyright 2024 The Android Open Source Project
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

import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.OsFamily
import kotlin.native.Platform

@OptIn(ExperimentalNativeApi::class)
internal actual fun platformDefaultBringIntoViewSpec(): BringIntoViewSpec =
    if (Platform.osFamily == OsFamily.TVOS) TvBringIntoViewSpec
    else BringIntoViewSpec.DefaultBringIntoViewSpec

// Match Android TV's default pivot. Minimum-distance scrolling pins newly focused cards
// to the viewport edge and can leave the first row slightly scrolled when navigating back up.
private object TvBringIntoViewSpec : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val initialTarget = 0.3f * containerSize
        val target =
            if (size <= containerSize && containerSize - initialTarget < size) {
                containerSize - size
            } else {
                initialTarget
            }
        return offset - target
    }
}
