package dev.tramai.security.approval

import dev.tramai.core.approval.ApprovalIdGenerator
import java.util.UUID

class UuidApprovalIdGenerator : ApprovalIdGenerator {
    override fun generate(): String = UUID.randomUUID().toString()
}
