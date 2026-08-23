package dev.tramai.security.audit

import dev.tramai.testing.persistence.audit.AuditStoreTck

/**
 * Epic 8.1d: InMemoryAuditStore — the security module's default store — must
 * satisfy the shared AuditStore compatibility contract (tramai-testing
 * testFixtures). A fresh store per case gives full isolation; the store
 * itself is the reference implementation and mutation target for the family.
 */
class InMemoryAuditStoreTckTest : AuditStoreTck() {

    override fun createStore(): AuditStore = InMemoryAuditStore()
}
