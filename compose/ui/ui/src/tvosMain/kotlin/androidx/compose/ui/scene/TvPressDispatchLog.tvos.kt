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

// Age out clickpad presses whose release was absorbed by a system overlay.
private const val HELD_PRESS_MAX_AGE_S = 1.0

/**
 * Records which presses have already been evaluated by a [ComposeSceneMediator], shared by every
 * mediator of one [ComposeContainer] (the root one and the one owned by each
 * [IosComposeSceneLayer]).
 *
 * A mediator replays an unhandled Menu press to `super`, so UIKit walks the responder chain and
 * delivers the very same press to the views and controllers above it, which route it back into
 * another mediator of the same container. Sharing the log makes those echoes visible: a press that
 * is already on its way to UIApplication is passed straight on ([isForwardedToSystem]) instead of
 * being dispatched to Compose again, and a press two mediators of one container both receive is
 * only evaluated once ([shouldEvaluate]).
 */
internal class TvPressDispatchLog {
    // The current event is identified by value rather than by reference: UIKit recycles
    // UIPressesEvent instances, so object identity alone would make a reused instance carrying a
    // brand new press look like the event already logged, and holding the instance would retain a
    // UIKit event. The timestamp distinguishes the reused instance.
    private var lastEventPtr: Long = 0L
    private var lastEventTimestamp: Double = Double.NaN
    private val evaluatedPhases = mutableMapOf<Long, UIPressPhase>()
    private val forwardedKeyIds = mutableSetOf<Long>()

    // Menu presses whose KeyDown nothing in Compose consumed, awaiting their KeyUp. Kept here
    // rather than in a mediator because the first responder can change between the two phases
    // (a layer that resigns focus hands the Ended to another mediator of the same container),
    // and it deliberately survives an event change: Began and Ended belong to two different
    // UIPressesEvents.
    private val pendingMenuKeyIds = mutableSetOf<Long>()

    /**
     * Returns `true` if the press identified by [keyId] in [phase] has not been evaluated yet for
     * [event], recording it as evaluated. Returns `false` for a press echoed back by the responder
     * chain and for a press already handed to the system.
     */
    fun shouldEvaluate(event: UIPressesEvent?, keyId: Long, phase: UIPressPhase): Boolean {
        syncEvent(event)
        if (keyId in forwardedKeyIds) {
            return false
        }
        if (evaluatedPhases[keyId] == phase) {
            return false
        }
        evaluatedPhases[keyId] = phase
        return true
    }

    /**
     * Records that the press identified by [keyId] is being replayed to the responder chain, so
     * that the responders above the one replaying it forward it towards `UIApplication` instead of
     * dispatching it to Compose a second time.
     */
    fun markForwardedToSystem(event: UIPressesEvent?, keyId: Long) {
        syncEvent(event)
        forwardedKeyIds.add(keyId)
    }

    /** Returns `true` for a press already handed to the system by [markForwardedToSystem]. */
    fun isForwardedToSystem(event: UIPressesEvent?, keyId: Long): Boolean {
        syncEvent(event)
        return keyId in forwardedKeyIds
    }

    /**
     * Stops short-circuiting [keyId] once its replay has travelled the whole responder chain.
     * Relying on the next event to clear it isn't enough: the presses may arrive with a null
     * `UIPressesEvent`, and then every event looks like the same one.
     */
    fun unmarkForwardedToSystem(keyId: Long) {
        forwardedKeyIds.remove(keyId)
    }

    /** Remembers that the Menu press [keyId] is waiting for its KeyUp to be offered to Compose. */
    fun setPendingMenu(keyId: Long) {
        pendingMenuKeyIds.add(keyId)
    }

    /** Returns and forgets the pending state recorded by [setPendingMenu]. */
    fun takePendingMenu(keyId: Long): Boolean = pendingMenuKeyIds.remove(keyId)

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
    val clickpadPressTimestamp: Double
        get() = lastClickpadPressTimestamp

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
            UIPressPhase.UIPressPhaseEnded,
            UIPressPhase.UIPressPhaseCancelled -> heldClickpadKeyIds.remove(keyId)
            else -> {}
        }
    }

    fun clear() {
        lastEventPtr = 0L
        lastEventTimestamp = Double.NaN
        evaluatedPhases.clear()
        forwardedKeyIds.clear()
        pendingMenuKeyIds.clear()
        lastClickpadPressTimestamp = Double.NEGATIVE_INFINITY
        heldClickpadKeyIds.clear()
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun syncEvent(event: UIPressesEvent?) {
        val ptr = event?.objcPtr()?.toLong() ?: 0L
        val timestamp = event?.timestamp ?: -1.0
        if (ptr != lastEventPtr || timestamp != lastEventTimestamp) {
            lastEventPtr = ptr
            lastEventTimestamp = timestamp
            evaluatedPhases.clear()
            forwardedKeyIds.clear()
        }
    }
}

/**
 * What the responder that received a set of presses must hand over to `super`, as decided by
 * [ComposeSceneMediator.onKeyboardPresses]. Everything not listed here is swallowed by Compose.
 */
internal class TvPressForwarding(
    /**
     * Presses to replay to the responder chain as a Began immediately followed by an Ended, so that
     * UIKit sees a completed press.
     */
    val replay: Set<Any?> = emptySet(),
    /**
     * Presses that are already travelling to the system: forward them unchanged, in the phase they
     * arrived in.
     */
    val passThrough: Set<Any?> = emptySet(),
    /** Called once [replay] has been sent up the responder chain, to drop its bookkeeping. */
    val onReplayFinished: () -> Unit = {},
) {
    companion object {
        val None = TvPressForwarding()
    }
}
