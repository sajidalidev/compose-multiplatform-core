package androidx.compose.mpp.demo.tvos

import kotlin.math.abs
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToFile
import platform.GameController.GCController
import platform.GameController.GCControllerButtonInput
import platform.GameController.GCControllerDidConnectNotification
import platform.GameController.GCMicroGamepad

private val probeStart = NSDate().timeIntervalSince1970

private val attached = mutableSetOf<GCController>()

private val probeLogPath: String by lazy {
    val documents =
        NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).first()
            as String
    "$documents/probe.log"
}

private val probeLogBuffer = StringBuilder()

@OptIn(ExperimentalForeignApi::class)
actual fun probeLog(line: String) {
    println(line)
    probeLogBuffer.append(line).append("\n")
    val bytes = probeLogBuffer.toString().encodeToByteArray()
    val data =
        bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
    data.writeToFile(probeLogPath, true)
}

private class ButtonState {
    var lastPrintMs = -1L
    var a = false
    var x = false
    var menu = false
}

private fun elapsedMs(): Long = ((NSDate().timeIntervalSince1970 - probeStart) * 1000.0).toLong()

private fun fmt(value: Float): String {
    val scaled = (abs(value) * 100f + 0.5f).toInt()
    val whole = scaled / 100
    val frac = scaled % 100
    val fracText = if (frac < 10) "0$frac" else "$frac"
    val sign = if (value < 0f) "-" else ""
    return "$sign$whole.$fracText"
}

private fun bit(pressed: Boolean): String = if (pressed) "1" else "0"

private fun attach(controller: GCController) {
    if (!attached.add(controller)) return
    val micro = controller.physicalInputProfile as? GCMicroGamepad
    probeLog(
        "PROBE controller connected: ${controller.vendorName} " +
            "class=${controller::class.simpleName} " +
            "micro=${micro != null} extended=${controller.extendedGamepad != null}"
    )
    probeLog("PROBE productCategory=${controller.productCategory} vendor=${controller.vendorName}")
    val profile = controller.physicalInputProfile
    val buttons = profile.buttons
    val buttonNames = buttons.keys.mapNotNull { it as? String }.sorted()
    probeLog("PROBE profile buttons: ${buttonNames.joinToString(",")}")
    probeLog(
        "PROBE profile dpads: " +
            profile.dpads.keys.mapNotNull { it as? String }.sorted().joinToString(",")
    )
    buttons.forEach { entry ->
        val name = entry.key as? String ?: return@forEach
        val button = entry.value as? GCControllerButtonInput ?: return@forEach
        button.pressedChangedHandler =
            { _: GCControllerButtonInput?, value: Float, pressed: Boolean ->
                probeLog(
                    "PROBE btn $name pressed=${bit(pressed)} value=${fmt(value)} t=${elapsedMs()}"
                )
            }
    }
    if (micro == null) return
    micro.reportsAbsoluteDpadValues = true
    micro.allowsRotation = false
    micro.buttonA.pressedChangedHandler =
        { _: GCControllerButtonInput?, value: Float, pressed: Boolean ->
            probeLog("PROBE btnA pressed=${bit(pressed)} value=${fmt(value)} t=${elapsedMs()}")
        }
    val state = ButtonState()
    micro.valueChangedHandler = { gamepad: GCMicroGamepad?, _ ->
        if (gamepad != null) {
            val dpad = gamepad.dpad
            val a = gamepad.buttonA.pressed
            val x = gamepad.buttonX.pressed
            val menu = gamepad.buttonMenu.pressed
            val buttonsChanged = a != state.a || x != state.x || menu != state.menu
            val now = elapsedMs()
            if (buttonsChanged || state.lastPrintMs < 0L || now - state.lastPrintMs >= 16L) {
                state.a = a
                state.x = x
                state.menu = menu
                state.lastPrintMs = now
                probeLog(
                    "PROBE t=$now dpad x=${fmt(dpad.xAxis.value)} y=${fmt(dpad.yAxis.value)} " +
                        "A=${bit(a)} X=${bit(x)} menu=${bit(menu)}"
                )
            }
        }
    }
}

actual fun startRemoteProbe() {
    probeLog("PROBE start t=${elapsedMs()}")
    GCController.controllers().forEach { controller ->
        (controller as? GCController)?.let { attach(it) }
    }
    NSNotificationCenter.defaultCenter.addObserverForName(
        GCControllerDidConnectNotification,
        null,
        null,
    ) { _ ->
        GCController.controllers().forEach { controller ->
            (controller as? GCController)?.let { attach(it) }
        }
    }
}

internal fun probeElapsedMs(): Long = elapsedMs()
