# bench — measurement dashboard

Two measurement families:

1. **Render/parse (Phase B):** frame work and jank, split into the phases a frame
   is made of (`pump` = snapshot, `build` = vertices + upload, `draw` = GL
   submit). Measured in the app, frozen on demand, sent as one logcat line.
2. **Guest runtime (Phase C):** identical workloads compared against Termux
   (proot reference) on the same device — `apt install`, `go build`, file-heavy
   work.

Results are recorded in [`../TODO.md`](../TODO.md) (device runs) and
[`../PLAN.md`](../PLAN.md). No performance claim is made without measurements
(AGENTS.md rule 2).

## How a Phase B measurement is taken

The first device run showed why the *process* has to be defined as carefully as
the code: the status line was a rolling 240-frame window updated twice a second,
so the number had changed by the time it was written down (and the workload —
`yes | head -30000` — was not recorded). What follows from that:

1. **The counters only grow.** Frame count, work buckets (`<2 / <4 / <8 / <16 /
   <33 / ≥33 ms`), phase averages, jank count and the single worst frame. The
   line never changes what it already reported.
2. **Tapping the status line freezes it** (amber), writes one
   `DroshLabBench` logcat line, and appends the same line to
   `files/bench.log`. A second tap resets and resumes. Frozen numbers are an
   artefact; live ones are a glance.
3. **`>8ms` is the acceptance numerator**: the share of drawn frames whose work
   exceeded 8 ms is what "frame p99 < 8 ms" means in practice, and it can be read
   off a frozen line.
4. **The workload is a scenario, not a stress test:**

   ```sh
   adb push bench/scenarios.sh /sdcard/
   # in the app's terminal:
   sh /sdcard/scenarios.sh
   ```

   `steady` (typing and `ls -la`), `scroll-cat` (a 2 MB burst), `scroll-ls`
   (`ls -R /system`) and `runaway` (`yes | head -30000`, the case that first
   produced 114 ms). Tap the status line after each `BENCH:<name> done`.

## Getting the numbers to the reviewer

Three channels, in order of how much manual work they need:

1. **Loopback collector (aimed for, device half is built).** The tester runs a
   collector *on the device* and bridges it out, so a frozen line is posted to
   `http://127.0.0.1:8080/bench` the moment the status line is tapped
   (`bench/BenchUploader.kt`: off the main thread, loopback constants only, never
   on a timer). Verified from the reviewing side: every path and POST to that
   address answers `200 WORKING`.
   **Open:** the collector does not serve back what it stores — a marker POSTed
   from here is not visible at `/`, `/data`, `/last`, `/bench`, `/results`,
   `/log` or `/lines` — so the read-back endpoint has to be defined before this
   channel replaces the ones below.
2. **By hand:** `adb logcat -d -s DroshLabBench` and paste the lines (the same
   line is also appended to `files/bench.log`).
3. **Semi-automatically:**

   ```sh
   ./bench/push-results.sh scroll-cat            # writes bench-results/<stamp>-scroll-cat.txt
   ./bench/push-results.sh scroll-cat --push     # ... and pushes it on a bench-results branch
   ```

   The script uses *your* `adb` and *your* git credentials on your machine. No
   credential is ever built into the APK, and the app posts only to loopback.

The device itself cannot push to a build box: its `127.0.0.1` is the device, and
nothing routes from a phone to the environment where the work happens — which is
why the collector has to run on the device and be bridged, rather than the app
uploading somewhere.

Rejected: an in-app upload to a GitHub API (a token inside a debug APK —
AGENTS.md §11) or to a public paste service (a terminal uploading to a third
party). The `INTERNET` permission the app now declares exists for the loopback
socket alone (Android requires it even for 127.0.0.1); it is documented in the
manifest and this is the only code that uses it.
