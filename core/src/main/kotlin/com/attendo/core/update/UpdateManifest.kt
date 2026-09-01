package com.attendo.core.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * What a release channel publishes about its latest version.
 *
 * Everything the update card shows, and everything the safety checks need, in one value.
 * The [channel] field is how a future Beta line fits in without a format change: the
 * Stable manifest and a Beta manifest are the same shape, and a provider that is asked for
 * Stable simply never reads the Beta one. It is carried *inside* the manifest rather than
 * inferred from the source so a manifest can be reasoned about on its own.
 *
 * [Serializable] so a successful check's manifest can be cached to disk and shown again on
 * a later start that happens offline — the one place a cached result is worth its bytes.
 */
@Serializable
data class UpdateManifest(
    /** "1.2" — shown to the student. */
    val versionName: String,
    /**
     * The monotone integer under it, which is what "newer" is decided on — when the
     * release states one. Null on a release published before this convention: the
     * first published release ("Attendo 1.0") carried no parenthesised code in its
     * title, and such a release is compared by [versionName] instead — see
     * [AppVersionRef.isUpdateTo].
     */
    val versionCode: Long? = null,
    /** The release's own title, when it has one worth showing. */
    val title: String? = null,
    /** What changed, as written for humans. Shown verbatim under "What's new". */
    val notes: String = "",
    /** Where the release APK can be downloaded from. */
    val apkUrl: String,
    /** The APK's size in bytes, when the source knows it — for "Update size". */
    val apkSizeBytes: Long? = null,
    /**
     * The APK's SHA-256, lowercase hex, when the source publishes one. The strongest
     * check available: a file that matches this is the file that was published.
     */
    val apkSha256: String? = null,
    /** Which line this release came off. Naming one channel now, not several. */
    val channel: ReleaseChannel = ReleaseChannel.STABLE,
)

/** The release lines a provider can be asked about. One exists today. */
enum class ReleaseChannel {
    STABLE,
    BETA,
}

/**
 * A version as the update system sees it: a name for the student and a code for the maths.
 *
 * The code is the only comparison that counts when the release states one. Version names
 * are marketing with punctuation in them — "1.10" versus "1.9" sorts the wrong way as a
 * string and as a decimal — so [isUpdateTo] looks at [UpdateManifest.versionCode] first.
 *
 * Releases published before the code convention (Attendo 1.0) state no code. For those
 * alone, the comparison falls back to [VersionNames]: numeric, component-wise, and honest
 * about "1.10" being newer than "1.9". The fallback decides only whether to *offer* the
 * release; an offered release still has to pass [ApkGate] against the downloaded APK's
 * own versionCode before anything is installed.
 */
data class AppVersionRef(val name: String, val code: Long) {

    /** True when [other] is a build this one should offer to update to. */
    fun isUpdateTo(other: UpdateManifest): Boolean = when (val code = other.versionCode) {
        null -> (VersionNames.compare(other.versionName, name) ?: -1) > 0
        else -> code > this.code
    }
}

/**
 * A stable identity for one release: its version code when it states one, its version
 * name otherwise (the legacy shape).
 *
 * This is what a dismissal is remembered against, so "not now" sticks to exactly the
 * release it was said about however that release was published — and what the launch
 * decision compares a cached update against, so a genuinely new release (a different key)
 * is heard about even when the release before it was declined. It lives here, beside the
 * version comparison it borrows its parts from, rather than in either of its users.
 */
val UpdateManifest.releaseKey: String
    get() = versionCode?.let { "code:$it" } ?: "name:$versionName"

/**
 * Compares dotted numeric version names — "1.10" against "1.9" — the way the numbers
 * read, not the way the strings sort.
 *
 * Returns a negative/zero/positive [Int] as [Comparable] does, or null when either name
 * is not a dotted numeric version ("1.2-beta", "june"). Null is the caller's signal to
 * give up rather than guess: a name that cannot be compared cannot honestly be called
 * newer, and "not an update" is the safe answer.
 */
object VersionNames {

    /** Whether [name] is a dotted numeric version [compare] can make sense of. */
    fun isVersion(name: String): Boolean = componentsOf(name) != null

    fun compare(a: String, b: String): Int? {
        val left = componentsOf(a) ?: return null
        val right = componentsOf(b) ?: return null
        val size = maxOf(left.size, right.size)
        for (i in 0 until size) {
            val part = (left.getOrNull(i) ?: 0).compareTo(right.getOrNull(i) ?: 0)
            if (part != 0) return part
        }
        return 0
    }

    /** "1.10" to [1, 10]; null when any component is not a plain non-negative integer. */
    private fun componentsOf(name: String): List<Int>? =
        name.trim()
            .takeIf { it.isNotEmpty() }
            ?.split('.')
            ?.takeIf { parts -> parts.all { part -> part.isNotEmpty() && part.toIntOrNull() != null } }
            ?.map { it.toInt() }
}

/**
 * Parses a GitHub Releases API response into an [UpdateManifest].
 *
 * This is the whole of the Direct APK update source: one request to
 * `repos/{owner}/{repo}/releases/latest`, no scraping, no backend of our own. The API
 * already carries the version, the notes and the asset's URL and size; the one thing it
 * does not carry is a checksum, which the release process publishes alongside the APK as
 * a second asset — see [SHA256_ASSET_SUFFIX].
 *
 * Parsing is total: anything wrong with the document comes back as a [GitHubReleaseError]
 * describing what was missing, never as an exception, because a failed update check must
 * degrade into "no update" rather than into a crash.
 */
object GitHubReleaseParser {

    /**
     * The suffix this app's release process gives the checksum asset: for an APK named
     * `Attendo-1.2.apk`, the checksum lives in `Attendo-1.2.apk.sha256`. The file is one
     * line of hex — the digest, optionally followed by whitespace and the file's name, the
     * shape `shasum -a 256` writes.
     */
    const val SHA256_ASSET_SUFFIX: String = ".sha256"

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Reads the `GET /releases/latest` document. [body] is the response text; a draft or
     * pre-release never reaches this endpoint, which is exactly the guarantee the Stable
     * channel wants.
     *
     * Two release shapes are accepted, so the Attendo 1.0 release did not have to be
     * rewritten when this updater shipped:
     *
     * - The current convention: the release title states the version code in
     *   parentheses — "Attendo 1.2 (3)" — and "newer" is decided on that code.
     * - The legacy shape: a plain title with no code, as Attendo 1.0 was published.
     *   Such a release is compared by version name — see [VersionNames] — which is
     *   only honest because the tag below is required to be a dotted numeric version.
     */
    fun parseLatest(body: String): Result<UpdateManifest> = runCatching {
        val root = json.parseToJsonElement(body).jsonObject
        val tag = root.string("tag_name")
            ?: return failure("the release has no tag name")
        val versionName = tag.trimStart('v', 'V')
        if (!VersionNames.isVersion(versionName)) {
            return failure("the tag '$tag' is not a version")
        }
        val code = root.string("name")?.let(::versionCodeFrom)
        val manifest = releaseFrom(root, versionName, code)
            ?: return failure("the release has no .apk asset to download")
        Result.success(manifest)
    }.getOrElse { error ->
        Result.failure(GitHubReleaseError("the release could not be read: ${error.message}"))
    }

    /**
     * Reads the checksum asset's contents: the first run of hex characters on the first
     * line. Tolerates the `shasum` two-column layout and a trailing newline, and refuses
     * anything that is not 64 hex digits.
     */
    fun parseSha256(body: String): String? {
        val firstLine = body.lineSequence().firstOrNull { it.isNotBlank() } ?: return null
        val hex = firstLine.trim().split(Regex("\\s+")).firstOrNull().orEmpty()
        return hex.lowercase().takeIf { it.matches(Regex("[0-9a-f]{64}")) }
    }

    /**
     * The version code out of a release title such as "Attendo 1.2 (3)".
     *
     * The parenthesised number is the convention this project's release process writes —
     * the code is not derivable from the name, so it is stated. A title without one is
     * the legacy shape (Attendo 1.0 was published as "Attendo 1.0"): null, and the
     * release is compared by its version name instead.
     */
    internal fun versionCodeFrom(title: String): Long? {
        val match = Regex("\\((\\d+)\\)").find(title) ?: return null
        return match.groupValues[1].toLongOrNull()
    }

    /**
     * Assembles the manifest from a release object, picking the APK asset out of its
     * asset list. Null when there is no `.apk` asset — a release published without its
     * APK yet is a release nobody can update to.
     *
     * The checksum is *not* read here: the releases endpoint only lists the checksum
     * asset's existence, not its contents, so the caller fetches the `.sha256` asset
     * separately and copies the digest in — see [SHA256_ASSET_SUFFIX].
     */
    private fun releaseFrom(root: JsonObject, versionName: String, code: Long?): UpdateManifest? {
        val assets = root["assets"]?.jsonArray ?: return null

        val apk = assets
            .map { it.jsonObject }
            .firstOrNull { it.string("name")?.endsWith(".apk", ignoreCase = true) == true }
            ?: return null
        val apkUrl = apk.string("browser_download_url") ?: return null

        return UpdateManifest(
            versionName = versionName,
            versionCode = code,
            title = root.string("name"),
            notes = root.string("body").orEmpty(),
            apkUrl = apkUrl,
            apkSizeBytes = apk.long("size"),
            apkSha256 = null, // Set by the caller once the checksum asset has been fetched and parsed.
            channel = ReleaseChannel.STABLE,
        )
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)
            ?.takeIf { it !is kotlinx.serialization.json.JsonNull }
            ?.contentOrNull

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()

    private fun failure(reason: String): Result<UpdateManifest> =
        Result.failure(GitHubReleaseError(reason))
}

/** Why a release document could not become a manifest. One sentence, for logs only. */
class GitHubReleaseError(message: String) : Exception(message)
