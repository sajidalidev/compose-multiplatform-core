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

// tvOS fork: swipes on the touch surface of a remote, which no upstream platform reports.

package androidx.compose.ui.input.remote

/** The direction a [RemoteSwipe] travelled in. */
public enum class RemoteSwipeDirection {
    Left,
    Right,
    Up,
    Down,
}

/** Where on the touch surface the finger of a [RemoteSwipe] started. */
public enum class RemoteSwipeOrigin {
    /** The centre of the pad, which is where a short swipe usually starts. */
    Center,

    /** The outer ring of the pad, which is where a swipe straight across starts. */
    Ring,
}

/**
 * A swipe recognised on the touch surface of a remote, such as the clickpad of the Siri Remote.
 *
 * Swipes are normally turned into directional key events, so focus moves the way it does for the
 * D-pad. A [Modifier.remoteSwipe][androidx.compose.ui.input.remote.remoteSwipe] receives them
 * instead, along with how fast the finger was moving and whether the swipe earned momentum.
 *
 * @property direction the direction the swipe travelled in.
 * @property velocity the speed of the finger along that direction when the swipe was recognised, in
 *   pad units per second, always positive. `0f` when the speed is not known.
 * @property isTraverse whether the swipe earned momentum: the finger went from the ring on one side,
 *   across the centre, to the ring on the far side, fast enough for the default handling to carry
 *   focus past the one move the swipe itself makes.
 * @property momentumSteps the number of extra moves the default handling would have sent after the
 *   first one. `0` when the swipe earned no momentum.
 * @property origin where on the pad the finger started.
 */
public class RemoteSwipe
public constructor(
    public val direction: RemoteSwipeDirection,
    public val velocity: Float,
    public val isTraverse: Boolean,
    public val momentumSteps: Int,
    public val origin: RemoteSwipeOrigin,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RemoteSwipe) return false

        if (direction != other.direction) return false
        if (velocity != other.velocity) return false
        if (isTraverse != other.isTraverse) return false
        if (momentumSteps != other.momentumSteps) return false
        if (origin != other.origin) return false

        return true
    }

    override fun hashCode(): Int {
        var result = direction.hashCode()
        result = 31 * result + velocity.hashCode()
        result = 31 * result + isTraverse.hashCode()
        result = 31 * result + momentumSteps
        result = 31 * result + origin.hashCode()
        return result
    }

    override fun toString(): String =
        "RemoteSwipe(direction=$direction, velocity=$velocity, isTraverse=$isTraverse, " +
            "momentumSteps=$momentumSteps, origin=$origin)"
}
