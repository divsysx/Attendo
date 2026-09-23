package com.attendo.data.community

import com.attendo.data.AppReset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The security invariant: the anonymous community identity never travels in a backup.
 *
 * An anonymous-auth session token restored onto a second device would make one student
 * two reporters — the reporter-per-person model depends on the token existing in exactly
 * one place. The guard is layered (see [CommunityClient]): the backup rule files are
 * include-lists where the identity file is deliberately absent, the app's own export
 * format has no community section, and Clear All ends the identity outright. This test
 * statically enforces all three, so a future edit that "helpfully" adds the identity to
 * a backup include-list, or grows a community field into [com.attendo.core.backup.BackupSnapshot],
 * fails the suite instead of quietly shipping the leak.
 *
 * The `.atid` transfer file (see [AtidCodec]) extends the same invariant to the
 * identity's *portable* form: it too appears in no include-list, its payload stays
 * a bare transfer code, it never touches app storage or Room, and the backup format
 * still has no community section now that moving the identity is a feature at all.
 */
class CommunityIdentityBackupGuardTest {

    private val identityFile = CommunityIdentityStore.FILE_NAME + ".xml"

    // ------------------------------------------------------------- the static rules

    @Test
    fun `the identity file appears in no backup include list`() {
        for (rules in listOf("backup_rules.xml", "data_extraction_rules.xml")) {
            val text = sourceFile("src/main/res/xml/$rules").readText()

            // Every include in every include-list, whatever the domain. The identity file
            // must not match any of them — in either direction, since the rules are
            // include-lists and an <exclude> would itself be an admission it was included.
            val includes = Regex("""<include\s+[^>]*?/>""")
                .findAll(text)
                .map { it.value }
                .toList()

            assertTrue("$rules has no include elements at all — the file stopped being an include-list.", includes.isNotEmpty())
            for (include in includes) {
                assertFalse(
                    "$rules includes $include: the identity file ($identityFile) must never " +
                        "appear in any backup include-list, because a restored session token " +
                        "would make one student two reporters.",
                    include.contains(identityFile),
                )
            }
        }
    }

    @Test
    fun `the identity file is a path Android would recognize if it ever appeared`() {
        // The guard above only bites if the file name used in the rules matches the store's
        // name. This pins the spelling: the rules use `attendo-community.xml`, the store
        // writes `attendo-community` (Android appends `.xml`). If CommunityIdentityStore
        // ever renames its file, this fails and forces the comment in the rule files to
        // be rewritten too — the comments name the file and must not go stale.
        assertEquals("attendo-community.xml", identityFile)
    }

    @Test
    fun `the identity prefs file dies with Clear All`() {
        // The outbox and cache die with the database (clearAllTables); the identity is
        // the one piece of community state that lives outside Room, so the reset's
        // cleared-files list is where its end is recorded.
        assertTrue(
            "Clear All must wipe ${CommunityIdentityStore.FILE_NAME}, or a reset would " +
                "leave a live reporter on a phone that no longer remembers reporting.",
            AppReset.clearedPreferenceFiles.contains(CommunityIdentityStore.FILE_NAME),
        )
    }

    @Test
    fun `the app's own backup format has no community section`() {
        // BackupSnapshot is the explicit export format, frozen by the approved plan. A
        // community field would put identity-adjacent data into a file students carry
        // between devices by hand — exactly the transfer the invariant forbids.
        val snapshot = sourceFile("core/src/main/kotlin/com/attendo/core/backup/BackupSnapshot.kt").readText()
        val codec = sourceFile("core/src/main/kotlin/com/attendo/core/backup/BackupCodec.kt").readText()

        for (source in listOf(snapshot, codec)) {
            for (needle in listOf("community", "identity", "attendo-community", "supabase")) {
                assertFalse(
                    "The backup format must stay free of community/identity data; found " +
                        "\"$needle\" in the backup sources. The format is frozen by the " +
                        "approved plan — identity transfer, if ever built, is a separate " +
                        "feature with an explicit secure flow.",
                    source.contains(needle, ignoreCase = true),
                )
            }
        }
    }

    @Test
    fun `the backup format has no account or sync state either`() {
        // The Phase 2 extension of the same invariant. An account now exists (the
        // community session, linked to GitHub), and attendance sync is coming — but the
        // backup file is *this phone's attendance*, carried by hand, and must never grow
        // a section for: the auth session (a restored token makes one student two
        // reporters), the account identity (whose account a restore lands under is a
        // sign-in decision, not a file's), or the sync cursor/state (which belongs to
        // the pair of devices doing the syncing, not to the term being carried). "sync"
        // as a needle is safe here: nothing in the backup sources has any reason to say
        // it, because syncing is not the file's business.
        val snapshot = sourceFile("core/src/main/kotlin/com/attendo/core/backup/BackupSnapshot.kt").readText()
        val codec = sourceFile("core/src/main/kotlin/com/attendo/core/backup/BackupCodec.kt").readText()

        for (source in listOf(snapshot, codec)) {
            for (needle in listOf("account", "cursor", "cloudid", "cloud_id", "anonymous", "sync")) {
                assertFalse(
                    "The backup format must stay free of account/session/sync state; found " +
                        "\"$needle\" in the backup sources. Backup Format v2 is frozen: the " +
                        "auth session stays in its excluded preferences file, the account is " +
                        "a sign-in and not a field, and sync state lives in the sync layer.",
                    source.contains(needle, ignoreCase = true),
                )
            }
        }
    }

    // ------------------------------------------- the .atid transfer file's guards

    @Test
    fun `an atid file appears in no backup include list either`() {
        // The identity's *file* form is as excluded as its session form. An .atid file
        // never lives in app storage (it is written straight to a SAF location the
        // student chose), so an include-list entry for it could only ever be a
        // mistake — and the same "include-lists stay include-lists" reasoning applies.
        for (rules in listOf("backup_rules.xml", "data_extraction_rules.xml")) {
            val text = sourceFile("src/main/res/xml/$rules").readText()
            for (needle in listOf(".atid", "atid")) {
                assertFalse(
                    "$rules mentions \"$needle\": an identity transfer file must never be " +
                        "backed up by Android — it can move who the reports belong to.",
                    text.contains(needle, ignoreCase = true),
                )
            }
        }
    }

    @Test
    fun `the atid file store touches only the place the user chose`() {
        // The store is the one class that moves the file's bytes, so it is where an
        // "also keep a copy in the cache for retries" would land. It must know
        // exactly one door: the content resolver, with a URI the user's picker
        // produced. No app storage, no preferences, no database.
        val source = sourceFile("src/main/kotlin/com/attendo/data/community/ReportingIdentityTransfer.kt").readText()
        val store = source.substringAfter("class AtidFileStore").substringBefore("\n}")

        for (door in listOf("filesDir", "cacheDir", "getSharedPreferences", "Room", "insert")) {
            assertFalse(
                "AtidFileStore must only use the content resolver; found \"$door\". A copy " +
                    "of an .atid file in app storage is a copy a backup could scoop up.",
                store.contains(door),
            )
        }
    }

    @Test
    fun `the atid plaintext carries a transfer code and nothing else`() {
        // The file's payload type is the security boundary in miniature: if it ever
        // grows a token, session or key field, the .atid file stops being "a transfer
        // authorization" and becomes "a credential" — the exact thing the design
        // forbids. Sliced to the type itself, because the file's *docs* say "no
        // session token" on purpose.
        val source = sourceFile("src/main/kotlin/com/attendo/data/community/ReportingIdentityTransfer.kt").readText()
        val plaintext = source.substringAfter("data class AtidPlaintext(").substringBefore("\n}")

        for (needle in listOf("token", "session", "refresh", "apikey", "password")) {
            assertFalse(
                "AtidPlaintext must carry only the transfer code and its timestamp; found " +
                    "\"$needle\". Adding a credential field to the .atid payload is a " +
                    "design change, not a refactor.",
                plaintext.contains(needle, ignoreCase = true),
            )
        }
    }

    @Test
    fun `Room has no table for transfer codes or identity files`() {
        // The server hashes codes; the client never persists one at all. A Room table
        // (or a column on an existing table) would be a place a code, a passphrase
        // fragment or a code path could quietly start living — and Room's database
        // file rides every Android backup of the app's storage.
        for (relative in listOf(
            "app/src/main/kotlin/com/attendo/data/db/CommunityEntities.kt",
            "app/src/main/kotlin/com/attendo/data/db/AttendoDatabase.kt",
        )) {
            val source = sourceFile(relative).readText()
            for (needle in listOf("transfer", "atid", "passphrase")) {
                assertFalse(
                    "$relative mentions \"$needle\": transfer state must never reach Room.",
                    source.contains(needle, ignoreCase = true),
                )
            }
        }
    }

    // ------------------------------------------------------------------ plumbing

    private fun sourceFile(relative: String): File =
        generateSequence(File(System.getProperty("user.dir") ?: ".")) { it.parentFile }
            .map { File(it, relative) }
            .first(File::exists)
}
