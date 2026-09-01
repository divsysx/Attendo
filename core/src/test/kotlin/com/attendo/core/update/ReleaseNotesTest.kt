package com.attendo.core.update

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Rendering a GitHub release body as the plain text the update card shows.
 *
 * The card is not a Markdown view, and release notes are written in Markdown for the
 * Releases page. This is a small normalisation, not a parser — the tests pin the handful
 * of markers a release actually uses, and that anything unrecognised passes through
 * untouched rather than being mangled.
 */
class ReleaseNotesTest {

    @Test
    fun `headings keep their words and lose their hashes`() {
        assertEquals(
            "What's new",
            ReleaseNotes.plain("## What's new"),
        )
        assertEquals("Title", ReleaseNotes.plain("# Title"))
        assertEquals("Title", ReleaseNotes.plain("###### Title"))
    }

    @Test
    fun `emphasis and code markers are dropped`() {
        assertEquals(
            "Fixed the thing that broke",
            ReleaseNotes.plain("**Fixed** the `thing` that __broke__"),
        )
    }

    @Test
    fun `list markers become bullets`() {
        assertEquals("• Fixed the thing\n• Fixed the other thing", ReleaseNotes.plain("- Fixed the thing\n* Fixed the other thing"))
    }

    @Test
    fun `runs of blank lines collapse to one`() {
        assertEquals("One\n\nTwo", ReleaseNotes.plain("One\n\n\n\n\nTwo"))
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals("One line", ReleaseNotes.plain("\n  One line  \n"))
    }

    @Test
    fun `a multi-line release reads as it was written`() {
        val markdown = """
            ## What's new

            - **Mark a whole day as missed** in one action
            - Bulk handling for unreviewed classes

            ## Fixed

            Backup import on older files.
        """.trimIndent()

        assertEquals(
            "What's new\n\n• Mark a whole day as missed in one action\n• Bulk handling for unreviewed classes\n\nFixed\n\nBackup import on older files.",
            ReleaseNotes.plain(markdown),
        )
    }

    @Test
    fun `unrecognised markup passes through untouched`() {
        // Links, tables, images — not normalised, not mangled. A stray Markdown artefact
        // in a card is a cosmetic bug; a note that stops saying what it said is worse.
        assertEquals("[link](https://example.com)", ReleaseNotes.plain("[link](https://example.com)"))
    }

    @Test
    fun `an empty body stays empty`() {
        assertEquals("", ReleaseNotes.plain(""))
        assertEquals("", ReleaseNotes.plain("  \n  "))
    }

    @Test
    fun `a hyphen inside a sentence is not a list marker`() {
        assertEquals("One - two", ReleaseNotes.plain("One - two"))
    }
}
