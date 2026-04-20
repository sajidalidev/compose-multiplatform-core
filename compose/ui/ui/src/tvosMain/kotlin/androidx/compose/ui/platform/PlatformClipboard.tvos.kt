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

import androidx.compose.ui.text.AnnotatedString

actual class NativeClipboard

actual class ClipEntry internal constructor() {
    actual val clipMetadata: ClipMetadata
        get() = TODO("ClipMetadata is not supported on tvOS")
}

private class TvOSPlatformClipboard : Clipboard {
    override suspend fun getClipEntry(): ClipEntry? = null

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        // No-op: tvOS does not support clipboard
    }

    override val nativeClipboard: NativeClipboard
        get() = NativeClipboard()
}

@Suppress("DEPRECATION")
private class TvOSPlatformClipboardManager : ClipboardManager {
    override fun getText(): AnnotatedString? = null
    override fun setText(annotatedString: AnnotatedString) {}
    override fun hasText(): Boolean = false
    override fun getClip(): ClipEntry? = null

    @Suppress("GetterSetterNames")
    override fun setClip(clipEntry: ClipEntry?) = Unit
}

@Suppress("DEPRECATION")
internal actual fun createPlatformClipboardManager(): ClipboardManager = TvOSPlatformClipboardManager()

internal actual fun createPlatformClipboard(): Clipboard = TvOSPlatformClipboard()
