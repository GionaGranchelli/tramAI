## Relocated in 0.7.4c2

These files no longer live in `:tramai-control-plane`. 0.7.4c2 (owner-decided Option C) moved them,
unchanged apart from the package line and the added imports, to `:tramai-security` in package
`dev.tramai.security.governance`: `GovernanceProjection.kt`, `GovernanceFactClassification.kt`,
`GovernanceDecisionFold.kt`, `GovernanceProjectionReader.kt` and `GovernanceProjectionTest.kt`.

The reason is structural, not stylistic: the control plane is layer `core-contracts` and
`config/quality/module-boundaries.yml` forbids `core-contracts -> governance-security`, which is where
`AuditStore` lives, so no audit-backed implementation of this seam could be compiled inside the control
plane; and the seam is `internal`, so no other module could implement it either. `:tramai-security` may
depend on `core-contracts`, so the projection and the audit read now live together with the seam still
`internal`, no type made public, and the registration authority still owned by
`:tramai-control-plane`.

Nothing in this record is retracted by the move: the source inventory, the contract, the authority
classes, the temporal classifications, the completeness rule, the absence vocabulary and the
discriminators are unchanged. Paths quoted above describe the 0.7.4c1 state. Behavioural equivalence is
evidenced by the relocated suite — 31/31 green with its assertions untouched.
