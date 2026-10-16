#!/usr/bin/env bash
# Pull bench artefacts from the endpoint the app hosts.
#
# The app listens on the device's loopback (127.0.0.1:8080, logged at startup);
# whoever reviews the numbers reaches it through whatever bridge the device
# tester set up — for example, on a machine with adb:
#
#   adb forward tcp:8080 tcp:8080
#   ./bench/fetch-results.sh scroll-cat
#
# Nothing is uploaded from the device: the app never dials out, it only serves.
#
#   BENCH_URL=http://127.0.0.1:8080 ./bench/fetch-results.sh <label> [--push]
set -euo pipefail

URL="${BENCH_URL:-http://127.0.0.1:8080}"
LABEL="${1:-run}"
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT_DIR="$REPO_ROOT/bench-results"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUT="$OUT_DIR/$STAMP-$LABEL.txt"

mkdir -p "$OUT_DIR"

{
    echo "# bench $LABEL, $STAMP"
    echo "# source: $URL"
    echo
    echo "## info"
    curl -sS -m 10 "$URL/info" || echo "(endpoint unreachable — is the app running and the bridge up?)"
    echo
    echo "## bench.log"
    curl -sS -m 10 "$URL/bench.log" || echo "(endpoint unreachable)"
} > "$OUT"

echo "wrote $OUT"
cat "$OUT"

if [ "${2:-}" = "--push" ]; then
    cd "$REPO_ROOT"
    BRANCH="bench-results"
    git fetch origin "$BRANCH" 2>/dev/null || true
    git checkout -B "$BRANCH" "origin/$BRANCH" 2>/dev/null || git checkout -B "$BRANCH"
    git add "$OUT"
    git -c user.name="bench" -c user.email="bench@localhost" commit -m "bench: $LABEL ($STAMP)"
    git push -u origin "$BRANCH"
    echo "pushed to branch $BRANCH"
fi
