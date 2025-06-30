/*
 * Copyright 2025 The Android Open Source Project
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

package androidx.compose.foundation.text.input.internal

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.uikit.LocalNativeTextInputContext

@OptIn(ExperimentalComposeUiApi::class)
internal actual fun CompositionLocalConsumerModifierNode.drawPlatformSelection(
    scope: DrawScope,
    selection: TextRange,
    textLayoutResult: TextLayoutResult
) {
    val usingNITI = currentValueOf(LocalNativeTextInputContext).usingNativeInput()
    // Don't draw selection on iOS when using NITI
    if (!usingNITI) {
        drawDefaultSelection(scope, selection, textLayoutResult)
    }
}

@OptIn(ExperimentalComposeUiApi::class)
internal actual fun CompositionLocalConsumerModifierNode.drawPlatformCursor(
    scope: DrawScope,
    cursorRect: Rect,
    brush: Brush,
    alpha: Float
) {
    val nativeTextInputContext = currentValueOf(LocalNativeTextInputContext)
    // Don't draw selection on iOS when using NITI
    if (!nativeTextInputContext.usingNativeInput()) {
        drawDefaultCursor(scope, cursorRect, brush, alpha)
    } else {
        (brush as? SolidColor)
            ?.value
            ?.takeIf { it != Color.Unspecified }
            ?.let { nativeTextInputContext.updateTintColor(it) }
    }
}