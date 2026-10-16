## What remains

**One thing:** the app counts frame work and, when you tap the status line, freezes
it into one immutable line — `DroshLabBench` in logcat, the same line appended to
`files/bench.log`. The line is:

```text
frames=… rows_per_frame=… slow8=… (…) max=… pump=… build=… upload=… draw=… jank=…
```

- `pump` is the snapshot (Rust: PTY + parser + grid), `build` is Kotlin vertex
  building, `upload` is the GL buffer update, `draw` is the submit.
- `rows/frame` matters because `build` and `upload` scale with how many rows were
  rebuilt: without it two runs cannot be compared (they were not, twice).
- `>8ms` is the acceptance numerator; jank means work over two refreshes, not the
  gap between frames (that counted idle time and reported 15/16 while nothing was
  happening).

If you want a number recorded, tap the line and paste it. That is the whole
process.

## What was cancelled, and why

A fuller harness grew here today and was cut (user decision, 2026-10-16): a
scenario script, a label file so captures stamp themselves, a result collector for
a machine with `adb`, and an HTTP endpoint the app hosts so the reviewer can pull
`/info` and `/bench.log` without anyone copying text. All of it *worked* — a device
reading was pulled end to end without a single paste — but it cost the person
holding the phone: open the app (or the endpoint is gone), keep a bridge up, run
the script, tap once per scenario, in order, and keep the order in mind. For two
numbers that were still not comparable, because the metric that matters
(`rows/frame`) was not even in the line.

The lesson is recorded rather than the tooling: **a measurement harness has to be
cheaper than the measurement it produces, and this one was not.**

## Caveat on the git history

The endpoint, the scripts and the pull path are in the repository's history
(commits for issues #18-#24). They are removed from the tree; the reasoning above
is the record.

## Phase C

Guest runtime timings (Termux/proot comparison) are still to come and belong
here when they do.
