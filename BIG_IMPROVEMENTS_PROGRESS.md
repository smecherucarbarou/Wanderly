# Wanderly — Big Improvements Progress (handoff)

Tracks `audits/2026-06-29/big_improvements.md` (structural items A–G). Each entry = state of one initiative.
`improvements.md` (all QW small wins) is already complete — see [AUDIT_PROGRESS.md](AUDIT_PROGRESS.md).

Suggested sequence (from the doc): **D** → **F** → **A** → **E** → **C** → **G** → **B**.

| Item | Title | Effort | Status |
|------|-------|--------|--------|
| A | Split ProfileRepository god class | M | Not started |
| B | Consolidate dual UI (Fragments→Compose) | L | Not started |
| C | Unify authenticated-proxy HTTP client | S | Not started |
| D | Test-coverage program | M | Not started |
| E | Realtime/FGS + hive-fetch strategy | S | Partly done (FGS removed in H-5/H-6; hive-fetch dedup + app-scope remain) |
| F | Lean PublicProfile DTO + drop admin_role | S | **Done** (decode-boundary; UI-type propagation = optional follow-up) |
| G | Baseline profile: wire or delete | S | Not started |

---

## F · Lean PublicProfile DTO + remove admin_role — IN PROGRESS

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
