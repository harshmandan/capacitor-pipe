package ink.harsh.plugins.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * One option in a sheet. Public because it crosses the plugin boundary.
 *
 * The player presents these; it does not invent them. It has no business
 * guessing which quality levels exist or which speeds are sensible.
 */
data class SheetOption(
    val id: String,
    val label: String,
)

/**
 * The speed and quality menus.
 *
 * A real Material3 [ModalBottomSheet], mounted at the **Activity**
 * level rather than inside the player's rotated box. That matters twice over:
 * a sheet drawn inside the player would be a page-level UI trapped in a video
 * frame, and — having been hand-rolled — it had no drag-to-dismiss, no
 * predictive back, and no scrim semantics. Those are exactly the behaviours
 * people reach for without thinking, so reimplementing them badly is worse than
 * taking the dependency.
 *
 * Consequence worth knowing: because it renders in its own window, it appears
 * in the Activity's orientation — which is why fullscreen makes the Activity
 * genuinely landscape before a sheet can open.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PipePlayerSheet(
    title: String,
    options: List<SheetOption>,
    selectedId: String?,
    accent: Color,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    /** True while the player is fullscreen, so the sheet must stay immersive. */
    immersive: Boolean = false,
) {
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()

    /*
     * Selection plays the exit animation before reporting.
     *
     * `onSelect` removes this composable from the tree, and a ModalBottomSheet
     * that leaves composition simply vanishes — the slide-out it owes the eye
     * only runs if it is asked to hide FIRST. The scrim-tap and back paths get
     * this for free, because the sheet hides itself before onDismissRequest.
     */
    fun settle(action: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion { action() }
    }

    // The player is always dark; inheriting a host's light theme would put black
    // text on a sheet that sits against video.
    MaterialTheme(colorScheme = darkColorScheme()) {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
        ) {
            /*
             * A ModalBottomSheet renders in its OWN window, and that window
             * does not inherit the Activity's immersive flags — opening speed
             * or quality in fullscreen quietly un-hid the system bars.
             *
             * The lookup has to happen HERE, inside the sheet's content, where
             * LocalView is the sheet window's own view and its parent is the
             * DialogWindowProvider. At the composable's top level LocalView is
             * still the Activity-side host view, the cast returns null, and
             * the whole fix silently never runs — which is exactly how it
             * shipped broken the first time.
             */
            val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
            LaunchedEffect(dialogWindow, immersive) {
                val window = dialogWindow ?: return@LaunchedEffect
                if (!immersive) return@LaunchedEffect
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    systemBarsBehavior =
                        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(WindowInsetsCompat.Type.systemBars())
                }
            }

            Column(Modifier.navigationBarsPadding()) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 24.dp, bottom = 8.dp),
                )

                options.forEach { option ->
                    val selected = option.id == selectedId
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { settle { onSelect(option.id) } }
                            .padding(horizontal = 24.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = option.label,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        )
                        Spacer(Modifier.weight(1f))
                        if (selected) {
                            // The tick is the one place a sheet picks up the
                            // consumer's accent.
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null,
                                tint = accent,
                            )
                        }
                    }
                }
            }
        }
    }
}
