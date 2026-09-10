package androidx.compose.mpp.demo.tvos

import androidx.compose.ui.graphics.ImageBitmap

expect fun initialDemoScreen(): Int?

expect fun saveScreenshot(bitmap: ImageBitmap, name: String)
