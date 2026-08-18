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
 * Consequence worth knowing: because it is not inside the rotated box, it
 * appears in the Activity's orientation. In a genuinely landscape Activity that
 * is correct; during the fake-rotated fullscreen it is upright over a rotated
 * video, which is the honest trade for it being a real page-level sheet.
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

    /*
     * A ModalBottomSheet renders in its OWN window, and that window does not
     * inherit the Activity's immersive flags. Opening speed or quality in
     * fullscreen therefore made the status and navigation bars reappear over the
     * video — the sheet was quietly un-hiding them.
     *
     * DialogWindowProvider is how Compose exposes that window; hiding the bars
     * on it too is the standard remedy.
     */
    val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
    LaunchedEffect(dialogWindow, immersive) {
        val window = dialogWindow ?: return@LaunchedEffect
        if (!immersive) return@LaunchedEffect
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    // The player is always dark; inheriting a host's light theme would put black
    // text on a sheet that sits against video.
    MaterialTheme(colorScheme = darkColorScheme()) {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
        ) {
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
                            .clickable { onSelect(option.id) }
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
