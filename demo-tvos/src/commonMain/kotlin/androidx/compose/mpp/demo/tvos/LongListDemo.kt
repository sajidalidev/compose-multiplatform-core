package androidx.compose.mpp.demo.tvos

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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

private const val ROW_COUNT = 500

// 45 dp rows with 7 dp gaps are 90 pt and 14 pt at the scene's 1 dp = 2 pt, about 10 rows on a
// 1080 pt screen. That matches the native reference list used to compare swipe momentum.
private val ROW_HEIGHT = 45.dp
private val ROW_SPACING = 7.dp

/** A long list of focusable rows for judging how far a Siri Remote swipe carries focus. */
@Composable
fun LongListScreen(focusRequester: FocusRequester, onDismiss: () -> Unit) {
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    var focusedIndex by remember { mutableStateOf(0) }

    Column(
        modifier =
            Modifier.fillMaxSize()
                .background(Color(0xFF101418))
                .padding(horizontal = 48.dp, vertical = 16.dp)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyUp && event.key == Key.Back) {
                        onDismiss()
                        true
                    } else false
                }
    ) {
        Text(
            text = "Focused row: $focusedIndex",
            fontSize = 20.sp,
            color = Color(0xFF9CCC65),
            modifier = Modifier.padding(bottom = 8.dp),
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(ROW_SPACING),
        ) {
            items((0 until ROW_COUNT).toList()) { index ->
                LongListRow(
                    index = index,
                    modifier = if (index == 0) Modifier.focusRequester(focusRequester) else Modifier,
                    onFocused = { focusedIndex = index },
                )
            }
        }
    }
}

@Composable
private fun LongListRow(index: Int, modifier: Modifier, onFocused: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(ROW_HEIGHT)
                .background(
                    color = if (isFocused) Color(0xFF3F51B5) else Color(0xFF243040),
                    shape = RoundedCornerShape(8.dp),
                )
                .border(
                    width = if (isFocused) 3.dp else 1.dp,
                    color = if (isFocused) Color.White else Color.White.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(8.dp),
                )
                .onFocusChanged { if (it.isFocused) onFocused() }
                .clickable(interactionSource = interactionSource, indication = null) {},
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = "Row $index",
            fontSize = 22.sp,
            color = Color.White,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}
