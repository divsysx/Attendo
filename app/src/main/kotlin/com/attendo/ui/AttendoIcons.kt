package com.attendo.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.attendo.R

/**
 * The seven Material icons this app uses that are not in `material-icons-core`.
 *
 * They were coming from `material-icons-extended`, which is the whole Material set —
 * some five thousand `ImageVector`s, each a class of its own. Nothing shrinks it in a
 * debug build, so it was contributing the large majority of the APK's dex and forcing
 * the app over the 64K method limit into a dozen dex files that all have to be verified
 * and loaded. For sixteen icons, nine of which are in the core artifact, that is a very
 * poor trade: it costs cold-start time and install size on every device for glyphs that
 * are a few hundred bytes of path data each.
 *
 * So the seven are checked in as vector drawables under `res/drawable`, with the path
 * data copied verbatim from Google's own SVGs — same glyphs, drawn by Android's vector
 * inflater. Compose caches the inflated `ImageVector` per resource id on the context, so
 * reading one of these in a composable is a map lookup after the first time.
 *
 * The getters are `@Composable` because that is how a resource is read; call them
 * exactly where `Icons.Filled.X` used to be.
 */
object AttendoIcons {

    val AutoAwesome: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_auto_awesome)

    val CalendarMonth: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_calendar_month)

    val DoneAll: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_done_all)

    val MeetingRoom: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_meeting_room)

    val Schedule: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_schedule)

    val School: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_school)

    val Today: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_today)
}
