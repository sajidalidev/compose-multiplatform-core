package androidx.compose.mpp.demo.tvos

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isRepeat
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

data class DemoItem(
    val id: Int,
    val title: String,
    val description: String,
    val color: Color
)

private const val TEXT_INPUT_DEMO_ID = 19

// Routes the "Dialogs" card to a real Popup (a ComposeSceneLayer) instead of the inline overlay,
// so the scene-layer first-responder reclaim on close can be exercised.
private const val POPUP_DEMO_ID = 16

@Composable
fun TvOSDemoApp() {
    var selectedItem by remember { mutableStateOf<DemoItem?>(null) }
    val overlayFocus = remember { FocusRequester() }
    // One FocusRequester per card so focus is restored to the EXACT card that opened an overlay once
    // it's dismissed. We restore explicitly (not via focusRestorer()) because the opening card is
    // always known (selectedItem), and focusRestorer()'s implicit save is scene-local — it doesn't
    // cover the Popup case, where the overlay is a separate ComposeSceneLayer and focus never
    // "leaves" the grid group within the main scene, so it would fall back to an arbitrary card.
    val cardFocusRequesters = remember { mutableMapOf<Int, FocusRequester>() }
    val focusRequesterFor: (Int) -> FocusRequester = { id ->
        cardFocusRequesters.getOrPut(id) { FocusRequester() }
    }
    var lastOpenedId by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(selectedItem) {
        val opened = selectedItem
        if (opened != null) {
            lastOpenedId = opened.id
        } else {
            lastOpenedId?.let { id ->
                lastOpenedId = null
                cardFocusRequesters[id]?.requestFocus()
            }
        }
    }
    MaterialTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF1A1A1A))
        ) {
            Box(modifier = Modifier.fillMaxSize().padding(48.dp)) {
                FocusableCardGrid(focusRequesterFor = focusRequesterFor) { item ->
                    selectedItem = item
                }
            }

            selectedItem?.let { item ->
                when (item.id) {
                    TEXT_INPUT_DEMO_ID -> TextInputDemo(
                        focusRequester = overlayFocus,
                        onDismiss = { selectedItem = null }
                    )
                    POPUP_DEMO_ID -> PopupOverlayDemo(
                        item = item,
                        onDismiss = { selectedItem = null }
                    )
                    else -> DetailOverlay(
                        item = item,
                        focusRequester = overlayFocus,
                        onDismiss = { selectedItem = null }
                    )
                }
            }
        }
    }
}

@Composable
fun DetailOverlay(
    item: DemoItem,
    focusRequester: FocusRequester,
    onDismiss: () -> Unit
) {
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC000000))
            .focusable()
            .focusRequester(focusRequester)
            .onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyUp && it.key == Key.Back) {
                    onDismiss()
                    true
                } else false
            },
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.size(width = 700.dp, height = 500.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A2A))
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(32.dp)) {
                Text(
                    text = item.title,
                    fontSize = 36.sp,
                    color = item.color,
                    style = MaterialTheme.typography.headlineLarge
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = item.description,
                    fontSize = 20.sp,
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(modifier = Modifier.height(16.dp))
                Divider(color = Color.White.copy(alpha = 0.2f))
                Spacer(modifier = Modifier.height(16.dp))

                // Items are clickable but NOT individually focusable.
                // focusProperties { canFocus = false } prevents D-pad focus from entering the list
                // item-by-item, so swiping scrolls the list instead of moving focus through rows.
                Text(
                    text = "Swipe UP/DOWN to scroll · Swipe LEFT/RIGHT to move focus · MENU to dismiss",
                    fontSize = 14.sp,
                    color = Color.White.copy(alpha = 0.5f)
                )
                Spacer(modifier = Modifier.height(12.dp))
                var tappedIndex by remember { mutableStateOf<Int?>(null) }
                val listItems = remember { (1..30).toList() }
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(listItems) { index ->
                        val tapped = tappedIndex == index
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { tappedIndex = index }
                                .focusProperties { canFocus = false }
                                .background(if (tapped) item.color.copy(alpha = 0.2f) else Color.Transparent)
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(item.color, shape = CircleShape)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "${item.title} item $index${if (tapped) " ✓" else ""}",
                                fontSize = 18.sp,
                                color = if (tapped) item.color else Color.White.copy(alpha = 0.85f)
                            )
                        }
                        Divider(color = Color.White.copy(alpha = 0.08f))
                    }
                }
            }
        }
    }
}

/**
 * A REAL overlay backed by a [Popup] (a ComposeSceneLayer), unlike the inline [DetailOverlay].
 * Used to exercise the scene-layer first-responder reclaim on close: after dismissing with MENU,
 * the grid D-pad should keep working immediately, with no extra wake-up press.
 */
@Composable
fun PopupOverlayDemo(item: DemoItem, onDismiss: () -> Unit) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Popup(
        alignment = Alignment.Center,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true, dismissOnBackPress = false)
    ) {
        Box(
            modifier = Modifier
                .size(width = 700.dp, height = 460.dp)
                .background(Color(0xFF2A2A2A), shape = RoundedCornerShape(16.dp))
                .focusable()
                .focusRequester(focusRequester)
                .onPreviewKeyEvent {
                    if (it.type == KeyEventType.KeyUp && it.key == Key.Back) {
                        onDismiss()
                        true
                    } else false
                },
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(32.dp)
            ) {
                Text(
                    text = "${item.title} — real Popup layer",
                    fontSize = 32.sp,
                    color = item.color,
                    style = MaterialTheme.typography.headlineLarge
                )
                Text(
                    text = "This overlay is a ComposeSceneLayer. Press MENU to dismiss — the grid " +
                        "D-pad should work immediately after, with no extra button press.",
                    fontSize = 16.sp,
                    color = Color.White.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
fun FocusableCardGrid(focusRequesterFor: (Int) -> FocusRequester, onClick: (DemoItem) -> Unit) {
    val items = remember {
        listOf(
            DemoItem(1, "Foundation", "Layout & Focus APIs", Color(0xFF6200EE)),
            DemoItem(2, "Material", "Cards & Typography", Color(0xFF03DAC6)),
            DemoItem(3, "UI Graphics", "Canvas & Drawing", Color(0xFFFF6F00)),
            DemoItem(4, "Text", "Typography System", Color(0xFFE91E63)),
            DemoItem(5, "Animation", "Transitions & Motion", Color(0xFF00BCD4)),
            DemoItem(6, "Gestures", "Input Handling", Color(0xFF4CAF50)),
            DemoItem(7, "Modifiers", "UI Customization", Color(0xFF9C27B0)),
            DemoItem(8, "Runtime", "Composition API", Color(0xFFFF5722)),
            DemoItem(9, "State", "State Management", Color(0xFF2196F3)),
            DemoItem(10, "Effects", "Side Effects API", Color(0xFFFFC107)),
            DemoItem(11, "Navigation", "Screen Navigation", Color(0xFF673AB7)),
            DemoItem(12, "Lifecycle", "Component Lifecycle", Color(0xFF009688)),
            DemoItem(13, "Theming", "Colors & Shapes", Color(0xFF795548)),
            DemoItem(14, "Accessibility", "A11y Support", Color(0xFF607D8B)),
            DemoItem(15, "Images", "Async Image Loading", Color(0xFFFF4081)),
            DemoItem(16, "Dialogs", "Popups & Sheets", Color(0xFF536DFE)),
            DemoItem(17, "Canvas", "Custom Drawing", Color(0xFF00E676)),
            DemoItem(18, "Interop", "Native View Interop", Color(0xFFFF6D00)),
            DemoItem(TEXT_INPUT_DEMO_ID, "Text Input", "System Keyboard Input", Color(0xFF1565C0))
        )
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(items) { item ->
            FocusableCard(item, focusRequesterFor(item.id), onClick)
        }
    }
}

@Composable
fun TextInputDemo(focusRequester: FocusRequester, onDismiss: () -> Unit) {
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC000000))
            .focusable()
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                when {
                    event.type == KeyEventType.KeyUp && event.key == Key.Back -> {
                        onDismiss(); true
                    }
                    // Intercept D-pad UP/DOWN so BasicTextField doesn't consume them for
                    // cursor movement — on tvOS the D-pad is for focus navigation, not text editing.
                    event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown -> {
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
            },
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.size(width = 800.dp, height = 560.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E2A3A))
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(40.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                Text(
                    text = "Text Input Demo",
                    fontSize = 36.sp,
                    color = Color(0xFF90CAF9),
                    style = MaterialTheme.typography.headlineLarge
                )
                Text(
                    text = "Navigate to a field with D-pad · Press SELECT to open keyboard · Press MENU to dismiss",
                    fontSize = 16.sp,
                    color = Color.White.copy(alpha = 0.6f)
                )
                Divider(color = Color.White.copy(alpha = 0.2f))

                LabeledTextField(label = "Username", placeholder = "Enter username…")
                LabeledTextField(label = "Search", placeholder = "Search for something…")
                LabeledTextField2(label = "Notes (TextFieldState)", placeholder = "Type notes here…")
            }
        }
    }
}

@Composable
private fun LabeledTextField(label: String, placeholder: String) {
    var text by remember { mutableStateOf("") }
    var isFocused by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text = label, fontSize = 18.sp, color = Color.White.copy(alpha = 0.8f))
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            textStyle = TextStyle(fontSize = 20.sp, color = Color.White),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { isFocused = it.isFocused }
                .border(
                    width = if (isFocused) 2.dp else 1.dp,
                    color = if (isFocused) Color(0xFF90CAF9) else Color.White.copy(alpha = 0.3f),
                    shape = RoundedCornerShape(8.dp)
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
            decorationBox = { inner ->
                if (text.isEmpty()) {
                    Text(placeholder, fontSize = 20.sp, color = Color.White.copy(alpha = 0.35f))
                }
                inner()
            }
        )
    }
}

@Composable
private fun LabeledTextField2(label: String, placeholder: String) {
    val state = remember { TextFieldState() }
    var isFocused by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text = label, fontSize = 18.sp, color = Color.White.copy(alpha = 0.8f))
        BasicTextField(
            state = state,
            textStyle = TextStyle(fontSize = 20.sp, color = Color.White),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { isFocused = it.isFocused }
                .border(
                    width = if (isFocused) 2.dp else 1.dp,
                    color = if (isFocused) Color(0xFF90CAF9) else Color.White.copy(alpha = 0.3f),
                    shape = RoundedCornerShape(8.dp)
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
            decorator = { inner ->
                if (state.text.isEmpty()) {
                    Text(placeholder, fontSize = 20.sp, color = Color.White.copy(alpha = 0.35f))
                }
                inner()
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FocusableCard(item: DemoItem, focusRequester: FocusRequester, onClick: (DemoItem) -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    var isLongPressed by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .size(width = 280.dp, height = 180.dp)
            .focusRequester(focusRequester)
            .onFocusChanged { focusState ->
                isFocused = focusState.isFocused
                if (!focusState.isFocused) isLongPressed = false
            }
            // combinedClickable handles long-press on Key.DirectionCenter (Select) natively.
            // For any other held key, use onPreviewKeyEvent + event.isRepeat.
            // NOTE: do NOT add .focusable() before combinedClickable — it adds its own FocusTarget
            // internally, and a second outer FocusTarget causes lastLocalKeyInputNode() to return
            // null, so key events never reach combinedClickable.onKeyEvent.
            .combinedClickable(
                onClick = { onClick(item) },
                onLongClick = { isLongPressed = true }
            )
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.isRepeat) {
                    // Any key held past keyRepeatInitialDelayMs — treat as long press signal.
                    isLongPressed = true
                }
                false // don't consume; let normal key handling proceed
            }
            .border(
                width = if (isFocused) 4.dp else 0.dp,
                color = if (isFocused) {
                    if (isLongPressed) Color.Yellow else Color.White
                } else Color.Transparent
            ),
        colors = CardDefaults.cardColors(
            containerColor = item.color.copy(alpha = if (isFocused) 1f else 0.7f)
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isFocused) 16.dp else 4.dp
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = item.title,
                    fontSize = if (isFocused) 32.sp else 28.sp,
                    color = Color.White,
                    style = MaterialTheme.typography.headlineLarge
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = item.description,
                    fontSize = if (isFocused) 20.sp else 18.sp,
                    color = Color.White.copy(alpha = 0.9f),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
    }
}
