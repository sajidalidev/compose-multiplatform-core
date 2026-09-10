package androidx.compose.mpp.demo.tvos

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSData
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.writeToFile

actual fun initialDemoScreen(): Int? =
    (NSProcessInfo.processInfo.environment["DEMO_SCREEN"] as? String)?.toIntOrNull()

@OptIn(ExperimentalForeignApi::class)
actual fun saveScreenshot(bitmap: ImageBitmap, name: String) {
    val skBitmap = bitmap.asSkiaBitmap()
    val data = Image.makeFromBitmap(skBitmap).encodeToData(EncodedImageFormat.PNG)!!
    val bytes = data.bytes
    val documents =
        NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).first()
            as String
    val path = "$documents/$name"
    val nsData =
        bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
    val written = nsData.writeToFile(path, true)
    println("SCREENSHOT file $path written=$written")
}
