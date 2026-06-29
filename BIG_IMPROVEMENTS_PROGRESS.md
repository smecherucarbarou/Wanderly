# Wanderly — Big Improvements Progress (handoff)

Tracks `audits/2026-06-29/big_improvements.md` (structural items A–G). Each entry = state of one initiative.
`improvements.md` (all QW small wins) is already complete — see [AUDIT_PROGRESS.md](AUDIT_PROGRESS.md).

Suggested sequence (from the doc): **D** → **F** → **A** → **E** → **C** → **G** → **B**.

| Item | Title | Effort | Status |
|------|-------|--------|--------|
| A | Split ProfileRepository god class | M | Not started |
| B | Consolidate dual UI (Fragments→Compose) | L | Not started |
| C | Unify authenticated-proxy HTTP client | S | Not started |
| D | Test-coverage program | M | **In progress** (decode + invariant tests landed; see below) |
| E | Realtime/FGS + hive-fetch strategy | S | Partly done (FGS removed in H-5/H-6; hive-fetch dedup + app-scope remain) |
| F | Lean PublicProfile DTO + drop admin_role | S | **Done** (decode-boundary; UI-type propagation = optional follow-up) |
| G | Baseline profile: wire or delete | S | Not started |

---

## D · Test-coverage program — IN PROGRESS

Incremental; each commit is a complete checkpoint.

**Done:**
- [x] Mission-completion decode contract in `RpcResponseDecodingTest`: `MissionLogRpcResponse` full payload, missing-optional→null, error payload, unknown-key tolerance. (Made `MissionLogRpcResponse` `internal`.)
- [x] F invariant test: `PublicProfile` decode drops `last_lat/last_lng/admin_role` even when present in the payload; `toProfile()` yields nulls.
- [x] (already in improvements.md) QW-15 StreakMutation/AdminStats array-vs-object decode; QW-6 PROFILE_VISIBLE_COLUMNS; QW-37 GeoMath edges; QW-38 DateUtils UTC.

**Remaining:**
- [ ] Error-mapping tests for `mapMissionLogError` — currently an *instance* method reading `_currentProfile`; extract a pure helper (snapshot passed in) so each error code is unit-testable. Small production refactor.
- [ ] Inject `HiveChallengeRepository` into `WanderlyRepository` (constructor param w/ default, replacing `HiveChallengeRepository()` at `WanderlyRepository.kt:~20`) to make hive paths integration-testable.
- [ ] Hive fire-and-forget behavioral tests: a throwing/inactive/`already_rewarded` contribution must not throw, delay, or alter the `logMissionCompletion`/`discoverGem` result.
- [ ] Tighten the Kover gate: class filters (generated Hilt/serialization, Compose previews, widgets), a branch-coverage rule, raise line `minBound` ~40-50%; run `lintRelease` in CI.

---

## F · Lean PublicProfile DTO + remove admin_role — DONE (commit 548c1ac)

**Goal (audit M-1, H-7):** other users' rows can only ever populate a lean `PublicProfile` (no `last_lat/last_lng/admin_role`), so the 4 social RPCs can't deserialize another user's sensitive columns; remove the structurally-always-false `admin_role` from the self `Profile` model.

**Scope decisions:**
- Keep `last_lat/last_lng` on the self `Profile` — written by `updateProfileLocation`, and the map reads the separate `FriendLocation` DTO, not `Profile`. PublicProfile already closes the cross-user leak, so removing them from self-Profile is unnecessary churn (doc marks it "ideally"/optional). Deferred.
- Remove `admin_role` from `Profile` (no production reads remain after QW-5).

**Sub-steps:**
- [x] F-admin: removed `admin_role` from `Profile`; fixed `ProfileRepositoryPayloadTest`. (No production reads remained after QW-5; `decodeRpc`/select use ignoreUnknownKeys so server still tolerated.)
- [x] F-core/1: added `PublicProfile` DTO + `PublicProfile.toProfile()` mapper in `Profile.kt`.
- [x] F-core/2: switched all 5 `SocialRepository` decode sites to `decodeList<PublicProfile>()` (leaderboard, accepted/pending friends, both find-by-code).
- [x] F-core/3: **chosen approach = decode-boundary mapping** — repo maps `PublicProfile.toProfile()` and keeps returning `Profile`, so ViewModel/UI/tests are unchanged. The privacy invariant holds (server rows only ever deserialize into `PublicProfile`; the derived `Profile` has last_lat/lng=null, no admin_role). This avoided a wide ripple through `SocialViewModel`/`SocialScreen`/tests.
- [x] F-verify: `:app:testDebugUnitTest` + `:app:compileDebugAndroidTestKotlin` BUILD SUCCESSFUL; lint pending; commit + push + CI.

**Optional follow-up (not required for the invariant):** propagate the `PublicProfile` *type* out to `SocialViewModel`/`SocialScreen` so the UI literally cannot reference sensitive fields. Pure type-safety hardening, no behavior change. Also `last_lat/last_lng` remain on self `Profile` (load-bearing for `updateProfileLocation`).

**Files changed:** app/src/main/java/com/novahorizon/wanderly/data/Profile.kt (removed admin_role; added PublicProfile + toProfile), app/src/main/java/com/novahorizon/wanderly/data/SocialRepository.kt (5 decode swaps), app/src/test/java/com/novahorizon/wanderly/data/ProfileRepositoryPayloadTest.kt (dropped admin_role).

**Current state:** F complete and verified locally; committing.
