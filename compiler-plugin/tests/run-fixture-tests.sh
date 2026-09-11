#!/usr/bin/env bash
# Compiler-plugin fixture tests: end-to-end diagnostics through the REAL toolchain.
#
# Each fixture under tests/fixtures/ is a tiny Ballerina project importing
# ballerina/smpp. The harness packs this package (plugin jar embedded per
# CompilerPlugin.toml), pushes the bala to the container-local repository, then
# `bal build`s every fixture against it and asserts the expected diagnostic codes
# (expected.txt, one code per line; the file may be empty = the fixture must produce
# NO SMPP_ diagnostics).
#
# This deliberately tests what a Central consumer executes — the bundled plugin
# resolved through a repository — rather than an in-process ProjectEnvironmentBuilder
# harness. Runs in the SAME docker image as every other bal invocation in this repo.
set -uo pipefail
cd "$(dirname "$0")/.."   # module-ballerina-smpp/compiler-plugin
PLUGIN_DIR="$(pwd)"
REPO_DIR="$(cd .. && pwd)"  # module-ballerina-smpp/
IMAGE="ballerina/ballerina:2201.13.4"

docker run --rm -v "${REPO_DIR}:/home/ballerina/smpp" "$IMAGE" \
    bash /home/ballerina/smpp/compiler-plugin/tests/inside-container.sh
rc=$?
if [ $rc -ne 0 ]; then
    echo "FIXTURE TESTS FAILED (rc=$rc)"
fi
exit $rc
