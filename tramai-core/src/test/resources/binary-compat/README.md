# Tramai core binary-compatibility fixture

`fixture-v0.5.0.jar` was compiled from `BinaryCompatFixture.kt` against the
`tramai-core` artifact built from the repository's `v0.5.0` Git tag. It calls
the public constructors and provider-failure functions whose v0.5.0 JVM
signatures must remain executable. `BinaryCompatibilityFixtureTest` runs this
old client bytecode against the current core classes.

The committed JAR has SHA-256
`d94c949aa2815d8da06931d3de6cafbd9355d53fe713d44fe9e3a5d6dec6a464`.
Its ZIP entry timestamps are normalized to Gradle's reproducible archive epoch
(`1980-02-01 00:00`), and entries use reproducible ordering.

## Rebuild

Run this from the repository root with JDK 21 available. The scratch build uses
the repository's Gradle wrapper and reads the Kotlin plugin version from the
`v0.5.0` tag's `gradle/libs.versions.toml`. The `Jar` task explicitly disables
file timestamps and enables reproducible ordering.

```bash
set -eu

REPO_ROOT=$(pwd)
SCRATCH_DIR=$(mktemp -d)
TAG_WORKTREE="$SCRATCH_DIR/tramai-v0.5.0"
FIXTURE_BUILD="$SCRATCH_DIR/fixture-build"

git worktree add --detach "$TAG_WORKTREE" v0.5.0
"$TAG_WORKTREE/gradlew" -p "$TAG_WORKTREE" :tramai-core:jar --no-daemon

KOTLIN_VERSION=$(sed -n 's/^kotlin = "\([^"]*\)"/\1/p' "$TAG_WORKTREE/gradle/libs.versions.toml")
CORE_JAR=$(find "$TAG_WORKTREE/tramai-core/build/libs" -maxdepth 1 -name 'tramai-core-*.jar' ! -name '*-sources.jar' | sort | head -n 1)

mkdir -p "$FIXTURE_BUILD/src/main/kotlin"
cp "$REPO_ROOT/tramai-core/src/test/resources/binary-compat/BinaryCompatFixture.kt" \
  "$FIXTURE_BUILD/src/main/kotlin/BinaryCompatFixture.kt"

cat > "$FIXTURE_BUILD/settings.gradle.kts" <<'EOF'
rootProject.name = "tramai-binary-fixture"
EOF

cat > "$FIXTURE_BUILD/build.gradle.kts" <<EOF
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "$KOTLIN_VERSION"
}

kotlin {
    jvmToolchain(21)
    compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
}

dependencies {
    implementation(files("$CORE_JAR"))
}

tasks.jar {
    archiveFileName.set("fixture-v0.5.0.jar")
    destinationDirectory.set(layout.projectDirectory.dir("out"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
EOF

"$TAG_WORKTREE/gradlew" -p "$FIXTURE_BUILD" jar --no-daemon
cmp "$FIXTURE_BUILD/out/fixture-v0.5.0.jar" \
  "$REPO_ROOT/tramai-core/src/test/resources/binary-compat/fixture-v0.5.0.jar"
sha256sum "$FIXTURE_BUILD/out/fixture-v0.5.0.jar"

git worktree remove "$TAG_WORKTREE"
rm -rf "$SCRATCH_DIR"
```

`cmp` must produce no output. `sha256sum` must print the committed digest above.
Copy the rebuilt JAR into this directory only when both the source and intended
ABI probes change; otherwise a byte difference indicates a non-reproducible or
incorrect rebuild and should be investigated rather than committed.
