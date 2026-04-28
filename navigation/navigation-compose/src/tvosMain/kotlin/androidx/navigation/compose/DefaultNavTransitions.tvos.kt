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

package androidx.navigation.compose

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.navigation.NavBackStackEntry

public actual object DefaultNavTransitions {
    public actual val enterTransition:
        AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition =
        { fadeIn(animationSpec = tween(700)) }

    public actual val exitTransition:
        AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition =
        { fadeOut(animationSpec = tween(700)) }

    public actual val predictivePopEnterTransition:
        AnimatedContentTransitionScope<NavBackStackEntry>.(Int) -> EnterTransition =
        {
            fadeIn(
                spring(
                    dampingRatio = 1.0f,
                    stiffness = 1600.0f,
                )
            )
        }

    public actual val predictivePopExitTransition:
        AnimatedContentTransitionScope<NavBackStackEntry>.(Int) -> ExitTransition =
        { scaleOut(targetScale = 0.7f) }

    public actual val sizeTransform:
        (AnimatedContentTransitionScope<NavBackStackEntry>.() -> SizeTransform?)? =
        null

    public actual fun popEnterTransition(
        enterTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition
    ): AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition =
        DefaultNavTransitions.enterTransition

    public actual fun popExitTransition(
        exitTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition
    ): AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition =
        DefaultNavTransitions.exitTransition
}
