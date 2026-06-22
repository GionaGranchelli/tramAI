package dev.tramai.core.security

fun interface DlpRedactionAuditEmitter {
    suspend fun emit(
        context: DlpContext,
        redactions: List<DlpRedaction>,
    )
}

val NoOpDlpRedactionAuditEmitter: DlpRedactionAuditEmitter = DlpRedactionAuditEmitter { _, _ -> Unit }
