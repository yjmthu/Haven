package org.connectbot.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real terminal/controller and Android clipboard service. */
@RunWith(AndroidJUnit4::class)
class SystemSelectionClipboardTest {
    @get:Rule
    val compose = createComposeRule()

    private class Menu : TextToolbar {
        override var status = TextToolbarStatus.Hidden
        var copy: (() -> Unit)? = null
        var paste: (() -> Unit)? = null
        override fun showMenu(
            rect: Rect,
            onCopyRequested: (() -> Unit)?,
            onPasteRequested: (() -> Unit)?,
            onCutRequested: (() -> Unit)?,
            onSelectAllRequested: (() -> Unit)?,
        ) {
            status = TextToolbarStatus.Shown
            copy = onCopyRequested
            paste = onPasteRequested
        }
        override fun hide() { status = TextToolbarStatus.Hidden }
    }

    @Test
    fun `system copy writes selected terminal text to the Android clipboard`() {
        val emulator = TerminalEmulatorFactory.create(initialRows = 8, initialCols = 40)
        runBlocking { emulator.writeInput("hello clipboard\r\n".toByteArray()) }
        val menu = Menu()
        lateinit var controller: SelectionController
        val context = ApplicationProvider.getApplicationContext<Context>()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("previous", "previous value"))
        compose.setContent {
            CompositionLocalProvider(LocalTextToolbar provides menu) {
                TerminalWithAccessibility(
                    terminalEmulator = emulator,
                    modifier = Modifier.size(400.dp, 600.dp),
                    selectionToolbarMode = SelectionToolbarMode.SYSTEM,
                    onSelectionControllerAvailable = { controller = it },
                )
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { controller.selectAll() }
        compose.waitForIdle()
        compose.runOnIdle {
            assertNotNull(menu.copy)
            menu.copy!!.invoke()
            assertEquals("hello clipboard", clipboard.primaryClip!!.getItemAt(0).text.toString())
            assertFalse(controller.isSelectionActive)
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(TextToolbarStatus.Hidden, menu.status) }
        emulator.close()
    }

    @Test
    fun `system paste uses the host paste action and hidden pages dismiss the menu`() {
        val emulator = TerminalEmulatorFactory.create(initialRows = 8, initialCols = 40)
        runBlocking { emulator.writeInput("selection\r\n".toByteArray()) }
        val menu = Menu()
        val mode = mutableStateOf(SelectionToolbarMode.SYSTEM)
        lateinit var controller: SelectionController
        var pastes = 0
        compose.setContent {
            CompositionLocalProvider(LocalTextToolbar provides menu) {
                TerminalWithAccessibility(
                    terminalEmulator = emulator,
                    modifier = Modifier.size(400.dp, 600.dp),
                    selectionToolbarMode = mode.value,
                    onSelectionControllerAvailable = { controller = it },
                    onPasteRequest = { pastes++ },
                )
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { controller.selectAll() }
        compose.waitForIdle()
        compose.runOnIdle {
            assertNotNull(menu.paste)
            menu.paste!!.invoke()
            assertEquals(1, pastes)
            assertFalse(controller.isSelectionActive)
            controller.selectAll()
        }
        compose.waitForIdle()
        compose.runOnIdle { mode.value = SelectionToolbarMode.HIDDEN }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(TextToolbarStatus.Hidden, menu.status) }
        emulator.close()
    }
}
