/*
 * Copyright 2023 The Android Open Source Project
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

package androidx.tv.material3

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.skiaPaint
import androidx.compose.ui.graphics.toArgb
import org.jetbrains.skia.FilterBlurMode
import org.jetbrains.skia.MaskFilter

// org.jetbrains.skia.Paint has no direct equivalent of android.graphics.Paint#setShadowLayer;
// approximate the glow by painting directly in the glow color and applying a blur mask filter.
// The radius-to-sigma conversion mirrors androidx.compose.ui.graphics.BlurEffect's internal
// (non-public) helper of the same name.
//
// tvOS guard: Skia's native SkMaskFilter::MakeBlur returns null when sigma <= 0 (e.g. when
// blurRadiusPx is 0, which happens whenever the glow is not currently visible, such as before a
// Surface/Card gains focus). Skiko's MaskFilter constructor wraps that null native pointer and
// throws RuntimeException("Can't wrap nullptr"), crashing the first Compose frame. Guard against
// both a non-positive sigma and a failed/null makeBlur result (defensively, in case tvOS Skia
// fails to produce a mask filter for other reasons too) by falling back to "no glow" instead of
// letting the exception propagate; this matches the visually correct behavior since a zero-radius
// glow renders nothing anyway.
internal actual fun Paint.applyGlow(blurRadiusPx: Float, shadowColor: Color) {
    val native = skiaPaint
    native.color = shadowColor.toArgb()
    val sigma = blurSigmaFromRadius(blurRadiusPx)
    native.maskFilter =
        if (sigma > 0f) {
            runCatching { MaskFilter.makeBlur(FilterBlurMode.NORMAL, sigma) }.getOrNull()
        } else {
            null
        }
}

private fun blurSigmaFromRadius(radius: Float): Float =
    if (radius > 0) BlurSigmaScale * radius + 0.5f else 0f

private const val BlurSigmaScale = 0.57735f
