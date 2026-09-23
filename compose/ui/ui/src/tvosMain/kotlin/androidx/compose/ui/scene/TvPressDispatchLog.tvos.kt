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

package androidx.compose.ui.scene

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.objcPtr
import platform.UIKit.UIPressPhase
import platform.UIKit.UIPressesEvent

/**
 * Records which presses have already been evaluated by a [ComposeSceneMediator], shared by every
 * mediator of one [ComposeContainer] (the root one and the one owned by each
 * [IosComposeSceneLayer]).
 *
 * A mediator forwards the presses it did not consume to `super`, so UIKit walks the responder
 * chain and delivers the very same press to the views and controllers above it, which route it
 * back into another mediator of the same container. Sharing the log makes those echoes visible:
 * a press a dialog's mediator already evaluated is reported unconsumed by the root mediator
 * without being dispatched to Compose again, so it keeps travelling to UIApplication (Menu
 * suspends the app) instead of moving focus or clicking behind the dialog.
 *
 * Consumption itself stays per mediator ([ComposeSceneMediator.consumedKeyIds]): the mediator
 * that consumed the Began phase is the one that must dispatch the matching KeyUp.
 */
internal class TvPressDispatchLog {
    // The current event is identified by value rather than by reference: UIKit recycles
    // UIPressesEvent instances, so object identity alone would make a reused instance carrying a
    // brand new press look like the event already logged, and holding the instance would retain a
    // UIKit event. The timestamp distinguishes the reused instance.
    private var lastEventPtr: Long = 0L
    private var lastEventTimestamp: Double = Double.NaN
    private val evaluatedPhases = mutableMapOf<Long, UIPressPhase>()

    /**
     * Returns `true` if the press identified by [keyId] in [phase] has not been evaluated yet for
     * [event], recording it as evaluated. Returns `false` for a press echoed back by the responder
     * chain.
     */
    @OptIn(ExperimentalForeignApi::class)
    fun shouldEvaluate(event: UIPressesEvent?, keyId: Long, phase: UIPressPhase): Boolean {
        val ptr = event?.objcPtr()?.toLong() ?: 0L
        val timestamp = event?.timestamp ?: -1.0
        if (ptr != lastEventPtr || timestamp != lastEventTimestamp) {
            lastEventPtr = ptr
            lastEventTimestamp = timestamp
            evaluatedPhases.clear()
        }
        if (evaluatedPhases[keyId] == phase) {
            return false
        }
        evaluatedPhases[keyId] = phase
        return true
    }

    fun clear() {
        lastEventPtr = 0L
        lastEventTimestamp = Double.NaN
        evaluatedPhases.clear()
    }
}
