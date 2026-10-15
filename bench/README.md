# bench — measurement dashboard

Two measurement families:

1. **Render/parse (Phase B):** frame p50/p99, jank count, parse throughput
   (shown as an in-app overlay — measure, don't guess).
2. **Guest runtime (Phase C):** identical workloads compared against Termux
   (proot reference) on the same device — `apt install`, `go build`,
   file-heavy work.

Results are recorded in [`../PLAN.md`](../PLAN.md). No performance claim is
made without measurements (AGENTS.md rule 2).
