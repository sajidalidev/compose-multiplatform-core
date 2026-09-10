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

package androidx.compose.mpp.demo.tvos

import androidx.compose.ui.window.ComposeUIViewController
import platform.Foundation.NSBundle
import platform.UIKit.UIPress
import platform.UIKit.UIPressesEvent
import platform.UIKit.UIView
import platform.UIKit.UIViewAutoresizingFlexibleHeight
import platform.UIKit.UIViewAutoresizingFlexibleWidth
import platform.UIKit.UIViewController
import platform.UIKit.addChildViewController
import platform.UIKit.didMoveToParentViewController

private class PressLoggingViewController(private val content: UIViewController) :
    UIViewController(nibName = null, bundle = null as NSBundle?) {

    override fun viewDidLoad() {
        super.viewDidLoad()
        addChildViewController(content)
        val contentView: UIView = content.view
        contentView.setFrame(view.bounds)
        contentView.setAutoresizingMask(
            UIViewAutoresizingFlexibleWidth or UIViewAutoresizingFlexibleHeight
        )
        view.addSubview(contentView)
        content.didMoveToParentViewController(this)
    }

    private fun logPresses(phase: String, presses: Set<*>) {
        val types = presses.mapNotNull { (it as? UIPress)?.type }.joinToString(",")
        probeLog("PROBE uipress $phase types=[$types] t=${probeElapsedMs()}")
    }

    override fun pressesBegan(presses: Set<*>, withEvent: UIPressesEvent?) {
        logPresses("began", presses)
        super.pressesBegan(presses, withEvent)
    }

    override fun pressesEnded(presses: Set<*>, withEvent: UIPressesEvent?) {
        logPresses("ended", presses)
        super.pressesEnded(presses, withEvent)
    }
}

fun MainViewController(): UIViewController =
    PressLoggingViewController(ComposeUIViewController { TvOSDemoApp() })
