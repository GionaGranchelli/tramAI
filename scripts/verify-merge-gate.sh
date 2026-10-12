#!/usr/bin/env bash
#
# Merge-gate verification, in one executable place.
#
# Why this exists
# ---------------
# A merge once proceeded through a gate whose query was invalid (`gh api … --json`). The query return-
# ed nothing, the gate read "no failures", reported success, and the merge went through. The merge was
# correct by luck of ordering, not because anything checked it. Remembering that is not a safeguard;
# this is.
#
# The gate is `verifyMergeGate` (registered by TramaiReleaseVerificationPlugin). It REFUSES when the
# evidence is unavailable, incomplete, pending, unsuccessful, or attached to a different commit — and
# a fetch that produces nothing is unavailable evidence, not a pass. That last rule is the one the old
# gate lacked.
#
# Usage
# -----
#   scripts/verify-merge-gate.sh <pr-number> <expected-head-sha>   # verify, then merge manually
#   scripts/verify-merge-gate.sh --self-check                      # prove it still fails closed
#
# Exit code 0 means the evidence covers exactly that commit and is complete and green. Any non-zero
# exit is a refusal, and the named rule(s) say why.
set -u

cd "$(dirname "$0")/.." || exit 9

if [ "${1:-}" = "--self-check" ]; then
    # Two refusals that need no knowledge of the current PR. They prove the property that matters: a
    # gate that cannot read its evidence must not pass.
    fail=0

    echo "== self-check 1: a pull request that does not exist must be refused"
    if ./gradlew verifyMergeGate -PprNumber=999999 -PexpectedHead=deadbeefdeadbeefdeadbeefdeadbeefdeadbeef \
        --console=plain >/tmp/mg-self-1.log 2>&1; then
        echo "  FAIL: the gate passed with no readable evidence"
        fail=1
    else
        echo "  ok: refused"
        grep -aE "merge gate REFUSED" /tmp/mg-self-1.log | head -2 | sed 's/^/    /'
    fi

    echo "== self-check 2: readable evidence for the wrong commit must be refused"
    # A real pull request, so the API returns data, paired with a head it never had: this exercises the
    # rule that checks and workflows must belong to the commit under review, not merely that evidence is
    # missing.
    if ./gradlew verifyMergeGate -PprNumber=518 -PexpectedHead=deadbeefdeadbeefdeadbeefdeadbeefdeadbeef \
        --console=plain >/tmp/mg-self-2.log 2>&1; then
        echo "  FAIL: the gate passed with a wrong head"
        fail=1
    else
        echo "  ok: refused"
        grep -aE "\[(head-mismatch|required-workflow-missing|workflow-runs-unavailable)\]" /tmp/mg-self-2.log |
            head -2 | sed 's/^/    /'
    fi

    echo "== self-check 3: missing properties must be refused, not defaulted"
    if ./gradlew verifyMergeGate --console=plain >/tmp/mg-self-3.log 2>&1; then
        echo "  FAIL: the gate ran without an explicit commit to tie evidence to"
        fail=1
    else
        echo "  ok: refused"
        grep -aE "verifyMergeGate requires" /tmp/mg-self-3.log | head -1 | sed 's/^/    /'
    fi

    if [ "$fail" -ne 0 ]; then
        echo
        echo "SELF-CHECK FAILED: the merge gate no longer fails closed."
        exit 1
    fi
    echo
    echo "SELF-CHECK PASSED: all three refusals hold."
    exit 0
fi

if [ "$#" -ne 2 ]; then
    echo "usage: $0 <pr-number> <expected-head-sha>" >&2
    echo "       $0 --self-check" >&2
    exit 2
fi

exec ./gradlew verifyMergeGate -PprNumber="$1" -PexpectedHead="$2" --console=plain
