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
package androidx.compose.ui.scene

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.remote.RemoteFastScrollExitAnchor

/** Whether [key] is one of the four directional keys. */
internal fun isDirectionalKey(key: Key): Boolean =
    key == Key.DirectionUp ||
        key == Key.DirectionDown ||
        key == Key.DirectionLeft ||
        key == Key.DirectionRight

/** Whether [key] runs toward the bottom or the right of the screen. */
internal fun isForwardKey(key: Key): Boolean = key == Key.DirectionDown || key == Key.DirectionRight

/** Keys that never reach the scene while fast scroll holds focus on the container. */
internal fun isSwallowedInFastScroll(key: Key): Boolean =
    isDirectionalKey(key) || key == Key.DirectionCenter

/**
 * The position of a clickpad sample along the scroll axis, positive toward the bottom or the right.
 * The clickpad's y is positive toward the top of the remote.
 */
internal fun fastScrollForwardPosition(sample: Offset, vertical: Boolean): Float =
    if (vertical) -sample.y else sample.x

/** Why fast scroll ended, from what the physics reports: hit the end of the content, stopped by the user, or ran out of speed. */
internal fun fastScrollExitReason(hitEnd: Boolean, velocity: Float): String =
    when {
        hitEnd -> "end"
        velocity == 0f -> "stop"
        else -> "speed"
    }

/**
 * Where focus lands when fast scroll ends: the centre of the container, or its trailing or leading
 * edge when the content ended while scrolling toward [lastRequested] (positive is forward).
 */
internal fun fastScrollExitAnchor(hitEnd: Boolean, lastRequested: Float): RemoteFastScrollExitAnchor =
    when {
        !hitEnd -> RemoteFastScrollExitAnchor.Center
        lastRequested >= 0f -> RemoteFastScrollExitAnchor.End
        else -> RemoteFastScrollExitAnchor.Start
    }

/** A count of attempts that [tryConsume] hands out [limit] times and then refuses. */
internal class TvRetryBudget(private val limit: Int) {
    private var used = 0

    fun tryConsume(): Boolean {
        if (used >= limit) return false
        used++
        return true
    }

    fun reset() {
        used = 0
    }
}

/**
 * The repeat of one held press: the delay before each repeat, how many focus moves it made, and
 * when it is time to look for a fast scroll container. Only a directional key repeats on the
 * native cadence and counts toward fast scroll, any other key repeats after 500 ms and then every
 * 50 ms.
 */
internal class TvHoldRepeatTracker(
    private val directional: Boolean,
    private val nativeCadence: Boolean = KEY_REPEAT_NATIVE_CADENCE,
) {
    /** Focus moves of the hold so far, its own press included when that was consumed. */
    var moves: Int = 0
        private set

    /** The 0 based number of the repeat that is waited for or dispatched next. */
    var repeatIndex: Int = 0
        private set

    fun onInitialPress(consumed: Boolean) {
        if (consumed) moves++
    }

    /** The delay before the repeat numbered [repeatIndex]. */
    fun nextDelayMs(): Long =
        when {
            directional && nativeCadence -> tvDirectionalKeyRepeatDelayMs(repeatIndex)
            repeatIndex == 0 -> LEGACY_REPEAT_INITIAL_DELAY_MS
            else -> LEGACY_REPEAT_INTERVAL_MS
        }

    /** Whether the repeat about to be dispatched is the one fast scroll replaces. */
    fun shouldTryFastEntry(): Boolean = directional && moves >= FAST_HOLD_ENTRY_MOVES

    fun onRepeatDispatched(consumed: Boolean) {
        repeatIndex++
        if (consumed) moves++
    }
}

private const val LEGACY_REPEAT_INITIAL_DELAY_MS = 500L
private const val LEGACY_REPEAT_INTERVAL_MS = 50L
