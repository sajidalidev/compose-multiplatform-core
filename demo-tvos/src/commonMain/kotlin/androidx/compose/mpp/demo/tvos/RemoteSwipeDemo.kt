package androidx.compose.mpp.demo.tvos

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.remote.RemoteSwipe
import androidx.compose.ui.input.remote.RemoteSwipeDirection
import androidx.compose.ui.input.remote.remoteSwipe
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private const val ITEM_COUNT = 30
private val ITEM_WIDTH = 160.dp

/** Pixels scrolled per pad unit per second of a traverse, so one carries several items. */
private const val TRAVERSE_SCROLL_FACTOR = 60f

/**
 * Three rows that swipe differently: the first one leaves the swipes to the default key conversion,
 * the second one takes the sideways ones with `Modifier.remoteSwipe` and leaves the vertical ones
 * to the keys, and the third one swallows them.
 *
 * The whole screen is wrapped in one more `Modifier.remoteSwipe` that only records what it sees and
 * returns `false`, so a swipe the second row leaves alone shows up there and still moves focus.
 */
@Composable
fun RemoteSwipeScreen(focusRequester: FocusRequester, onDismiss: () -> Unit) {
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    val scope = rememberCoroutineScope()
    val handledState = rememberLazyListState()
    val itemWidthPx = with(LocalDensity.current) { ITEM_WIDTH.toPx() }
    var lastSwipe by remember { mutableStateOf<RemoteSwipe?>(null) }
    var lastOuterSwipe by remember { mutableStateOf<RemoteSwipe?>(null) }

    Column(
        modifier =
            Modifier.fillMaxSize()
                .background(Color(0xFF101418))
                .verticalScroll(rememberScrollState())
                .padding(48.dp)
                .remoteSwipe { swipe ->
                    lastOuterSwipe = swipe
                    false
                }
                // Back is consumed on KeyUp only, the pattern most apps use. It is kept that way
                // on purpose: a Menu press here must return to the grid without the press also
                // reaching UIKit and backgrounding the app.
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyUp && event.key == Key.Back) {
                        onDismiss()
                        true
                    } else false
                },
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Text(
            text = "Outer handler saw: ${lastOuterSwipe?.direction?.toString() ?: "nothing yet"}",
            fontSize = 16.sp,
            color = Color(0xFF9CCC65),
        )

        Label("Default: swipes move focus (D-pad)")
        SwipeRow(
            state = rememberLazyListState(),
            rowModifier = Modifier.focusRequester(focusRequester),
        )

        Label("remoteSwipe enabled: left and right scroll, up and down move focus")
        SwipeRow(
            state = handledState,
            rowModifier =
                Modifier.remoteSwipe { swipe ->
                    lastSwipe = swipe
                    when (swipe.direction) {
                        RemoteSwipeDirection.Left,
                        RemoteSwipeDirection.Right -> {
                            val sign =
                                if (swipe.direction == RemoteSwipeDirection.Left) -1f else 1f
                            val distance =
                                if (swipe.isTraverse) swipe.velocity * TRAVERSE_SCROLL_FACTOR
                                else itemWidthPx
                            scope.launch { handledState.animateScrollBy(sign * distance) }
                            true
                        }
                        // Left to the keys, so a vertical swipe still leaves the row.
                        RemoteSwipeDirection.Up,
                        RemoteSwipeDirection.Down -> false
                    }
                },
        )
        Text(
            text = lastSwipe?.toString() ?: "no swipe yet",
            fontSize = 16.sp,
            color = Color.White.copy(alpha = 0.7f),
        )

        Label("remoteSwipe disabled: swipes ignored")
        SwipeRow(
            state = rememberLazyListState(),
            rowModifier = Modifier.remoteSwipe(enabled = false) { false },
        )
        Text(
            text = "Swiping should do nothing here; clicking the D-pad still moves focus.",
            fontSize = 16.sp,
            color = Color.White.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun Label(text: String) {
    Text(text = text, fontSize = 20.sp, color = Color.White)
}

@Composable
private fun SwipeRow(state: LazyListState, rowModifier: Modifier) {
    LazyRow(
        state = state,
        modifier = rowModifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items((1..ITEM_COUNT).toList()) { index -> SwipeItem(index = index) }
    }
}

@Composable
private fun SwipeItem(index: Int) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    Box(
        modifier =
            Modifier.size(width = ITEM_WIDTH, height = 90.dp)
                .background(
                    color = if (isFocused) Color(0xFF3F51B5) else Color(0xFF243040),
                    shape = RoundedCornerShape(8.dp),
                )
                .border(
                    width = if (isFocused) 3.dp else 1.dp,
                    color = if (isFocused) Color.White else Color.White.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(8.dp),
                )
                .focusable(interactionSource = interactionSource),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = "$index", fontSize = 22.sp, color = Color.White)
    }
}
