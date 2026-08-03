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
import androidx.compose.ui.graphics.PaintingStyle

// The tvOS/Android implementations approximate a shadow layer with a Gaussian blur
// (MaskFilter.makeBlur / setShadowLayer) run on the Paint every time SurfaceGlowNode.draw() is
// invoked - which, for the focused card, is every animation frame while its elevation is
// changing. That blur is a per-pixel filter over the (blurred) glow shape, and it's expensive
// enough on lower-end (e.g. Mali-class) TV GPUs commonly found in smart TVs to be a measurable
// chunk of frame time during focus-move animations.
//
// A blurred *fill* is only ever visible where it extends past the surface's own bounds anyway:
// SurfaceGlowNode.draw() paints the glow shape at the exact same bounds and outline the surface
// itself is drawn at (see draw() calling drawContent() right after), so a non-blurred fill would
// be entirely hidden underneath the surface's own content. We approximate the visible sliver -
// the blur's bleed past the shape's edge - with a plain translucent *stroke* straddling that
// edge instead: no mask filter, so no per-frame blur cost, at the cost of a harder-edged halo
// than a true Gaussian blur would produce.
internal actual fun Paint.applyGlow(blurRadiusPx: Float, shadowColor: Color) {
    color = shadowColor
    style = PaintingStyle.Stroke
    strokeWidth = blurRadiusPx
    // Skia treats strokeWidth == 0f as a 1px hairline, not "invisible" - unlike the tvOS/Android
    // implementations, which rely on MaskFilter.makeBlur/setShadowLayer producing no visible
    // effect for a non-positive radius. Force the same "zero radius means no glow" behavior
    // explicitly, since that's the state the surface rests in before it is ever focused.
    alpha = if (blurRadiusPx > 0f) 1f else 0f
}
