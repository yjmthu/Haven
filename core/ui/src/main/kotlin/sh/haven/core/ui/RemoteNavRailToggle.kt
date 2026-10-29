package sh.haven.core.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ViewSidebar
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * Toolbar switch that shows or hides Haven's side navigation rail while a
 * desktop is open in landscape, so the remote image can use the full width.
 *
 * Landscape only — portrait uses the bottom tab bar, which the docked built-in
 * keyboard already owns — and the state is persisted, so the next session opens
 * the way this one was left.
 */
@Composable
fun RemoteNavRailToggle(
    visible: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilledIconToggleButton(
        checked = visible,
        onCheckedChange = { onToggle() },
        modifier = modifier.size(40.dp),
    ) {
        Icon(
            Icons.Filled.ViewSidebar,
            contentDescription = stringResource(
                if (visible) R.string.remote_nav_rail_hide else R.string.remote_nav_rail_show
            ),
        )
    }
}
