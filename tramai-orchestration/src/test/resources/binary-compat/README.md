# v0.5.0 binary compatibility fixture

`fixture-v0.5.0.jar` is compiled from `BinaryCompatFixture.kt` against tag `v0.5.0`.

Scope note: the v0.5.0 exception constructors had no default arguments. The
Kotlin default-argument marker synthetics (omitted-cause calls) were added
between v0.5.0 and master; their preservation is verified by the api dump
descriptor comparison in `tramai-orchestration.api`, not by this fixture.

The fixture exercises the v0.5.0 constructors for `FileWorkflowCheckpointStore`,
`MarkdownWorkflowCheckpointStore`, `JdbcWorkflowCheckpointStore`,
`FileWorkflowLeaseStore`, `JdbcWorkflowLeaseStore`,
`InMemoryWorkflowCheckpointStore`, and `InMemoryWorkflowLeaseStore`.

Rebuild: `git worktree add /tmp/tramai-v050 v0.5.0`, then run
`env -u HTTP_PROXY -u HTTPS_PROXY -u http_proxy -u https_proxy -u ALL_PROXY -u all_proxy ./gradlew :tramai-orchestration:compileTestKotlin -q`
in that worktree and jar `BinaryCompatFixtureKt*.class` from its orchestration test output.
