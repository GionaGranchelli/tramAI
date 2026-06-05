package dev.tramai.core.approval

fun interface ApprovalIdGenerator {
    fun generate(): String
}
