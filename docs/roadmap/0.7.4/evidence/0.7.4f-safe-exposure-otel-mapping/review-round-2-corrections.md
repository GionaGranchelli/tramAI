# Review round 2 - the authority contract was still overclaiming

Reviewed head `b0ebf10f`. The finding is correct and the previous round's claim was wrong: changing the public data
classes into interfaces removed constructors and `copy()`, but **a public Kotlin interface can be implemented by
any consumer**, so the documented guarantee ("a caller cannot assemble a stronger claim") was stronger than the
code. The round-1 test only checked constructor visibility, `copy()` and implementation visibility; it never
attempted to implement the interface, which is the obvious bypass.

## Decision taken

The review offered two options: build a genuinely controlled source boundary, or scope the contract honestly to
a safe representation of caller-supplied query results. **The second was chosen**, because a library cannot
prevent a JVM consumer from implementing a public type - and, as the review notes, Kotlin's `internal` is not a
security boundary either. Claiming otherwise in a published contract is the actual defect.

## What changed

**Enforcement where it genuinely exists.** Both public types are now `sealed` interfaces with private
implementations, so a Kotlin consumer in another module cannot supply its own implementation. That is a real
compile-time boundary and it is asserted (`isSealed`), alongside the existing checks: no public constructor, no
`copy()`, package-private implementation class, and a companion that exposes `from` but not the name-mangled
module factory.

**The claim.** The KDoc now separates the two guarantees explicitly:

- *content safety* - only the named properties can ever be rendered, no payload field exists, nothing is invented
  or upgraded;
- *not authentication* - neither the type nor `from` proves a query occurred, since `GovernanceQueryResult` and
  its DTOs are publicly constructible and any JVM consumer can implement the interfaces. No consumer may treat
  these attributes as authenticated enforcement evidence.

`GovernanceTelemetryExporter` says the same at the export boundary: the attributes are **caller-attested, not
authenticated**, which is consistent with OpenTelemetry being a representation of evidence here.

**A test that pins the limit instead of hiding it.** `exported attributes are caller-attested, and this adapter
does not authenticate them` feeds the adapter a hand-built `GovernanceQueryResult.Found` and asserts the export
reports exactly that claim, with no attribute anywhere claiming authentication. If a later change starts
implying otherwise, this test and the KDoc disagree visibly.

## P2 - recorded decision vocabulary

Confirmed. `GovernanceDecisionFact.decision` is checked only for syntactic token safety, and `sk-live-example123`
passes that check, so forwarding it verbatim put an attacker-chosen string on an attribute. The telemetry boundary
now applies the closed vocabulary the repository's audit readers already use - `ALLOW`, `DENY`,
`REQUIRE_APPROVAL` - and replaces anything else with the fixed, non-disclosing `UNRECOGNIZED`, which keeps "a
decision was recorded but is not disclosed" distinguishable from "no decision was recorded".

The vocabulary is duplicated here rather than shared, because those sets are private to `:tramai-security`'s
audit and evidence readers and this module must not reach into them. The duplication is safe in the direction
that matters: an unlisted value is withheld here, never disclosed.

Pinned by two tests - the credential-shaped token is replaced and never rendered, and all three approved values
are disclosed as themselves.

## Not changed

The catalogue registration, the module edge, the PostgreSQL end-to-end test, the mutation-artifact removal, the
currencies and every quality gate. No suppression, no threshold, no baseline movement.
