# SDD ledger — plan: docs/superpowers/plans/2026-11-24-chief-orchestrator.md

Spec: `.agent/chief-orchestrator-design.md`.

Execution: Native (user chose 2026-11-24). Branch `build/plugin-search-compact-fix` (not main).

Ruling: SDD helper scripts are absent from the installed superpowers plugin — ledger is written by hand at the conventional path. Cost: no automated brief/task-done output; bookkeeping only.

Ruling: no fresh git worktree. Current branch is already a feature branch and holds uncommitted user files (`.harness/`, `.agent/`, docs backlog, progress/feature files); creating a worktree would require stashing those. Cost: shared working tree, mitigated by feature flag default-off and one commit per task.

Pre-flight: Task 2 stores the `ChiefTree` produced by Task 1; Task 3 consumes both store and tree; Task 4 wires Task 3 into the registry. Same interfaces, no conflicts found beyond the stated order.

| Task | Files | Test command | Result | Commit |
|---|---|---|---|---|
| 1 state machine | `ChiefOrchestration.kt`, `ChiefOrchestrationTest.kt` | JVM runner, `org.junit.runner.JUnitCore com.androidharness.app.agent.ChiefOrchestrationTest` RED: unresolved `ChiefTree`; GREEN: OK (9 tests); mutation `maxRetries 2→3` caught by `third rejection fails the node`, restored | PASS | pending |
| 2 JSON store | `ChiefTreeStore.kt`, `ChiefTreeStoreTest.kt`, `ChiefOrchestration.kt` (snapshot/INTERRUPTED) | JVM JUnitCore both suites: RED unresolved store/snapshot/INTERRUPTED; GREEN OK (12 tests); mutation `active -> false` caught by `active nodes become interrupted after reload`, restored | PASS | pending |

Ruling: plan file lives under `docs/superpowers/`, which `.gitignore` ignores; spec text was committed with the design doc under `.agent/` instead.
| 3 chief_* tools | `ChiefToolLogic.kt`, `ChiefTools.kt`, `ChiefOrchestration.kt` (delegateId/descendantIds), `ChiefTreeStore.kt` (delegateId), `ChiefToolLogicTest.kt` | RED unresolved ChiefDelegate/SpawnRequest/ChiefToolLogic; GREEN OK (17 tests); mutation `setDelegate -> no-op` caught; tool wrappers smoke-compiled against stub Tool/Schema | PASS | pending |

Ruling: `Tool`/`ToolContext` pull in Android and workspace types that the local JVM runner cannot compile. Behavior lives in pure `ChiefToolLogic.kt`; `ChiefTools.kt` is a thin wrapper. Cost: two files instead of one; CI still checks the real interface.
| 4 wiring + flag | `ChiefToolAvailability.kt`/Test, `SettingsRepository.kt`, `AgentSettingsCatalog.kt`, `ui_extra_strings.xml`, `AgentEngine.kt`, `HarnessApp.kt` | RED unresolved `ChiefToolAvailability`; GREEN OK (21 tests). Device test NOT RUN locally; CI will build | PASS (unit), device NOT RUN | pending |

Ruling: new setting title is `translatable="false"` so the 21-locale parity test does not need 21 translations. This is an experimental hidden flag, not user-facing copy.
Known gap for increment 2: finished subagents are not yet auto-attached to `ChiefNode.report`; `chief_wait` returns the live SubagentRuns report and the chief then reviews/merges. No UI tree yet.
| Review fixes | `ChiefToolAvailability.kt`, `ChiefOrchestration.kt`, `ChiefTreeStore.kt`, `AgentEngine.kt`, 3 tests | RED: unresolved visibleToSubagent + interrupted-not-persisted; GREEN OK (24 tests) | PASS | pending |

Final review (fresh subagent) findings and rulings:
- FIXED: `chief_*` tools could reach subagents. Added `ChiefToolAvailability.visibleToSubagent` and applied it in `AgentEngine.subagentTools`.
- FIXED: INTERRUPTED statuses were not written back. `ChiefTreeStore.load()` now saves the recovered snapshot.
- FIXED: `ChiefTree` map/counter were unsynchronized. All public entry points use `synchronized(this)`; parallel-assign test added.
- ACCEPTED: `ChiefNode.permissions` is stored but not enforced — all chief children are background/read-only, which is stricter than the design. Per-role grants deferred to increment 2.
- ACCEPTED: `AgentEngine` fallback store path is relative `.`; only device tests use the default and the flag is off there. HarnessApp supplies `noBackupFilesDir`.
- ACCEPTED: rename-without-fallback matches the existing `SubagentRuns.save()` pattern.
- REJECTED: missing `Charsets` import — Kotlin default-imports `kotlin.text.*`.
- REJECTED: empty-scope overlap is intentional: a child without an explicit scope cannot be assigned beside a scoped active node.
| CI run 45426163974 | old commit `420a7a33` | FAIL: `AgentEngine.kt:460 Unresolved reference agentMode` (run-scope variable is `mode`) | FAIL -> fixed | - |
| CI run 45431689787 | `57bb49b4` | unit tests PASS, debug APK PASS, release APK PASS, final conclusion success | PASS | final |

## Increment 2 — UI cây Chief

| Task | Files | Test | Result |
|---|---|---|---|
| Expose snapshot | `ChiefToolLogic.kt`, `ChiefTools.kt`, `AgentEngine.kt`, `AgentEvent`, `RunManager.kt`, `ChatViewModel.kt`, `ChiefToolLogicTest.kt` | RED no `onChange`; GREEN OK (25 pure tests) | PASS |
| UI card | `ChiefTreeCard.kt`, `ui_extra_strings.xml`, `ChatScreen.kt` | local JVM N/A; CI device/unit build pending | NOT RUN locally |

Ruling: action buttons prefill the composer with `chief_review`/`chief_stop` commands instead of injecting tool calls directly. Safer and matches existing composer patterns; user reviews before sending.
Ruling: 5 new UI strings are `translatable="false"` to keep the 21-locale parity test green.
| CI run 45444250163 | `2861e3af` | FAIL: `AgentEngine.kt:479 Suspension functions can only be called within coroutine body` (onChange called `emit`) | FAIL -> fixed | - |

Ruling: `ChiefToolLogic.onChange` is synchronous; AgentEngine bridges it to the flow with an unlimited `Channel` and a coroutine that calls `emit`. Cost: one extra channel per run.
| CI run 45446192982 | `7fe43909` | FAIL: deprecated `kotlinx.coroutines.launch` called without receiver scope | FAIL -> fixed | - |

Ruling: inside `channelFlow` use the imported `launch` extension (ProducerScope is a CoroutineScope), not the top-level fully-qualified function.
| CI run 45447719721 | `0aa9ac1f` | unit tests PASS, debug PASS, release PASS, success | PASS | final |

## Increment 3 — auto report

| Task | Files | Test | Result |
|---|---|---|---|
| Attach finished report | `ChiefOrchestration.kt`, `ChiefToolLogic.kt`, `ChiefTools.kt`, `AgentEngine.kt`, `ChiefToolLogicTest.kt` | RED unresolved `attachFinished`; GREEN OK (26 pure tests), tool wrappers smoke-compiled | PASS |
| Show report | `ChiefTreeCard.kt` | CI build pending | NOT RUN locally |

Ruling: the engine watches each spawned background run with `waitFor(30s)` and calls `attachFinished`, which moves the node to REVIEW with the report. The chief still reviews before merge.
| CI run 45457502033 | `6a22cb06` | unit tests PASS, debug PASS, release PASS, success | PASS | final |

## Increment 4 — role gating

| Task | Files | Test | Result |
|---|---|---|---|
| Role enum + assign rules | `ChiefOrchestration.kt`, `ChiefTreeStore.kt`, `ChiefToolLogic.kt`, `ChiefTools.kt`, `ChiefTreeCard.kt`, 2 tests | RED unresolved `ChiefRole`/`role`; GREEN OK (29 pure tests), wrappers smoke-compiled | PASS |

Ruling: WORKER cannot delegate at all; only CHIEF may create a LEAD. Tool schema exposes `role` with that contract. This closes the reviewer’s "permissions stored but unused" finding at the delegation layer; commit/push/deploy remain unavailable to every child because chief delegates run background/read-only.
| CI run 45468379316 | `ac2c1fc7` | FAIL unrelated flake: `GroqWhisperClientTest` TimeoutException (2562 tests, 1 failed) | FAIL (flake) | - |
| CI run 45470503836 | `ac2c1fc7` | unit tests PASS, debug PASS, release PASS, success | PASS | final |

## Increment 5 — per-node scratch workspace

| Task | Files | Test | Result |
|---|---|---|---|
| Scratch copy helper | `ChiefWorkspace.kt`, `ChiefWorkspaceTest.kt` | RED unresolved helper; GREEN OK (4 tests) | PASS |
| Engine routing | `AgentEngine.kt`, `HarnessApp.kt` | CI build pending | NOT RUN locally |

Ruling: scratch contains only the node's `scope` paths, copied at assign time. SAF workspaces and empty scopes fall back to the shared workspace; no merge-back happens automatically — the chief reviews the report/diff and merges through tools.
| CI run 45479629853 | `02be81e0` | FAIL: `AgentEngine.kt:272` inferred type on constructor property rejected; cascaded `effectiveWorkspace` type errors | FAIL -> fixed | - |
| CI run 45482158604 | `041c4be5` | unit tests PASS, debug PASS, release PASS, success | PASS | final |

## Fix — missing settings button

Root cause: `SettingsPage.SUBAGENT` renders the hand-written `SubagentSettingsSection`; adding a row to `AgentSettingsCatalog` only affected settings search and agent tools, not the page. The chief toggle therefore never appeared.
Fix: `SubagentSettingsSection` now renders a catalog fallback for every SUBAGENT toggle not in `wiredSubagentKeys`; future catalog entries appear without another hand edit.
Ruling: explicit `onChiefHierarchy` row removed to avoid duplicates; `SettingsScreen` writes catalog toggles through `setting.write`.
