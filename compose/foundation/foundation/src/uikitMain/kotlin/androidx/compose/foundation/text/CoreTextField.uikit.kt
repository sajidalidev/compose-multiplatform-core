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

package androidx.compose.foundation.text

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.uikit.LocalNativeTextInputContext

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal actual fun platformShouldDrawTextControls(cursorBrush: Brush, selectionColor: Color): Boolean {
    val nativeInputContext = LocalNativeTextInputContext.current
    val isUsingNativeInput = nativeInputContext.usingNativeInput()
    if (isUsingNativeInput) {
        val controlsColor = (cursorBrush as? SolidColor)
            ?.value
            ?.takeIf { it != Color.Unspecified }
            ?: selectionColor
        nativeInputContext.updateTintColor(controlsColor)
    }
    return isUsingNativeInput
}