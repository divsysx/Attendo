package com.attendo.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Where feedback emails go. No feedback backend — just the mail app the student already has. */
private const val FEEDBACK_EMAIL = "divyanshssharma1@gmail.com"

/** The repo's issue tracker, offered as the alternative to writing an email. */
private const val ISSUES_URL = "https://github.com/divsysx/Attendo/issues"

/**
 * The "Report a bug or suggest an improvement" section: a row that opens the choice of
 * channels, and the two email intents behind it.
 *
 * Feedback has no backend of its own — nothing in-app for a message to travel
 * over (analytics is aggregate usage numbers only and deliberately carries no message
 * content). The email app is the channel that already exists on every phone, and a
 * pre-filled message — subject and template included — costs the student nothing but the
 * details only they know. The subject says "Attendo" because an inbox full of "Bug
 * report" subject lines is one nobody can file.
 */
@Composable
internal fun FeedbackSection(appVersion: String, onDismiss: () -> Unit) {
    val context = LocalContext.current

    // This section is mounted only while the feedback dialog should be on screen — see
    // the caller — so the dialog is simply shown. (It used to sit behind an internal
    // flag that nothing ever set, which made tapping the row do nothing at all.)
    FeedbackDialog(
        appVersion = appVersion,
        onReportBug = { context.sendFeedbackEmail(bugReport(appVersion)) },
        onSuggest = { context.sendFeedbackEmail(suggestion(appVersion)) },
        onOpenIssues = { context.openUrl(ISSUES_URL) },
        onDismiss = onDismiss,
    )
}

/** Which of the two feedback channels is about to open. */
@Composable
private fun FeedbackDialog(
    appVersion: String,
    onReportBug: () -> Unit,
    onSuggest: () -> Unit,
    onOpenIssues: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Send feedback") },
        text = {
            Column {
                Text("What kind of feedback is it?", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = onReportBug, modifier = Modifier.fillMaxWidth()) {
                    Text("Report a bug", modifier = Modifier.fillMaxWidth())
                }
                TextButton(onClick = onSuggest, modifier = Modifier.fillMaxWidth()) {
                    Text("Suggest an improvement", modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "Either opens your email app with the message started for you. " +
                        "You can also open an issue on GitHub instead.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onOpenIssues) {
                    Text("Open GitHub issues")
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun bugReport(appVersion: String) = FeedbackEmail(
    subject = "Attendo bug report",
    body = "What happened?\n\n" +
        "What did you expect?\n\n" +
        "Steps to reproduce:\n\n" +
        "Device and Android version (optional):\n\n" +
        "App version: $appVersion\n",
)

private fun suggestion(appVersion: String) = FeedbackEmail(
    subject = "Attendo suggestion",
    body = "What would you like Attendo to improve or add?\n\n" +
        "Why would this be useful?\n\n" +
        "App version: $appVersion\n",
)

private data class FeedbackEmail(val subject: String, val body: String)

/**
 * Fires the standard email intent — ACTION_SENDTO over a `mailto:` URI, with the
 * recipient, subject and body as extras — and says so in plain language when the phone
 * has no app that can take it.
 *
 * The intent is launched as an ordinary implicit intent, never wrapped in a forced
 * chooser, because Android's own resolution *is* the chooser behaviour wanted here:
 *
 * - several mail apps and no default — Android puts its own chooser on screen and the
 *   student picks the app;
 * - a default handler set — Android opens it straight away, as the student configured;
 * - one app only — it opens.
 *
 * No app is targeted by name and none is special-cased — whatever declares itself able
 * to handle `mailto:` is a candidate. The receiving app gets the prefilled recipient,
 * subject and body and nothing else: the sending account is chosen by the student inside
 * their mail app, and the send itself is theirs to press. Attendo never touches,
 * enumerates or selects an account, and never sends anything on anyone's behalf.
 *
 * The availability check happens *before* the intent is fired because a launch nobody
 * can handle reads as "the button did nothing" on several devices. A phone with no mail
 * app configured is normal — a shared device, a freshly wiped one — and the honest
 * answer is one sentence on screen pointing at the GitHub-issues alternative still open
 * in the dialog behind it.
 *
 * [resolveActivity] needs the `<queries>` declaration in the manifest to see other
 * apps' mail activities on Android 11 and up; without it every phone would be reported
 * as having no mail app.
 */
private fun Context.sendFeedbackEmail(email: FeedbackEmail) {
    val intent = Intent(Intent.ACTION_SENDTO).apply {
        data = Uri.parse("mailto:")
        putExtra(Intent.EXTRA_EMAIL, arrayOf(FEEDBACK_EMAIL))
        putExtra(Intent.EXTRA_SUBJECT, email.subject)
        putExtra(Intent.EXTRA_TEXT, email.body)
    }
    if (intent.resolveActivity(packageManager) == null) {
        Toast.makeText(this, NO_MAIL_APP_MESSAGE, Toast.LENGTH_LONG).show()
        return
    }
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // The check above can pass and the launch still miss — a mail app deactivated
        // between the two, for instance. Same sentence, same reasoning.
        Toast.makeText(this, NO_MAIL_APP_MESSAGE, Toast.LENGTH_LONG).show()
    }
}

/** What the student is told when the phone cannot send the email at all. */
internal val NO_MAIL_APP_MESSAGE: String =
    "No email app was found on this phone. You can open an issue on GitHub instead."

/** What the student is told when a link cannot be opened because no browser answered. */
internal val NO_BROWSER_MESSAGE: String =
    "No app on this phone could open that link."

/**
 * Opens a URL in whatever the phone uses for links — the one way Attendo leaves itself for
 * the web.
 *
 * It is `internal` because the About footer's project links open the same way: one helper,
 * one intent, so a phone with no browser behaves the same wherever a link is tapped.
 *
 * Nothing is rendered in-app. A WebView would be a second browser to maintain and, on a
 * page that asks for a GitHub login, a surface that can lie about where it is — the system
 * browser keeps its own address bar and the student's own session.
 */
internal fun Context.openUrl(url: String) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: ActivityNotFoundException) {
        // A phone with no browser at all — rare, but a tap that silently does nothing reads
        // as a broken row. Same reasoning as the email path above.
        Toast.makeText(this, NO_BROWSER_MESSAGE, Toast.LENGTH_LONG).show()
    }
}
