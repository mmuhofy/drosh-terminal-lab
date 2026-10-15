# AGENTS.md — drosh-terminal-lab

This repo is the lab for proving Drosh terminal's next core (Rust core +
chroot-ng + GL renderer). Every agent and developer working here follows these
rules. They are ordered: 1–5 always apply; 6–12 are their concrete application
to this project.

---

## 1. Never flatter. No sycophancy.

- Do not approve every user idea with "great", "perfect", "amazing".
- If an idea is genuinely good: approve with a **short, technical reason**
  ("this is right, because it solves X without breaking Y").
- If an idea is weak, wrong, or has a better alternative: **decline it and
  offer the reasoned alternative.** Silely complying, saying "sure" and moving
  on is forbidden.
- When the user asks your honest opinion, give it. Do not soften your position
  because you think they came to hear what they already believe.

## 2. Invent nothing. Nothing is written before it is verified.

- Version numbers, file names, API signatures, behavior, benchmark numbers,
  source links — **none** of these enters any document, code, or answer
  without verification.
- When unsure: either research (rule 4) or write it explicitly tagged as
  "unverified". A guess is never presented as a fact.
- Nothing is reported as "done" before it has been run/compiled/observed
  (rule 5, rule 10).

## 3. Get required information from the user.

- When a decision branches and you cannot decide: **ask instead of assuming**.
  The question must be specific and short, with one sentence on what determines
  the decision.
- Record the user's answer in the relevant document (PLAN/TODO) — the same
  question is never asked twice.
- Do not ask unnecessary questions: make concrete sub-decisions yourself and
  report them. Stop and ask at critical forks only.

## 4. Find the best method; do not improvise. Research effectively.

Before proposing a solution, three questions must have been asked:

1. What is the industry standard / known best practice? (Have the sources —
   Alacritty, kitty, WezTerm, Ghostty, Termux, Termi, chroot-ng — actually
   been read, not remembered?)
2. What is different on mobile/Android? (SELinux, surface lifecycle, insets,
   battery, OEM differences)
3. Could an existing decision in this repo (PLAN.md D1–D13) already cover it?

Research: claims about versions, licenses, compilability are verified online,
and the source is recorded in PLAN.md or TODO.md. If research invalidates an
existing decision, report it to the user; never change a decision silently.

## 5. Verify by running. Report only what you have seen.

- For every piece of code/command: CI runs (rule 10) — `cargo check`,
  `cargo clippy`, `cargo test`, `gradle assembleDebug` — whatever applies.
- "It should be fine" without output is not a report.
- Gates are never skipped: Phase A Gate 1 (Android compile), Phase B Gate 2
  (swash glyphs), Phase C Gate 3 (chroot-ng probe).
- On error: minimal repro first, then report options to the user.

## 6. Keep docs up to date.

- When a decision changes: update PLAN.md with the rationale; archive the old
  decision under "Decision history" with a "changed:" note; never delete
  silently.
- When task status changes: TODO.md is updated in the same session.
- Work not done stays in TODO.md as "not done + why".
- Verification results (gates, benchmarks) are recorded in PLAN.md.

## 7. Ask questions, then report. No silent progress.

- Stop and ask at critical decision points (e.g. scope, licenses, target
  devices, performance targets, backward compatibility).
- After the answer: apply it and **report what you did in one short
  paragraph**. The user must not need to micro-manage.

## 8. Do the unspoken details the way the user would want them.

The quality bar: the level where the user says "you even did that, nice".
Concrete examples:

- `.gitignore`, CI skeleton, README structure
- Edge-case tests (channel boundaries, empty scrollback, 1×1 resize, color
  edge cases, CJK + emoji lines)
- Clear error messages — what happened and what to do, not a panic dump
- Version pins with a comment on why they are pinned
- Release profile (LTO/strip/panic=abort) and measured `.so` size
- Meaningful, atomic commits with a checkpoint commit at every phase end

## 9. Project technical rules

- **VT engine:** `alacritty_terminal =0.26.0` pinned. If engine behavior must
  change: fork + patch, and evaluate sending it upstream (Apache-2.0 allows).
- **FFI boundary:** no `unwrap()`/panic across JNI; panic hook installed;
  handles are opaque positive IDs (never raw pointers — Termi's
  "-1905618432" lesson); the Kotlin mirror of error codes is always in sync.
- **PTY:** libc forkpty path (portable-pty does not compile for Android —
  this is a verified fact, do not re-litigate). OEM fallback: slave-stdio path
  when the login-tty spawn fails.
- **chroot-ng:** vendored subtree, commit-pinned; Drosh-specific patches in a
  separate patch branch; generic fixes go upstream.
- **Kotlin renderer:** GL ES 3.0; zero-allocation in the render loop;
  damage-limited redraw; atlas size buckets (1x–3x).
- **Prototype scope:** NO config system, NO SSH (PLAN.md D12). Anyone adding
  them updates the PLAN.md decision-history section first.
- **targetSdk 28** (PLAN.md D9) is preserved; never changed silently.

## 10. Build, CI & verification policy (mandatory)

**No local Rust or Gradle builds. Ever.** The dev box is not a build farm.
All compilation and testing happens in one of:

- **GitHub Actions** — mandatory on every push; runs the gates:
  `cargo check` (host + aarch64-linux-android), `cargo clippy -- -D warnings`,
  `cargo fmt --check`, `cargo test`, `./gradlew assembleDebug`
- **GitHub Codespaces** (connect via SSH) — when interactive iteration is
  needed
- **Google Colab** — alternative remote environment

Rules:

1. **After every push, wait for the CI result** before declaring anything:

   ```sh
   gh run watch                  # follow the current run
   gh run view <run-id> --log-failed   # inspect failures
   ```

   Never report success before CI is green. If CI is red: diagnose from the
   log, fix, push again — do not report "done" on a red build.
2. **Device testing uses CI-built APK artifacts**: download the APK from the
   Actions run, install on the device, run the acceptance checklist, and
   record the result (device model, Android version, kernel) in TODO.md.
3. Quick local sanity commands that are **not builds or tests** (e.g. reading
   files, `git`, `gh`) are fine. Anything that compiles runs remotely.
4. Gate results (pass/fail + CI run URL) are recorded in TODO.md.

## 11. Commits & changelog discipline

**Commits are in English, conventional, and professional.**

- Format: `<type>(<scope>): <imperative summary>` — types: `feat`, `fix`,
  `docs`, `refactor`, `perf`, `test`, `build`, `ci`, `chore`. Scopes: `core`,
  `renderer`, `input`, `guest`, `ci`, `docs`, ...
- Body (when the why is not obvious): what changed and **why**, not how.
- One logical change per commit; no "wip" / "asdf" / "fix stuff" messages.
- Work on a branch (`feat/phase-a-engine`, `fix/snapshot-damage`, …);
  `main` stays green; merge (squash when noisy) only with CI passing.
  No force-push to `main`.
- **CHANGELOG.md is maintained** (Keep a Changelog format): every structural
  or user-visible change gets an entry under `[Unreleased]` — Added / Changed
  / Fixed / Removed sections with a one-line description. Phase milestones get
  a version tag (`0.1.0` etc.) and a release entry.
- **Repo language is English**: docs, commit messages, code comments, log
  messages. (The Drosh main repo keeps its own conventions; this lab is
  English-only so upstream contributions and external reviewers can follow.)
- Never commit build artifacts, `.so` files, keystores, or secrets. Build
  products come from CI, not from git.

## 12. Prohibitions

- Softening the user's ideas to be agreeable; performing agreement
- Reporting anything untested/unbuilt as working
- Leaving code that contradicts the docs (if one is wrong, fix both)
- Leaving "for now, fix later" mock/half-work — instead record its status in
  TODO.md
- Large scope changes (engine swap, renderer decision, runtime swap) without
  consulting the user
- Local Rust/Gradle builds (rule 10)
- Non-English commit messages; undocumented changes missing from the
  CHANGELOG (rule 11)

## 13. Working protocol (summary)

1. Read the task and the relevant PLAN/TODO items.
2. If a critical decision is unclear: ask. Otherwise: find the best method
   (research).
3. Implement; push; **watch CI**; iterate on red.
4. Update docs (PLAN/TODO); record gate results.
5. Update CHANGELOG.md; commit professionally (English, conventional).
6. Report briefly: what was done, what was verified, what is next.
