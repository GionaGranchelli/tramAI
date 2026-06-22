package dev.tramai.core.security

interface DlpRedactionAuditEmitter {
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
