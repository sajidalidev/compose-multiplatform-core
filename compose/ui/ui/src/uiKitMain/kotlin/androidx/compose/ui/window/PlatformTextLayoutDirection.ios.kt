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

package androidx.compose.ui.window

import platform.UIKit.UITextLayoutDirection
import platform.UIKit.UITextLayoutDirectionDown
import platform.UIKit.UITextLayoutDirectionLeft
import platform.UIKit.UITextLayoutDirectionRight
import platform.UIKit.UITextLayoutDirectionUp

internal enum class PlatformTextLayoutDirection(val platform: UITextLayoutDirection) {
    Left(UITextLayoutDirectionLeft),
    Right(UITextLayoutDirectionRight),
    Up(UITextLayoutDirectionUp),
    Down(UITextLayoutDirectionDown);

    companion object {
        operator fun invoke(platform: UITextLayoutDirection): PlatformTextLayoutDirection? {
            return entries.find { it.platform == platform }
        }
    }
}
