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

package androidx.compose.mpp.demo.textfield

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.delete
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Icon
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.SecureTextField
import androidx.compose.material.Text
import androidx.compose.material.TextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.mpp.demo.Screen
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val TextFields = Screen.Selection(
    "TextFields",
    Screen.Example("AlmostFullscreen") {
        ClearFocusBox {
            AlmostFullscreen()
        }
    },
    Screen.Example("AlmostFullscreen2") {
        ClearFocusBox {
            AlmostFullscreen2()
        }
    },
    Screen.Example("Keyboard Actions") {
        ClearFocusBox {
            KeyboardActionsExample()
        }
    },
    Screen.Example("Password Textfield Example") {
        ClearFocusBox {
            PasswordTextfieldExample()
        }
    },
    Screen.Example("Emoji") {
        ClearFocusBox {
            EmojiExample()
        }
    },
    Screen.Example("FastDelete") {
        ClearFocusBox {
            FastDelete()
        }
    },
    Screen.Example("OutlinedTextField") {
        ClearFocusBox {
            var text by remember { mutableStateOf("Some text") }
            OutlinedTextField(
                readOnly = true,
                value = text,
                onValueChange = { text = it },
                label = { Text("OutlinedTextField Label") },
            )
        }
    },
    Screen.Example("BasicTextField") {
        var text by remember { mutableStateOf("usage of BasicTextField") }
        BasicTextField(text, { text = it })
    },

    Screen.Example("BasicTextField2") {
        var textFieldState by remember { mutableStateOf("I am an old TextField") }
        val textFieldState2 = remember { TextFieldState("I am a BasicTextField(TextFieldState)") }
        val textFieldState3 = remember { TextFieldState(bigTextExampleString) }

        val defaultModifier = Modifier
            .padding(16.dp)
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = Color.LightGray,
                shape = RoundedCornerShape(4.dp)
            )

        ClearFocusBox {
            Column(Modifier.fillMaxWidth()) {
                BasicTextField(
                    textFieldState,
                    onValueChange = { textFieldState = it },
                    defaultModifier.height(24.dp)
                )
                Box(Modifier.height(16.dp))
                @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
                val nativeKeyboardOptions = nativeKeyboardOptionsUseNativeInputHandling(true)
                BasicTextField(
                    textFieldState2,
                    defaultModifier.height(24.dp),
                    keyboardOptions = nativeKeyboardOptions
                )
                Box(Modifier.height(16.dp))
                BasicTextField(
                    textFieldState3,
                    defaultModifier
                )
            }
        }
    },

    Screen.Example("RTL and BiDi") {
        ClearFocusBox { RtlAndBidiTextfieldExample() }
    }
)


val NITITextFields = Screen.Selection(
    title = "NITI Tests",
    screens = listOf(
        Screen.Example("Brush") {
            ClearFocusBox {
                Column {
                    var text by remember { mutableStateOf("BasicTextField 1 with a long text") }
                    TextField(
                        value = text,
                        onValueChange = { text = it },
                        Modifier.fillMaxWidth().height(56.dp),
                        textStyle = TextStyle(
                            brush = Brush.linearGradient(listOf(Color.Magenta, Color.Cyan)),
                            fontSize = 18.sp
                        )
                    )
                    Box(modifier = Modifier.height(16.dp))
                    var text2 = rememberTextFieldState("BasicTextField 2 with a long text")
                    BasicTextField(
                        state = text2,
                        Modifier.fillMaxWidth().height(56.dp),
                        textStyle = TextStyle(
                            brush = Brush.linearGradient(listOf(Color.Magenta, Color.Cyan)),
                            fontSize = 18.sp
                        )
                    )
                }
            }
        },
        Screen.Example("InteractionSource focus border") {
            Column {
                var text by remember { mutableStateOf("BasicTextField 1 with a long text") }
                val interaction1 = remember { MutableInteractionSource() }
                val focused1 by interaction1.collectIsFocusedAsState()
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    interactionSource = interaction1,
                    modifier = Modifier
                        .border(2.dp, if (focused1) Color.Cyan else Color.Gray, RoundedCornerShape(8.dp))
                        .padding(8.dp)
                )
                Box(modifier = Modifier.height(16.dp))
                val state = rememberTextFieldState("BasicTextField 2 with a long text")
                val interaction = remember { MutableInteractionSource() }
                val focused by interaction.collectIsFocusedAsState()
                BasicTextField(
                    state = state,
                    interactionSource = interaction,
                    modifier = Modifier
                        .border(2.dp, if (focused) Color.Cyan else Color.Gray, RoundedCornerShape(8.dp))
                        .padding(8.dp)
                )
            }
        },
        Screen.Example("TextFieldDecorator / DecorationBox") {
            ClearFocusBox {
                Column {
                    var text by remember { mutableStateOf("BasicTextField 1 with a long text") }
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                        decorationBox = { inner ->
                            Row(
                                Modifier
                                    .background(Color(0xFF121212), RoundedCornerShape(10.dp))
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Search, contentDescription = null, tint = Color.Gray)
                                Spacer(Modifier.width(8.dp))
                                Box(Modifier.weight(1f)) {
                                    if (text.isEmpty()) Text("Search…", color = Color.Gray)
                                    inner()
                                }
                                Text("${text.length}", color = Color.Gray)
                            }
                        }
                    )

                    Box(Modifier.height(16.dp))

                    val state2 = rememberTextFieldState("BasicTextField 2 with a long text")
                    BasicTextField(
                        state = state2,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                        decorator = { inner ->
                            Row(
                                Modifier
                                    .background(Color(0xFF121212), RoundedCornerShape(10.dp))
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Search, contentDescription = null, tint = Color.Gray)
                                Spacer(Modifier.width(8.dp))
                                Box(Modifier.weight(1f)) {
                                    if (state2.text.isEmpty()) Text("Search…", color = Color.Gray)
                                    inner()
                                }
                                Text("${state2.text.length}", color = Color.Gray)
                            }
                        }
                    )
                }
            }
        },
        Screen.Example("ScrollState") {
            ClearFocusBox {
                Column {
                    var text by remember { mutableStateOf(("lots of text BTF1 \n").repeat(40)) }
                    val scroll1 = rememberScrollState()
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .verticalScroll(scroll1),
                        textStyle = TextStyle(color = Color.Black, fontSize = 14.sp)
                    )

                    Box(Modifier.height(16.dp))

                    val state2 = rememberTextFieldState(("Lots of text BTF2\n").repeat(40))
                    val scroll2 = rememberScrollState()
                    BasicTextField(
                        state = state2,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .verticalScroll(scroll2),
                        textStyle = TextStyle(color = Color.Black, fontSize = 14.sp)
                    )
                }
            }
        },
        Screen.Example("GraphicsLayer") {
            ClearFocusBox {
                Column {
                    var text by remember { mutableStateOf("BasicTextField 1 with a long text") }
                    val pulse1 by rememberInfiniteTransition().animateFloat(
                        initialValue = 0.96f, targetValue = 1.04f,
                        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse)
                    )
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .graphicsLayer { scaleX = pulse1; scaleY = pulse1 },
                        textStyle = TextStyle(color = Color.Black, fontSize = 18.sp)
                    )

                    Box(Modifier.height(16.dp))

                    val state2 = rememberTextFieldState("BasicTextField 2 with a long text")
                    val pulse2 by rememberInfiniteTransition().animateFloat(
                        0.96f, 1.04f, infiniteRepeatable(tween(600), RepeatMode.Reverse)
                    )
                    BasicTextField(
                        state = state2,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .graphicsLayer { scaleX = pulse2; scaleY = pulse2 },
                        textStyle = TextStyle(color = Color.Black, fontSize = 18.sp)
                    )
                }
            }
        },
        Screen.Example("Appearance modifiers") {
            ClearFocusBox {
                Column {
                    var text by remember { mutableStateOf("BasicTextField 1 with a long text") }
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        textStyle = TextStyle(color = Color.Black, fontSize = 16.sp),
                        cursorBrush = SolidColor(Color.Cyan),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .background(Color(0xFF202124), RoundedCornerShape(10.dp))
                            .border(1.dp, Color(0xFF2A2A2A), RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    )

                    Box(Modifier.height(16.dp))

                    val state2 = rememberTextFieldState("BasicTextField 2 with a long text")
                    BasicTextField(
                        state = state2,
                        textStyle = TextStyle(color = Color.Black, fontSize = 16.sp),
                        cursorBrush = SolidColor(Color.Cyan),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .background(Color(0xFF202124), RoundedCornerShape(10.dp))
                            .border(1.dp, Color(0xFF2A2A2A), RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }
        },
        Screen.Example("Secure input") {
            ClearFocusBox {
                Column {
                    var text by remember { mutableStateOf("BasicTextField 1 with a long text") }
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            autoCorrectEnabled = false
                        ),
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        textStyle = TextStyle(color = Color.Black, fontSize = 16.sp)
                    )

                    Box(Modifier.height(16.dp))

                    val state2 = rememberTextFieldState("SecureTextField with a long text")
                    SecureTextField(
                        state = state2,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            autoCorrectEnabled = false
                        ),
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        textStyle = TextStyle(color = Color.Black, fontSize = 16.sp)
                    )
                }
            }
        },
        Screen.Example("Transformations (Phone)") {
            ClearFocusBox {
                Column {
                    // VisualTransformation for String-based BasicTextField
                    val phoneMask = remember {
                        VisualTransformation { text ->
                            val raw = text.text.filter(Char::isDigit)
                                .let { if (it.startsWith("7")) it.drop(1) else it }

                            val formatted = buildString {
                                append("+7 ")
                                if (raw.isNotEmpty()) {
                                    if (raw.length >= 3) {
                                        append("(${raw.take(3)}) ")
                                        if (raw.length >= 6) {
                                            append(raw.substring(3, 6))
                                            append('-')
                                            if (raw.length >= 8) {
                                                append(raw.substring(6, 8))
                                                append('-')
                                                if (raw.length > 8) {
                                                    append(raw.substring(8))
                                                }
                                            } else if (raw.length > 6) {
                                                append(raw.substring(6))
                                            }
                                        } else if (raw.length > 3) {
                                            append(raw.substring(3))
                                        }
                                    } else {
                                        append(raw)
                                    }
                                }
                            }

                            // Create offset mapping for proper cursor positioning
                            val offsetMapping = object : OffsetMapping {
                                override fun originalToTransformed(offset: Int): Int {
                                    if (offset <= 0) return 3 // "+7 "

                                    val digitsBeforeCursor = text.text.take(offset).count(Char::isDigit)
                                    val normalized = if (text.text.take(offset).startsWith("7"))
                                        (digitsBeforeCursor - 1).coerceAtLeast(0) else digitsBeforeCursor

                                    return when {
                                        normalized <= 0 -> 3
                                        normalized <= 3 -> 4 + normalized // "+7 (xxx"
                                        normalized <= 6 -> 6 + normalized // "+7 (xxx) xxx"
                                        normalized <= 8 -> 7 + normalized // "+7 (xxx) xxx-xx"
                                        else -> 8 + normalized // "+7 (xxx) xxx-xx-xx"
                                    }
                                }

                                override fun transformedToOriginal(offset: Int): Int {
                                    if (offset <= 3) return 0 // before/in "+7 "

                                    val withoutPrefix = offset - 3
                                    val digitsCount = when {
                                        withoutPrefix <= 1 -> 0 // "("
                                        withoutPrefix <= 4 -> withoutPrefix - 1 // "(xxx"
                                        withoutPrefix <= 6 -> withoutPrefix - 2 // "(xxx) "
                                        withoutPrefix <= 9 -> withoutPrefix - 3 // "(xxx) xxx"
                                        withoutPrefix <= 10 -> withoutPrefix - 4 // "(xxx) xxx-"
                                        withoutPrefix <= 12 -> withoutPrefix - 5 // "(xxx) xxx-xx"
                                        withoutPrefix <= 13 -> withoutPrefix - 6 // "(xxx) xxx-xx-"
                                        else -> withoutPrefix - 7 // "(xxx) xxx-xx-xxx..."
                                    }

                                    return text.text.take(text.text.length)
                                        .withIndex()
                                        .count { it.value.isDigit() && it.index < digitsCount }
                                }
                            }

                            TransformedText(AnnotatedString(formatted), offsetMapping)
                        }
                    }

                    var text by remember { mutableStateOf("") }
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it.filter { char -> char.isDigit() }.take(11) },
                        visualTransformation = phoneMask,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        textStyle = TextStyle(color = Color.Black, fontSize = 16.sp)
                    )

                    Box(Modifier.height(16.dp))

                    // OutputTransformation for TextFieldState-based BasicTextField
                    val state2 = rememberTextFieldState("")
                    BasicTextField(
                        state = state2,
                        inputTransformation = {
                            // Filter to keep only digits, max 11 digits
                            if (!asCharSequence().all { it.isDigit() }) {
                                val digitsOnly = asCharSequence().filter { it.isDigit() }.toString()
                                replace(0, length, digitsOnly.take(11))
                            } else if (length > 11) {
                                delete(11, length)
                            }
                        },
                        outputTransformation = {
                            val raw = asCharSequence().toString()
                                .let { if (it.startsWith("7")) it.drop(1) else it }

                            val formatted = buildString {
                                append("+7 ")
                                if (raw.isNotEmpty()) {
                                    if (raw.length >= 3) {
                                        append("(${raw.take(3)}) ")
                                        if (raw.length >= 6) {
                                            append(raw.substring(3, 6))
                                            append('-')
                                            if (raw.length >= 8) {
                                                append(raw.substring(6, 8))
                                                append('-')
                                                if (raw.length > 8) {
                                                    append(raw.substring(8))
                                                }
                                            } else if (raw.length > 6) {
                                                append(raw.substring(6))
                                            }
                                        } else if (raw.length > 3) {
                                            append(raw.substring(3))
                                        }
                                    } else {
                                        append(raw)
                                    }
                                }
                            }

                            replace(0, length, formatted)
                        },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        textStyle = TextStyle(color = Color.Black, fontSize = 16.sp)
                    )
                }
            }
        },
    )
)

@Composable
private fun AlmostFullscreen() {
    val textState = remember {
        mutableStateOf(
            buildString {
                repeat(100) {
                    appendLine("Text line $it")
                }
            }
        )
    }
    TextField(
        textState.value, { textState.value = it },
        Modifier.fillMaxSize().padding(vertical = 40.dp)
    )
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun AlmostFullscreen2() {
    val state = remember {
        TextFieldState(
            buildString {
                repeat(100) {
                    appendLine("Text line $it")
                }
            }
        )
    }
    TextField(
        state,
        Modifier.fillMaxSize().padding(vertical = 40.dp).background(Color.LightGray),
        keyboardOptions = nativeKeyboardOptionsUseNativeInputHandling(true)
    )
}

private val bigTextExampleString = """
    An example of big text in a TextField
    
    And another paragraph. Just to be sure. Here are random numbers: 0000000 00000000
    0000 000000000000 000000 00000000 0 000
    
    Test with some brackets (word) (longer phrase in a brackets)
    [different types of brackets] words between brackets [another one]
    {let's use curly brackets too} words between brackets {and again}
    
    This must be a random long phrase to check BiDi
    يجب أن تكون هذه عبارة طويلة عشوائية للتحقق من Bidi

    A compound emoji line: 👨‍👩‍👧‍👦👨‍👩‍👧‍👦👨‍👩‍👧‍👦👨‍👩‍👧‍👦,
    
    """.trimIndent() +
    """
            Lorem ipsum dolor sit amet, consectetur adipiscing elit. 
            Praesent placerat ligula sapien, sit amet viverra ligula bibendum sed. Fusce vitae neque pulvinar, 
            tempus sapien ut, tincidunt nisl. Interdum et malesuada fames ac ante ipsum primis in faucibus. 
            Sed aliquam congue euismod. Etiam porttitor vehicula ex, a interdum sapien. 
            Nam sed vehicula dui, quis ornare nulla. Nullam porttitor ante nec magna porta, eget cursus sapien auctor. 
            Sed hendrerit, nisi eget consequat molestie, eros massa tristique mi, sed consectetur nisl mauris quis eros. 
            Quisque id leo a sem euismod iaculis non sit amet orci. Proin efficitur pellentesque orci vitae facilisis. 
            Nulla vulputate tempus leo, ut vehicula ex. Maecenas fringilla pulvinar erat, ac dapibus libero tempor vel. 
            Sed et dapibus sapien, vel imperdiet augue.
    """.trimIndent().replace("\n", " ") +
    """
            Integer finibus justo facilisis mi porttitor,
            et malesuada ligula pretium. Integer ipsum felis, 
            dictum a metus ut, sagittis mattis libero. Morbi facilisis pulvinar nulla eget molestie. 
            Nulla porta neque eros, at vulputate turpis tristique pretium. 
            Vestibulum aliquet metus id nisi euismod varius. Nunc nec mi id lorem molestie interdum. 
            Fusce eget metus quis dui varius scelerisque et id mauris. In sit amet nunc sed tellus sagittis finibus. 
            Aliquam eleifend lorem vitae lobortis dapibus. Suspendisse ipsum nisi, molestie et porta quis, maximus at ante. 
            Nam et accumsan nisi, sit amet efficitur ante. Aliquam id volutpat quam, at vestibulum ligula. 
    """.trimIndent().replace("\n", " ")