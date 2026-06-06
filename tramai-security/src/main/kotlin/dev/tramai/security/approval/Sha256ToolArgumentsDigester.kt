package dev.tramai.security.approval

import dev.tramai.core.approval.SensitiveToolArguments
import dev.tramai.core.approval.Sha256Digest
import dev.tramai.core.approval.ToolArgumentsDigester
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

class Sha256ToolArgumentsDigester : ToolArgumentsDigester {
    override fun digest(arguments: SensitiveToolArguments): Sha256Digest {
        val bytes = MessageDigest
            .getInstance("SHA-256")
            .digest(arguments.reveal().toByteArray(StandardCharsets.UTF_8))
        val hex = bytes.joinToString("") { "%02x".format(it) }
        return Sha256Digest.of("sha256:$hex")
    }
}
