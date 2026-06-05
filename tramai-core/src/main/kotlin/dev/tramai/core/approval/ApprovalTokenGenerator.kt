package dev.tramai.core.approval

fun interface ApprovalTokenGenerator {
    fun generate(): ApprovalToken
}
