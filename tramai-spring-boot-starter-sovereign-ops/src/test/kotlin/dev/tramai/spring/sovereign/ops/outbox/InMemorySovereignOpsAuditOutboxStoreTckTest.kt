package dev.tramai.spring.sovereign.ops.outbox

import dev.tramai.testing.persistence.outbox.SovereignOpsAuditOutboxStoreTck

/**
 * Epic 8.1e: InMemorySovereignOpsAuditOutboxStore — the sovereign-ops
 * module's default store — must satisfy the shared outbox compatibility
 * contract (tramai-testing testFixtures). A fresh store per case gives full
 * isolation; the store itself is the mutation target for the family.
 */
class InMemorySovereignOpsAuditOutboxStoreTckTest : SovereignOpsAuditOutboxStoreTck() {

    override fun createStore(): SovereignOpsAuditOutboxStore = InMemorySovereignOpsAuditOutboxStore()
}
