# Compiler-plugin fixture tests

**Deliberate deviation from the usual `module-ballerina-*` convention.** Most
`module-ballerina-*` repos with a compiler plugin (e.g. `module-ballerina-ftp`) ship a
separate `compiler-plugin-tests` Gradle module: JUnit tests driving an in-process
`ProjectEnvironmentBuilder`/`AbstractCodeActionTest` harness against
`ballerina_sources/{valid,invalid}_*` fixtures and `expected_sources/*/result.bal`
files.

This module instead tests the compiler plugin end to end through the real toolchain:
`run-fixture-tests.sh` packs `ballerina/smpp` (plugin jar embedded per
`CompilerPlugin.toml`), pushes the bala to a container-local repository, then runs
`bal build` on every fixture under `fixtures/` inside the exact same dockerised
`ballerina/ballerina:2201.13.4` image the rest of this repo builds with, and asserts
the emitted `SMPP_*` diagnostic codes against each fixture's `expected.txt` (see
`inside-container.sh`). That is exactly what a Ballerina Central consumer executes —
bundled plugin, real `bal` resolution, and all — rather than an in-process harness
that never touches packaging/resolution at all.

The tradeoff, and why this isn't just "the standard approach with extra steps":

- It requires Docker and cannot run nested inside a container (see the
  `-x :smpp-compiler-plugin:fixtureTest` exclusions in
  `.github/workflows/build-with-bal-test-graalvm.yml` for the ubuntu/windows
  GraalVM template runs), so it only executes on runners where a docker daemon is
  actually available.
- It is slower than an in-process JUnit suite (one `docker run` per test invocation,
  a real `bal build` per fixture) in exchange for testing the real distribution path.

If a future maintainer wants parity with the `ftp`-style `compiler-plugin-tests`
module instead (for CI portability or speed), that is a legitimate alternative — see
the "Either accept ... or port to the standard JUnit module" tradeoff recorded
against this file in review history. Until then, treat the shell-script harness here
as the intended design, not an oversight.
