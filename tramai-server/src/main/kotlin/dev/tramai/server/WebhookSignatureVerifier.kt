package dev.tramai.server

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

interface WebhookSignatureVerifier {
    val name: String
    fun verify(payload: String, headers: Map<String, String>): Boolean
}

class GitHubWebhookSignatureVerifier(
    private val secret: String,
) : WebhookSignatureVerifier {
    override val name: String = "github"

    override fun verify(payload: String, headers: Map<String, String>): Boolean {
        val candidate = headers["X-Hub-Signature-256"]?.trim()
        if (secret.isBlank() || candidate.isNullOrBlank()) {
            return false
        }
        val expected = computeSignature(secret, payload)
        return MessageDigest.isEqual(expected.toByteArray(UTF_8), candidate.toByteArray(UTF_8))
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

    private fun ByteArray.toHexString(): String {
        val chars = CharArray(size * 2)
        forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xff
            chars[index * 2] = HEX_DIGITS[value ushr 4]
            chars[index * 2 + 1] = HEX_DIGITS[value and 0x0f]
        }
        return String(chars)
    }

    companion object {
        private val HEX_DIGITS = "0123456789abcdef".toCharArray()
    }
}
