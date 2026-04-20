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

import androidx.compose.runtime.Composable
import androidx.compose.ui.uikit.ComposeUIViewControllerConfiguration
import androidx.compose.ui.uikit.utils.CMPViewController
import androidx.compose.ui.window.ComposeContainerLifecycleDelegate
import androidx.compose.ui.window.MetalRedrawer
import kotlin.coroutines.CoroutineContext
import kotlin.native.runtime.NativeRuntimeApi
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExportObjCClass
import kotlinx.coroutines.Dispatchers
import platform.UIKit.UIFocusAnimationCoordinator
import platform.UIKit.UIFocusUpdateContext
import platform.UIKit.UIPressesEvent
import platform.UIKit.nextFocusedView

@OptIn(BetaInteropApi::class)
@ExportObjCClass
internal class ComposeHostingViewController(
    private val configuration: ComposeUIViewControllerConfiguration,
    private val content: @Composable () -> Unit,
    coroutineContext: CoroutineContext = Dispatchers.Main,
    private val lifecycleDelegate: ComposeContainerLifecycleDelegate = ComposeContainerLifecycleDelegate()
) : CMPViewController(lifecycleDelegate = lifecycleDelegate) {
    private val container = ComposeContainer(
        configuration = configuration,
        content = content,
        coroutineContext = coroutineContext,
        lifecycleDelegate = lifecycleDelegate
    )

    val rootRedrawer: MetalRedrawer? get() = container.view.redrawer
    fun hasInvalidations(): Boolean = container.hasInvalidations()

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
        container.onKeyboardPresses(presses)
        // Do not call super to prevent the back button from exiting the app
    }

    override fun pressesEnded(presses: Set<*>, withEvent: UIPressesEvent?) {
        container.onKeyboardPresses(presses)
    }

    override fun pressesCancelled(presses: Set<*>, withEvent: UIPressesEvent?) {
        container.onKeyboardPresses(presses)
    }

    override fun didUpdateFocusInContext(
        context: UIFocusUpdateContext,
        withAnimationCoordinator: UIFocusAnimationCoordinator
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
