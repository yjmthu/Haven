package org.connectbot.terminal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalTextToolbar

/** Custom library controls by default; hosts can opt into Android text actions. */
enum class SelectionToolbarMode { CUSTOM, SYSTEM, HIDDEN }

@Composable
internal fun SystemSelectionToolbar(
    rect: Rect,
    selection: SelectionRange,
    controller: SelectionController,
    onPaste: (() -> Unit)?,
) {
    val toolbar = LocalTextToolbar.current
    val currentController by rememberUpdatedState(controller)
    val currentPaste by rememberUpdatedState(onPaste)
    DisposableEffect(toolbar) {
        onDispose { toolbar.hide() }
    }
    LaunchedEffect(toolbar, rect, selection) {
        toolbar.showMenu(
            rect = rect,
            onCopyRequested = { currentController.copySelection() },
            onPasteRequested = if (onPaste == null) null else {
                {
                    currentController.clearSelection()
                    currentPaste?.invoke()
                }
            },
            onCutRequested = null,
            onSelectAllRequested = { currentController.selectAll() },
        )
    }
}
