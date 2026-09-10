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

import androidx.compose.ui.geometry.Offset
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ObjCAction
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSRunLoop
import platform.Foundation.NSRunLoopCommonModes
import platform.Foundation.NSSelectorFromString
import platform.GameController.GCController
import platform.GameController.GCControllerDidConnectNotification
import platform.GameController.GCControllerDidDisconnectNotification
import platform.GameController.GCMicroGamepad
import platform.GameController.GCProductCategorySiriRemote1stGen
import platform.QuartzCore.CADisplayLink
import platform.darwin.NSObject
import platform.darwin.NSObjectProtocol

/**
 * Reports where the finger physically is on the Siri Remote clickpad.
 *
 * UIKit indirect touches ([platform.UIKit.UITouchTypeIndirect]) carry a relative location in a
 * space that is re-centred on every new contact, so they cannot tell a movement that started on the
 * centre pad from one that started on the outer ring of the second generation remote. The
 * GameController micro gamepad does: with `reportsAbsoluteDpadValues` its dpad axes are the
 * absolute finger position in [-1, 1] on both axes.
 *
 * This is an observer only: no `GCEventViewController` is installed and
 * `controllerUserInteractionEnabled` is never touched, so UIKit keeps delivering presses and
 * touches through the responder chain exactly as before.
 *
 * The gamepad is polled at the touch event that needs it rather than through `valueChangedHandler`.
 * GameController keeps a single handler per element, so the last writer wins: an app that installs
 * its own handler on the same remote would silently replace the oracle's and leave it without
 * samples. Polling reads the live element values, so the oracle coexists with app-level
 * GameController usage and never overwrites an app's handler.
 *
 * UIKit reports indirect movement sparsely: polling only inside `touchesBegan`/`touchesMoved`
 * samples the origin of a contact up to 0.2 normalised units after the finger landed, which makes
 * genuine centre swipes look like they started on the ring. [beginSampling] therefore drives a
 * display link that polls the pad every frame while a contact is active and feeds the samples to
 * the listeners installed by [addSampleListener].
 */
internal class SiriRemoteTouchOracle {
    private var connectObserver: NSObjectProtocol? = null
    private var disconnectObserver: NSObjectProtocol? = null

    private var controller: GCController? = null
    private var remoteGamepad: GCMicroGamepad? = null
    private var productCategory: String? = null
    // The gamepad's own rotation mode, restored when the oracle lets go of it.
    private var previousAllowsRotation: Boolean? = null

    private val sampleListeners = mutableListOf<(Offset?) -> Unit>()
    private var samplingCount = 0
    // Bumped by every stop, so a session token of a previous run cannot close a current session.
    private var samplingGeneration = 0
    private var displayLink: CADisplayLink? = null
    private val displayLinkTarget = SampleDisplayLinkTarget {
        val sample = position()
        // A listener may dispose its mediator, and with it remove listeners, while being
        // notified, so the list is snapshotted before it is walked.
        sampleListeners.toList().forEach { it(sample) }
    }

    /** `true` while a micro gamepad, i.e. a Siri Remote, is connected and reporting. */
    val isAvailable: Boolean
        get() = remoteGamepad != null

    /**
     * `true` for remotes whose clickpad has an outer ring of arrow buttons.
     *
     * tvOS coalesces every paired remote into a single controller reporting the "Coalesced Remote"
     * product category, so a physical remote's generation cannot be distinguished once connected.
     * The ring gate is therefore the default, applying to the coalesced controller and any other
     * unrecognised micro gamepad category. First-generation remotes have no ring and let a swipe
     * start anywhere on the pad, so they are exempted here; so are genuine game controllers, which
     * report an extended gamepad rather than a bare micro gamepad.
     */
    val hasRing: Boolean
        get() {
            val category = productCategory ?: return false
            if (category == GCProductCategorySiriRemote1stGen) return false
            return true
        }

    fun start() {
        if (connectObserver != null) return
        val center = NSNotificationCenter.defaultCenter
        val queue = NSOperationQueue.mainQueue
        connectObserver =
            center.addObserverForName(
                name = GCControllerDidConnectNotification,
                `object` = null,
                queue = queue,
            ) {
                refresh()
            }
        disconnectObserver =
            center.addObserverForName(
                name = GCControllerDidDisconnectNotification,
                `object` = null,
                queue = queue,
            ) {
                refresh()
            }
        refresh()
    }

    /**
     * Adds a listener invoked with [position] on every display-link tick while any [beginSampling]
     * session is open. Every mediator, the root one and each dialog layer, installs its own, so a
     * contact live in one of them is sampled for all of them.
     */
    fun addSampleListener(listener: (Offset?) -> Unit) {
        sampleListeners.add(listener)
    }

    /** Removes a listener installed by [addSampleListener]. */
    fun removeSampleListener(listener: (Offset?) -> Unit) {
        sampleListeners.remove(listener)
    }

    /**
     * Opens a sampling session, starting the per-frame polling of the pad if it is the first one.
     * Every call must be matched by an [endSampling] with the returned token.
     */
    fun beginSampling(): Int {
        samplingCount++
        if (displayLink != null) return samplingGeneration
        val link =
            CADisplayLink.displayLinkWithTarget(
                target = displayLinkTarget,
                selector = NSSelectorFromString("tick:"),
            )
        link.preferredFramesPerSecond = 60L
        link.addToRunLoop(NSRunLoop.mainRunLoop, NSRunLoopCommonModes)
        displayLink = link
        return samplingGeneration
    }

    /**
     * Closes the sampling session opened with [token], stopping the polling once the last one is
     * closed. A token of an older generation is ignored: [stop] already dropped every session of
     * it, so honouring it would underflow the count of the sessions opened since.
     */
    fun endSampling(token: Int) {
        if (token != samplingGeneration) return
        if (samplingCount == 0) return
        samplingCount--
        if (samplingCount > 0) return
        stopSampling()
    }

    private fun stopSampling() {
        // Invalidating is what releases the display link's retain of the target, so it must run
        // for every begun sampling session.
        displayLink?.invalidate()
        displayLink = null
    }

    fun stop() {
        samplingCount = 0
        samplingGeneration++
        stopSampling()
        sampleListeners.clear()
        val center = NSNotificationCenter.defaultCenter
        connectObserver?.let { center.removeObserver(it) }
        disconnectObserver?.let { center.removeObserver(it) }
        connectObserver = null
        disconnectObserver = null
        detach()
    }

    /**
     * Current absolute finger position in [-1, 1] on both axes, `null` if no remote is connected or
     * if the pad is at rest. The pad reports exactly (0, 0) whenever no finger is touching it, and
     * no touching sample is ever exactly (0, 0), so an exact-zero sample is treated as "no finger":
     * otherwise a BEGAN sampled before the pad reports a real position would be classified as a
     * centre-pad contact at radius 0 using a stale origin instead of falling to fallback mode.
     */
    fun position(): Offset? {
        val microGamepad = remoteGamepad ?: return null
        // An app is free to reset this flag on the shared gamepad; setting it back only takes
        // effect from the next sample, so the sample read right after such a reset may still be
        // relative.
        if (!microGamepad.reportsAbsoluteDpadValues) {
            microGamepad.reportsAbsoluteDpadValues = true
        }
        val pad = microGamepad.dpad
        val x = pad.xAxis.value
        val y = pad.yAxis.value
        if (x == 0f && y == 0f) return null
        return Offset(x, y)
    }

    /** `true` while the clickpad or one of the ring buttons is physically held down. */
    fun anyButtonPressed(): Boolean {
        val microGamepad = remoteGamepad ?: return false
        return isAnyButtonPressed(microGamepad)
    }

    private fun refresh() {
        // Read the generic profile: the simulator can return an extended gamepad from the
        // typed microGamepad getter, throwing before Kotlin can apply a safe cast.
        for (connected in GCController.controllers().filterIsInstance<GCController>()) {
            val profile = connected.physicalInputProfile as? GCMicroGamepad ?: continue
            if (connected == controller && profile == remoteGamepad) return
            detach()
            attach(connected, profile)
            return
        }
        detach()
    }

    private fun attach(connected: GCController, microGamepad: GCMicroGamepad) {
        remoteGamepad = microGamepad
        controller = connected
        productCategory = connected.productCategory
        previousAllowsRotation = microGamepad.allowsRotation
        microGamepad.reportsAbsoluteDpadValues = true
        microGamepad.allowsRotation = false
    }

    private fun detach() {
        // No handler is installed, so nothing is cleared here: an app's own
        // `valueChangedHandler` on the same gamepad is never touched. The rotation mode is
        // restored, since an app that enabled it did so for its own reading of the gamepad.
        previousAllowsRotation?.let { remoteGamepad?.allowsRotation = it }
        previousAllowsRotation = null
        controller = null
        remoteGamepad = null
        productCategory = null
    }

    // The dpad's up/down/left/right are virtual direction buttons synthesised from the axis
    // sign, pressed whenever the finger is off-centre in that direction; they are NOT the
    // physical ring click buttons. Treating them as presses would make every swipe candidate
    // that leaves the centre pad look like a clickpad press. Only the physical action buttons
    // (buttonA, buttonX) and the menu button count as a press here.
    private fun isAnyButtonPressed(microGamepad: GCMicroGamepad): Boolean =
        microGamepad.buttonA.pressed ||
            microGamepad.buttonX.pressed ||
            microGamepad.buttonMenu.pressed
}

private class SampleDisplayLinkTarget(private val onTick: () -> Unit) : NSObject() {
    @OptIn(BetaInteropApi::class)
    @ObjCAction
    fun tick(link: CADisplayLink) {
        onTick()
    }
}
