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

package androidx.compose.ui.platform

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.text.input.CommitTextCommand
import androidx.compose.ui.text.input.SetSelectionCommand
import androidx.compose.ui.window.FocusedViewsList
import kotlinx.cinterop.readValue
import platform.CoreGraphics.CGRectZero
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIColor
import platform.UIKit.UITextField
import platform.UIKit.UITextFieldDelegateProtocol
import platform.UIKit.UITextFieldTextDidChangeNotification
import platform.UIKit.UIView
import platform.darwin.NSObject

/**
 * Manages text input on tvOS by bridging a hidden [UITextField] with Compose's
 * [PlatformTextInputMethodRequest].
 *
 * On tvOS, the system keyboard is a full-screen overlay that appears when a
 * [UITextField] becomes first responder. This service creates a 0×0 invisible
 * [UITextField], makes it first responder when the user presses Select on a
 * focused Compose text field, and syncs all text changes back to Compose via
 * [PlatformTextInputMethodRequest.onEditCommand].
 */
@OptIn(ExperimentalComposeUiApi::class)
internal class TvOSTextInputService(
    private val view: UIView,
    private val focusedViewsList: FocusedViewsList?,
) {
    /** The currently active text input request, set when [startInput] is called. */
    var activeRequest: PlatformTextInputMethodRequest? = null
        private set

    /** True while the system keyboard is visible (i.e. [UITextField] is first responder). */
    val isKeyboardVisible: Boolean get() = textField != null

    private var textField: UITextField? = null
    // Kept alive to prevent ARC from collecting the delegate (UITextField only holds weak refs).
    private var textFieldDelegate: TvOSTextFieldDelegate? = null
    private var notificationObserver: Any? = null
    private var previousText: String = ""

    /** Records the active request. Does NOT show the keyboard yet. */
    fun startInput(request: PlatformTextInputMethodRequest) {
        activeRequest = request
    }

    /**
     * Show the tvOS system keyboard by making a hidden [UITextField] the first
     * responder. Should be called when the user presses Select on a focused text field.
     */
    fun showKeyboard() {
        val request = activeRequest ?: return
        if (textField != null) return // already showing

        val tf = UITextField(frame = CGRectZero.readValue())
        tf.backgroundColor = UIColor.clearColor
        val initialText = request.value().text
        tf.text = initialText
        previousText = initialText

        val del = TvOSTextFieldDelegate(this)
        tf.delegate = del
        textFieldDelegate = del

        // Must be in the view hierarchy before becomeFirstResponder.
        view.addSubview(tf)
        textField = tf

        notificationObserver = NSNotificationCenter.defaultCenter.addObserverForName(
            name = UITextFieldTextDidChangeNotification,
            `object` = tf,
            queue = NSOperationQueue.mainQueue,
        ) { _ ->
            syncTextToCompose()
        }

        // becomeFirstResponder is called via FocusedViewsList so that focus
        // management stays consistent with the rest of the tvOS scene.
        if (focusedViewsList != null) {
            focusedViewsList.addAndFocus(tf)
        } else {
            tf.becomeFirstResponder()
        }
    }

    /**
     * Called by the delegate when the Return/Done key is pressed.
     * Fires the IME action and dismisses the keyboard.
     */
    fun onReturnKeyPressed() {
        val request = activeRequest ?: return
        request.onImeAction?.invoke(request.imeOptions.imeAction)
        // resignFirstResponder triggers textFieldDidEndEditing synchronously.
        textField?.resignFirstResponder()
    }

    /**
     * Called by the delegate when the keyboard finishes editing (any dismissal path).
     * Cleans up UIKit state. The [startInputMethod] session stays alive so the user
     * can re-open the keyboard by pressing Select again on the same field.
     */
    fun onKeyboardEndedEditing() {
        hideKeyboard()
    }

    /**
     * Fully tears down the text input session. Idempotent – safe to call multiple times.
     * Called from [kotlinx.coroutines.CancellableContinuation.invokeOnCancellation] when
     * Compose cancels the session (e.g. the text field loses focus).
     */
    fun stopInput() {
        hideKeyboard()
        activeRequest = null
    }

    /**
     * Tears down UIKit state. Sets [textField] to null *before* calling UIKit APIs so
     * that re-entrant calls from [UITextField] delegate/notification callbacks are no-ops.
     */
    private fun hideKeyboard() {
        val tf = textField ?: return // already cleaned up
        val observer = notificationObserver

        // Clear Kotlin state first to prevent re-entry via UIKit callbacks.
        textField = null
        textFieldDelegate = null
        notificationObserver = null
        previousText = ""

        // UIKit cleanup.
        observer?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
        if (focusedViewsList != null) {
            focusedViewsList.remove(tf)
        } else {
            tf.resignFirstResponder()
        }
        // Synchronously reclaim first responder before removing tf from the hierarchy.
        // focusedViewsList.remove() schedules becomeFirstResponder via delay(0), but on tvOS
        // the focus engine can reassign first responder in that gap, causing all subsequent
        // D-pad and button events to stop being delivered to the overlay view.
        view.becomeFirstResponder()
        tf.removeFromSuperview()
    }

    /**
     * Called by [UITextFieldTextDidChangeNotification]. Diffs the old and new text
     * and dispatches edit commands to keep Compose in sync.
     */
    private fun syncTextToCompose() {
        val request = activeRequest ?: return
        val newText = textField?.text ?: ""
        val oldText = previousText
        if (newText == oldText) return

        // Replace-all strategy: select the entire old text and commit the new text.
        // This is correct for all single-character additions, deletions, and pastes.
        request.onEditCommand(
            listOf(
                SetSelectionCommand(0, oldText.length),
                CommitTextCommand(newText, 1),
            )
        )
        previousText = newText
    }
}

/**
 * UITextField delegate that routes keyboard events to [TvOSTextInputService].
 * Held strongly by [TvOSTextInputService] to prevent premature deallocation
 * (UITextField only keeps a weak reference to its delegate).
 */
private class TvOSTextFieldDelegate(
    private val service: TvOSTextInputService,
) : NSObject(), UITextFieldDelegateProtocol {

    override fun textFieldShouldReturn(textField: UITextField): Boolean {
        service.onReturnKeyPressed()
        return true
    }

    override fun textFieldDidEndEditing(textField: UITextField) {
        service.onKeyboardEndedEditing()
    }
}
