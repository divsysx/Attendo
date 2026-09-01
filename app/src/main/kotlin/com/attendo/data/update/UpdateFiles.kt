package com.attendo.data.update

import java.io.File

/**
 * Where a downloaded update lives, and how it is cleaned up.
 *
 * Private app storage (`filesDir/updates`), not anywhere shared: the file is an
 * installation payload on its way through, not something the student manages, and a
 * private directory is the one a FileProvider can expose one file from without exposing
 * anything else.
 *
 * Obsolete files are removed eagerly — on download start and on successful hand-off to
 * the installer — because a superseded APK is tens of megabytes of nothing, and on this
 * one path the app writes files the student cannot see to delete.
 */
class UpdateFiles(private val directory: File) {

    constructor(context: android.content.Context) : this(File(context.filesDir, "updates"))

    /** The directory, created on demand. */
    private fun dir(): File = directory.apply { mkdirs() }

    /**
     * Where the APK for [versionName] would land. The name is sanitised rather than
     * validated — a version name comes out of a release tag, and a tag may contain
     * anything a maintainer typed, including path separators, so a strange tag still
     * downloads, into a strange but harmless file name.
     */
    fun apkFor(versionName: String): File =
        File(dir(), "attendo-${versionName.replace(Regex("[^A-Za-z0-9._-]"), "-")}.apk")

    /** Deletes every downloaded file. Called before a new download and after an install. */
    fun clear() {
        dir().listFiles()?.forEach { it.delete() }
    }

    /** The finished download for [versionName], when one exists and is not empty. */
    fun existingApk(versionName: String): File? =
        apkFor(versionName).takeIf { it.exists() && it.length() > 0 }
}
