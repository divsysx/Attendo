package com.attendo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The one step back after a replacement.
 *
 * A restore deletes a term's marks, so the promise on the confirmation dialog — "Attendo saves a
 * copy of it first" — has to hold on a phone that is nearly full, that gets killed mid-write, or
 * that has already been restored to once today. These tests are about the store keeping a
 * *complete* snapshot or admitting it has none, never a truncated file that looks like one.
 */
class SafetySnapshotStoreTest {

    @get:Rule
    val folder: TemporaryFolder = TemporaryFolder()

    private fun store(directory: File = folder.root) = SafetySnapshotStore(directory)

    // ---- nothing saved -------------------------------------------------------

    @Test
    fun `a fresh install has no snapshot`() {
        val store = store()

        assertFalse(store.exists())
        assertNull(store.read())
    }

    @Test
    fun `clearing when there is nothing to clear is harmless`() {
        val store = store()

        store.clear()

        assertFalse(store.exists())
    }

    // ---- writing and reading -------------------------------------------------

    @Test
    fun `what was written comes back`() {
        val store = store()

        store.write("""{"formatVersion":1,"courses":[]}""")

        assertTrue(store.exists())
        assertEquals("""{"formatVersion":1,"courses":[]}""", store.read())
    }

    @Test
    fun `a directory that does not exist yet is created`() {
        // filesDir/backup on a phone that has never imported anything.
        val store = store(File(folder.root, "backup"))

        store.write("a term")

        assertEquals("a term", store.read())
    }

    @Test
    fun `writing again replaces the previous snapshot`() {
        val store = store()

        store.write("first import")
        store.write("second import")

        // One step back, not a history. Keeping both would invite picking the wrong one.
        assertEquals("second import", store.read())
    }

    @Test
    fun `no half-written file is left lying about`() {
        val store = store()

        store.write("a term")

        // The snapshot is written under .part and renamed into place. A leftover .part after a
        // successful write would mean the rename did not happen and the visible file is stale.
        assertFalse(File(folder.root, "previous-data.json.part").exists())
        assertEquals(listOf("previous-data.json"), folder.root.list()?.sorted())
    }

    @Test
    fun `a snapshot outlives the object that wrote it`() {
        store().write("a term")

        // Nothing is held in memory: the next launch builds a new store over the same directory
        // and has to find the same snapshot, because that is the launch that offers the undo.
        assertTrue(store().exists())
        assertEquals("a term", store().read())
    }

    @Test
    fun `clearing leaves nothing behind`() {
        val store = store()
        store.write("a term")

        store.clear()

        assertFalse(store.exists())
        assertNull(store.read())
        assertEquals(emptyList<String>(), folder.root.list()?.toList())
    }

    @Test
    fun `a used snapshot is not offered twice`() {
        val store = store()
        store.write("a term")

        // What undoLastRestore does: put it back, then use it up. A second undo has nothing to
        // put back, and must not put the same data back again over whatever came after it.
        val recovered = store.read()
        store.clear()

        assertEquals("a term", recovered)
        assertFalse(store.exists())
    }

    // ---- damage --------------------------------------------------------------

    @Test
    fun `an empty file does not count as a snapshot`() {
        val store = store()
        folder.newFile("previous-data.json")

        // A zero-length file is what a write killed at the wrong moment used to leave behind.
        // Offering an undo that restores nothing would be worse than offering none.
        assertFalse(store.exists())
    }

    @Test
    fun `a directory in the snapshot's place is not a snapshot`() {
        val store = store()
        folder.newFolder("previous-data.json")

        assertFalse(store.exists())
        assertNull(store.read())
    }

    // ---- contents ------------------------------------------------------------

    @Test
    fun `a snapshot with newlines and unicode comes back unchanged`() {
        val store = store()
        val text = """
            {
              "courses": [{"code":"गणित","name":"Mathematics — III"}],
              "note": "tab\there, quote\"here"
            }
        """.trimIndent()

        store.write(text)

        // The snapshot is a whole backup document, and it is compared byte for byte with what a
        // restore produces. UTF-8 in, UTF-8 out — a store that mangled a course name would fail
        // the restore's own verification and look like a bug somewhere else entirely.
        assertEquals(text, store.read())
    }

    @Test
    fun `a snapshot the size of a full semester survives`() {
        val store = store()
        val text = "x".repeat(2_000_000)

        store.write(text)

        assertEquals(text.length, store.read()?.length)
    }
}
