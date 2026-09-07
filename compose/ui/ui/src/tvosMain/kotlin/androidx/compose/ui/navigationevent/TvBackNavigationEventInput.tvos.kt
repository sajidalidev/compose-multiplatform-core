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

package androidx.compose.ui.navigationevent

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.navigationevent.NavigationEventInput

/**
 * tvOS flavour of [BackNavigationEventInput].
 *
 * The common implementation reports every Back/Escape KeyDown as consumed, even when no
 * navigation handler is registered. That is wrong on tvOS: the Siri Remote's Menu button maps
 * to [Key.Back], and Apple's HIG requires an unhandled Menu press to reach `UIApplication` so
 * the system can move the app to the background. If Compose swallowed it, the app would appear
 * frozen at the root screen with no way out.
 *
 * Therefore this input only consumes the key while at least one enabled navigation handler
 * exists ([onHasEnabledHandlersChanged]); otherwise it reports the press as unconsumed, which
 * lets [androidx.compose.ui.scene.ComposeSceneMediator] forward the [platform.UIKit.UIPress] up
 * the responder chain via `super.pressesBegan`.
 *
 * The logic of [BackNavigationEventInput.onKeyEvent] is duplicated instead of overridden
 * because that member is final.
 */
internal class TvBackNavigationEventInput : NavigationEventInput() {
    private var hasEnabledHandlers: Boolean = false

    override fun onHasEnabledHandlersChanged(hasEnabledHandlers: Boolean) {
        this.hasEnabledHandlers = hasEnabledHandlers
    }

    fun onKeyEvent(event: KeyEvent): Boolean {
        if (!hasEnabledHandlers) {
            // Nothing to navigate back to: let tvOS handle the Menu press.
            return false
        }
        return if (event.type == KeyEventType.KeyDown &&
            (event.key == Key.Escape || event.key == Key.Back)
        ) {
            dispatchOnBackCompleted()
            true
        } else {
            false
        }
    }
}
