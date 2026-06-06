package dev.tramai.security.approval

import dev.tramai.core.approval.ApprovalToken
import dev.tramai.core.approval.ApprovalTokenDigester
import dev.tramai.core.approval.Sha256Digest
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

class Sha256ApprovalTokenDigester : ApprovalTokenDigester {
    override fun digest(token: ApprovalToken): Sha256Digest {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(token.reveal().toByteArray(StandardCharsets.UTF_8))
        val hex = hash.joinToString("") { "%02x".format(it) }
        return Sha256Digest.of("sha256:$hex")
    }
}
