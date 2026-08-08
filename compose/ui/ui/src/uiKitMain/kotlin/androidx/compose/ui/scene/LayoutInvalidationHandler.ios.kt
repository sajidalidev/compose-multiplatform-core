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

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

/**
 * Prevent cases where invalidate layout may be called during the rendering process,
 * which lead to another frame rendering during the same frame.
 */
internal class LayoutInvalidationHandler(
    coroutineContext: CoroutineContext,
    private var doInvalidateLayout: () -> Unit
) {
    private var invalidationPostponed = false
    private var hasInvalidations = false
    private val scope = CoroutineScope(coroutineContext)

    init {
        coroutineContext.job.invokeOnCompletion {
            doInvalidateLayout = {}
        }
    }

    fun invalidateLayoutIfNeeded() {
        if (invalidationPostponed) {
            hasInvalidations = true
            return
        }
        doInvalidateLayout()
        hasInvalidations = false
    }

    fun postponeLayoutInvalidationCalls(block: () -> Unit) {
        assert(!invalidationPostponed)
        invalidationPostponed = true
        try {
            block()
        } finally {
            invalidationPostponed = false
        }
        if (hasInvalidations) {
            scope.launch {
                invalidateLayoutIfNeeded()
            }
        }
    }
}
