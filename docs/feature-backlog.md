# Feature backlog

Kept in the repository so a later session does not lose the plan. Each entry names
what is missing, what it needs, and its state.

## Status legend

- **done** — implemented, tested, in the branch.
- **partial** — a tool or framework exists, the UI does not.
- **todo** — not started.

## IDE capabilities (the batch the user wants built together)

| Feature | What it must do | Needs | State |
| --- | --- | --- | --- |
| LSP | Autocomplete, go to definition, find references | proot distro + language servers + an LSP client bridge in the editor | todo |
| Terminal sessions | Several persistent shells, switch between them, split panes | real PTY, foreground service, capped output ring buffer | todo |
| Watch mode | Save a file, tests run, results appear live | file watcher, debounce, task runner, battery guard | todo |
| proot Ubuntu | apt, node, python inside a distro | working arm64 proot on Android 16, on-demand rootfs download (~400 MB–1 GB) | todo |
| DAP debugger | Breakpoints, step, variables, call stack | debug adapters (debugpy now, Kotlin later), attach rights, debug UI | todo |
| Test explorer | Tree of tests, run one, read its output | JUnit XML and pytest parsers, run configurations | todo |
| HTTP client | Send requests, read responses, keep variables | a collections store and one screen | todo |
| Git integration | Diff, commit, branch, pull request | mostly exists; the missing pieces are listed below | partial |

Every one of those sits behind the Off / Automatic / On switch added in
`data/FeatureSwitch.kt`; "Automatic" is a policy (battery, RAM, installed payloads),
never a synonym for on.

## Git batch (18, 28–37, 58)

| Item | State |
| --- | --- |
| 18 Show what the agent is doing while a tool runs | todo — needs chat UI state |
| 28 Commit only chosen paths | done (`git_commit` `paths`) |
| 29 Amend the last commit | done (`git_commit` `amend`) |
| 30 Tag and release notes | done (`git_tag`) |
| 31 Read and answer PR comments | todo — needs a token bridge into tool context |
| 32 Merge conflict resolver | partial (`git_conflicts` lists, continues, aborts; no 3-way UI) |
| 33 Warn before publishing a secret or a 5 MB+ file | done (`GitCommitGuard`) |
| 34 Build and install in one tap | todo — needs a screen plus the install flow |
| 35 Build log filtered to the first real error | todo |
| 36 Watch CI, pull the artifact, sign, install | todo |
| 37 Compare two APKs | todo — needs a picker and a manifest diff |
| 58 Save a chat workflow as a skill | done (`save_skill`) |

## Build and signing notes

- CI builds debug **and** a minified release APK; the release build carries
  `applicationIdSuffix = ".release"` so it never collides with the Play app.
- The stable signing key lives outside Git: `drafts/AndroidHarness/.harness/signing/`
  (`debug.keystore`, `ks.pass`, `sign-and-install.sh`). Re-sign every downloaded APK
  with that script so the next install upgrades in place.
- One build is roughly 35–45 minutes; the artifact download adds 10–20 minutes on a
  slow connection. Batch changes and build once.
