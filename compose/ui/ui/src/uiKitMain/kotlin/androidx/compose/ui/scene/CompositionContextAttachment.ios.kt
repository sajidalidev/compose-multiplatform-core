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

import androidx.compose.runtime.CompositionContext
import androidx.compose.ui.platform.FrameChoreographer
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import platform.UIKit.UIResponder
import platform.UIKit.UIWindow
import platform.objc.OBJC_ASSOCIATION_RETAIN
import platform.objc.objc_getAssociatedObject
import platform.objc.objc_setAssociatedObject

@OptIn(ExperimentalForeignApi::class)
private val compositionContextAssociationKey: COpaquePointer = nativeHeap.alloc<IntVar>().ptr

@OptIn(ExperimentalForeignApi::class)
internal var UIResponder.attachedCompositionContext: CompositionContext?
    get() = objc_getAssociatedObject(this, compositionContextAssociationKey) as? CompositionContext
    set(value) {
        objc_setAssociatedObject(this, compositionContextAssociationKey, value, OBJC_ASSOCIATION_RETAIN)
    }

internal fun UIResponder.findParentCompositionContext(): CompositionContext {
    if (this is UIWindow) {
        return FrameChoreographer.choreographerForScene(
            scene = windowScene ?: error("Window scene is null")
        ).frameRecomposer.compositionContext
    }
    this.attachedCompositionContext?.let {
        return it
    }
    return nextResponder?.findParentCompositionContext()
        ?: error("Unable to find parent composition context")
}
