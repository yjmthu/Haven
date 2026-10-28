package sh.haven.core.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardAlt
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * Toolbar switch between the system IME and Haven's built-in keyboard, sitting
 * next to the keyboard button in the remote desktop toolbars.
 *
 * The state it flips lives in Settings → Desktop, so this is a shortcut for
 * that setting; the tonal fill marks which keyboard is active, the same way the
 * pan-mode toggle marks its state. The glyph is `KeyboardAlt` rather than the
 * plain keyboard so it never reads as a second copy of the show/hide button.
 */
@Composable
fun RemoteKeyboardModeToggle(
    builtin: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilledIconToggleButton(
        checked = builtin,
        onCheckedChange = { onToggle() },
        modifier = modifier.size(40.dp),
    ) {
        Icon(
            Icons.Filled.KeyboardAlt,
            contentDescription = stringResource(
                if (builtin) R.string.remote_keyboard_use_system
                else R.string.remote_keyboard_use_builtin
            ),
        )
    }
}
