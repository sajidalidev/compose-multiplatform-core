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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class DemoItem(
    val id: Int,
    val title: String,
    val description: String,
    val color: Color
)

@Composable
fun TvOSDemoApp() {
    var selectedItem by remember { mutableStateOf<DemoItem?>(null) }
    var overlayFocus = remember { FocusRequester() }
    MaterialTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF1A1A1A))
        ) {
            Box(modifier = Modifier.fillMaxSize().padding(48.dp)) {
                FocusableCardGrid { item ->
                    selectedItem = item
                    overlayFocus.requestFocus()
                }
            }

            selectedItem?.let { item ->
                DetailOverlay(
                    item = item,
                    focusRequester = overlayFocus,
                    onDismiss = { selectedItem = null }
                )
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
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC000000))
            .focusable()
            .focusRequester(focusRequester)
            .onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyUp && it.key == Key.Back) {
                    onDismiss()
                }
                false
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
                    text = "Swipe UP/DOWN to scroll · Swipe LEFT/RIGHT to move focus · BACK to dismiss",
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

@Composable
fun FocusableCardGrid(onClick: (DemoItem) -> Unit) {
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
            DemoItem(18, "Interop", "Native View Interop", Color(0xFFFF6D00))
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
            FocusableCard(item, onClick)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FocusableCard(item: DemoItem, onClick: (DemoItem) -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    var isLongPressed by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .size(width = 280.dp, height = 180.dp)
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
