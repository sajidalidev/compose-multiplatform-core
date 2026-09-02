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

import androidx.compose.ui.unit.Density

/**
 * The density the Compose scene lays out at on tvOS: the *square* of the UIKit screen scale.
 *
 * UIKit reports Apple TV at the same scale as a phone (1x/2x), which would give a 1080p TV a
 * 1920x1080 dp canvas — far too dense for a 10-foot UI. Android TV treats 1080p as density 2.0
 * (960x540 dp); squaring the UIKit scale reproduces that. Only the scene's dp-to-pixel density is
 * squared: [screenDensity] stays the UIKit points-to-pixels factor used for touch positions, insets,
 * accessibility frames and `sizeThatFits` (see `ComposeSceneMediator.measureSceneSize`).
 *
 * This is the single owner of that rule. `ComposeSceneMediator` applies it once when the scene is
 * created, so the iOS-mirrored call sites in `ComposeContainer` and `IosComposeSceneLayer` pass the
 * plain UIKit scale exactly like iOS and need no tvOS-specific edits when upstream rewrites them.
 */
internal fun tvSceneDensity(screenDensity: Density, fontScale: Float): Density =
    Density(density = screenDensity.density * screenDensity.density, fontScale = fontScale)
