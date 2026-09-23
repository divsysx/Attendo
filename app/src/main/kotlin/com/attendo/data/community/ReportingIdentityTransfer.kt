package com.attendo.data.community

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.LocalDate
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * The `.atid` file — Attendo Reporting IDentity — read and written here, and nowhere else.
 *
 * ### What the file is, and deliberately is not
 *
 * A `.atid` file carries **exactly one secret**: a single-use transfer code the server
 * minted for the owner's current identity. It contains no session token, no refresh
 * token, no user id, no reputation — nothing that authenticates as the reporter by
 * itself. Possession of the file plus its passphrase starts a *transfer*, which the
 * server gates behind a 24-hour window the old device can veto; it never silently
 * displaces a working device. That separation (a transfer authorization, not a
 * credential) is the whole security model, so the codec refuses to encode anything
 * but the code: there is no API here that could put a token in the file even by
 * mistake.
 *
 * ### The format
 *
 * ```json
 * {
 *   "format": "attendo-reporting-identity",
 *   "version": 1,
 *   "kdf": { "algorithm": "pbkdf2-hmac-sha256", "iterations": 600000, "salt": "…" },
 *   "cipher": { "algorithm": "aes-256-gcm", "nonce": "…", "ciphertext": "…" }
 * }
 * ```
 *
 * The salt and nonce are fresh per export (never reused, never derived from anything),
 * the key is stretched from the passphrase with PBKDF2-HMAC-SHA256 at 600,000
 * iterations (OWASP's guidance for PBKDF2-SHA256), and AES-256-GCM's tag makes
 * every wrong passphrase and every tampered byte fail loudly — a wrong passphrase
 * never decrypts into a plausible-looking code.
 *
 * The plaintext is `{"transfer_code": "…", "created_at": "…"}` — the code, and when
 * it was exported so the UI can warn about stale files. Nothing else, ever.
 *
 * ### Where the file may never go
 *
 * Not into Room, SharedPreferences, logs, analytics, crash reports, or any normal
 * backup: the backup include-lists exclude it, and
 * [CommunityIdentityBackupGuardTest] pins that statically. The file exists only in
 * the place the user chose with SAF, encrypted, for as long as they keep it.
 */
object AtidCodec {

    const val FORMAT: String = "attendo-reporting-identity"

    /** The format version this build writes. Version 1 is the first. */
    const val VERSION: Int = 1

    const val KDF_ALGORITHM: String = "pbkdf2-hmac-sha256"
    const val CIPHER_ALGORITHM: String = "aes-256-gcm"

    /**
     * OWASP's recommended PBKDF2-HMAC-SHA256 work factor: a second or two of CPU on a
     * phone — deliberately too slow to brute-force, and exactly why [encode] and
     * [decode] are suspend (they stretch on [Dispatchers.Default], never the caller's
     * thread).
     */
    const val KDF_ITERATIONS: Int = 600_000

    private const val SALT_BYTES: Int = 16
    private const val NONCE_BYTES: Int = 12
    private const val KEY_BITS: Int = 256

    /** A decode-side sanity bound: no honest file asks for more stretching than this. */
    private const val MAX_ITERATIONS: Int = 10_000_000

    const val FILE_EXTENSION: String = "atid"

    /**
     * No public registry has an `.atid` type, and inventing an unregistered
     * `application/vnd.*` name makes pickers filter the student's own file out. The
     * generic binary type is what every document provider hands a `.atid` back as —
     * and the contents are validated on the way in regardless of what the file
     * claims to be, exactly like a backup.
     */
    const val MIME_TYPE: String = "application/octet-stream"

    /** `attendo-identity-2026-09-11.atid` — says what it is, sorts chronologically. */
    fun suggestedFileName(on: LocalDate): String = "attendo-identity-$on.$FILE_EXTENSION"

    /**
     * The decode outcome. Every failure is typed, because the UI has different things
     * to say: a wrong passphrase is the user's to retry, a corrupt file is the file's
     * to re-export, and conflating them teaches the wrong lesson.
     */
    sealed interface DecodeResult {

        /** The file decrypted and carried a code. The code is in memory only. */
        data class Decoded(val transferCode: String, val createdAt: Instant) : DecodeResult

        /** Not a `.atid` file at all (not JSON, or not this format). */
        data object NotAnAtidFile : DecodeResult

        /** A version this build does not know how to read. */
        data class UnsupportedVersion(val version: Int) : DecodeResult

        /** Decryption failed the GCM tag: the passphrase is wrong (or the bytes changed). */
        data object WrongPassphrase : DecodeResult

        /** Structurally a `.atid` file, but its parameters are unusable or inconsistent. */
        data object Corrupt : DecodeResult
    }

    /**
     * Encrypts [transferCode] under [passphrase] and serialises the file.
     *
     * [createdAt] is when the code was exported — the honest "created", which the UI
     * shows when warning about stale files.
     *
     * Suspend because the key stretching is seconds of CPU, and the callers run on
     * the main thread (ViewModel scope): the stretching happens on
     * [Dispatchers.Default], so the export stays a spinner instead of Android's
     * "App isn't responding" dialog.
     */
    suspend fun encode(transferCode: String, passphrase: CharArray, createdAt: Instant): String =
        withContext(Dispatchers.Default) {
            val salt = SecureRandoms.bytes(SALT_BYTES)
            val nonce = SecureRandoms.bytes(NONCE_BYTES)
            val ciphertext = encryptCipher(nonce, passphrase, salt, KDF_ITERATIONS).doFinal(
                json.encodeToString(
                    AtidPlaintext(transferCode = transferCode, createdAt = createdAt.toString()),
                ).toByteArray(Charsets.UTF_8),
            )

            val b64 = Base64.getEncoder()
            json.encodeToString(
                AtidEnvelope(
                    format = FORMAT,
                    version = VERSION,
                    kdf = AtidKdf(
                        algorithm = KDF_ALGORITHM,
                        iterations = KDF_ITERATIONS,
                        salt = b64.encodeToString(salt),
                    ),
                    cipher = AtidCipher(
                        algorithm = CIPHER_ALGORITHM,
                        nonce = b64.encodeToString(nonce),
                        ciphertext = b64.encodeToString(ciphertext),
                    ),
                ),
            )
        }

    /**
     * Reads a `.atid` file. Never throws: every failure is a typed [DecodeResult], so a
     * hostile or truncated file cannot crash the import flow.
     *
     * Suspend for the same reason [encode] is: the stretching runs on
     * [Dispatchers.Default], because every road into a decode (the ordinary import,
     * the re-import after "keep this phone's identity", the confirmed destructive
     * replace) starts on the main thread, and seconds of main-thread PBKDF2 there
     * read to the student as a frozen app — the "Close or wait" dialog of the
     * two-device test.
     */
    suspend fun decode(text: String, passphrase: CharArray): DecodeResult =
        withContext(Dispatchers.Default) {
            // Read straight out of the tree rather than via the envelope type: the format
            // field decides what this file even is, and a future version is entitled to
            // have reshaped everything around it. (The same reasoning as BackupCodec.)
            val tree = runCatching { json.parseToJsonElement(text) }.getOrNull()
                as? JsonObject ?: return@withContext DecodeResult.NotAnAtidFile
            if (tree["format"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } != FORMAT) {
                return@withContext DecodeResult.NotAnAtidFile
            }

            val envelope = runCatching { json.decodeFromString(AtidEnvelope.serializer(), text) }
                .getOrElse { return@withContext DecodeResult.NotAnAtidFile }

            if (envelope.version != VERSION) return@withContext DecodeResult.UnsupportedVersion(envelope.version)
            if (envelope.kdf.algorithm != KDF_ALGORITHM || envelope.cipher.algorithm != CIPHER_ALGORITHM) {
                return@withContext DecodeResult.Corrupt
            }

            // Parameters are untrusted: an attacker-crafted file must not turn the import
            // into a CPU bomb or feed the cipher nonsense lengths.
            if (envelope.kdf.iterations !in 1..MAX_ITERATIONS) return@withContext DecodeResult.Corrupt
            val salt = envelope.kdf.salt.fromBase64() ?: return@withContext DecodeResult.Corrupt
            if (salt.size !in 8..64) return@withContext DecodeResult.Corrupt
            val nonce = envelope.cipher.nonce.fromBase64() ?: return@withContext DecodeResult.Corrupt
            if (nonce.size != NONCE_BYTES) return@withContext DecodeResult.Corrupt
            val ciphertext = envelope.cipher.ciphertext.fromBase64() ?: return@withContext DecodeResult.Corrupt
            if (ciphertext.isEmpty()) return@withContext DecodeResult.Corrupt

            val plaintext = try {
                decryptCipher(nonce, passphrase, salt, envelope.kdf.iterations)
                    .doFinal(ciphertext)
            } catch (_: Exception) {
                // GCM's tag failing is the wrong-passphrase case; a truncated ciphertext
                // or engine error is corruption. Both are safe to report as-is, and
                // neither leaks how close the guess was.
                return@withContext DecodeResult.WrongPassphrase
            }

            val inner = runCatching {
                json.decodeFromString(AtidPlaintext.serializer(), String(plaintext, Charsets.UTF_8))
            }.getOrElse { return@withContext DecodeResult.Corrupt }
            if (inner.transferCode.isEmpty() || inner.transferCode.length > 128) {
                return@withContext DecodeResult.Corrupt
            }
            val createdAt = inner.createdAt
                ?.let { runCatching { Instant.parse(it) }.getOrNull() }
                ?: Instant.EPOCH

            DecodeResult.Decoded(transferCode = inner.transferCode, createdAt = createdAt)
        }

    // ---- internals ----------------------------------------------------------

    private fun encryptCipher(nonce: ByteArray, passphrase: CharArray, salt: ByteArray, iterations: Int): Cipher =
        gcmCipher(Cipher.ENCRYPT_MODE, nonce, passphrase, salt, iterations)

    private fun decryptCipher(nonce: ByteArray, passphrase: CharArray, salt: ByteArray, iterations: Int): Cipher =
        gcmCipher(Cipher.DECRYPT_MODE, nonce, passphrase, salt, iterations)

    private fun gcmCipher(
        mode: Int,
        nonce: ByteArray,
        passphrase: CharArray,
        salt: ByteArray,
        iterations: Int,
    ): Cipher {
        // The spec lives in memory only as long as this call: derived, used, gone.
        val spec = PBEKeySpec(passphrase, salt, iterations, KEY_BITS)
        val key = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec)
        } finally {
            spec.clearPassword()
        }
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key.encoded, "AES"), GCMParameterSpec(128, nonce))
        }
    }

    private fun String.fromBase64(): ByteArray? = runCatching {
        Base64.getMimeDecoder().decode(this)
    }.getOrNull()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
}

// ---------------------------------------------------------------- wire types

/** The single thing a `.atid` file protects. Adding a field here is a design change. */
@Serializable
private data class AtidPlaintext(
    @SerialName("transfer_code") val transferCode: String,
    /** ISO-8601 instant; nullable so a decode never throws over it. */
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
private data class AtidEnvelope(
    val format: String,
    val version: Int,
    val kdf: AtidKdf,
    val cipher: AtidCipher,
)

@Serializable
private data class AtidKdf(
    val algorithm: String,
    val iterations: Int,
    /** Standard base64, 16 bytes on write; bounds-checked on read. */
    val salt: String,
)

@Serializable
private data class AtidCipher(
    val algorithm: String,
    /** Standard base64, exactly 12 bytes (96-bit GCM nonce) on read. */
    val nonce: String,
    /** Standard base64, ciphertext with the 128-bit GCM tag appended. */
    val ciphertext: String,
)

/** One seam so tests can pin that salt and nonce are unique per export. */
internal object SecureRandoms {
    fun bytes(count: Int): ByteArray = ByteArray(count).also { java.security.SecureRandom().nextBytes(it) }
}

/**
 * Moves the encrypted `.atid` file between the app and the place the user chose —
 * SAF only, so the file never lives in app storage a backup could scoop up or
 * another app could read. The content is always the ciphertext: this class cannot
 * see the code inside, and the codec never learns a Uri.
 */
class AtidFileStore(private val context: Context) {

    /**
     * Writes the file to a user-chosen [uri] (CreateDocument result). False means the
     * stream could not be opened or written — nothing was exported, so the export
     * screen stays up with its warning.
     */
    suspend fun writeTo(uri: Uri, content: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openOutputStream(uri, "wt")?.use { stream ->
                stream.write(content.toByteArray(Charsets.UTF_8))
            } ?: return@runCatching false
            true
        }.getOrDefault(false)
    }

    /**
     * Reads the file the user picked (OpenDocument result). Null means unreadable —
     * wrong file type, revoked permission, or a stream that is not text; the import
     * flow stops there without touching the passphrase.
     */
    suspend fun readFrom(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                stream.readBytes().toString(Charsets.UTF_8).takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }
}
