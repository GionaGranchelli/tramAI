package dev.tramai.core.security

fun interface DlpRedactionAuditEmitter {
    suspend fun emit(
        context: DlpContext,
        redactions: List<DlpRedaction>,
    )
}

object NoOpDlpRedactionAuditEmitter : DlpRedactionAuditEmitter {
    override suspend fun emit(
        context: DlpContext,
        redactions: List<DlpRedaction>,
    ) = Unit
}
