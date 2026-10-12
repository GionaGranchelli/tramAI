#!/usr/bin/env bash
# G1a.1 binary-compatibility reproducer — replayable from committed files only.
#
#   TRAMAI_REPO=<clone> bash probe2.sh
#
# It creates its own throwaway worktree, so the caller's checkout is never modified. The candidate
# design is supplied by the committed patch rather than by an uncommitted working-tree edit.
#
#   worktree at BASE  -> build base jar -> compile consumers against the base artifact
#                     -> apply candidate patch IN THE SAME worktree -> build candidate jar
#                     -> run the SAME consumer class files against base jar (control) and candidate jar (test)
set -u
export JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO=${TRAMAI_REPO:-$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel 2>/dev/null || echo "")}
BASE=${BASE_SHA:-6e1d4a1e8cbab8f5561f1cd372be9d4efb995938}
OUT=$(mktemp -d "${TMPDIR:-/tmp}/bincompat2.XXXXXX")
WT="$OUT/worktree"
REPORT="$OUT/report.txt"
GRADLE_CACHE=${GRADLE_CACHE:-$HOME/.gradle/caches/modules-2/files-2.1}

if [ -z "$REPO" ] || ! git -C "$REPO" rev-parse --git-dir > /dev/null 2>&1; then
    echo "TRAMAI_REPO must point at a git clone or worktree of this repository (got: '$REPO')" >&2
    exit 9
fi
: > "$REPORT"

STDLIB=$(ls "$GRADLE_CACHE"/org.jetbrains.kotlin/kotlin-stdlib/*/*/kotlin-stdlib-*.jar 2>/dev/null | grep -v sources | sort | tail -1)
COROUTINES=$(ls "$GRADLE_CACHE"/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/*/*/kotlinx-coroutines-core-jvm-*.jar 2>/dev/null | grep -v sources | sort | tail -1)
if [ -z "$STDLIB" ]; then echo "kotlin-stdlib not found under $GRADLE_CACHE" >&2; exit 9; fi
{
    echo "repo=$REPO"
    echo "base=$BASE"
    echo "out=$OUT"
    echo "stdlib=$STDLIB"
    echo "coroutines=$COROUTINES"
} >> "$REPORT"

echo "=== worktree at base $BASE"
if ! git -C "$REPO" worktree add --detach "$WT" "$BASE" >> "$REPORT" 2>&1; then
    echo "WORKTREE_FAIL (see $REPORT)" >&2; exit 9
fi
echo "  worktree HEAD=$(git -C "$WT" rev-parse HEAD)" | tee -a "$REPORT"

echo "=== consumers, compiled against the BASE artifact"
mkdir -p "$WT/tramai-core/src/test/kotlin/bincompat"
cp "$SCRIPT_DIR/Consumer.kt.txt" "$WT/tramai-core/src/test/kotlin/bincompat/Consumer.kt"
( cd "$WT" && ./gradlew :tramai-core:jar --console=plain ) >> "$REPORT" 2>&1
echo "EXIT[base jar]=$?" >> "$REPORT"
BASE_JAR=$(ls "$WT"/tramai-core/build/libs/*.jar 2>/dev/null | grep -vE "sources|javadoc" | head -1)
( cd "$WT" && ./gradlew :tramai-core:compileTestKotlin --console=plain ) >> "$REPORT" 2>&1
echo "EXIT[base kotlin consumer compile]=$?" >> "$REPORT"
mkdir -p "$OUT/classes/kotlin" "$OUT/classes/java" "$OUT/javasrc"
cp -r "$WT/tramai-core/build/classes/kotlin/test/bincompat" "$OUT/classes/kotlin/" 2>>"$REPORT"
# javac requires a .java basename; the committed evidence file carries a .txt suffix so the
# repository's file-scoped static gates do not treat it as module source.
cp "$SCRIPT_DIR/JavaConsumer.java.txt" "$OUT/javasrc/JavaConsumer.java"
"$JAVA_HOME/bin/javac" -cp "$BASE_JAR:$STDLIB" -d "$OUT/classes/java" "$OUT/javasrc/JavaConsumer.java" >> "$REPORT" 2>&1
echo "EXIT[javac against base jar]=$?" >> "$REPORT"
echo "kotlin classes=$(find "$OUT/classes/kotlin" -name '*.class' | wc -l) java classes=$(find "$OUT/classes/java" -name '*.class' | wc -l)" >> "$REPORT"
# Fail closed on a vacuous run: a missing consumer class would make every Java category look like a
# compatibility failure when it is an absent artifact. This must stop the run, not decorate it.
if [ ! -f "$OUT/classes/java/bincompat/JavaConsumer.class" ] || [ ! -f "$OUT/classes/kotlin/bincompat/Consumer.class" ]; then
    echo "HARNESS_FAIL: consumer classes missing (see $REPORT) - this run proves nothing" >&2
    exit 9
fi
mkdir -p "$OUT/jars"; cp "$BASE_JAR" "$OUT/jars/"

run_pair() {
    echo "--- $1" >> "$REPORT"
    "$JAVA_HOME/bin/java" -cp "$OUT/classes/kotlin:$OUT/classes/java:$2:$STDLIB:$COROUTINES" bincompat.Consumer >> "$REPORT" 2>&1
    echo "KOTLIN_EXIT=$?" >> "$REPORT"
    "$JAVA_HOME/bin/java" -cp "$OUT/classes/kotlin:$OUT/classes/java:$2:$STDLIB:$COROUTINES" bincompat.JavaConsumer >> "$REPORT" 2>&1
    echo "JAVA_EXIT=$?" >> "$REPORT"
}

echo "=== CONTROL: the precompiled consumers against the BASE jar"
run_pair "CONTROL base jar $(basename "$BASE_JAR")" "$OUT/jars/$(basename "$BASE_JAR")"

echo "=== applying the committed candidate patch inside the worktree"
if git -C "$WT" apply --verbose "$SCRIPT_DIR/candidate-governedRunIdentity.patch" >> "$REPORT" 2>&1; then
    echo "  patch applied" | tee -a "$REPORT"
else
    echo "PATCH_FAIL (see $REPORT)" >&2; exit 9
fi
echo "  candidate diff:" >> "$REPORT"
git -C "$WT" diff --stat >> "$REPORT" 2>&1

echo "=== building the CANDIDATE jar from the patched worktree"
( cd "$WT" && ./gradlew :tramai-core:jar --console=plain ) >> "$REPORT" 2>&1
echo "EXIT[candidate jar]=$?" >> "$REPORT"
CAND_JAR=$(ls "$WT"/tramai-core/build/libs/*.jar 2>/dev/null | grep -vE "sources|javadoc" | head -1)
mkdir -p "$OUT/jars-candidate"; cp "$CAND_JAR" "$OUT/jars-candidate/"

echo "=== TEST: the SAME precompiled class files against the CANDIDATE jar"
run_pair "TEST candidate jar $(basename "$CAND_JAR")" "$OUT/jars-candidate/$(basename "$CAND_JAR")"

git -C "$REPO" worktree remove --force "$WT" >> "$REPORT" 2>&1
echo
echo "=== outcomes (full log: $REPORT)"
grep -aE "^CONTROL|^TEST|^OK |^FAIL |^INFO |KOTLIN_EXIT|JAVA_EXIT|EXIT\[|classes=" "$REPORT" | sed 's/dev\.tramai\.core\.policy\.//g'
