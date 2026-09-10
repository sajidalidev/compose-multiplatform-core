package androidx.compose.mpp.demo.tvos

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Divider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay

// The demo does not depend on tv-material, so the NoIndication fix that androidx.tv.material3
// installs is reproduced locally and provided through LocalIndication for this screen.
private data object NoIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = NoIndicationNode()
}

private class NoIndicationNode : Modifier.Node(), DrawModifierNode {
    override fun ContentDrawScope.draw() {
        drawContent()
    }
}

@Composable
fun InputChecksScreen(focusRequester: FocusRequester, onDismiss: () -> Unit) {
    val focusManager = LocalFocusManager.current
    val screenLayer = rememberGraphicsLayer()
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    LaunchedEffect(Unit) {
        delay(3000)
        val bmp = screenLayer.toImageBitmap()
        saveScreenshot(bmp, "inputchecks.png")
        println("SCREENSHOT saved ${bmp.width}x${bmp.height}")
    }
    CompositionLocalProvider(LocalIndication provides NoIndication) {
        BoxWithConstraints(
            modifier =
                Modifier.fillMaxSize()
                    .background(Color(0xFF101418))
                    .drawWithContent {
                        screenLayer.record { this@drawWithContent.drawContent() }
                        drawLayer(screenLayer)
                    }
                    .focusable()
                    .focusRequester(focusRequester)
                    .onPreviewKeyEvent { event ->
                        when {
                            event.type == KeyEventType.KeyUp && event.key == Key.Back -> {
                                onDismiss()
                                true
                            }
                            event.type == KeyEventType.KeyDown &&
                                event.key == Key.DirectionDown -> {
                                if (!focusManager.moveFocus(FocusDirection.Down))
                                    focusManager.moveFocus(FocusDirection.Enter)
                                true
                            }
                            event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp -> {
                                if (!focusManager.moveFocus(FocusDirection.Up))
                                    focusManager.moveFocus(FocusDirection.Enter)
                                true
                            }
                            else -> false
                        }
                    }
        ) {
            val canvasWidth = maxWidth
            val canvasHeight = maxHeight
            InputChecksContent(canvasWidth.value, canvasHeight.value)
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Spacer(modifier = Modifier.height(16.dp))
    Text(text = text, fontSize = 30.sp, color = Color(0xFF90CAF9))
    Divider(color = Color.White.copy(alpha = 0.2f))
    Spacer(modifier = Modifier.height(8.dp))
}

@Composable
private fun Info(text: String) {
    Text(text = text, fontSize = 24.sp, color = Color.White)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InputChecksContent(canvasWidth: Float, canvasHeight: Float) {
    val density = LocalDensity.current
    val keyboardController = LocalSoftwareKeyboardController.current

    var clicks by remember { mutableStateOf(0) }
    var clicks2 by remember { mutableStateOf(0) }
    var longClicks by remember { mutableStateOf(0) }
    var behindClicks by remember { mutableStateOf(0) }
    var showDialog by remember { mutableStateOf(false) }
    var focusedField by remember { mutableStateOf("none") }

    var number by remember { mutableStateOf("12345") }
    var password by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("a@b.c") }
    var auto by remember { mutableStateOf("") }
    var autoFocused by remember { mutableStateOf(false) }
    var autoStatus by remember { mutableStateOf("focus the field to call show()") }
    val plainState = rememberTextFieldState()

    LaunchedEffect(autoFocused) {
        if (autoFocused) {
            keyboardController?.show()
            for (secondsLeft in 4 downTo 1) {
                autoStatus = "keyboard requested at focus; hide() in $secondsLeft s"
                delay(1000)
            }
            keyboardController?.hide()
            autoStatus = "hide() called"
        } else {
            autoStatus = "focus the field to call show()"
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = "Input Checks: MENU returns to the grid", fontSize = 28.sp, color = Color.White)

        SectionHeader("DENSITY")
        Info("canvas $canvasWidth x $canvasHeight dp, density ${density.density}")
        Info("fontScale ${density.fontScale}")
        Info("expected on 1x Apple TV: 960.0 x 540.0 dp, density 2.0")

        SectionHeader("ARC VECTOR (issue 18)")
        Info(
            "Expected: items 1 and 3 show a continuous three-quarter ring open at the top-left; " +
                "fragments only in item 1 reproduce issue 18."
        )
        val arcLayer = rememberGraphicsLayer()
        val arcItemSizePx = with(density) { 96.dp.toPx() }
        LaunchedEffect(Unit) {
            delay(3200)
            val bmp = arcLayer.toImageBitmap()
            saveScreenshot(bmp, "arcrow.png")
            println("SCREENSHOT arcrow saved ${bmp.width}x${bmp.height}")
            val pixels = bmp.toPixelMap()
            val itemW = bmp.width / 3
            val imageH = minOf(bmp.height, arcItemSizePx.toInt())
            for (i in 0 until 3) {
                val x0 = i * itemW
                val midX = x0 + itemW / 2
                val midY = imageH / 2
                var tl = 0
                var tr = 0
                var bl = 0
                var br = 0
                for (y in 0 until imageH) {
                    for (x in x0 until x0 + itemW) {
                        val c = pixels[x, y]
                        if (c.red > 0.588f && c.green < 0.392f && c.blue < 0.392f) {
                            if (y < midY) {
                                if (x < midX) tl++ else tr++
                            } else {
                                if (x < midX) bl++ else br++
                            }
                        }
                    }
                }
                println("ARC item=${i + 1} TL=$tl TR=$tr BL=$bl BR=$br")
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            modifier =
                Modifier.drawWithContent {
                    arcLayer.record { this@drawWithContent.drawContent() }
                    drawLayer(arcLayer)
                },
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val arc270 = remember {
                    ImageVector.Builder(
                            defaultWidth = 24.dp,
                            defaultHeight = 24.dp,
                            viewportWidth = 24f,
                            viewportHeight = 24f,
                        )
                        .apply {
                            path(
                                stroke = SolidColor(Color.Red),
                                strokeLineWidth = 2f,
                                fill = null,
                            ) {
                                moveTo(12f, 3f)
                                arcTo(
                                    horizontalEllipseRadius = 9f,
                                    verticalEllipseRadius = 9f,
                                    theta = 0f,
                                    isMoreThanHalf = true,
                                    isPositiveArc = true,
                                    x1 = 3f,
                                    y1 = 12f,
                                )
                            }
                        }
                        .build()
                }
                Image(
                    painter = rememberVectorPainter(arc270),
                    contentDescription = null,
                    modifier = Modifier.size(96.dp),
                )
                Text(text = "270 deg ImageVector", fontSize = 16.sp, color = Color.White)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val arc90 = remember {
                    ImageVector.Builder(
                            defaultWidth = 24.dp,
                            defaultHeight = 24.dp,
                            viewportWidth = 24f,
                            viewportHeight = 24f,
                        )
                        .apply {
                            path(
                                stroke = SolidColor(Color.Red),
                                strokeLineWidth = 2f,
                                fill = null,
                            ) {
                                moveTo(12f, 3f)
                                arcTo(
                                    horizontalEllipseRadius = 9f,
                                    verticalEllipseRadius = 9f,
                                    theta = 0f,
                                    isMoreThanHalf = false,
                                    isPositiveArc = true,
                                    x1 = 3f,
                                    y1 = 12f,
                                )
                            }
                        }
                        .build()
                }
                Image(
                    painter = rememberVectorPainter(arc90),
                    contentDescription = null,
                    modifier = Modifier.size(96.dp),
                )
                Text(text = "90 deg ImageVector", fontSize = 16.sp, color = Color.White)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Canvas(Modifier.size(96.dp)) {
                    val p = Path()
                    val s = size.width / 24f
                    p.moveTo(12f * s, 3f * s)
                    p.arcTo(
                        Rect(3f * s, 3f * s, 21f * s, 21f * s),
                        startAngleDegrees = -90f,
                        sweepAngleDegrees = 270f,
                        forceMoveTo = false,
                    )
                    drawPath(p, Color.Red, style = Stroke(width = 2f * s))
                }
                Text(text = "270 deg Canvas drawPath", fontSize = 16.sp, color = Color.White)
            }
        }

        SectionHeader("CLICKABLE")
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Box(
                modifier = Modifier.size(160.dp).background(Color.White).clickable { clicks++ },
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "clicks: $clicks", fontSize = 24.sp, color = Color.Black)
            }
            Box(
                modifier =
                    Modifier.size(160.dp)
                        .background(Color.White)
                        .combinedClickable(onClick = { clicks2++ }, onLongClick = { longClicks++ }),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "clicks: $clicks2\nlong: $longClicks",
                    fontSize = 24.sp,
                    color = Color.Black,
                )
            }
            val interactionSource = remember { MutableInteractionSource() }
            val focused by interactionSource.collectIsFocusedAsState()
            Box(
                modifier =
                    Modifier.size(160.dp)
                        .clickable(interactionSource = interactionSource, indication = null) {}
                        .border(4.dp, if (focused) Color.Yellow else Color.Gray),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (focused) "FOCUSED" else "not focused",
                    fontSize = 22.sp,
                    color = Color.White,
                )
            }
        }

        SectionHeader("KEYBOARD")
        Info("focused field: $focusedField")
        Info("Play/Pause on a focused field calls show()")
        LabeledField(
            label = "Number",
            value = number,
            onValueChange = { number = it },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            onFocused = { focusedField = "Number" },
        )
        LabeledField(
            label = "Password",
            value = password,
            onValueChange = { password = it },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            visualTransformation = PasswordVisualTransformation(),
            onFocused = { focusedField = "Password" },
        )
        LabeledField(
            label = "Email / Search",
            value = email,
            onValueChange = { email = it },
            keyboardOptions =
                KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Search),
            onFocused = { focusedField = "Email / Search" },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Plain (state)",
                fontSize = 24.sp,
                color = Color.White,
                modifier = Modifier.width(280.dp),
            )
            BasicTextField(
                state = plainState,
                textStyle = TextStyle(fontSize = 24.sp, color = Color.White),
                modifier =
                    Modifier.weight(1f)
                        .height(56.dp)
                        .onFocusChanged { if (it.isFocused) focusedField = "Plain (state)" }
                        .onKeyEvent { event ->
                            if (
                                event.type == KeyEventType.KeyDown &&
                                    event.key == Key.MediaPlayPause
                            ) {
                                keyboardController?.show()
                                true
                            } else {
                                false
                            }
                        }
                        .background(Color(0xFF404040))
                        .padding(horizontal = 12.dp, vertical = 12.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Auto (show on focus, hide after 4 s)",
                fontSize = 24.sp,
                color = Color.White,
                modifier = Modifier.width(280.dp),
            )
            BasicTextField(
                value = auto,
                onValueChange = { auto = it },
                textStyle = TextStyle(fontSize = 24.sp, color = Color.White),
                modifier =
                    Modifier.weight(1f)
                        .height(56.dp)
                        .onFocusChanged {
                            if (it.isFocused) focusedField = "Auto"
                            autoFocused = it.isFocused
                        }
                        .onKeyEvent { event ->
                            if (
                                event.type == KeyEventType.KeyDown &&
                                    event.key == Key.MediaPlayPause
                            ) {
                                keyboardController?.show()
                                true
                            } else {
                                false
                            }
                        }
                        .background(Color(0xFF404040))
                        .padding(horizontal = 12.dp, vertical = 12.dp),
            )
            Text(
                text = autoStatus,
                fontSize = 22.sp,
                color = Color(0xFF90CAF9),
                modifier = Modifier.width(340.dp).padding(start = 12.dp),
            )
        }

        SectionHeader("DIALOG")
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            ActionBox("Open Dialog") { showDialog = true }
            ActionBox("behind clicks: $behindClicks") { behindClicks++ }
        }

        SectionHeader("HARDWARE KEYBOARD")
        Info("Pair a Bluetooth keyboard and type into any field;")
        Info("arrows, Esc and Backspace must not insert characters.")
    }

    if (showDialog) {
        val itemFocus = remember { FocusRequester() }
        Dialog(onDismissRequest = { showDialog = false }) {
            LaunchedEffect(Unit) { itemFocus.requestFocus() }
            Box(
                modifier =
                    Modifier.size(width = 400.dp, height = 300.dp).background(Color(0xFF16202A)),
                contentAlignment = Alignment.Center,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DialogItem("Item 1", Modifier.focusRequester(itemFocus)) {}
                    DialogItem("Item 2") {}
                    DialogItem("Item 3") {}
                    DialogItem("Close") { showDialog = false }
                }
            }
        }
    }
}

@Composable
private fun LabeledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    keyboardOptions: KeyboardOptions,
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation =
        androidx.compose.ui.text.input.VisualTransformation.None,
    onFocused: () -> Unit,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = label, fontSize = 24.sp, color = Color.White, modifier = Modifier.width(280.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            keyboardOptions = keyboardOptions,
            visualTransformation = visualTransformation,
            textStyle = TextStyle(fontSize = 24.sp, color = Color.White),
            modifier =
                Modifier.weight(1f)
                    .height(56.dp)
                    .onFocusChanged { if (it.isFocused) onFocused() }
                    .onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.MediaPlayPause) {
                            keyboardController?.show()
                            true
                        } else {
                            false
                        }
                    }
                    .background(Color(0xFF404040))
                    .padding(horizontal = 12.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun ActionBox(text: String, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    Box(
        modifier =
            Modifier.height(56.dp)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                )
                .border(4.dp, if (focused) Color.Yellow else Color.Gray)
                .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, fontSize = 24.sp, color = Color.White)
    }
}

@Composable
private fun DialogItem(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    Box(
        modifier =
            modifier
                .width(320.dp)
                .height(56.dp)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                )
                .border(4.dp, if (focused) Color.Yellow else Color.Gray),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, fontSize = 24.sp, color = Color.White)
    }
}
