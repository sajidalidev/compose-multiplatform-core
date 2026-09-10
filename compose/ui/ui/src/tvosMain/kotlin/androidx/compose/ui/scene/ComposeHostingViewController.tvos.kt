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

package androidx.compose.ui.scene

import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.Composable
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.uikit.ComposeUIViewControllerConfiguration
import androidx.compose.ui.uikit.utils.CMPViewController
import androidx.compose.ui.window.ComposeContainerLifecycleDelegate
import kotlin.native.runtime.NativeRuntimeApi
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExportObjCClass
import platform.UIKit.UIFocusAnimationCoordinator
import platform.UIKit.UIFocusUpdateContext
import platform.UIKit.UIPressesEvent
import platform.UIKit.nextFocusedView

@OptIn(BetaInteropApi::class)
@ExportObjCClass
internal class ComposeHostingViewController(
    private val configuration: ComposeUIViewControllerConfiguration,
    private val content: @Composable () -> Unit,
    private val lifecycleDelegate: ComposeContainerLifecycleDelegate =
        ComposeContainerLifecycleDelegate(),
) : CMPViewController(lifecycleDelegate = lifecycleDelegate) {
    private val container =
        ComposeContainer(
            configuration = configuration,
            content = content,
            lifecycleDelegate = lifecycleDelegate,
        )

    @VisibleForTesting fun hasInvalidations(): Boolean = container.hasInvalidations()

    @VisibleForTesting
    @OptIn(InternalComposeUiApi::class)
    var rootForTestListener: PlatformContext.RootForTestListener?
        get() = container.rootForTestListener
        set(value) {
            container.rootForTestListener = value
        }

    override fun loadView() {
        view = container.view
    }

    @Suppress("DEPRECATION")
    override fun viewDidLoad() {
        super.viewDidLoad()

        configuration.delegate.viewDidLoad()
        container.updateUserInterfaceStyle(traitCollection.userInterfaceStyle)
    }

    override fun userInterfaceStyleDidChange() {
        container.updateUserInterfaceStyle(traitCollection.userInterfaceStyle)
    }

    @Suppress("DEPRECATION")
    override fun viewWillAppear(animated: Boolean) {
        super.viewWillAppear(animated)

        configuration.delegate.viewWillAppear(animated)
    }

    @Suppress("DEPRECATION")
    override fun viewDidAppear(animated: Boolean) {
        super.viewDidAppear(animated)

        container.sceneDidAppear()

        configuration.delegate.viewDidAppear(animated)
    }

    @Suppress("DEPRECATION")
    override fun viewWillDisappear(animated: Boolean) {
        super.viewWillDisappear(animated)

        container.sceneWillDisappear()

        configuration.delegate.viewWillDisappear(animated)
    }

    @Suppress("DEPRECATION")
    @OptIn(NativeRuntimeApi::class)
    override fun viewDidDisappear(animated: Boolean) {
        super.viewDidDisappear(animated)

        configuration.delegate.viewDidDisappear(animated)
    }

    override fun pressesBegan(presses: Set<*>, withEvent: UIPressesEvent?) {
        // Mirrors the overlay input view: Compose gets every press in both phases, and the only
        // press that can reach tvOS is a Menu press no Compose handler wanted on either phase.
        // Such a press is either replayed from here (when this controller saw it first, because
        // no overlay view was first responder) or passed on unchanged while it travels up the
        // responder chain from the view that replayed it.
        val forwarding = container.onKeyboardPresses(presses, withEvent)
        if (forwarding.passThrough.isNotEmpty()) {
            super.pressesBegan(forwarding.passThrough, withEvent)
        }
    }

    override fun pressesEnded(presses: Set<*>, withEvent: UIPressesEvent?) {
        val forwarding = container.onKeyboardPresses(presses, withEvent)
        if (forwarding.passThrough.isNotEmpty()) {
            super.pressesEnded(forwarding.passThrough, withEvent)
        }
        replayToSystem(forwarding, withEvent)
    }

    override fun pressesCancelled(presses: Set<*>, withEvent: UIPressesEvent?) {
        val forwarding = container.onKeyboardPresses(presses, withEvent)
        if (forwarding.passThrough.isNotEmpty()) {
            super.pressesCancelled(forwarding.passThrough, withEvent)
        }
    }

    override fun pressesChanged(presses: Set<*>, withEvent: UIPressesEvent?) {
        // Analog buttons of an MFi controller report their pressure through this phase. Compose
        // has no event for it, so it is swallowed rather than left to UIResponder's default
        // implementation, which would send it up the chain behind Compose's back.
        val forwarding = container.onKeyboardPresses(presses, withEvent)
        if (forwarding.passThrough.isNotEmpty()) {
            super.pressesChanged(forwarding.passThrough, withEvent)
        }
    }

    /**
     * Sends [TvPressForwarding.replay] up the responder chain as a Began immediately followed by an
     * Ended.
     *
     * Assumption to verify on a simulator: UIKit acts on the *completed* press, so both phases have
     * to be replayed for the system to move the app to the background on Menu. The replayed
     * [platform.UIKit.UIPress] still carries `phase == Ended`; if UIKit keys on `press.phase`
     * rather than on the selector, the Began leg is a no-op and forwarding only the real Ended is
     * the fallback.
     */
    private fun replayToSystem(forwarding: TvPressForwarding, event: UIPressesEvent?) {
        if (forwarding.replay.isEmpty()) return
        for (press in forwarding.replay) {
            val single = setOf(press)
            super.pressesBegan(single, event)
            super.pressesEnded(single, event)
        }
        forwarding.onReplayFinished()
    }

    override fun didUpdateFocusInContext(
        context: UIFocusUpdateContext,
        withAnimationCoordinator: UIFocusAnimationCoordinator,
    ) {
        super.didUpdateFocusInContext(context, withAnimationCoordinator)
        if (context.nextFocusedView == view) {
            container.didUpdateFocusInContext()
        }
    }

    override fun viewControllerDidEnterWindowHierarchy() {
        container.initializeComposeScene()
    }

    override fun viewControllerDidLeaveWindowHierarchy() {
        container.disposeComposeScene()
    }
}
