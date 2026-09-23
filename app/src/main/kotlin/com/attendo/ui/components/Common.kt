package com.attendo.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * The app's title bar. [onBack] adds the back arrow, which is the only way off any
 * screen that isn't one of the two tabs.
 *
 * Window insets are deliberately zeroed: the single [androidx.compose.material3.Scaffold]
 * in `AttendoApp` has already inset its content below the status bar, and a top bar that
 * padded itself again would sit a status bar's height too low.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttendoTopBar(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
) {
    TopAppBar(
        title = {
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        },
        actions = { actions() },
        windowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            scrolledContainerColor = MaterialTheme.colorScheme.surface,
        ),
    )
}

/** A small all-caps heading between blocks of a scrolling screen. */
@Composable
fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        trailing()
    }
}

/**
 * What a screen shows instead of an empty list.
 *
 * [action] is where the way *out* of the empty state goes — an empty course list is
 * useless without the button that fills it.
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: @Composable () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        action()
    }
}

/** Centred spinner for the moment before the first database emission arrives. */
@Composable
fun LoadingPane(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
    }
}

/**
 * A yes/no dialog. Used for the handful of actions that deserve a second look before they
 * happen — deleting a course takes every session it ever had with it.
 *
 * [destructive] colours the confirm label as a warning. It defaults to true because losing
 * data is what most of these dialogs are for, and a confirmation that is *not* a warning
 * (applying a target to every course) has to say so, or the red text teaches the student to
 * read a routine choice as a dangerous one.
 */
@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String = "Delete",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = true,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm()
                    onDismiss()
                },
            ) {
                Text(
                    text = confirmLabel,
                    color = if (destructive) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/** Standard edge padding for a screen's scrolling content. */
val ScreenPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 12.dp)

/**
 * Text-field state owned by the composition, not by a `ViewModel`.
 *
 * Every editable field in this app is written this way, and the reason is worth stating once
 * rather than per screen. A field whose `value` comes back out of a `StateFlow` is not
 * showing what was typed — it is showing what the last emission said, which is a different
 * thing. `MutableStateFlow` and `stateIn` both conflate, `combine` hands its result over a
 * channel, and anything with `flowOn` crosses a dispatcher on the way, so the text arrives a
 * frame or more after the keystroke and with fast typing some values never arrive at all.
 * Compose's own text field then merges that late text with the caret belonging to what is
 * *already* on screen, and the caret jumps — backwards, usually mid-word, and often taking a
 * character with it.
 *
 * Holding [TextFieldValue] here instead makes the keystroke land in the frame it arrives in,
 * caret and all, and makes it impossible for a result further down the screen to move it.
 * The `ViewModel` still receives every edit and is still the source of truth for whatever the
 * edit *drives*; it simply stops being asked what the field says.
 *
 * [initial] seeds the field, which is what restores it across a configuration change — the
 * `ViewModel` survives that, so its value is the one to come back to. Pass [ready] as false
 * while a screen is still reading the record it is about to edit, and the field re-seeds once
 * [initial] is real. It must not flip back: this deliberately discards what was typed.
 */
@Composable
fun rememberEditableText(
    initial: String,
    ready: Boolean = true,
): MutableState<TextFieldValue> = remember(ready) {
    // Caret at the end, where somebody returning to a filled-in field expects to continue.
    mutableStateOf(TextFieldValue(initial, TextRange(initial.length)))
}
