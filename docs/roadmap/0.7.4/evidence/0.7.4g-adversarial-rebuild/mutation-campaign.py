#!/usr/bin/env python3
"""0.7.4g hand-authored mutation campaign.

Each mutant: clean check -> (control once per discriminator class) -> apply by exact single-occurrence anchor ->
prove applied -> run discriminator -> verdict from the JUnit XML -> restore -> prove byte-identical.

Resumable: verdicts append to RESULTS, so a killed run continues where it stopped.
Never touches config/quality/**, never runs a generate*Baseline task, never commits.
"""
import hashlib
import json
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = "/home/gionag/tramai-g"
RESULTS = "/tmp/074g-mutants.txt"
STATE = "/tmp/074g-mutants-state.json"
LOG_DIR = "/tmp/074g-mutants"
JAVA_HOME = "/usr/lib/jvm/java-21-openjdk-amd64"

GOV = "tramai-security/src/main/kotlin/dev/tramai/security/governance/"
OBS = "tramai-observability/src/main/kotlin/dev/tramai/observability/governance/"

MUTANTS = [
    dict(
        id="M01",
        what="ignore audit hash-chain mismatch",
        file=GOV + "AuditBackedGovernanceDecisionFactSource.kt",
        old="        if (!verification.isValid) {",
        new="        if (false) {",
        module=":tramai-security",
        test="dev.tramai.security.governance.AuditBackedGovernanceDecisionFactSourceTest",
    ),
    dict(
        id="M02",
        what="elect the newest record by arrival order instead of source sequence",
        file=GOV + "GovernanceDecisionFold.kt",
        old="        val latest =\n            requireNotNull(distinct.maxByOrNull { it.sourceSequence ?: Long.MIN_VALUE }) {",
        new="        val latest =\n            requireNotNull(distinct.firstOrNull()) {",
        module=":tramai-security",
        test="dev.tramai.security.governance.GovernanceProjectionTest",
    ),
    dict(
        id="M03",
        what="accept contradictory records at one source position",
        file=GOV + "GovernanceDecisionFold.kt",
        old="            .filterValues { atPosition -> atPosition.map { it.copy(sourceSequence = null) }.distinct().size > 1 }",
        new="            .filterValues { false }",
        module=":tramai-security",
        test="dev.tramai.security.governance.GovernanceProjectionTest",
    ),
    dict(
        id="M04",
        what="allow a stale projection to replace a newer one",
        file=GOV + "JdbcGovernanceProjectionStore.kt",
        old="            ) > (\n                governance_projection_snapshot.audit_head_position,\n                governance_projection_snapshot.registration_version\n            )",
        new="            ) <> (\n                governance_projection_snapshot.audit_head_position,\n                governance_projection_snapshot.registration_version\n            )",
        module=":tramai-security",
        test="dev.tramai.security.governance.JdbcGovernanceProjectionStoreTest",
    ),
    dict(
        id="M05",
        what="let a newer publication overwrite a corrupted identity",
        file=GOV + "JdbcGovernanceProjectionStore.kt",
        old='            throw ProjectionCorruptionException(\n                "stored projection identity columns disagree with the projection key",\n            )',
        new="            // mutant: identity disagreement tolerated",
        module=":tramai-security",
        test="dev.tramai.security.governance.GovernanceProjectionIdentityIntegrityTest",
    ),
    dict(
        id="M06",
        what="skip stored semantic-digest verification",
        file=GOV + "JdbcGovernanceProjectionStore.kt",
        old="            if (snapshot.semanticDigest != header.semanticDigest) {",
        new="            if (false) {",
        module=":tramai-security",
        test="dev.tramai.security.governance.JdbcGovernanceProjectionStoreTest",
    ),
    dict(
        id="M07",
        what="accept an unknown stored format as supported",
        file=GOV + "JdbcGovernanceProjectionStore.kt",
        old="        if (formatVersion != GOVERNANCE_READ_MODEL_FORMAT_VERSION) {",
        new="        if (false) {",
        module=":tramai-security",
        test="dev.tramai.security.governance.JdbcGovernanceProjectionStoreTest",
    ),
    dict(
        id="M08",
        what="promote unknown freshness to current",
        file=GOV + "GovernanceFactClassification.kt",
        old="            else -> FactFreshness.STALE_OR_UNKNOWN",
        new="            else -> FactFreshness.CURRENT_AT_VERSION",
        module=":tramai-security",
        test="dev.tramai.security.governance.MaterializedReadModelQualificationTest",
    ),
    dict(
        id="M09",
        what="derive COMPLETE decision records from a verified raw range",
        file=GOV + "GovernanceFactClassification.kt",
        old="        val range = coverage.verifiedRange ?: return Completeness.UNKNOWN",
        new="        val range = coverage.verifiedRange ?: return Completeness.UNKNOWN\n        return Completeness.COMPLETE",
        module=":tramai-security",
        test="dev.tramai.security.governance.GovernanceProjectionTest",
    ),
    dict(
        id="M10",
        what="promote a policy authorization record to persisted execution authority",
        file=GOV + "GovernanceFactClassification.kt",
        old="            DecisionKind.POLICY_DECISION, DecisionKind.TOOL_PERMISSION -> FactAuthority.POLICY_AUTHORIZATION_RECORD",
        new="            DecisionKind.POLICY_DECISION, DecisionKind.TOOL_PERMISSION -> FactAuthority.AUTHORITATIVE_PERSISTED",
        module=":tramai-observability",
        test="dev.tramai.observability.governance.GovernanceTelemetryExportPostgresTest",
    ),
    dict(
        id="M11",
        what="export unapproved reason-code text instead of the approved decision token",
        file=OBS + "GovernanceExposure.kt",
        old="                approvedDecisionOrUnrecognized(decision.decision),",
        new="                decision.reasonCode,",
        module=":tramai-observability",
        test="dev.tramai.observability.governance.GovernanceExposurePrivacyTest",
    ),
    dict(
        id="M12",
        what="write to the projection store while querying (read path is not read-only)",
        file=GOV + "JdbcGovernanceProjectionStore.kt",
        old="            val loaded = loadWithin(connection, identity)",
        new=(
            "            connection.createStatement().use {\n"
            '                it.executeUpdate("UPDATE governance_projection_snapshot SET materialized_at = materialized_at")\n'
            "            }\n"
            "            val loaded = loadWithin(connection, identity)"
        ),
        module=":tramai-security",
        test="dev.tramai.security.governance.GovernanceCorruptionPreservationTest",
    ),
    dict(
        id="M13",
        what="merge arbitrary metadata into the emitted event (re-run of the stale 0.7.4f M6)",
        file=OBS + "GovernanceTelemetryExporter.kt",
        old="        return builder.build()",
        new=(
            '        builder.put("tramai.governance.debug", event.toString())\n'
            "        return builder.build()"
        ),
        module=":tramai-observability",
        test="dev.tramai.observability.governance.GovernanceTelemetryExporterTest",
    ),
    dict(
        id="M14",
        what="let a publication replace a stored row it cannot verify (the corruption-repair path)",
        file=GOV + "JdbcGovernanceProjectionStore.kt",
        old="        loadWithin(connection, snapshot.identity, forUpdate = true)",
        new="        // mutant: stored row not verified before replacement",
        module=":tramai-security",
        test="dev.tramai.security.governance.GovernanceCorruptionPreservationTest",
    ),
]


def sh(args, timeout=1800):
    env = dict(os.environ, JAVA_HOME=JAVA_HOME)
    return subprocess.run(args, cwd=ROOT, capture_output=True, text=True, timeout=timeout, env=env)


def sha(path):
    with open(os.path.join(ROOT, path), "rb") as handle:
        return hashlib.sha256(handle.read()).hexdigest()


def clean(rel):
    out = sh(["git", "status", "--porcelain", rel]).stdout.strip()
    return out == ""


def xml_summary(module, test):
    pattern = os.path.join(ROOT, module.lstrip(":").replace(":", "/"), "build/test-results/test")
    if not os.path.isdir(pattern):
        return None
    simple = test.split(".")[-1]
    for name in os.listdir(pattern):
        if name.endswith(".xml") and simple in name:
            root = ET.parse(os.path.join(pattern, name)).getroot()
            failures = int(root.get("failures") or 0) + int(root.get("errors") or 0)
            first = ""
            for case in root.iter("testcase"):
                for child in case:
                    if child.tag in ("failure", "error") and not first:
                        first = (child.get("message") or "")[:240].replace("\n", " ")
            return dict(
                tests=int(root.get("tests") or 0),
                failures=failures,
                first=first,
                skipped=int(root.get("skipped") or 0),
            )
    return None


def run_tests(module, test, log):
    result = sh([
        "./gradlew", f"{module}:test", "--tests", test,
        "--rerun-tasks", "--no-build-cache", "--no-daemon", "--console=plain",
    ])
    with open(log, "w") as handle:
        handle.write(result.stdout + "\n" + result.stderr)
    return result


def main():
    os.makedirs(LOG_DIR, exist_ok=True)
    state = json.load(open(STATE)) if os.path.exists(STATE) else {"controls": {}, "done": []}
    open(RESULTS, "a").close()

    for mutant in MUTANTS:
        mid = mutant["id"]
        if mid in state["done"]:
            continue
        rel = mutant["file"]
        if not clean(rel):
            line = f"{mid} {mutant['what']}: INVALID - {rel} was not clean before the run"
            open(RESULTS, "a").write(line + "\n")
            state["done"].append(mid)
            json.dump(state, open(STATE, "w"))
            continue

        # matched control, once per discriminator class
        key = f"{mutant['module']}|{mutant['test']}"
        if key not in state["controls"]:
            control = run_tests(mutant["module"], mutant["test"], f"{LOG_DIR}/{mid}-control.log")
            summary = xml_summary(mutant["module"], mutant["test"])
            verdict = "green" if (control.returncode == 0 and summary and summary["failures"] == 0) else "RED"
            state["controls"][key] = dict(verdict=verdict, summary=summary)
            json.dump(state, open(STATE, "w"))
            if verdict != "green":
                open(RESULTS, "a").write(
                    f"{mid} {mutant['what']}: UNDETERMINED - control for {mutant['test']} was {verdict} "
                    f"({summary}); mutant not applied\n"
                )
                state["done"].append(mid)
                json.dump(state, open(STATE, "w"))
                continue

        before = sha(rel)
        path = os.path.join(ROOT, rel)
        source = open(path).read()
        occurrences = source.count(mutant["old"])
        if occurrences != 1:
            open(RESULTS, "a").write(
                f"{mid} {mutant['what']}: INVALID - anchor occurred {occurrences} times, expected exactly 1\n"
            )
            state["done"].append(mid)
            json.dump(state, open(STATE, "w"))
            continue

        open(path, "w").write(source.replace(mutant["old"], mutant["new"], 1))
        mutated = sha(rel)
        diff = sh(["git", "diff", "--stat", rel]).stdout.strip().splitlines()

        result = run_tests(mutant["module"], mutant["test"], f"{LOG_DIR}/{mid}-mutant.log")
        log_text = ""
        try:
            log_text = open(f"{LOG_DIR}/{mid}-mutant.log").read()
        except OSError:
            pass
        compile_error = bool(re.search(r"^e: ", log_text, re.M)) or "Compilation error" in log_text
        summary = xml_summary(mutant["module"], mutant["test"])

        sh(["git", "checkout", "--", rel])
        restored = sha(rel)
        restoration = "byte-identical" if restored == before else f"MISMATCH {before[:12]}->{restored[:12]}"

        if compile_error:
            verdict = "INVALID (did not compile)"
        elif summary and summary["failures"] > 0:
            verdict = f"KILLED ({summary['failures']} failing of {summary['tests']})"
        elif summary and summary["tests"] == 0:
            verdict = "UNDETERMINED (no tests executed)"
        else:
            verdict = "SURVIVED"

        line = (
            f"{mid} {mutant['what']}: {verdict} | applied={before[:12]}->{mutated[:12]} "
            f"| restore={restoration} | {diff[0] if diff else 'no diff stat'} "
            f"| mechanism={summary['first'] if summary else 'no xml'}"
        )
        open(RESULTS, "a").write(line + "\n")
        print(line, flush=True)
        state["done"].append(mid)
        json.dump(state, open(STATE, "w"))

        if restoration != "byte-identical":
            open(RESULTS, "a").write("ABORT: restoration was not byte-identical\n")
            sys.exit(1)

    print("CAMPAIGN COMPLETE", flush=True)


if __name__ == "__main__":
    main()
