#!/usr/bin/env bash
# Collect Phase B bench artefacts from the device.
#
# Runs on the machine that has `adb` (the one that flashed/installed the APK),
# *not* on the device: the app cannot reach any machine of ours — its localhost is
# the device itself and nothing routes from a phone to a build box — so the data
# leaves the device either through adb or through the user's hands. This uses adb
# and the user's own git credentials; no credential is ever shipped in the APK.
#
#   ./bench/push-results.sh <label>            # capture and write a file
#   ./bench/push-results.sh <label> --push     # ... then commit and push a branch
#
# The app writes one `DroshLabBench` line per frozen capture, and appends the same
# line to `files/bench.log`, which this script pulls as a second source.
set -euo pipefail

LABEL="${1:-unnamed}"
PUSH="${2:-}"
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT_DIR="$REPO_ROOT/bench-results"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUT="$OUT_DIR/$STAMP-$LABEL.txt"

if ! command -v adb >/dev/null 2>&1; then
    echo "adb is not on PATH; run this on the machine that installed the APK" >&2
    exit 1
fi

mkdir -p "$OUT_DIR"
{
    echo "# bench $LABEL, $STAMP"
    echo "# device: $(adb shell getprop ro.product.model 2>/dev/null | tr -d '\r')"
    echo "# android: $(adb shell getprop ro.build.version.release 2>/dev/null | tr -d '\r')"
    echo "# kernel: $(adb shell uname -a 2>/dev/null | tr -d '\r')"
    echo
    echo "## DroshLabBench (logcat)"
    adb logcat -d -s DroshLabBench 2>/dev/null | sed 's/.*DroshLabBench: //' | grep 'frames=' || echo "(no frozen capture — tap the status line in the app first)"
    echo
    echo "## bench.log (app files dir)"
    adb shell run-as dev.drosh.lab cat files/bench.log 2>/dev/null || echo "(not readable; logcat above is the source of truth)"
} > "$OUT"

echo "wrote $OUT"
sed -n '1,40p' "$OUT"

if [ "$PUSH" = "--push" ]; then
    cd "$REPO_ROOT"
    BRANCH="bench-results"
    git fetch origin "$BRANCH" 2>/dev/null || true
    git checkout -B "$BRANCH" "origin/$BRANCH" 2>/dev/null || git checkout -B "$BRANCH"
    git add "$OUT"
    git -c user.name="bench" -c user.email="bench@localhost" commit -m "bench: $LABEL ($STAMP)"
    git push -u origin "$BRANCH"
    echo "pushed to branch $BRANCH; the numbers can be read from there without pasting anything"
fi
