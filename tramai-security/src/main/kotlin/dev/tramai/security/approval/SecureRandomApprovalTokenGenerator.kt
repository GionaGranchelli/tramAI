package dev.tramai.security.approval

import dev.tramai.core.approval.ApprovalToken
import dev.tramai.core.approval.ApprovalTokenGenerator
import java.security.SecureRandom
import java.util.Base64

class SecureRandomApprovalTokenGenerator(
    private val secureRandom: SecureRandom = SecureRandom(),
    private val tokenBytes: Int = 32,
) : ApprovalTokenGenerator {

    init {
        require(tokenBytes >= 32) { "tokenBytes must be at least 32 (256 bits)" }
    }

    override fun generate(): ApprovalToken {
        val bytes = ByteArray(tokenBytes)
        secureRandom.nextBytes(bytes)
        return ApprovalToken.parsePresented(
            Base64.getUrlEncoder().withoutPadding().encodeToString(bytes),
        )
    }
}
