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

/** Android TV treats a 1080p (1920x1080 px) screen as density 2.0, a 960x540 dp canvas. */
private const val TEN_FOOT_DENSITY_PER_1080P = 2f

/**
 * The density the Compose scene lays out at on tvOS: a constant 10-foot factor times the UIKit
 * screen scale.
 *
 * tvOS always exposes a 1920x1080 point canvas, at UIKit scale 1x (Apple TV HD, or any Apple TV
 * driving a 1080p display) or 2x (Apple TV 4K driving a 4K display). Android TV lays out 1080p at
 * density 2.0 (960x540 dp), so the scene density is that 10-foot factor multiplied by the UIKit
 * scale: 2.0 on a 1x screen (1920 px / 2.0 = 960 dp) and 4.0 on a 2x screen (3840 px / 4.0 = 960
 * dp), both a 960x540 dp canvas. The previous rule squared the UIKit scale instead, which
 * coincides with this only at 2x and collapsed to density 1.0 (a 1920x1080 dp canvas, no 10-foot
 * scaling at all) on 1x hardware such as Apple TV HD.
 *
 * Only the scene's dp-to-pixel density is scaled this way: [screenDensity] stays the UIKit
 * points-to-pixels factor used for touch positions, insets, accessibility frames and
 * `sizeThatFits` (see `ComposeSceneMediator.measureSceneSize`).
 *
 * This is the single owner of that rule. `ComposeSceneMediator` applies it once when the scene is
 * created, so the iOS-mirrored call sites in `ComposeContainer` and `IosComposeSceneLayer` pass the
 * plain UIKit scale exactly like iOS and need no tvOS-specific edits when upstream rewrites them.
 */
internal fun tvSceneDensity(screenDensity: Density, fontScale: Float): Density =
    Density(density = TEN_FOOT_DENSITY_PER_1080P * screenDensity.density, fontScale = fontScale)
