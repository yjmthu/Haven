package sh.haven.core.ui

import android.view.HapticFeedbackConstants
import android.view.View

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.KeyboardCapslock

import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Protocol key codes the built-in keyboard needs. RDP fills these with HID
 * scancodes, VNC with X11 keysyms — the keyboard itself never knows which.
 */
data class RemoteKeyCodes(
    val escape: Int,
    val tab: Int,
    val enter: Int,
    val backspace: Int,
    val left: Int,
    val up: Int,
    val down: Int,
    val right: Int,
    val ctrl: Int,
    val alt: Int,
    val win: Int,
    /** Left Shift. Sent as a real modifier so Shift+Tab / Win+Shift+S carry the
     *  shift state to the server, not just an upper-cased character. */
    val shift: Int,
    /** Home / End — reached through Shift+Left / Shift+Right on the panel. */
    val home: Int,
    val end: Int,
)

/**
 * Persisted geometry of the floating (landscape) panel. Stored as fractions so
 * it survives screen-size and density changes: [xFraction] is a share of the
 * horizontal travel (-1..1), [yFraction] a share of the container height
 * measured up from the bottom edge, and [scale] the pinch zoom.
 */
/** Corner radius of the floating landscape panel (the arcs wrap around it). */
private val PanelCornerRadius = 14.dp

/** Grab box of a corner resize arc; its half is the arc radius. */
private val ArcBoxSize = 44.dp

/**
 * Every key cap draws inside its grid cell with this inset, so neighbouring keys
 * read as separate buttons instead of one tiled surface.
 */
private val KeyInsetH = 2.dp
private val KeyInsetV = 1.5.dp

/** Hold time before a key latches (Win)). Shorter than the system 500ms. */
private const val KeyHoldMs = 350L

data class RemoteKeyboardLayout(
    val scale: Float = 1f,
    val xFraction: Float = 0f,
    val yFraction: Float = 0.27f,
) {
    companion object {
        const val MIN_SCALE = 0.7f
        const val MAX_SCALE = 1.8f
    }
}

/**
 * Haven's own small floating keyboard for remote desktops.
 *
 * It exists because Android gives an app no way to request that the system IME
 * float rather than dock: floating is entirely the IME's own decision. This
 * panel draws the keys itself and sends them through the same callbacks the
 * hidden text field uses, so it works in portrait and landscape, over any
 * server, and never summons the IME.
 *
 * Printable keys go through [onTypeChar] (the protocol's own char→key mapping,
 * so layout remaps and AltGr overlays keep working); modifiers and navigation
 * keys use [onKeyDown]/[onKeyUp] with [keyCodes].
 *
 * The floating panel is dragged and pinched by its grip and clamps itself
 * inside [containerSize] (the viewer's root size), so it can be moved off
 * whatever it covers. Its scale and position come from [layout] and are reported
 * back through [onLayoutChange] when a gesture ends, so the next session opens
 * where the last one left it.
 */
@Composable
fun RemoteKeyboard(
    keyCodes: RemoteKeyCodes,
    onTypeChar: (Char) -> Unit,
    onKeyDown: (Int) -> Unit,
    onKeyUp: (Int) -> Unit,
    containerSize: IntSize,
    modifier: Modifier = Modifier,
    /** Called by the panel's own collapse affordance. */
    onDismiss: (() -> Unit)? = null,
    /** Measured panel height in px — the viewer lifts its toolbar above it. */
    onHeightChange: ((Int) -> Unit)? = null,
    /** Saved floating-panel geometry (landscape only). */
    layout: RemoteKeyboardLayout = RemoteKeyboardLayout(),
    /** Gesture-end report so the host can persist the geometry. */
    onLayoutChange: (RemoteKeyboardLayout) -> Unit = {},
) {
    var symbols by remember { mutableStateOf(false) }
    // Shift is a three-state key: off -> armed for one character -> caps lock.
    // The second tap (i.e. a double tap) locks it, the third clears it — the
    // same cycle a phone keyboard uses.
    var shift by remember { mutableStateOf(false) }
    var capsLock by remember { mutableStateOf(false) }
    var ctrl by remember { mutableStateOf(false) }
    var alt by remember { mutableStateOf(false) }
    var win by remember { mutableStateOf(false) }
    var panelSize by remember { mutableStateOf(IntSize.Zero) }

    val density = LocalDensity.current
    // Portrait = a docked, full-width keyboard like a regular IME; landscape
    // stays a draggable floating panel (there the docked one would eat most of
    // the height).
    val portrait = LocalConfiguration.current.orientation ==
            android.content.res.Configuration.ORIENTATION_PORTRAIT
    val marginPx = with(density) { 8.dp.toPx() }

    var scale by remember {
        mutableFloatStateOf(
            layout.scale.coerceIn(
                RemoteKeyboardLayout.MIN_SCALE,
                RemoteKeyboardLayout.MAX_SCALE
            )
        )
    }

    val scaledWidth = panelSize.width * scale
    val scaledHeight = panelSize.height * scale
    val maxX = ((containerSize.width - scaledWidth) / 2f - marginPx).coerceAtLeast(0f)
    // The panel hangs upward from the bottom edge, so its y offset is negative.
    // Both bounds must stay negative-safe: on the first composition the sizes
    // are still zero and a naive `-(h - panel - margin)` evaluates to a POSITIVE
    // number, which used to hand coerceIn a reversed range and crash the
    // viewer the moment the keyboard was opened.
    val maxY = -marginPx
    val minY = minOf(-(containerSize.height - scaledHeight - marginPx), maxY)

    var offset by remember { mutableStateOf(Offset.Zero) }
    // Whether this composition has already moved/resized the panel. The host
    // echoes our own reports back as [layout], so re-seeding from it after the
    // user started dragging used to snap the panel back mid-gesture.
    var userMovedPanel by remember { mutableStateOf(false) }

    // Seed the geometry once the panel has been measured (xFraction is a share
    // of the horizontal travel, which needs the real panel width), and keep
    // following the saved layout until the user takes over.
    LaunchedEffect(panelSize, containerSize, layout) {
        if (userMovedPanel || panelSize.width == 0) return@LaunchedEffect
        val seededScale = layout.scale.coerceIn(
            RemoteKeyboardLayout.MIN_SCALE,
            RemoteKeyboardLayout.MAX_SCALE,
        )
        scale = seededScale
        val travel = ((containerSize.width - panelSize.width * seededScale) / 2f - marginPx)
            .coerceAtLeast(0f)
        offset = Offset(
            layout.xFraction.coerceIn(-1f, 1f) * travel,
            -layout.yFraction.coerceIn(0f, 0.9f) * containerSize.height,
        )
    }
    val position = if (portrait) Offset.Zero else offset

    // The pinch zoom belongs to the floating panel. Portrait is a docked IME and
    // must stay exactly full-width, so it always draws at 1:1 — otherwise a
    // landscape zoom persisted from an earlier session shrinks the docked
    // keyboard and leaves fat margins on all four sides.
    val panelScale = if (portrait) 1f else scale

    // Portrait keeps the roomy keys (it is docked and replacing the IME); the
    // floating landscape panel is deliberately more compact, and its extra
    // width keeps each cap from looking like a thin strip.
    val keyHeight = if (portrait) 46.dp else 36.dp
    val smallKeyHeight = if (portrait) 36.dp else 28.dp

    // Persist after the gesture goes quiet. detectTransformGestures loops for the
    // whole gesture and never returns, so there is no "gesture ended" hook to
    // persist from; a short debounce is the next best thing.
    LaunchedEffect(scale, offset, portrait) {
        if (portrait) return@LaunchedEffect
        kotlinx.coroutines.delay(500)
        onLayoutChange(
            RemoteKeyboardLayout(
                scale = scale,
                xFraction = if (maxX > 0f) (offset.x / maxX).coerceIn(-1f, 1f) else 0f,
                yFraction = if (containerSize.height > 0) {
                    (-offset.y / containerSize.height).coerceIn(0f, 0.9f)
                } else {
                    RemoteKeyboardLayout().yFraction
                },
            ),
        )
    }

    // Armed modifiers live only in this panel — nothing reaches the remote
    // until there is a chord to send. That way arming (or cancelling by tapping
    // again) is silent, and a modifier can never be left stuck down on the
    // server.
    //
    // They are independent: arming Ctrl does not clear Alt/Win, and a chord
    // presses every armed modifier at once (Ctrl+Alt+Win+key works). An armed
    // modifier can only be cancelled by itself — but only while it has not been
    // used: the first key that consumes it clears it, so a modifier never keeps
    // applying to keys the user did not mean to combine it with.
    // One-shot Shift (not the caps lock) is a real modifier for chords, so
    // Shift+Tab and Win+Shift+S reach the server with the shift state set.
    fun pressModifiers(includeShift: Boolean) {
        if (ctrl) onKeyDown(keyCodes.ctrl)
        if (alt) onKeyDown(keyCodes.alt)
        if (includeShift) onKeyDown(keyCodes.shift)
        if (win) onKeyDown(keyCodes.win)
    }

    fun releaseModifiers(includeShift: Boolean) {
        if (ctrl) onKeyUp(keyCodes.ctrl)
        if (alt) onKeyUp(keyCodes.alt)
        if (includeShift) onKeyUp(keyCodes.shift)
        if (win) onKeyUp(keyCodes.win)
    }

    /** A sent key consumes the armed modifiers: they fired together and are done. */
    fun consumeModifiers() {
        ctrl = false
        alt = false
        win = false
        // Caps lock is a latching state, not an armed modifier.
        if (!capsLock) shift = false
    }

    fun type(c: Char) {
        // A plain armed Shift upper-cases the character itself (safest for every
        // protocol). With another modifier in play the character goes out
        // un-shifted and Shift is held as a real modifier, so the server sees
        // the shift state a hotkey checks for (Win+Shift+S).
        val chordShift = shift && !capsLock && (ctrl || alt || win)
        pressModifiers(includeShift = chordShift)
        val out = if (!chordShift && (shift || capsLock)) c.uppercaseChar() else c
        onTypeChar(out)
        releaseModifiers(includeShift = chordShift)
        consumeModifiers()
    }

    /**
     * Long press sends this modifier as a standalone key press and disarms
     * only itself — the other armed modifiers are untouched.
     */
    fun standaloneModifier(code: Int) {
        when (code) {
            keyCodes.ctrl -> ctrl = false
            keyCodes.alt -> alt = false
            keyCodes.win -> win = false
            keyCodes.shift -> {
                shift = false
                capsLock = false
            }
        }
        onKeyDown(code)
        onKeyUp(code)
    }

    fun tapShift() {
        when {
            capsLock -> {
                capsLock = false
                shift = false
            }

            shift -> {
                capsLock = true
                shift = true
            }

            else -> shift = true
        }
    }

    fun tap(code: Int) {
        // Shift participates here too (Shift+Tab, Shift+arrows); caps lock
        // deliberately does not.
        val chordShift = shift && !capsLock
        pressModifiers(includeShift = chordShift)
        onKeyDown(code)
        onKeyUp(code)
        releaseModifiers(includeShift = chordShift)
        consumeModifiers()
    }

    val width = if (portrait) {
        with(density) { containerSize.width.toDp() }.takeIf { it > 0.dp } ?: 360.dp
    } else {
        // Narrower than before: the panel floats over the remote image, and at
        // 420dp each key was wide enough that five rows of them covered a
        // large slice of the landscape viewport for no typing benefit.
        with(density) {
            (containerSize.width * 0.92f).toDp().coerceAtMost(372.dp)
        }.coerceAtLeast(280.dp)
    }

    // Corner drag: the panel scales about the corner *opposite* the one being
    // dragged, so grabbing the bottom-right arc pins the top-left corner and the
    // panel grows right and down (and vice versa for the bottom-left arc). The
    // layer's transform origin is the bottom centre, so half of the horizontal
    // growth happens on each side and all of the vertical growth goes upward;
    // the offset shift below cancels that so the opposite corner really stays.
    fun resizeFromCorner(dragX: Float, outward: Float) {
        val panelWidthPx = panelSize.width.toFloat().coerceAtLeast(1f)
        val panelHeightPx = panelSize.height.toFloat().coerceAtLeast(1f)
        val next = (scale + outward * dragX / panelWidthPx).coerceIn(
            RemoteKeyboardLayout.MIN_SCALE,
            RemoteKeyboardLayout.MAX_SCALE,
        )
        val applied = next - scale
        if (applied == 0f) return
        userMovedPanel = true
        scale = next
        val travel = ((containerSize.width - panelWidthPx * next) / 2f - marginPx)
            .coerceAtLeast(0f)
        // The top edge is pinned too, so the panel may grow downwards — but it
        // still cannot leave the viewer at either end.
        val nextMaxY = -marginPx
        val nextMinY = minOf(
            -(containerSize.height - panelHeightPx * next - marginPx),
            nextMaxY,
        )
        offset = Offset(
            (offset.x + outward * applied * panelWidthPx / 2f).coerceIn(-travel, travel),
            (offset.y + applied * panelHeightPx).coerceIn(nextMinY, nextMaxY),
        )
    }

    // Root overlay: as large as the viewer, because Compose only hit-tests
    // pointer input that falls inside every ancestor's layout bounds. The resize
    // arcs hang below the panel's corners, so they need a parent that is bigger
    // than the panel to be draggable — the overlay itself has no pointer input,
    // so touches away from the panel still reach the remote canvas.
    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .width(width)
                .onSizeChanged {
                    panelSize = it
                    onHeightChange?.invoke((it.height * panelScale).toInt())
                }
                .offset {
                    if (portrait) {
                        // Docked flush with the bottom of the viewer, the way the
                        // system IME sits: no lift, no margin, so it reads as part
                        // of the screen edge instead of a card hovering above it.
                        IntOffset.Zero
                    } else {
                        IntOffset(
                            position.x.coerceIn(-maxX, maxX).toInt(),
                            position.y.coerceIn(minY, maxY).toInt(),
                        )
                    }
                }
                // Offset must stay outside the graphics layer: inside it the
                // translation would be scaled too, which skews every drag and the
                // corner-resize anchoring.
                .graphicsLayer {
                    // Zoom about the bottom centre so the panel grows upward from
                    // the edge it is anchored to instead of drifting off-screen.
                    scaleX = panelScale
                    scaleY = panelScale
                    transformOrigin = TransformOrigin(0.5f, 1f)
                },
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = if (portrait) RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp)
                else RoundedCornerShape(PanelCornerRadius),
                // Opaque in portrait (an IME never shows the app through it); the
                // floating landscape panel keeps a little translucency so it blends
                // with whatever it covers.
                color = if (portrait) MaterialTheme.colorScheme.surface
                else MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
                contentColor = MaterialTheme.colorScheme.onSurface,
                tonalElevation = if (portrait) 1.dp else 3.dp,
                shadowElevation = if (portrait) 0.dp else 12.dp,
            ) {
                Box {
                    Column(
                        modifier = Modifier.padding(
                            start = 6.dp,
                            end = 6.dp,
                            // The resize arcs live outside the panel, so the keys can use
                            // the full height right down to the rounded corners.
                            bottom = 6.dp,
                            top = if (portrait) 6.dp else 0.dp,
                        ),
                    ) {
                        // Landscape only: the drag grip doubles as the pinch handle, and the
                        // collapse button sits at the end of the same row (outside the drag
                        // area). The docked portrait panel skips this row entirely — it has
                        // no drag/pinch, and the toolbar's keyboard key above it is the
                        // close affordance, so the strip would only eat height.
                        if (!portrait) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(24.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .pointerInput(containerSize, panelSize) {
                                            // Two fingers zoom, one finger drags the panel. The
                                            // gesture loops until every pointer is up; the
                                            // debounced effect above is what persists it.
                                            detectTransformGestures { _, pan, zoom, _ ->
                                                userMovedPanel = true
                                                if (zoom != 1f) {
                                                    scale = (scale * zoom).coerceIn(
                                                        RemoteKeyboardLayout.MIN_SCALE,
                                                        RemoteKeyboardLayout.MAX_SCALE,
                                                    )
                                                }
                                                // The gesture reports local pixels; the
                                                // layer is scaled, so convert back to
                                                // screen pixels (the offset lives
                                                // outside the scale).
                                                offset = Offset(
                                                    (offset.x + pan.x * scale).coerceIn(-maxX, maxX),
                                                    (offset.y + pan.y * scale).coerceIn(minY, maxY),
                                                )
                                            }
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(width = 40.dp, height = 4.dp)
                                            .background(
                                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                                RoundedCornerShape(2.dp),
                                            ),
                                    )
                                }
                                if (onDismiss != null) {
                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .clickable(
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = null,
                                                onClick = onDismiss,
                                            ),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.KeyboardArrowDown,
                                            contentDescription = stringResource(R.string.remote_keyboard_hide),
                                            modifier = Modifier.size(20.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }

                        // Special / modifier row. The layer switch and the vim-friendly
                        // ':' / '!' sit here (Ctrl and Win moved to the bottom-left corner),
                        // so a colon or bang is one tap away without leaving the letters.
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            SmallKey("Esc", height = smallKeyHeight) { tap(keyCodes.escape) }
                            SmallKey("Tab", height = smallKeyHeight) { tap(keyCodes.tab) }
                            SmallKey(
                                label = if (symbols) "ABC" else "?123",
                                height = smallKeyHeight,
                            ) {
                                symbols = !symbols
                                shift = false
                            }
                            SmallKey(":", height = smallKeyHeight) { type(':') }
                            SmallKey("!", height = smallKeyHeight) { type('!') }
                            SmallKey(
                                icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                label = "Left",
                                height = smallKeyHeight,
                                onLongPress = { tap(keyCodes.home) },
                            ) {
                                tap(keyCodes.left)
                            }
                            SmallKey(icon = Icons.Filled.KeyboardArrowUp, label = "Up", height = smallKeyHeight) {
                                tap(keyCodes.up)
                            }
                            SmallKey(
                                icon = Icons.Filled.KeyboardArrowDown,
                                label = "Down",
                                height = smallKeyHeight,
                            ) {
                                tap(keyCodes.down)
                            }
                            SmallKey(
                                icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                label = "Right",
                                height = smallKeyHeight,
                                onLongPress = { tap(keyCodes.end) },
                            ) {
                                tap(keyCodes.right)
                            }
                        }

                        val letters = if (symbols) {
                            listOf("1234567890", "!@#\$%^&*()", "-_=+[]{};", "/\\|<>~\"")
                        } else {
                            listOf("1234567890", "qwertyuiop", "asdfghjkl", "zxcvbnm")
                        }

                        letters.forEachIndexed { index, row ->
                            Row(
                                modifier = Modifier.padding(top = 2.dp),
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                // Stagger the letter rows like a physical keyboard: the
                                // home row is inset by half a key and Z sits half a key
                                // further right, so Q/A/Z read as a diagonal instead of a
                                // straight column.
                                if (index == 2) Spacer(Modifier.weight(0.5f))
                                if (index == 3) {
                                    KeyCap(
                                        // Hollow arrow = one-shot shift; arrow with an
                                        // underline = caps lock (the usual keyboard glyph,
                                        // far clearer than a solid arrow). Tap to arm it for
                                        // one character, tap twice to lock, tap again to
                                        // clear.
                                        icon = if (capsLock) Icons.Filled.KeyboardCapslock
                                        else Icons.Outlined.ArrowUpward,
                                        label = if (capsLock) "Caps lock" else "Shift",
                                        weight = 1.5f,
                                        height = keyHeight,
                                        active = shift || capsLock,
                                        onTap = { tapShift() },
                                        onLongPress = { standaloneModifier(keyCodes.shift) },
                                    )
                                }
                                row.forEach { ch ->
                                    KeyCap(
                                        // Caps lock (or a one-shot armed Shift) shows
                                        // the capitals, so the cap matches what will
                                        // actually be typed.
                                        label = if (ch.isLetter() && (capsLock || shift)) {
                                            ch.uppercaseChar().toString()
                                        } else {
                                            ch.toString()
                                        },
                                        height = keyHeight,
                                        // The digit row reads as squares; letters stay
                                        // rectangles, so the two rows are distinguishable
                                        // at a glance.
                                        square = index == 0,
                                    ) { type(ch) }
                                }
                                if (index == 3) {
                                    KeyCap(
                                        icon = Icons.AutoMirrored.Filled.Backspace,
                                        label = "Backspace",
                                        weight = 1.5f,
                                        height = keyHeight,
                                    ) {
                                        tap(keyCodes.backspace)
                                    }
                                }
                                if (index == 2) Spacer(Modifier.weight(0.5f))
                            }
                        }

                        // Bottom row: Ctrl, Win, Alt, space, comma, period, enter.
                        // The punctuation sits to the right of the space bar — where a
                        // thumb naturally reaches — and the modifiers cluster on the left.
                        Row(
                            modifier = Modifier.padding(top = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            KeyCap(
                                label = "Ctrl",
                                height = keyHeight,
                                active = ctrl,
                                // Tap = arm for the next key, tap again = cancel (silent).
                                // Hold = send a single Ctrl press on the remote.
                                onTap = { ctrl = !ctrl },
                                onLongPress = { standaloneModifier(keyCodes.ctrl) },
                            )
                            KeyCap(
                                icon = Icons.Filled.GridView,
                                label = "Win",
                                height = keyHeight,
                                active = win,
                                // Tap = arm for the next key (Win+L, Win+E, ...); tap
                                // again = cancel. Nothing is sent while arming. Hold =
                                // send one standalone Win press, i.e. open the Start menu.
                                onTap = { win = !win },
                                onLongPress = { standaloneModifier(keyCodes.win) },
                            )
                            KeyCap(
                                label = "Alt",
                                height = keyHeight,
                                active = alt,
                                onTap = { alt = !alt },
                                onLongPress = { standaloneModifier(keyCodes.alt) },
                            )
                            KeyCap(label = "Space", weight = 3.6f, height = keyHeight) { type(' ') }
                            KeyCap(label = ",", height = keyHeight) { type(',') }
                            KeyCap(label = ".", height = keyHeight) { type('.') }
                            KeyCap(
                                icon = Icons.AutoMirrored.Filled.KeyboardReturn,
                                label = "Enter",
                                weight = 1.4f,
                                height = keyHeight,
                            ) {
                                tap(keyCodes.enter)
                            }
                        }
                    }
                }
            }
        }

        // Corner resize arcs (floating panel only). Each one wraps the outside of
        // its corner and is the grip for resizing the panel: drag it away from the
        // panel to grow, towards it to shrink. They are placed in container
        // coordinates because the panel's layer is scaled, and they share the
        // persisted scale with the two-finger pinch.
        if (!portrait) {
            val panelWidthPx = panelSize.width.toFloat()
            val panelLeftPx = (containerSize.width - panelWidthPx) / 2f +
                    position.x + 0.5f * panelWidthPx * (1f - scale)
            val panelBottomPx = containerSize.height + position.y
            val arcSize = ArcBoxSize * scale
            val corner = PanelCornerRadius * scale
            val arcTop = with(density) { panelBottomPx.toDp() } - corner
            val arcRight = with(density) { (panelLeftPx + panelWidthPx * scale).toDp() } - corner
            val arcLeft = with(density) { panelLeftPx.toDp() } - (arcSize - corner)
            CornerResizeArc(
                x = arcLeft,
                y = arcTop,
                boxSize = arcSize,
                outward = -1f,
            ) {
                resizeFromCorner(it, -1f)
            }
            CornerResizeArc(
                x = arcRight,
                y = arcTop,
                boxSize = arcSize,
                outward = 1f,
            ) {
                resizeFromCorner(it, 1f)
            }
        }
    }
}

/**
 * Light quarter-arc wrapping one bottom corner of the floating panel. Its whole
 * box is the grab zone: touching it fades the arc in, dragging it resizes the
 * panel, and it fades away again once the finger is gone, so it never competes
 * with the keys. [onDrag] reports the horizontal drag in screen pixels.
 *
 * Position and size are given in container coordinates because the arc lives
 * outside the panel's scaled layer (and outside its layout bounds — the panel's
 * own rounded shape would clip it).
 */
@Composable
private fun CornerResizeArc(
    x: Dp,
    y: Dp,
    boxSize: Dp,
    /** +1 = bottom-right corner, -1 = bottom-left. */
    outward: Float,
    onDrag: (Float) -> Unit,
) {
    val arcColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)

    // Hidden until touched: the arcs are a resizing hint, not chrome.
    var shown by remember { mutableStateOf(false) }
    var touching by remember { mutableStateOf(false) }
    var touches by remember { mutableIntStateOf(0) }
    val alpha by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(durationMillis = if (shown) 120 else 450),
        label = "cornerArcAlpha",
    )
    // lingers for a moment after the finger lifts, then fades.
    LaunchedEffect(shown, touching, touches) {
        if (!shown || touching) return@LaunchedEffect
        kotlinx.coroutines.delay(900)
        shown = false
    }

    Box(
        modifier = Modifier
            .offset(x = x, y = y)
            .size(boxSize)
            // Observer: reveals the arc on touch-down and holds it up until the
            // finger is gone. Nothing is consumed here, so the drag detector
            // below still sees the whole gesture.
            .pointerInput(outward) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    shown = true
                    touching = true
                    try {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Final)
                            if (event.changes.none { it.pressed }) break
                        }
                    } finally {
                        touching = false
                        touches++
                    }
                }
            }
            .pointerInput(outward) {
                detectDragGestures { change, drag ->
                    change.consume()
                    onDrag(drag.x)
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            if (alpha > 0.01f) {
                // The box is ArcBoxSize, whose half is the arc radius, so the arc
                // is centred on the panel's rounded corner in the box's inner
                // corner.
                val width = this.size.width
                val r = width / 2f
                val factor = width / ArcBoxSize.toPx()
                val center = if (outward < 0f) Offset(width, 0f) else Offset(0f, 0f)
                drawArc(
                    color = arcColor.copy(alpha = arcColor.alpha * alpha),
                    startAngle = if (outward < 0f) 90f else 0f,
                    sweepAngle = 90f,
                    useCenter = false,
                    topLeft = Offset(center.x - r, center.y - r),
                    size = Size(r * 2, r * 2),
                    style = Stroke(
                        width = 1.6.dp.toPx() * factor,
                        cap = StrokeCap.Round,
                    ),
                )
            }
        }
    }
}

/**
 * Tap/hold handling shared by every key that has a hold action: a press shorter
 * than [KeyHoldMs] is a tap, a longer one fires [onLongPress] with a heavier
 * tick (the same feedback the system uses for a long press) so the user knows
 * it latched before lifting.
 *
 * Drives its own pressed flag because emitting Press interactions from inside
 * the restricted pointer scope is not allowed.
 */
private fun Modifier.holdGesture(
    view: View,
    onHeldChange: (Boolean) -> Unit,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
): Modifier = pointerInput(onTap, onLongPress) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        down.consume()
        onHeldChange(true)
        val releasedInTime = try {
            withTimeout(KeyHoldMs) {
                var released = false
                while (!released) {
                    val event = awaitPointerEvent()
                    released = event.changes.none { it.pressed }
                }
                true
            }
        } catch (_: PointerEventTimeoutCancellationException) {
            false
        }
        onHeldChange(false)
        if (!releasedInTime) {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onLongPress()
        } else {
            onTap()
        }
    }
}

@Composable
private fun RowScope.KeyCap(
    weight: Float = 1f,
    active: Boolean = false,
    label: String? = null,
    icon: ImageVector? = null,
    /** Portrait keys are taller; the floating landscape panel stays compact. */
    height: Dp = 46.dp,
    /** Draw the cap as a rounded square (its width) instead of a rectangle. */
    square: Boolean = false,
    /** Optional hold gesture (used by the Win key to arm a combination). */
    onLongPress: (() -> Unit)? = null,
    onTap: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressedFromClick by interaction.collectIsPressedAsState()
    // The hold gesture drives its own down flag: emitting Press interactions
    // from inside the restricted pointer scope is not allowed, and this keeps
    // the press visuals identical.
    var heldDown by remember { mutableStateOf(false) }
    val pressed = pressedFromClick || heldDown
    HapticKeyPress(pressed)
    // Press feedback: the key dips and tints while held, like a hardware key.
    // A plain ripple was easy to miss under a finger on a small, light key.
    val background by animateColorAsState(
        targetValue = when {
            active -> MaterialTheme.colorScheme.primaryContainer
            pressed -> MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        label = "keyColor",
    )
    val scale by animateFloatAsState(if (pressed) 0.92f else 1f, label = "keyScale")
    val tint = if (active) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSurface
    val iconTint = if (active) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSurfaceVariant
    val baseModifier = Modifier
        .weight(weight)
        .then(
            if (square) Modifier.aspectRatio(1f)
            else Modifier.height(height)
        )
        .padding(horizontal = KeyInsetH, vertical = KeyInsetV)
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
    val shape = RoundedCornerShape(8.dp)
    // A hairline edge plus a 1dp shadow reads as a raised keycap instead of a
    // flat tinted rectangle; both vanish while pressed so the key looks pushed
    // in.
    val border = BorderStroke(
        1.dp,
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (pressed) 0.9f else 0.5f),
    )
    val elevation = if (pressed) 0.dp else 1.dp
    val content: @Composable () -> Unit = {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    modifier = Modifier.size(22.dp),
                    tint = iconTint,
                )
            } else {
                Text(
                    text = label.orEmpty(),
                    fontSize = 17.sp,
                    color = tint,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
    }
    if (onLongPress == null) {
        Surface(
            onClick = onTap,
            interactionSource = interaction,
            modifier = baseModifier,
            shape = shape,
            color = background,
            border = border,
            shadowElevation = elevation,
        ) { content() }
    } else {
        // Hold-capable key. combinedClickable would use the system long-press
        // timeout (500ms), which feels sluggish for a modifier you latch often,
        // so the gesture is timed here at [KeyHoldMs] instead — still with the
        // pressed visuals from the shared interaction source, and a heavier tick
        // the moment the hold fires.
        Surface(
            modifier = baseModifier.holdGesture(
                view = LocalView.current,
                onHeldChange = { heldDown = it },
                onTap = onTap,
                onLongPress = onLongPress,
            ),
            shape = shape,
            color = background,
            border = border,
            shadowElevation = elevation,
        ) { content() }
    }
}

@Composable
private fun RowScope.SmallKey(
    label: String? = null,
    active: Boolean = false,
    icon: ImageVector? = null,
    height: Dp = 36.dp,
    onLongPress: (() -> Unit)? = null,
    onTap: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressedFromClick by interaction.collectIsPressedAsState()
    var heldDown by remember { mutableStateOf(false) }
    val pressed = pressedFromClick || heldDown
    HapticKeyPress(pressed)
    val background by animateColorAsState(
        targetValue = when {
            active -> MaterialTheme.colorScheme.primaryContainer
            pressed -> MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)
        },
        label = "smallKeyColor",
    )
    val scale by animateFloatAsState(if (pressed) 0.92f else 1f, label = "smallKeyScale")
    val tint = if (active) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSurface
    val iconTint = if (active) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSurfaceVariant
    val baseModifier = Modifier
        .weight(1f)
        .height(height)
        .padding(horizontal = KeyInsetH, vertical = KeyInsetV)
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
    val shape = RoundedCornerShape(6.dp)
    val border = BorderStroke(
        1.dp,
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (pressed) 0.9f else 0.4f),
    )
    val elevation = if (pressed) 0.dp else 0.5.dp
    val content: @Composable () -> Unit = {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    modifier = Modifier.size(20.dp),
                    tint = iconTint,
                )
            } else {
                Text(text = label.orEmpty(), fontSize = 12.sp, color = tint, maxLines = 1)
            }
        }
    }
    if (onLongPress == null) {
        Surface(
            onClick = onTap,
            interactionSource = interaction,
            modifier = baseModifier,
            shape = shape,
            color = background,
            border = border,
            shadowElevation = elevation,
        ) { content() }
    } else {
        Surface(
            modifier = baseModifier.holdGesture(
                view = LocalView.current,
                onHeldChange = { heldDown = it },
                onTap = onTap,
                onLongPress = onLongPress,
            ),
            shape = shape,
            color = background,
            border = border,
            shadowElevation = elevation,
        ) { content() }
    }
}

/**
 * A keyboard key should tick under the finger the moment it goes down, the way
 * a hardware key does. Uses the platform's KEYBOARD_TAP constant (the one real
 * keyboards and other soft keyboards use) rather than Compose's generic
 * haptic types, which are tuned for controls rather than typing.
 */
@Composable
private fun HapticKeyPress(pressed: Boolean) {
    val view = LocalView.current
    LaunchedEffect(pressed) {
        if (pressed) {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }
}
