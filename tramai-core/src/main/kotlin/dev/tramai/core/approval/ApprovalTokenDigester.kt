package dev.tramai.core.approval

fun interface ApprovalTokenDigester {
    fun digest(token: ApprovalToken): Sha256Digest
}
