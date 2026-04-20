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

package androidx.compose.foundation

import androidx.compose.ui.geometry.Offset
import platform.UIKit.UIView

internal interface PlatformMagnifierFactory {
    fun create(
        view: UIView,
        useTextDefault: Boolean = false,
        size: Long = 0L,
        cornerRadius: Float = 0f,
        elevation: Float = 0f,
        clippingEnabled: Boolean = true,
    ): PlatformMagnifier

    companion object {
        fun getForCurrentPlatform(): PlatformMagnifierFactory = NoOpMagnifierFactory
    }
}

internal interface PlatformMagnifier {
    fun updateContent(
        sourceCenter: Offset,
        magnifierCenter: Offset,
    )
    fun dismiss()
}

private object NoOpMagnifierFactory : PlatformMagnifierFactory {
    override fun create(
        view: UIView,
        useTextDefault: Boolean,
        size: Long,
        cornerRadius: Float,
        elevation: Float,
        clippingEnabled: Boolean,
    ): PlatformMagnifier = NoOpMagnifier

    private object NoOpMagnifier : PlatformMagnifier {
        override fun updateContent(sourceCenter: Offset, magnifierCenter: Offset) {}
        override fun dismiss() {}
    }
}
