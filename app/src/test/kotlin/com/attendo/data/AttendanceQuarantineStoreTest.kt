package com.attendo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The copy that keeps a deleted account's attendance from being lost to the next sign-in.
 *
 * This is the one file in the whole lifecycle whose absence is unrecoverable. Every other part
 * of the boundary is a decision that can be re-made on the next pass; the rows this store is
 * written from are cleared immediately afterwards, and the cloud copy went with the account.
 * So the properties pinned here are the ones the ordering depends on: a write either lands
 * whole or raises, never leaves a truncated file standing where a complete one used to be, and
 * leaves no `.part` behind to be mistaken for progress.
 *
 * The store has no reader — nothing in the app opens the copy back up — so the assertions read
 * the file directly. That is deliberate: a test that went through a `read()` would be pinning
 * an API that does not exist, and would stop meaning anything the moment one is added.
 */
class AttendanceQuarantineStoreTest {

    @get:Rule
    val folder: TemporaryFolder = TemporaryFolder()

    private fun store(directory: File = folder.root) = AttendanceQuarantineStore(directory)

    /** The file the store writes, read the way anything outside the app would read it. */
    private fun copy(directory: File = folder.root): File =
        File(directory, "removed-account-attendance.json")

    // ---- nothing set aside ---------------------------------------------------

    @Test
    fun `an install that has never had an account deleted has no copy`() {
        assertFalse(store().exists())
    }

    @Test
    fun `a phone that has never synced has no copy`() {
        // The directory only exists once something is set aside. `AppReset` clears by
        // directory, so a store that created its directory eagerly would leave a reset
        // logging a directory it had to clear where nothing was ever written.
        val directory = File(folder.root, AttendanceQuarantineStore.DIRECTORY_NAME)

        assertFalse(store(directory).exists())
        assertFalse("Nothing may be created by asking the question.", directory.exists())
    }

    // ---- writing -------------------------------------------------------------

    @Test
    fun `what was set aside is on the phone afterwards`() {
        val store = store()

        store.write("""{"formatVersion":1,"courses":[{"code":"CS301"}]}""")

        assertTrue(store.exists())
        assertEquals("""{"formatVersion":1,"courses":[{"code":"CS301"}]}""", copy().readText())
    }

    @Test
    fun `a directory that does not exist yet is created`() {
        // filesDir/quarantine on the first deletion this install ever sees.
        val directory = File(folder.root, AttendanceQuarantineStore.DIRECTORY_NAME)

        store(directory).write("a term")

        assertTrue(store(directory).exists())
        assertEquals("a term", copy(directory).readText())
    }

    @Test
    fun `a second deletion replaces the first copy`() {
        val store = store()

        store.write("the first deleted account's term")
        store.write("the second deleted account's term")

        // One phone, one body of attendance, one file. Accumulating would mean the copy
        // outliving every account that ever touched the phone, and the student paying for it.
        assertEquals("the second deleted account's term", copy().readText())
        assertEquals(listOf("removed-account-attendance.json"), folder.root.list()?.sorted())
    }

    @Test
    fun `no half-written file is left lying about`() {
        val store = store()

        store.write("a term")

        // Written under .part and renamed into place. A leftover .part after a successful
        // write would mean the rename did not happen and the visible file is stale — which,
        // for this file, means the rows about to be cleared were never saved.
        assertFalse(File(folder.root, "removed-account-attendance.json.part").exists())
        assertEquals(listOf("removed-account-attendance.json"), folder.root.list()?.sorted())
    }

    @Test
    fun `the copy outlives the object that wrote it`() {
        store().write("a term")

        // Nothing is held in memory. The next pass — possibly after a process death, possibly
        // days later — builds a new store over the same directory and has to find it.
        assertTrue(store().exists())
        assertEquals("a term", copy().readText())
    }

    // ---- damage ------------------------------------------------------------

    @Test
    fun `an empty file does not count as a copy`() {
        val store = store()
        folder.newFile("removed-account-attendance.json")

        // A zero-length file is what a write killed at the wrong moment would leave behind if
        // the rename were not atomic. Reporting it as a copy is worse than reporting none: the
        // Account screen would promise the student their term is here, and it would not be.
        assertFalse(store.exists())
    }

    @Test
    fun `a directory in the copy's place is not a copy`() {
        val store = store()
        folder.newFolder("removed-account-attendance.json")

        assertFalse(store.exists())
    }

    // ---- contents ----------------------------------------------------------

    @Test
    fun `a copy with newlines and unicode round-trips unchanged`() {
        val store = store()
        val text = """
            {
              "courses": [{"code":"गणित","name":"Mathematics — III"}],
              "note": "tab\there, quote\"here"
            }
        """.trimIndent()

        store.write(text)

        // The copy is a whole backup document, written in the format the restore path already
        // validates — so if a way back is ever built, it reads a file it knows how to check.
        // UTF-8 in, UTF-8 out, byte for byte.
        assertEquals(text, copy().readText())
    }

    @Test
    fun `a copy the size of a full semester survives`() {
        val store = store()
        val text = "x".repeat(2_000_000)

        store.write(text)

        assertEquals(text.length, copy().length().toInt())
    }

    // ---- the directory is cleared by a reset --------------------------------

    @Test
    fun `the copy's directory is one a reset clears`() {
        // Pinned beside the store because the two have to agree: the copy is deliberately not
        // in the Android backup include-list and nothing in the app reads it, so a reset is the
        // only way a student can get rid of it — and it must actually be got rid of.
        assertTrue(AppReset.clearedDirectories.contains(AttendanceQuarantineStore.DIRECTORY_NAME))
    }
}
