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

package androidx.compose.ui.input.remote

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.scene.dispatchRemoteTap
import androidx.compose.ui.scene.runRemoteInput
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class, InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
class RemoteTapTest {
    private fun SkikoComposeUiTest.tap(): Boolean {
        val handled = scene.runRemoteInput { scene.dispatchRemoteTap() }
        waitForIdle()
        return handled
    }

    private fun SkikoComposeUiTest.focus(requester: FocusRequester) {
        runOnIdle { requester.requestFocus() }
        waitForIdle()
    }

    @Test
    fun nearestNodeAroundTheFocusedItemIsTapped() = runSkikoComposeUiTest {
        val requester = FocusRequester()
        val taps = mutableListOf<String>()
        setContent {
            Box(Modifier.onRemoteTap { taps += "outer" }) {
                Box(Modifier.onRemoteTap { taps += "inner" }) {
                    Box(Modifier.size(10.dp).focusRequester(requester).focusable())
                }
            }
        }
        focus(requester)

        assertTrue(tap())
        assertEquals(listOf("inner"), taps)
    }

    @Test
    fun nodeAfterTheFocusTargetIsTapped() = runSkikoComposeUiTest {
        val requester = FocusRequester()
        var taps = 0
        setContent {
            Box(Modifier.size(10.dp).focusRequester(requester).focusable().onRemoteTap { taps++ })
        }
        focus(requester)

        assertTrue(tap())
        assertEquals(1, taps)
    }

    @Test
    fun nodeOutsideTheFocusedBranchIsNotTapped() = runSkikoComposeUiTest {
        val requester = FocusRequester()
        var taps = 0
        setContent {
            Column {
                Box(Modifier.size(10.dp).onRemoteTap { taps++ })
                Box(Modifier.size(10.dp).focusRequester(requester).focusable())
            }
        }
        focus(requester)

        assertFalse(tap())
        assertEquals(0, taps)
    }
}
