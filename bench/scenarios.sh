#!/system/bin/sh
# Phase B benchmark scenarios (PLAN.md §7, TODO.md "Acceptance").
#
# Run this *inside the app's terminal*, not on a PC: each scenario is followed by
# a marker, and the numbers are captured by tapping the status line (which freezes
# the counters and writes one `DroshLabBench` logcat line).
#
#   adb push bench/scenarios.sh /sdcard/
#   # in the app's terminal:
#   sh /sdcard/scenarios.sh
#   # after each "BENCH:<name> done", tap the status line once, then once again
#   # (the first tap freezes and logs, the second resets and resumes)
#
# Then collect the artefacts with bench/push-results.sh, or by hand:
#   adb logcat -d -s DroshLabBench
#
# Why `sh /sdcard/...` and not executing it: /sdcard is mounted noexec, but the
# shell can read the file and run its contents.

FILES=/data/data/dev.drosh.lab/files
BIG="$FILES/bench-big"

scenario() {
    name="$1"
    shift
    echo ""
    echo "BENCH:$name starting"
    "$@"
    echo "BENCH:$name done — tap the status line (freeze), then tap again (reset)"
}

echo "BENCH:steady — ordinary use: type a few commands, run 'ls -la', let it idle."
echo "When the screen is quiet: tap the status line once, then again to reset."

# A 2 MB burst: one screen of scrolling after another.
mkdir -p "$FILES" 2>/dev/null
if [ ! -f "$BIG" ]; then
    head -c 2000000 /dev/zero | tr '\0' 'x' > "$BIG"
    echo "created $BIG ($(wc -c < "$BIG") bytes)"
fi

scenario scroll-cat cat "$BIG"
scenario scroll-ls ls -R /system
# The case that produced 114 ms on the first device run.
scenario runaway sh -c 'yes | head -30000'

echo ""
echo "BENCH:all done"
