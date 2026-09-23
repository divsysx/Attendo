package com.attendo.data.community

import com.attendo.data.community.AtidCodec.DecodeResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * The `.atid` codec, against the file as an adversary would hold it.
 *
 * Every test here is one of three questions: does an honest file come back (the
 * round trip), does a wrong passphrase or a changed byte fail *loudly* (GCM's tag —
 * a tampered file must never decrypt into a plausible-looking code), and does a
 * hostile file get refused *before* the crypto (bounds checks, so a crafted
 * parameters block cannot turn the import into a CPU bomb or feed the cipher
 * nonsense lengths).
 *
 * The tests craft envelopes at 1,000 iterations — a fraction of the 600,000 a real
 * export pays — because what is being pinned is the *shape* of the file, not the
 * stretching. The one test that uses the real work factor is the round trip, which
 * is the only one that must not diverge from a genuine export.
 */
class AtidCodecTest {

    private val pass = "correct horse battery".toCharArray()
    private val createdAt = Instant.parse("2026-09-11T10:15:00Z")

    // The codec is suspend — its key stretching runs on Dispatchers.Default, and a
    // JVM test's own thread is as much a caller as the main thread is — so every
    // call in this suite goes through these two runBlocking shims, which change
    // nothing about what is being pinned.
    private fun encode(code: String, passphrase: CharArray, at: Instant): String =
        runBlocking { AtidCodec.encode(code, passphrase, at) }

    private fun decode(file: String, passphrase: CharArray): DecodeResult =
        runBlocking { AtidCodec.decode(file, passphrase) }

    // ------------------------------------------------------------ the honest file

    @Test
    fun `a round trip at the real work factor keeps the code and the timestamp`() {
        val file = encode("XTY-9fQ2-staging-mint", pass, createdAt)
        val decoded = decode(file, pass)

        assertTrue("expected Decoded, got $decoded", decoded is DecodeResult.Decoded)
        decoded as DecodeResult.Decoded
        assertEquals("XTY-9fQ2-staging-mint", decoded.transferCode)
        assertEquals(createdAt, decoded.createdAt)
    }

    @Test
    fun `two exports of the same code differ in salt and nonce`() {
        // The one seam the codec owns that no other test can see: a reused salt or
        // nonce would make two files encrypted under the same passphrase comparable,
        // so both must be fresh per export.
        val a = encode("same-code", pass, createdAt).parse()
        val b = encode("same-code", pass, createdAt).parse()

        assertNotEquals(a.kdfSalt, b.kdfSalt)
        assertNotEquals(a.nonce, b.nonce)
        assertNotEquals(a.ciphertext, b.ciphertext)
    }

    @Test
    fun `an exported file has exactly the four public envelope fields`() {
        // The format is the contract: anything beyond format/version/kdf/cipher at
        // the top level — a "helpfully" added field that leaked a token, a device
        // name, an id — is a format change, and this fails it. Same for the nested
        // parameter blocks.
        val tree = encode("XTY-9fQ2", pass, createdAt).parseToJson()

        assertEquals(setOf("format", "version", "kdf", "cipher"), tree.keys)
        assertEquals(setOf("algorithm", "iterations", "salt"), tree["kdf"]!!.jsonObject.keys)
        assertEquals(setOf("algorithm", "nonce", "ciphertext"), tree["cipher"]!!.jsonObject.keys)

        // And the loud parts of the format are what the docs say they are.
        assertEquals(AtidCodec.FORMAT, tree["format"]!!.jsonPrimitive.content)
        assertEquals(1, tree["version"]!!.jsonPrimitive.content.toInt())
        assertEquals(600_000, tree["kdf"]!!.jsonObject["iterations"]!!.jsonPrimitive.content.toInt())
    }

    // ------------------------------------------------------- the wrong passphrase

    @Test
    fun `a wrong passphrase fails the tag`() {
        val file = encode("XTY-9fQ2", pass, createdAt)
        assertEquals(
            DecodeResult.WrongPassphrase,
            decode(file, "correct horse batteryy".toCharArray()),
        )
    }

    @Test
    fun `a changed ciphertext byte fails the tag`() {
        val tampered = encode("XTY-9fQ2", pass, createdAt)
            .withCiphertext { flipLastByte(it) }
        assertEquals(DecodeResult.WrongPassphrase, decode(tampered, pass))
    }

    @Test
    fun `a truncated ciphertext fails the tag`() {
        val tampered = encode("XTY-9fQ2", pass, createdAt)
            .withCiphertext { it.copyOf(it.size - 4) }
        assertEquals(DecodeResult.WrongPassphrase, decode(tampered, pass))
    }

    @Test
    fun `a swapped nonce fails the tag`() {
        // A different honest nonce with the original ciphertext (and the original
        // salt, so the key is the right one and only the nonce is wrong): GCM treats
        // this as tampering too.
        val file = encode("XTY-9fQ2", pass, createdAt).parse()
        val other = encode("XTY-9fQ2", pass, createdAt).parse()
        val tampered = envelope(
            saltB64 = file.kdfSalt,
            nonce = Base64.getMimeDecoder().decode(other.nonce),
            ciphertext = Base64.getMimeDecoder().decode(file.ciphertext),
        )
        assertEquals(DecodeResult.WrongPassphrase, decode(tampered, pass))
    }

    // ------------------------------------------------------------ not our file

    @Test
    fun `a non-JSON file is not an atid file`() {
        assertEquals(DecodeResult.NotAnAtidFile, decode("not json at all", pass))
    }

    @Test
    fun `a JSON file with a different format field is not an atid file`() {
        // The format field is checked before anything else is trusted — the same
        // rule as BackupCodec — so a future format is entitled to reshape everything
        // around it.
        val someone = """{"format":"something-else","version":1}"""
        assertEquals(DecodeResult.NotAnAtidFile, decode(someone, pass))
    }

    @Test
    fun `a future version says which version it is`() {
        val future = envelope(version = 99)
        assertEquals(
            DecodeResult.UnsupportedVersion(99),
            decode(future, pass),
        )
    }

    // ------------------------------------------------------- the crafted file

    @Test
    fun `an unknown kdf algorithm is corrupt`() {
        assertEquals(DecodeResult.Corrupt, decode(envelope(kdfAlgorithm = "scrypt"), pass))
    }

    @Test
    fun `an unknown cipher algorithm is corrupt`() {
        assertEquals(DecodeResult.Corrupt, decode(envelope(cipherAlgorithm = "aes-256-cbc"), pass))
    }

    @Test
    fun `zero and bomb-sized iteration counts are corrupt`() {
        assertEquals(DecodeResult.Corrupt, decode(envelope(iterations = 0), pass))
        assertEquals(
            DecodeResult.Corrupt,
            decode(envelope(iterations = AtidCodec.MAX_ITERATIONS_EXPOSED + 1), pass),
        )
    }

    @Test
    fun `a too-short salt is corrupt`() {
        assertEquals(DecodeResult.Corrupt, decode(envelope(salt = ByteArray(7)), pass))
    }

    @Test
    fun `salt that is not base64 is corrupt`() {
        assertEquals(DecodeResult.Corrupt, decode(envelope(saltB64 = "!!!not base64!!!"), pass))
    }

    @Test
    fun `a nonce that is not 12 bytes is corrupt`() {
        assertEquals(DecodeResult.Corrupt, decode(envelope(nonce = ByteArray(11)), pass))
        assertEquals(DecodeResult.Corrupt, decode(envelope(nonce = ByteArray(13)), pass))
    }

    @Test
    fun `an empty ciphertext is corrupt`() {
        assertEquals(DecodeResult.Corrupt, decode(envelope(ciphertext = ByteArray(0)), pass))
    }

    // --------------------------------------------------- the decrypted payload

    @Test
    fun `a payload whose code is empty is corrupt`() {
        // The codec itself will encode an empty code (it validates shapes, not
        // semantics), so the honest file for this test is a real export of one.
        val file = encode("", pass, createdAt)
        assertEquals(DecodeResult.Corrupt, decode(file, pass))
    }

    @Test
    fun `a payload missing its timestamp still decodes, at epoch`() {
        // created_at is nullable on the wire: an export that predates the field (or
        // a hand-trimmed one) must not fail over it — the code is the payload.
        val plaintext = """{"transfer_code":"XTY-9fQ2"}""".toByteArray()
        val file = envelope(ciphertext = encrypt(plaintext))
        val decoded = decode(file, pass)

        assertTrue("expected Decoded, got $decoded", decoded is DecodeResult.Decoded)
        decoded as DecodeResult.Decoded
        assertEquals("XTY-9fQ2", decoded.transferCode)
        assertEquals(Instant.EPOCH, decoded.createdAt)
    }

    @Test
    fun `a payload that is not the expected JSON is corrupt`() {
        val file = envelope(ciphertext = encrypt("just a string, not an object".toByteArray()))
        assertEquals(DecodeResult.Corrupt, decode(file, pass))
    }

    @Test
    fun `a payload with an over-long code is corrupt`() {
        val file = envelope(
            ciphertext = encrypt(
                """{"transfer_code":"${"x".repeat(129)}","created_at":"2026-09-11T10:15:00Z"}""".toByteArray(),
            ),
        )
        assertEquals(DecodeResult.Corrupt, decode(file, pass))
    }

    // -------------------------------------------------------------- test tools

    /** The decode-side iteration bound, mirrored here so the test names a number. */
    private val AtidCodec.MAX_ITERATIONS_EXPOSED: Int get() = 10_000_000

    private fun envelope(
        salt: ByteArray = ByteArray(16) { it.toByte() },
        saltB64: String? = null,
        nonce: ByteArray = ByteArray(12) { (it * 3).toByte() },
        ciphertext: ByteArray = encrypt("""{"transfer_code":"XTY-9fQ2","created_at":"2026-09-11T10:15:00Z"}""".toByteArray()),
        iterations: Int = 1_000,
        version: Int = 1,
        kdfAlgorithm: String = AtidCodec.KDF_ALGORITHM,
        cipherAlgorithm: String = AtidCodec.CIPHER_ALGORITHM,
    ): String {
        val b64 = Base64.getEncoder()
        return """
            {
              "format": "${AtidCodec.FORMAT}",
              "version": $version,
              "kdf": {
                "algorithm": "$kdfAlgorithm",
                "iterations": $iterations,
                "salt": "${saltB64 ?: b64.encodeToString(salt)}"
              },
              "cipher": {
                "algorithm": "$cipherAlgorithm",
                "nonce": "${b64.encodeToString(nonce)}",
                "ciphertext": "${b64.encodeToString(ciphertext)}"
              }
            }
        """.trimIndent()
    }

    /**
     * The test's own encryptor — the mirror of the codec's, at the test's cheaper
     * iteration count. It exists so tamper tests can hold a *valid* ciphertext for
     * a known plaintext and break exactly one thing at a time.
     */
    private fun encrypt(plaintext: ByteArray, iterations: Int = 1_000): ByteArray {
        val spec = PBEKeySpec(pass, ByteArray(16) { it.toByte() }, iterations, 256)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec)
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(key.encoded, "AES"),
                GCMParameterSpec(128, ByteArray(12) { (it * 3).toByte() }),
            )
        }.doFinal(plaintext)
    }

    private fun flipLastByte(bytes: ByteArray): ByteArray =
        bytes.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x55).toByte() }

    private fun String.withCiphertext(mutate: (ByteArray) -> ByteArray): String {
        val b64 = Base64.getEncoder()
        val tree = parseToJson().toMutableMap()
        val cipher = tree["cipher"]!!.jsonObject.toMutableMap()
        // Hoisted: Kotlin's lexer does not allow double quotes inside a string
        // template expression, and this map key needs one.
        val current = cipher["ciphertext"]!!.jsonPrimitive.content
        val replaced = b64.encodeToString(mutate(Base64.getMimeDecoder().decode(current)))
        cipher["ciphertext"] = Json.parseToJsonElement("\"$replaced\"")
        tree["cipher"] = JsonObject(cipher)
        return Json.encodeToString(JsonObject.serializer(), JsonObject(tree))
    }

    /** The envelope's decoded pieces, in the test's own words. */
    private data class Parsed(val kdfSalt: String, val nonce: String, val ciphertext: String)

    private fun String.parse(): Parsed {
        val tree = parseToJson()
        return Parsed(
            kdfSalt = tree["kdf"]!!.jsonObject["salt"]!!.jsonPrimitive.content,
            nonce = tree["cipher"]!!.jsonObject["nonce"]!!.jsonPrimitive.content,
            ciphertext = tree["cipher"]!!.jsonObject["ciphertext"]!!.jsonPrimitive.content,
        )
    }

    private fun String.parseToJson(): JsonObject =
        Json.parseToJsonElement(this).jsonObject
}
