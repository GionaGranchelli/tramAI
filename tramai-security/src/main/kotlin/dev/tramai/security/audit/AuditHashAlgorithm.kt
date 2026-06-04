package dev.tramai.security.audit

enum class AuditHashAlgorithm(val wireName: String, val jcaName: String) {
    SHA_256("SHA-256", "SHA-256")
}
