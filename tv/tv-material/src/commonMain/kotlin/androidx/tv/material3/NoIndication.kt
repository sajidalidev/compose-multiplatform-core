/*
 * Copyright 2023 The Android Open Source Project
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

package androidx.tv.material3

import androidx.compose.foundation.Indication
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.ui.Modifier
import androidx.compose.ui.node.DelegatableNode

/**
 * An [Indication] that draws nothing.
 *
 * Foundation's default [androidx.compose.foundation.LocalIndication] is a debug indication that
 * paints a translucent black overlay over focused, hovered and pressed clickables. TV components
 * express focus through scale, border and glow instead, so [MaterialTheme] installs this
 * indication as the default. Apps that do not use [MaterialTheme] can provide it themselves:
 * `CompositionLocalProvider(LocalIndication provides NoIndication) { ... }`.
 */
object NoIndication : IndicationNodeFactory {
    // A fresh node per call is required: the returned node is delegated by the platform
    // indication wrapper and again by the indication modifier node, and a node can only be
    // delegated once.
    override fun create(interactionSource: InteractionSource): DelegatableNode = NoIndicationNode()

    override fun equals(other: Any?): Boolean = other === this

    override fun hashCode(): Int = 0

    private class NoIndicationNode : Modifier.Node()
}
