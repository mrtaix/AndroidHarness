# SDD ledger — plan: docs/superpowers/plans/2026-11-26-mission-agent.md

Spec: `.agent/mission-agent-design.md`.

Execution: Native (user chose 2026-11-26). Branch `build/plugin-search-compact-fix`, already a feature branch with uncommitted user files; no new worktree. Feature flag `missionEnabled` must default false.

Ruling: SDD helper scripts missing in installed plugin — ledger written by hand at the conventional path. Cost: bookkeeping only.

Ruling: no worktree — creating one would need stashing the user's uncommitted docs/feature-backlog and runtime files. Cost: shared working tree; mitigated by default-off flag and one commit per task.

Pre-flight: Task 2 consumes Task 1's `MissionState`/`MissionRetryPolicy`; Task 3 consumes Task 1 + Task 2; Task 4 consumes Task 3's manager; Task 5 consumes Task 4's manager API. No conflicting interfaces found; proceed in order.
| 1 model + retry | `MissionModel.kt`, `MissionRetryPolicy.kt`, tests | RED unresolved refs; GREEN OK (13 tests); mutation network-loss retry caught | PASS | pending |
| 2 store | `MissionStore.kt`, `MissionStoreTest.kt` | RED unresolved ref; GREEN OK (18 tests total) | PASS | pending |
| 3 manager | `MissionManager.kt`, `MissionManagerTest.kt`, `MissionModel.kt` + `MissionStore.kt` (nextRetryAtMs) | RED unresolved refs; GREEN OK (25 tests) | PASS | pending |
| 4 bridge + issue | `MissionIssue.kt`, `MissionGoalBridge.kt`, `MissionBridgeTest.kt`, `HarnessApp.kt`, `ChatViewModel.kt` | RED unresolved refs; GREEN OK (31 tests) | PASS (CI pending for wiring) | pending |
| 5 UI + boot | `MissionScreen.kt`, `SettingsNavigation.kt`, `SettingsHome.kt`, `SettingsScreen.kt`, `HarnessApp.kt`, strings | compile checked by CI; device test NOT RUN locally | NOT RUN | pending |
| CI run 45708639589 | `e7accb7c` | FAIL wiring: Flow.first qualified, PR publisher lambda, WorkspaceFs vs WorkspaceManager | FAIL -> fixed | - |
| CI run 45711907272 | `3986c809` | FAIL 2 tests: LearnCatalog tutorial link + settings home list for MISSIONS | FAIL -> fixed | - |
