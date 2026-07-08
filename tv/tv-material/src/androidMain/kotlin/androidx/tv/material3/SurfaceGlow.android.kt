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
import androidx.compose.ui.graphics.nativePaint
import androidx.compose.ui.graphics.toArgb

internal actual fun Paint.applyGlow(blurRadiusPx: Float, shadowColor: Color) {
    val native = nativePaint
    native.color = android.graphics.Color.TRANSPARENT
    native.setShadowLayer(
        /* radius= */ blurRadiusPx,
        /* dx= */ 0f,
        /* dy= */ 0f,
        /* shadowColor= */ shadowColor.toArgb()
    )
}
