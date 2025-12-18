package androidx.compose.mpp.demo.tvos

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
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
    var showOverLay by remember { mutableStateOf(false) }
    var overlayFocus = remember { FocusRequester() }
    MaterialTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF1A1A1A))
        ) {
            Box(modifier = Modifier.fillMaxSize().padding(48.dp)) {
                FocusableCardGrid {
                    showOverLay = true
                    overlayFocus.requestFocus()
                }
            }

            if(showOverLay) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.White)
                        .focusable()
                        .focusRequester(overlayFocus)
                        .onPreviewKeyEvent {
                            if(it.type == KeyEventType.KeyUp && it.key == Key.Back) {
                                showOverLay = false
                            }
                            false
                        }
                )
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
            DemoItem(12, "Lifecycle", "Component Lifecycle", Color(0xFF009688))
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
            FocusableCard(item,onClick)
        }
    }
}

@Composable
fun FocusableCard(item: DemoItem,onClick: (DemoItem) -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    
    Card(
        modifier = Modifier
            .size(width = 280.dp, height = 180.dp)
            .onFocusChanged { focusState ->
                isFocused = focusState.isFocused
            }
            .focusable()
            .clickable {
                onClick(item)
            }
            .border(
                width = if (isFocused) 4.dp else 0.dp,
                color = if (isFocused) Color.White else Color.Transparent
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
