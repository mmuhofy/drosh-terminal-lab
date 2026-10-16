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

The app **hosts** the endpoint (`bench/BenchServer.kt`); nothing is uploaded from
the device and the app never dials out. Whoever reviews the numbers pulls from
it:

```sh
./bench/fetch-results.sh scroll-cat          # writes bench-results/<stamp>-scroll-cat.txt
./bench/fetch-results.sh scroll-cat --push   # ... and pushes it on a bench-results branch
# or directly:
curl http://127.0.0.1:8080/info; curl http://127.0.0.1:8080/bench.log
```

| Path | What it answers |
|---|---|
| `GET /` | a short index naming the other paths |
| `GET /info` | device, Android, kernel, core version, grid, cell metrics, density, GL, font |
| `GET /bench.log` | one header line per app start, then one line per frozen capture |

Each capture carries the **scenario label**: `scenarios.sh` writes the name into
`files/bench-label` (it runs as the app's own uid), the app reads that file on the
next freeze, stamps the line with `label=<name>` and deletes it. The label is
written by the script — the app never reads the screen — so a pulled line says
which workload it belongs to without anyone having to remember the order.

The server binds **every interface** on a fixed port (`8080`, logged at startup
along with the addresses to try), so whichever address the bridge can reach
answers — the device's Wi-Fi address or its loopback. That makes the endpoint
reachable from the network the device is on: accepted for a lab build (it serves
bench numbers and device facts, never terminal content, and the API is three
read-only GETs), and not what a shipped build would do — that one binds loopback
and leaves the bridging to `adb forward`.

**The app has to be running** for the endpoint to exist; that is the trade the
device tester accepted in exchange for never having to copy numbers by hand.

If the bridge is not in place, the machine with `adb` is enough:

```sh
adb forward tcp:8080 tcp:8080     # then the same ./bench/fetch-results.sh
adb logcat -d -s DroshLabBench    # or just the logcat lines
```

Rejected: an in-app upload to a GitHub API (a token inside a debug APK —
AGENTS.md §11) or to a public paste service (a terminal phoning home).
