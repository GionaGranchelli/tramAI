package dev.tramai.server

import java.nio.charset.StandardCharsets.UTF_8
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

interface WebhookSignatureVerifier {
    val name: String
    fun verify(payload: String, headers: Map<String, String>): Boolean
}

class GitHubWebhookSignatureVerifier(
    private val secret: String,
    private val replayCache: ReplayCache = ReplayCache(),
) : WebhookSignatureVerifier {
    override val name: String = "github"

    override fun verify(payload: String, headers: Map<String, String>): Boolean {
        val candidate = headers["X-Hub-Signature-256"]?.trim()
        if (secret.isBlank() || candidate.isNullOrBlank()) {
            return false
        }
        val expected = computeSignature(secret, payload)
        if (!MessageDigest.isEqual(expected.toByteArray(UTF_8), candidate.toByteArray(UTF_8))) {
            return false
        }
        return replayCache.recordIfNotReplayed(candidate)
    }

    private fun computeSignature(
        secret: String,
        payload: String,
    ): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(UTF_8), "HmacSHA256"))
        val digest = mac.doFinal(payload.toByteArray(UTF_8))
        return "sha256=${digest.toHexString()}"
    }
}

class ReplayCache(
    private val maxAge: Duration = Duration.ofMinutes(5),
    private val clock: Clock = Clock.systemUTC(),
) {
    private val entries = ConcurrentHashMap<String, Instant>()

    init {
        require(!maxAge.isNegative && !maxAge.isZero) { "maxAge must be greater than zero" }
    }

    fun recordIfNotReplayed(signature: String): Boolean {
        val now = clock.instant()
        cleanup(now)
        val signatureHash = signatureHash(signature)
        val existing = entries[signatureHash]
        if (existing != null && existing.plus(maxAge).isAfter(now)) {
            return false
        }
        entries[signatureHash] = now
        return true
    }

    private fun cleanup(now: Instant) {
        entries.entries.removeIf { (_, timestamp) -> !timestamp.plus(maxAge).isAfter(now) }
    }

    private fun signatureHash(signature: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(signature.toByteArray(UTF_8))
            .toHexString()
}

private fun ByteArray.toHexString(): String {
    val chars = CharArray(size * 2)
    forEachIndexed { index, byte ->
        val value = byte.toInt() and 0xff
        chars[index * 2] = HEX_DIGITS[value ushr 4]
        chars[index * 2 + 1] = HEX_DIGITS[value and 0x0f]
    }
    return String(chars)
}

private val HEX_DIGITS = "0123456789abcdef".toCharArray()
