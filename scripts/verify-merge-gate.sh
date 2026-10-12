#!/usr/bin/env bash
#
# Merge-gate verification and the merge it authorises, in one place.
#
# Why this exists
# ---------------
# A merge once proceeded through a gate whose query was invalid (`gh api … --json`). The query returned
# nothing, the gate read "no failures", reported success, and the merge went through. The merge happened
# to be correct, but nothing in the gate checked it.
#
# A fail-closed *checker* is not a fail-closed *merge process*: verifying a commit is not the same as
# ensuring only that commit can be merged. So the merge itself is bound to the verified SHA — GitHub
# rejects the call when the head has moved since verification (409 Conflict), which closes the gap
# between the time of check and the time of use.
#
# Usage
# -----
#   scripts/verify-merge-gate.sh <pr> <head>            # verify only; 0 = safe to merge
#   scripts/verify-merge-gate.sh <pr> <head> --merge    # verify, then merge with <head> pinned
#   scripts/verify-merge-gate.sh --self-check           # prove the gate still refuses AND still passes
#
# Verification must pass before any merge call is made. Exit code 0 means either "verified" (verify
# mode) or "verified and merged" (--merge mode). Any non-zero exit is a refusal, and the named rules say
# why. Merging identifies preconditions at the edge of a validation regime with the regime.
set -u

cd "$(dirname "$0")/.." || exit 9

GRADLE="./gradlew"

# ── the expected refusal, and its named rule ─────────────────────────────────────────────────────────
# A nonzero Gradle exit is NOT sufficient evidence of a refusal: an unregistered task, a compilation
# failure or a broken wrapper all exit nonzero too. Each negative check therefore asserts the specific
# rule name it expects, so infrastructure failure is classified as a failed self-check rather than a
# successful negative test.
expect_refusal() {
    label="$1"; log="$2"; rule="$3"
    if ./gradlew verifyMergeGate "${@:4}" --console=plain >"$log" 2>&1; then
        echo "  FAIL [$label]: the gate passed; it must refuse"
        return 1
    fi
    if ! grep -q "\[$rule\]" "$log"; then
        echo "  FAIL [$label]: exit was nonzero but the expected rule [$rule] is absent."
        echo "        A broken invocation, a compile failure or a missing task looks like this."
        grep -aE "Task .* not found|^e: |FAILURE:|What went wrong" "$log" | head -3 | sed 's/^/        /'
        return 1
    fi
    echo "  ok [$label]: refused with [$rule]"
    return 0
}

if [ "${1:-}" = "--self-check" ]; then
    fail=0

    echo "== refusing: evidence that cannot be read"
    expect_refusal "unknown pull request" /tmp/mg-self-1.log "checks-unavailable" \
        -PprNumber=999999 -PexpectedHead=deadbeefdeadbeefdeadbeefdeadbeefdeadbeef || fail=1

    echo "== refusing: readable evidence belonging to another commit"
    expect_refusal "wrong head" /tmp/mg-self-2.log "head-mismatch" \
        -PprNumber=518 -PexpectedHead=deadbeefdeadbeefdeadbeefdeadbeefdeadbeef || fail=1

    echo "== refusing: a required check identity absent from the rollup"
    expect_refusal "missing required check" /tmp/mg-self-3.log "required-check-missing" \
        -PprNumber=518 -PexpectedHead=32d9a4abf4c74965aeaa1dbe86919bf1bf6edeb3 \
        -PrequiredChecks="an-identity-that-does-not-exist" || fail=1

    echo "== refusing: an explicitly empty required-checks override"
    # An empty override would disable identity coverage entirely. It fails at configuration time, so it
    # carries no [rule] line and is asserted on its own message.
    if $GRADLE verifyMergeGate -PprNumber=518 \
        -PexpectedHead=32d9a4abf4c74965aeaa1dbe86919bf1bf6edeb3 -PrequiredChecks="" \
        --console=plain >/tmp/mg-self-3b.log 2>&1; then
        echo "  FAIL [empty override]: the gate ran with identity coverage disabled"
        fail=1
    elif grep -q "requiredChecks was supplied but is empty" /tmp/mg-self-3b.log; then
        echo "  ok [empty override]: refused"
    else
        echo "  FAIL [empty override]: nonzero exit, but not the intended refusal"
        fail=1
    fi

    echo "== refusing: invoked without a commit to tie evidence to"
    if $GRADLE verifyMergeGate --console=plain >/tmp/mg-self-4.log 2>&1; then
        echo "  FAIL [no properties]: the gate ran without an explicit commit"
        fail=1
    elif grep -q "verifyMergeGate requires" /tmp/mg-self-4.log; then
        echo "  ok [no properties]: refused"
    else
        echo "  FAIL [no properties]: nonzero exit, but not the gate's own refusal"
        fail=1
    fi

    # The positive control. The expected failures above are equally consistent with a gate that refuses
    # everything, so this asserts that a genuinely verified commit still passes, exercising the fetch, parse,
    # head and workflow paths end to end.
    #
    # The head is fetched at runtime rather than pinned: a pinned head stops being the PR's head on the next
    # push, so the control would break every time the branch moved. The pinned cases are the refusals, where
    # the fixture is a merged head that never moves.
    FIXTURE_HEAD=$(gh pr view 519 --json headRefOid --jq .headRefOid 2>/dev/null | tr -d '\n\r ')
    if [ -z "$FIXTURE_HEAD" ]; then
        echo "  FAIL [positive control]: could not read the fixture head; the control cannot be skipped"
        fail=1
    else
        echo "== passing: the fixture PR's current head, which is green (positive control) [$FIXTURE_HEAD]"
        if $GRADLE verifyMergeGate -PprNumber=519 -PexpectedHead="$FIXTURE_HEAD" --console=plain \
            >/tmp/mg-self-5.log 2>&1; then
            grep -aE "merge gate PASSED" /tmp/mg-self-5.log | head -1 | sed 's/^/  ok: /'
        else
            echo "  FAIL [positive control]: a commit whose checks and workflows are complete and green was"
            echo "       not verified. Possible causes: the branch is red at its head, the CI matrix changed,"
            echo "       or a rule now refuses evidence it used to accept."
            grep -aE "merge gate REFUSED|\[[a-z-]+\]" /tmp/mg-self-5.log | head -5 | sed 's/^/       /'
            fail=1
        fi
    fi

    # A second pinned refusal, and the reason the fixture above cannot be a fixed SHA: PR #518 is green and
    # merged, but it predates the "Mutation authority transport" workflow, so its rollup cannot satisfy the
    # current required set. Refusing it is the gate working — an old head is not evidence for today's
    # contract — and it is stable because a merged head never moves.
    echo "== refusing: a green head that predates a required check (#518)"
    expect_refusal "predates a required check" /tmp/mg-self-6.log "required-check-missing" \
        -PprNumber=518 -PexpectedHead=32d9a4abf4c74965aeaa1dbe86919bf1bf6edeb3 || fail=1

    if [ "$fail" -ne 0 ]; then
        echo
        echo "SELF-CHECK FAILED: the merge gate no longer behaves as required."
        exit 1
    fi
    echo
    echo "SELF-CHECK PASSED: six named refusals hold, and the positive control still passes."
    exit 0
fi

MODE="verify"
if [ "${3:-}" = "--merge" ]; then
    MODE="merge"
elif [ -n "${3:-}" ]; then
    echo "unknown argument: $3" >&2
    exit 2
fi

if [ "$#" -lt 2 ]; then
    echo "usage: $0 <pr-number> <expected-head-sha> [--merge]" >&2
    echo "       $0 --self-check" >&2
    exit 2
fi

PR="$1"
HEAD="$2"

# ── verify ──────────────────────────────────────────────────────────────────────────────────────────
if ! $GRADLE verifyMergeGate -PprNumber="$PR" -PexpectedHead="$HEAD" --console=plain; then
    echo
    echo "REFUSED: not merging. The evidence for $HEAD is unavailable, incomplete, pending," >&2
    echo "unsuccessful, or belongs to another commit." >&2
    exit 1
fi

if [ "$MODE" = "verify" ]; then
    echo
    echo "VERIFIED: $HEAD is safe to merge. To merge it with that exact commit pinned:"
    echo "  $0 $PR $HEAD --merge"
    exit 0
fi

# ── merge, bound to the verified commit ─────────────────────────────────────────────────────────────
# `sha` is the expected-head condition: GitHub refuses to merge if the pull request's head is no longer
# this commit. That is what stops a branch that moved after verification from being merged on the
# strength of the earlier check.
if [ -n "${TRAMAI_MERGE_DRY_RUN:-}" ]; then
    echo
    echo "DRY RUN: would call"
    echo "  gh api --method PUT repos/{owner}/{repo}/pulls/$PR/merge -f sha=$HEAD -f merge_method=squash"
    exit 0
fi

echo
echo "Merging $PR with head $HEAD pinned..."
if ! out=$(gh api --method PUT "repos/{owner}/{repo}/pulls/$PR/merge" \
    -f "sha=$HEAD" -f "merge_method=squash" 2>&1); then
    echo "MERGE FAILED: $out" >&2
    echo "If this is a 409, the head moved after verification; re-verify and re-review the new head." >&2
    exit 1
fi
echo "$out"
