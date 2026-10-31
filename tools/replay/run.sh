#!/usr/bin/env bash
set -euo pipefail
TASK_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TASK_GAME="${1:?Pass an unmodified co-dugapk checkout}"
TASK_BUILD="$(mktemp -d)"
trap 'rm -rf "$TASK_BUILD"' EXIT
javac -d "$TASK_BUILD" "$TASK_ROOT"/tools/replay/stubs/android/graphics/*.java \
 "$TASK_ROOT"/app/src/main/java/com/tanuki276/cosc/GameAnalyzer.java \
 "$TASK_ROOT"/app/src/main/java/com/tanuki276/cosc/GameFeedback.java \
 "$TASK_ROOT"/tools/replay/ReplayBridge.java
node "$TASK_ROOT/tools/replay/replay.cjs" "$TASK_GAME" "$TASK_BUILD"
