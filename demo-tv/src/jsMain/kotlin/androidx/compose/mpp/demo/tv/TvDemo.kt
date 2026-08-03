/*
 * Copyright 2026 The Android Open Source Project
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

package androidx.compose.mpp.demo.tv

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isRepeat
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Background = Color(0xFF101014)
private val Surface = Color(0xFF1D1D24)
private val Accent = Color(0xFF7AC7FF)

/** A tile in the demo's grid. */
data class DemoTile(
    val id: Int,
    val title: String,
    val subtitle: String,
    val color: Color,
)

private val demoTiles = listOf(
    DemoTile(1, "Focus", "D-pad traversal", Color(0xFF1E88E5)),
    DemoTile(2, "Remote", "TV remote keys", Color(0xFF43A047)),
    DemoTile(3, "Density", "10-foot scaling", Color(0xFFE53935)),
    DemoTile(4, "Back", "Navigation events", Color(0xFF8E24AA)),
    DemoTile(5, "Repeat", "Held-key handling", Color(0xFFF4511E)),
    DemoTile(6, "Skiko", "WebGL2 rendering", Color(0xFF00897B)),
    DemoTile(7, "Layout", "Lazy grids", Color(0xFF3949AB)),
    DemoTile(8, "Text", "Typography", Color(0xFF6D4C41)),
    DemoTile(9, "Theme", "Material 3", Color(0xFFC0CA33)),
)

/**
 * Demo app for a TV, driven entirely by the remote: the four-way pad moves focus, OK opens a tile,
 * Back closes it, and Back on the grid quits.
 *
 * @param platformName the detected TV platform, shown in the header so a run on a real set proves
 * the detection worked. `None` means the app is running in an ordinary browser.
 * @param onExit invoked when Back is pressed on the top-level screen, which a TV user expects to
 * quit the app.
 */
@Composable
fun TvDemoApp(platformName: String = "None", onExit: () -> Unit = {}) {
    var openTile by remember { mutableStateOf<DemoTile?>(null) }
    var lastKey by remember { mutableStateOf<PressedKey?>(null) }
    val overlayFocus = remember { FocusRequester() }
    // Which tile the remote is on, and a token that is bumped whenever that tile has to claim
    // focus back — at startup, and again after the overlay that took it away is closed.
    var focusedTileId by remember { mutableStateOf(demoTiles.first().id) }
    var focusRestoreToken by remember { mutableStateOf(0) }

    MaterialTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Background)
                // The root preview handler sees every remote press before the focused tile does,
                // which is what makes Back work the same on the grid and on the overlay.
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown) {
                        lastKey = event.toPressedKey()
                    }
                    if (event.type == KeyEventType.KeyUp && event.key == Key.Back) {
                        if (openTile != null) {
                            openTile = null
                            focusRestoreToken++
                        } else {
                            onExit()
                        }
                        true
                    } else {
                        false
                    }
                }
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(32.dp)) {
                Header(platformName)
                Spacer(Modifier.height(24.dp))
                TileGrid(
                    modifier = Modifier.weight(1f),
                    focusedTileId = focusedTileId,
                    focusRestoreToken = focusRestoreToken,
                    onTileFocused = { focusedTileId = it },
                    onOpen = { openTile = it },
                )
                Spacer(Modifier.height(16.dp))
                RemoteKeyMonitor(lastKey)
            }

            openTile?.let { tile ->
                TileOverlay(tile = tile, focusRequester = overlayFocus)
            }
        }
    }
}

@Composable
private fun Header(platformName: String) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Compose for TV",
                fontSize = 34.sp,
                color = Color.White,
                style = MaterialTheme.typography.headlineLarge,
            )
            Spacer(Modifier.size(16.dp))
            Text(
                text = platformName,
                fontSize = 16.sp,
                color = if (platformName == "None") Color.White.copy(alpha = 0.35f) else Accent,
                modifier = Modifier
                    .background(Surface, RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "D-pad moves focus · OK opens · BACK closes, or quits from here",
            fontSize = 15.sp,
            color = Color.White.copy(alpha = 0.55f),
        )
    }
}

@Composable
private fun TileGrid(
    modifier: Modifier = Modifier,
    focusedTileId: Int,
    focusRestoreToken: Int,
    onTileFocused: (Int) -> Unit,
    onOpen: (DemoTile) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(4.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        items(demoTiles) { tile ->
            FocusableTile(
                tile = tile,
                // Nothing holds focus when the app starts and a TV has no pointer to click with,
                // so the tile claims focus from inside the item. Requesting it from the screen
                // root instead would run before the grid has composed any item, leaving the
                // requester attached to nothing.
                claimsFocus = tile.id == focusedTileId,
                focusRestoreToken = focusRestoreToken,
                onFocused = { onTileFocused(tile.id) },
                onOpen = onOpen,
            )
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
private fun FocusableTile(
    tile: DemoTile,
    claimsFocus: Boolean,
    focusRestoreToken: Int,
    onFocused: () -> Unit,
    onOpen: (DemoTile) -> Unit,
) {
    var isFocused by remember { mutableStateOf(false) }
    var isHeld by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    if (claimsFocus) {
        LaunchedEffect(focusRestoreToken) { focusRequester.requestFocus() }
    }

    Card(
        modifier = Modifier
            .focusRequester(focusRequester)
            .size(width = 260.dp, height = 150.dp)
            .onFocusChanged { state ->
                isFocused = state.isFocused
                if (state.isFocused) onFocused()
                if (!state.isFocused) isHeld = false
            }
            // combinedClickable installs its own focus target, so this must not be wrapped in an
            // extra .focusable() — a second outer focus target stops key events from reaching it.
            .combinedClickable(
                onClick = { onOpen(tile) },
                onLongClick = { isHeld = true },
            )
            .onPreviewKeyEvent { event ->
                // A held remote button reports every press after the first as a repeat, which is
                // how a long press is recognised for keys combinedClickable does not track.
                if (event.type == KeyEventType.KeyDown && event.isRepeat) {
                    isHeld = true
                }
                false
            }
            .border(
                width = if (isFocused) 4.dp else 0.dp,
                color = if (isFocused) {
                    if (isHeld) Color(0xFFFFD54F) else Color.White
                } else {
                    Color.Transparent
                },
                shape = RoundedCornerShape(12.dp),
            ),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = tile.color.copy(alpha = if (isFocused) 1f else 0.6f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isFocused) 16.dp else 2.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = tile.title,
                fontSize = if (isFocused) 28.sp else 24.sp,
                color = Color.White,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = tile.subtitle,
                fontSize = 15.sp,
                color = Color.White.copy(alpha = 0.85f),
            )
        }
    }
}

@Composable
private fun TileOverlay(tile: DemoTile, focusRequester: FocusRequester) {
    LaunchedEffect(tile.id) { focusRequester.requestFocus() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xE6000000))
            .focusRequester(focusRequester)
            .focusable(),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier.size(width = 620.dp, height = 340.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Surface),
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(32.dp)) {
                Text(text = tile.title, fontSize = 34.sp, color = tile.color)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = tile.subtitle,
                    fontSize = 18.sp,
                    color = Color.White.copy(alpha = 0.7f),
                )
                Spacer(Modifier.height(24.dp))
                Text(
                    text = "Press BACK on the remote to return to the grid.",
                    fontSize = 16.sp,
                    color = Accent,
                )
            }
        }
    }
}

/** The last remote button that reached Compose, for the readout at the bottom of the screen. */
data class PressedKey(val name: String, val isRepeat: Boolean)

@Composable
private fun RemoteKeyMonitor(lastKey: PressedKey?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface, RoundedCornerShape(10.dp))
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "Last remote key", fontSize = 15.sp, color = Color.White.copy(alpha = 0.5f))
        Spacer(Modifier.size(16.dp))
        Text(
            text = lastKey?.let { if (it.isRepeat) "${it.name} (repeat)" else it.name }
                ?: "waiting for a press…",
            fontSize = 17.sp,
            color = if (lastKey == null) Color.White.copy(alpha = 0.35f) else Accent,
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
private fun KeyEvent.toPressedKey(): PressedKey {
    val name = when (key) {
        Key.DirectionUp -> "UP"
        Key.DirectionDown -> "DOWN"
        Key.DirectionLeft -> "LEFT"
        Key.DirectionRight -> "RIGHT"
        Key.Enter -> "OK"
        Key.Back -> "BACK"
        Key.MediaPlay -> "PLAY"
        Key.MediaPause -> "PAUSE"
        Key.MediaPlayPause -> "PLAY/PAUSE"
        Key.MediaStop -> "STOP"
        Key.MediaRewind -> "REWIND"
        Key.MediaFastForward -> "FAST FORWARD"
        Key.MediaNext -> "NEXT"
        Key.MediaPrevious -> "PREVIOUS"
        Key.ProgramRed -> "RED"
        Key.ProgramGreen -> "GREEN"
        Key.ProgramYellow -> "YELLOW"
        Key.ProgramBlue -> "BLUE"
        Key.ChannelUp -> "CHANNEL UP"
        Key.ChannelDown -> "CHANNEL DOWN"
        Key.Info -> "INFO"
        Key.Guide -> "GUIDE"
        Key.Captions -> "CAPTION"
        Key.Search -> "SEARCH"
        Key.Menu -> "TOOLS"
        Key.Unknown -> "unmapped"
        else -> key.toString()
    }
    return PressedKey(name, isRepeat)
}
