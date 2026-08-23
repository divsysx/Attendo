package com.attendo.ui

import android.net.Uri
import java.time.LocalDate

/**
 * Every navigation destination, and the builders that fill in their arguments.
 *
 * Routes are strings rather than the type-safe serialization API because
 * navigation-compose's typed graphs would pull in kotlinx-serialization for four
 * arguments, all of which are a `Long`, a `LocalDate` or a room name.
 */
object Routes {

    /** The two tabs. Only these show the bottom bar. */
    const val DASHBOARD: String = "dashboard"
    const val ROOMS: String = "rooms"

    const val COURSES: String = "courses"
    const val SEED: String = "seed"
    const val SETTINGS: String = "settings"
    const val BACKUP: String = "backup"

    const val DAY: String = "day/{${Arg.DATE}}"
    fun day(date: LocalDate): String = "day/$date"

    const val COURSE: String = "course/{${Arg.COURSE_ID}}"
    fun course(courseId: Long): String = "course/$courseId"

    /** `courseId = 0` means "new course". */
    const val COURSE_EDITOR: String = "courseEditor/{${Arg.COURSE_ID}}"
    fun courseEditor(courseId: Long = NEW): String = "courseEditor/$courseId"

    const val ROOM: String = "room/{${Arg.ROOM}}"

    /** Room names contain spaces — "Basement Lab" — so they are encoded into the path. */
    fun room(room: String): String = "room/${Uri.encode(room)}"

    /** The id that stands for "not saved yet". Matches `Course.id`'s default. */
    const val NEW: Long = 0L

    object Arg {
        const val DATE: String = "date"
        const val COURSE_ID: String = "courseId"
        const val ROOM: String = "room"
    }
}
