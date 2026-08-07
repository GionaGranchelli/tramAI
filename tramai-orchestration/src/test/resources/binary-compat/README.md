# v0.5.0 binary compatibility fixture

`fixture-v0.5.0.jar` is compiled from `BinaryCompatFixture.kt` against tag `v0.5.0`.

Rebuild: `git worktree add /tmp/tramai-v050 v0.5.0`, then run
`env -u HTTP_PROXY -u HTTPS_PROXY -u http_proxy -u https_proxy -u ALL_PROXY -u all_proxy ./gradlew :tramai-orchestration:compileTestKotlin -q`
in that worktree and jar `BinaryCompatFixtureKt.class` from its orchestration test output.
