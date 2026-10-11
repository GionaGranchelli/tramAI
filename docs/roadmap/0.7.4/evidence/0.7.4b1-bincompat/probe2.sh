#!/usr/bin/env bash
# G1a.1 binary-compatibility probe.
#  1. compile the consumers INSIDE the base tree (6e1d4a1e) so they bind to the released contract
#  2. keep their .class files
#  3. run those exact class files against the BASE jar (control) and the CANDIDATE jar (test)
set -u
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
BASE_SHA=6e1d4a1e8cbab8f5561f1cd372be9d4efb995938
HEAD_WT=/home/gionag/tramai-audit-074          # candidate design in the working tree
BASE_WT=/home/gionag/tmp/bincompat2/base
OUT=/home/gionag/tmp/bincompat2
REPORT="$OUT/report2.txt"
STDLIB=$(ls /home/gionag/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/*/*/kotlin-stdlib-*.jar 2>/dev/null | grep -v sources | sort | tail -1)
COROUTINES=$(ls /home/gionag/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/*/*/kotlinx-coroutines-core-jvm-*.jar 2>/dev/null | grep -v sources | sort | tail -1)
: > "$REPORT"
echo "stdlib=$STDLIB" >> "$REPORT"
echo "coroutines=$COROUTINES" >> "$REPORT"

echo "=== base worktree at $BASE_SHA"
git -C "$HEAD_WT" worktree remove --force "$BASE_WT" > /dev/null 2>&1
rm -rf "$BASE_WT"
git -C "$HEAD_WT" worktree prune > /dev/null 2>&1
if ! git -C "$HEAD_WT" worktree add --detach "$BASE_WT" "$BASE_SHA" >> "$REPORT" 2>&1; then
    echo "WORKTREE_FAIL" >> "$REPORT"; echo "WORKTREE_FAIL"; exit 9
fi
echo "base worktree HEAD=$(git -C "$BASE_WT" rev-parse HEAD)" >> "$REPORT"

echo "=== compiling consumers against the BASE artifact"
mkdir -p "$BASE_WT/tramai-core/src/test/kotlin/bincompat"
cp "$OUT/Consumer.kt" "$BASE_WT/tramai-core/src/test/kotlin/bincompat/Consumer.kt"
( cd "$BASE_WT" && ./gradlew :tramai-core:jar --console=plain ) >> "$REPORT" 2>&1
echo "EXIT[base :tramai-core:jar]=$?" >> "$REPORT"
BASE_JAR=$(ls "$BASE_WT"/tramai-core/build/libs/*.jar 2>/dev/null | grep -vE "sources|javadoc" | head -1)
echo "base jar: $BASE_JAR" >> "$REPORT"

( cd "$BASE_WT" && ./gradlew :tramai-core:compileTestKotlin --console=plain ) >> "$REPORT" 2>&1
echo "EXIT[base kotlin consumer compile]=$?" >> "$REPORT"
mkdir -p "$OUT/classes/kotlin"
rm -rf "$OUT/classes/kotlin/bincompat"
cp -r "$BASE_WT/tramai-core/build/classes/kotlin/test/bincompat" "$OUT/classes/kotlin/" 2>>"$REPORT"
echo "kotlin consumer classes: $(find "$OUT/classes/kotlin" -name '*.class' | wc -l)" >> "$REPORT"

mkdir -p "$OUT/classes/java"
"$JAVA_HOME/bin/javac" -cp "$BASE_JAR:$STDLIB" -d "$OUT/classes/java" "$OUT/JavaConsumer.java" >> "$REPORT" 2>&1
echo "EXIT[javac against base jar]=$?" >> "$REPORT"
echo "java consumer classes: $(find "$OUT/classes/java" -name '*.class' | wc -l)" >> "$REPORT"

mkdir -p "$OUT/jars/base"
cp "$BASE_JAR" "$OUT/jars/base/"

run_pair() { # $1 label, $2 jar path
    echo "--- $1" >> "$REPORT"
    "$JAVA_HOME/bin/java" -cp "$OUT/classes/kotlin:$OUT/classes/java:$2:$STDLIB:$COROUTINES" bincompat.Consumer >> "$REPORT" 2>&1
    echo "KOTLIN_EXIT=$?" >> "$REPORT"
    "$JAVA_HOME/bin/java" -cp "$OUT/classes/kotlin:$OUT/classes/java:$2:$STDLIB:$COROUTINES" bincompat.JavaConsumer >> "$REPORT" 2>&1
    echo "JAVA_EXIT=$?" >> "$REPORT"
}

echo "=== CONTROL: precompiled consumers against the BASE jar"
run_pair "CONTROL $(basename "$BASE_JAR")" "$OUT/jars/base/$(basename "$BASE_JAR")"

echo "=== building the CANDIDATE jar (working tree with the proposed field)"
( cd "$HEAD_WT" && ./gradlew :tramai-core:jar --console=plain ) >> "$REPORT" 2>&1
echo "EXIT[candidate :tramai-core:jar]=$?" >> "$REPORT"
HEAD_JAR=$(ls "$HEAD_WT"/tramai-core/build/libs/*.jar 2>/dev/null | grep -vE "sources|javadoc" | head -1)
echo "candidate jar: $HEAD_JAR" >> "$REPORT"
mkdir -p "$OUT/jars/head"
cp "$HEAD_JAR" "$OUT/jars/head/"

echo "=== TEST: same precompiled consumers against the CANDIDATE jar"
run_pair "TEST $(basename "$HEAD_JAR")" "$OUT/jars/head/$(basename "$HEAD_JAR")"

git -C "$HEAD_WT" worktree remove --force "$BASE_WT" >> "$REPORT" 2>&1
echo "BINCOMPAT2_DONE"
grep -vE "^> Task|^\[|^Download|^Calculating|^$|^Note:" "$REPORT" | tail -70
