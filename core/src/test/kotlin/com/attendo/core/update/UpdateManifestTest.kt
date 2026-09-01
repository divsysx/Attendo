package com.attendo.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading a GitHub `releases/latest` document into an [UpdateManifest].
 *
 * The parser is the whole of the Direct APK update source, so its failure modes are the
 * app's failure modes: anything it cannot make sense of must come back as a
 * [GitHubReleaseError], never an exception, because a bad release document has to degrade
 * into "no update available" rather than into a crash on a student's phone.
 *
 * The fixture bodies mirror the real API's shape — `tag_name`, `name`, `body`, and an
 * `assets` array with `browser_download_url` and `size` — trimmed to the fields the parser
 * reads.
 */
class UpdateManifestTest {

    /** A complete, healthy release: one APK asset, one checksum asset, a titled release. */
    private fun releaseJson(
        tag: String = "v1.2",
        name: String = "Attendo 1.2 (3)",
        body: String = "## What's new\n- Faster rooms",
        apkName: String = "attendo-1.2.apk",
    ): String = """
        {
          "tag_name": "$tag",
          "name": "$name",
          "body": "$body",
          "draft": false,
          "prerelease": false,
          "assets": [
            {
              "name": "$apkName",
              "size": 13000000,
              "browser_download_url": "https://github.com/divsysx/Attendo/releases/download/$tag/$apkName"
            },
            {
              "name": "$apkName.sha256",
              "size": 96,
              "browser_download_url": "https://github.com/divsysx/Attendo/releases/download/$tag/$apkName.sha256"
            }
          ]
        }
    """.trimIndent()

    // ---- the healthy path ----------------------------------------------------

    @Test
    fun `a complete release becomes a manifest`() {
        val manifest = GitHubReleaseParser.parseLatest(releaseJson()).getOrThrow()

        assertEquals("1.2", manifest.versionName)
        assertEquals(3L, manifest.versionCode)
        assertEquals("Attendo 1.2 (3)", manifest.title)
        assertEquals("## What's new\n- Faster rooms", manifest.notes)
        assertEquals("https://github.com/divsysx/Attendo/releases/download/v1.2/attendo-1.2.apk", manifest.apkUrl)
        assertEquals(13000000L, manifest.apkSizeBytes)
        assertEquals(ReleaseChannel.STABLE, manifest.channel)
        // The releases endpoint cannot carry the checksum's *contents*; the caller fetches
        // the .sha256 asset separately. Pinning null keeps that contract honest.
        assertNull(manifest.apkSha256)
    }

    @Test
    fun `a leading v is stripped from the version name`() {
        // The tag is how a release is named; the student-facing version has no "v".
        val manifest = GitHubReleaseParser.parseLatest(releaseJson(tag = "v2.0", name = "Attendo 2.0 (9)")).getOrThrow()

        assertEquals("2.0", manifest.versionName)
    }

    @Test
    fun `the version code comes from the parenthesised number in the release title`() {
        // The code is not derivable from the name — "1.10" versus "1.9" — so the release
        // process states it. The title is the only place it is stated.
        assertEquals(3L, GitHubReleaseParser.versionCodeFrom("Attendo 1.2 (3)"))
        assertEquals(41L, GitHubReleaseParser.versionCodeFrom("Attendo 1.10 (41)"))
        assertEquals(1L, GitHubReleaseParser.versionCodeFrom("1.0 (1)"))
    }

    // ---- version comparison --------------------------------------------------

    @Test
    fun `newer is decided on the version code, never the name`() {
        val installed = AppVersionRef(name = "1.10", code = 41)

        assertTrue(installed.isUpdateTo(UpdateManifest(versionName = "1.9", versionCode = 42, apkUrl = "u")))
        assertFalse(installed.isUpdateTo(UpdateManifest(versionName = "2.0", versionCode = 41, apkUrl = "u")))
        assertFalse(installed.isUpdateTo(UpdateManifest(versionName = "1.2", versionCode = 40, apkUrl = "u")))
    }

    @Test
    fun `an installed build newer than the advertised one is not an update`() {
        // The published release being older than what is installed happens with local
        // builds and with rolled-back releases. Offering a "new" update to an older
        // version would be a downgrade dressed as an update.
        val installed = AppVersionRef(name = "1.2", code = 3)

        assertFalse(installed.isUpdateTo(UpdateManifest(versionName = "1.1", versionCode = 2, apkUrl = "u")))
        // Code equal, name ahead: still no update. The code is the comparison.
        assertFalse(installed.isUpdateTo(UpdateManifest(versionName = "1.3", versionCode = 3, apkUrl = "u")))
    }

    // ---- the legacy release shape ---------------------------------------------

    @Test
    fun `the real Attendo 1 point 0 release parses`() {
        // The actual document `releases/latest` served for v1.0, captured verbatim on
        // 2026-09-01. Its title, "Attendo 1.0", predates the parenthesised-code
        // convention — the first published release is never rewritten, so the parser
        // has to read it as-is.
        val body = javaClass.getResourceAsStream("/github-release-v1.0-verbatim.json")!!
            .readBytes().decodeToString()
        val manifest = GitHubReleaseParser.parseLatest(body).getOrThrow()

        assertEquals("1.0", manifest.versionName)
        assertNull(manifest.versionCode)
        assertEquals("Attendo 1.0", manifest.title)
        assertTrue(manifest.notes.startsWith("The first stable release of Attendo"))
        assertEquals(
            "https://github.com/divsysx/Attendo/releases/download/v1.0/Attendo-1.0.apk",
            manifest.apkUrl,
        )
        assertEquals(7470144L, manifest.apkSizeBytes)
    }

    @Test
    fun `a legacy release with no version code is compared by name`() {
        // "Attendo 1.0" without "(1)": the release predates the convention, and its
        // name is a dotted numeric version, which is comparable — so it is used.
        val manifest = GitHubReleaseParser.parseLatest(releaseJson(name = "Attendo 1.2")).getOrThrow()

        assertNull(manifest.versionCode)
        assertFalse(AppVersionRef(name = "1.2", code = 3).isUpdateTo(manifest)) // equal
        assertFalse(AppVersionRef(name = "1.3", code = 4).isUpdateTo(manifest)) // installed ahead
        assertTrue(AppVersionRef(name = "1.1", code = 2).isUpdateTo(manifest)) // genuinely newer
    }

    @Test
    fun `a name-only comparison still sorts 1 point 10 above 1 point 9`() {
        // The whole reason the code convention exists, checked on the fallback path
        // that has no code to lean on: "1.10" must read as newer than "1.9".
        val manifest = UpdateManifest(versionName = "1.10", versionCode = null, apkUrl = "u")

        assertTrue(AppVersionRef(name = "1.9", code = 9).isUpdateTo(manifest))
        assertFalse(AppVersionRef(name = "1.10", code = 10).isUpdateTo(manifest))
        assertFalse(AppVersionRef(name = "1.11", code = 11).isUpdateTo(manifest))
    }

    @Test
    fun `a name-only release that cannot be compared is never an update`() {
        // A tag that is not a dotted numeric version cannot honestly be called newer
        // than anything. Fail safe: no update offered.
        val manifest = UpdateManifest(versionName = "spring-build", versionCode = null, apkUrl = "u")

        assertFalse(AppVersionRef(name = "1.1", code = 2).isUpdateTo(manifest))
    }

    // ---- every way a document is refused -------------------------------------

    @Test
    fun `a release whose tag is not a version is refused`() {
        // A "latest" endpoint pointing at a tag like "final-final" is unusable
        // metadata: with no code and no comparable name there is no honest verdict.
        val result = GitHubReleaseParser.parseLatest(releaseJson(tag = "final-final", name = "Attendo 1.2 (3)"))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is GitHubReleaseError)
    }

    @Test
    fun `a release with no APK asset cannot be updated to`() {
        val json = """
            { "tag_name": "v1.2", "name": "Attendo 1.2 (3)", "assets": [
              { "name": "attendo-1.2.sha256", "browser_download_url": "https://x/attendo-1.2.sha256" }
            ] }
        """.trimIndent()

        assertTrue(GitHubReleaseParser.parseLatest(json).isFailure)
    }

    @Test
    fun `a document with no tag name is refused`() {
        val json = """{ "name": "Attendo 1.2 (3)", "assets": [] }"""

        assertTrue(GitHubReleaseParser.parseLatest(json).isFailure)
    }

    @Test
    fun `a non-JSON body is refused, not thrown`() {
        // A captive portal's login page answering "200 OK" with HTML is the classic
        // mid-lecture failure. It must become "no update", not a SerializationException.
        val result = GitHubReleaseParser.parseLatest("<html><body>Please sign in</body></html>")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is GitHubReleaseError)
    }

    // ---- the name comparator ---------------------------------------------------

    @Test
    fun `version names compare numerically, component by component`() {
        assertTrue(VersionNames.compare("1.10", "1.9")!! > 0)
        assertTrue(VersionNames.compare("1.9", "1.10")!! < 0)
        assertEquals(0, VersionNames.compare("1.2", "1.2"))
        assertTrue(VersionNames.compare("2.0", "1.99")!! > 0)
        assertTrue(VersionNames.compare("1.2.1", "1.2")!! > 0)
        assertEquals(0, VersionNames.compare("1", "1.0.0"))
    }

    @Test
    fun `anything not a dotted numeric version is incomparable`() {
        assertNull(VersionNames.compare("1.2-beta", "1.2"))
        assertNull(VersionNames.compare("1.2", "june"))
        assertNull(VersionNames.compare("", "1.2"))
        assertNull(VersionNames.compare("1..2", "1.2"))
        assertNull(VersionNames.compare("1.2.3.4.5.x", "1.2"))

        assertFalse(VersionNames.isVersion("1.2-beta"))
        assertTrue(VersionNames.isVersion("1.2"))
        assertTrue(VersionNames.isVersion("2.0.1"))
        assertFalse(VersionNames.isVersion(""))
    }

    // ---- the checksum sidecar ------------------------------------------------

    @Test
    fun `a shasum-style checksum line parses`() {
        val digest = "a".repeat(64)

        assertEquals(digest, GitHubReleaseParser.parseSha256("$digest  attendo-1.2.apk\n"))
    }

    @Test
    fun `a bare digest parses`() {
        val digest = "0123456789abcdef".repeat(4)

        assertEquals(digest, GitHubReleaseParser.parseSha256(digest))
    }

    @Test
    fun `an uppercase digest is normalised to lowercase`() {
        // The comparison is case-insensitive, and the stored form is lowercase — one side
        // has to normalise, and this is the side that knows the convention.
        val digest = "ABCDEF".repeat(10) + "abcd"

        assertEquals(digest.lowercase(), GitHubReleaseParser.parseSha256(digest))
    }

    @Test
    fun `a checksum that is not 64 hex digits is refused`() {
        assertNull(GitHubReleaseParser.parseSha256("z".repeat(64)))
        assertNull(GitHubReleaseParser.parseSha256("a".repeat(63)))
        assertNull(GitHubReleaseParser.parseSha256("a".repeat(65)))
        assertNull(GitHubReleaseParser.parseSha256(""))
        assertNull(GitHubReleaseParser.parseSha256("   \n"))
    }

    // ---- the digest shape ----------------------------------------------------

    @Test
    fun `looksLikeSha256 accepts exactly 64 hex digits`() {
        val digest = "0123456789abcdef".repeat(4)

        assertTrue(ApkGate.looksLikeSha256(digest))
        assertTrue(ApkGate.looksLikeSha256(digest.uppercase()))
        assertFalse(ApkGate.looksLikeSha256("0123456789abcdef".repeat(4) + "0"))
        assertFalse(ApkGate.looksLikeSha256(null))
        assertFalse(ApkGate.looksLikeSha256("not a digest"))
    }
}
