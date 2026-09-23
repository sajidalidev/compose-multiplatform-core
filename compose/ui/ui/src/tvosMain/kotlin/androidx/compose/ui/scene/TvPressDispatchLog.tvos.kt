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
// A held clickpad press older than this relative to the event being evaluated is stale: its
// Ended phase was absorbed before reaching the mediator (the tvOS keyboard overlay does that
// while it is up), and without ageing it out every later contact would be suppressed forever.
private const val HELD_PRESS_MAX_AGE_S = 1.0

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

    // Timestamp (seconds since boot, the same timebase as UITouch.timestamp) of the last
    // clickpad press Began, and the ids of the clickpad presses currently held. The Siri Remote
    // clickpad is itself a button (Select in the middle, arrows on the outer ring of 2nd
    // generation remotes): clicking it delivers a UIPress alongside an indirect UITouch that
    // drifts as the finger settles, so a contact whose lifetime overlaps a clickpad press is
    // click contact, not a swipe. Shared like the log itself, because the press and the touch of
    // one click can be delivered to different mediators of the same container.
    private var lastClickpadPressTimestamp: Double = Double.NEGATIVE_INFINITY
    private val heldClickpadKeyIds = mutableMapOf<Long, Double>()

    /**
     * `true` while at least one clickpad press is held, evaluated at [timestamp]: presses whose
     * Began is older than [HELD_PRESS_MAX_AGE_S] are dropped as never-ended leftovers.
     */
    fun isClickpadPressHeld(timestamp: Double): Boolean {
        if (heldClickpadKeyIds.isEmpty()) return false
        heldClickpadKeyIds.entries.removeAll { timestamp - it.value > HELD_PRESS_MAX_AGE_S }
        return heldClickpadKeyIds.isNotEmpty()
    }

    /** Timestamp of the last clickpad press Began, [Double.NEGATIVE_INFINITY] if there was none. */
    val clickpadPressTimestamp: Double get() = lastClickpadPressTimestamp

    /** Records the lifetime of a clickpad press identified by [keyId]. */
    fun recordClickpadPress(keyId: Long, phase: UIPressPhase, timestamp: Double) {
        when (phase) {
            UIPressPhase.UIPressPhaseBegan -> {
                // A second Began for a key that is still held means the previous press's Ended
                // never arrived, so that press ends here rather than staying held for good.
                heldClickpadKeyIds.remove(keyId)
                lastClickpadPressTimestamp = timestamp
                heldClickpadKeyIds[keyId] = timestamp
            }
            UIPressPhase.UIPressPhaseEnded, UIPressPhase.UIPressPhaseCancelled ->
                heldClickpadKeyIds.remove(keyId)
            else -> {}
        }
    }

    fun clear() {
        lastEventPtr = 0L
        lastEventTimestamp = Double.NaN
        evaluatedPhases.clear()
        lastClickpadPressTimestamp = Double.NEGATIVE_INFINITY
        heldClickpadKeyIds.clear()
    }
}
