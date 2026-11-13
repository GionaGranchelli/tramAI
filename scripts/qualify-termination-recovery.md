# Qualification: an interrupted rebuild (R5)

`scripts/qualify-termination-recovery.sh` proves the two interruption cases a same-process test
cannot reach, against a real PostgreSQL in a container the script starts and then removes:

- **committed but not acknowledged** — a publish whose transaction commits and whose caller never
  receives the outcome;
- **abrupt termination** — `kill -9` on a JVM while its publish transaction is open.

## Run it

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64   # or any JDK 21
scripts/qualify-termination-recovery.sh
```

Requirements: docker, a JDK. Expected wall time ~1 minute, most of it the Gradle compile. The script
prints an evidence block and also writes it to
`tramai-security/build/qualification/termination-recovery.txt` (build output, untracked).

It exits nonzero unless the single test case actually ran and passed, and unless all three verdict
lines (`R5-B`, `R5-A`, `R5-CONTROL`) appear in the test's captured stdout. A skipped case is a
failure: a qualification that qualifies nothing must not look green.

The test itself is
`tramai-security/src/test/kotlin/dev/tramai/security/governance/GovernanceProjectionTerminationQualificationTest.kt`
and is opt-in: without `GOV_TERM_URL` (set by the script) it is skipped, which is what happens in CI.

## What it proves

The test publishes a baseline snapshot, then spawns child JVMs of its own class and kills them:

1. **Inside the transaction.** The child publishes through the store with a JDBC proxy that pauses
   after the store's header upsert has executed and before any fact or decision row is written. The
   parent verifies from its own connection that the write is *uncommitted and invisible* (the header
   still reads the baseline digest, the child rows are still the baseline's), then sends SIGKILL and
   records the exit status (137 = death by signal). Afterwards the database must hold either the
   previously committed projection — byte-identical, compared row by row across all three tables —
   or the fully committed new one; `load()` must rebuild it and agree on the digest, and there must be
   exactly one header row with its facts and decision rows. A mixture (new header, old children) makes
   the digest disagree and is refused as corruption, so it cannot pass.
2. **After the commit.** The child publishes, prints a marker only after `publish()` returned — i.e.
   after `commit()` returned — and never reports the outcome. The parent confirms the new digest is
   visible, kills the child, and confirms the projection is unchanged and readable, with exactly one
   version and no duplicated children. Re-publishing the same content must answer `ALREADY_CURRENT`
   and leave the row contents byte-identical.
3. **Control.** The parent then tampers with the stored row (a header digest that disagrees with its
   children; a header whose children are missing) and requires `load()` to refuse both, which is what
   makes the two assertions above discriminating rather than merely satisfied.

Not claimed: that a kill can land at an arbitrary instruction. The kill point is chosen and proven —
the parent knows the transaction is open and the header write is uncommitted when it kills — and the
assertion is about the only two states PostgreSQL can leave, not about coverage of every millisecond
of the publish.
