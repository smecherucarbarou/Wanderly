# Wanderly — Big Improvements Progress (handoff)

Tracks `audits/2026-06-29/big_improvements.md` (structural items A–G). Each entry = state of one initiative.
`improvements.md` (all QW small wins) is already complete — see [AUDIT_PROGRESS.md](AUDIT_PROGRESS.md).

Suggested sequence (from the doc): **D** → **F** → **A** → **E** → **C** → **G** → **B**.

| Item | Title | Effort | Status |
|------|-------|--------|--------|
| A | Split ProfileRepository god class | M | **Largely done** (holder + Shop/Streak/Referral carved; ProfileRepository 950→537 lines) |
| B | Consolidate dual UI (Fragments→Compose) | L | **In progress** (inventory done; dead duplicate stack deleted; migration plan written — see [B_MIGRATION_PLAN.md](B_MIGRATION_PLAN.md)) |
| C | Unify authenticated-proxy HTTP client | S | **Done** (shared awaitWithTokenRefresh; GeminiClient/PlacesProxyClient/Gateway routed) |
| D | Test-coverage program | M | **In progress** (decode + invariant tests landed; see below) |
| E | Realtime/FGS + hive-fetch strategy | S | **Done** (FGS removed H-5/H-6; hive-fetch deduped to 1 read/action + TTL cache) |
| F | Lean PublicProfile DTO + drop admin_role | S | **Done** (decode-boundary; UI-type propagation = optional follow-up) |
| G | Baseline profile: wire or delete | S | **Done** (deleted — was dead weight) |

---

## D · Test-coverage program — IN PROGRESS

Incremental; each commit is a complete checkpoint.

**Done:**
- [x] Mission-completion decode contract (`MissionLogRpcResponse`: full payload, missing-optional→null, error payload, unknown-key tolerance; DTO made `internal`).
- [x] F invariant test: `PublicProfile` decode drops `last_lat/last_lng/admin_role`; `toProfile()` yields nulls.
- [x] Extracted `mapMissionLogError(error, snapshot)` to the companion (pure) + error-code tests (already_completed echo + null fallback, known codes, unknown/null → ServerFailure).
- [x] Injected `HiveChallengeRepository` into `WanderlyRepository` (constructor param w/ default) — enabling refactor for the hive behavioral tests.
- [x] (already in improvements.md) QW-15 StreakMutation/AdminStats array decode; QW-6 PROFILE_VISIBLE_COLUMNS; QW-37 GeoMath; QW-38 DateUtils.

**Remaining:**
- [ ] Hive fire-and-forget behavioral tests — BLOCKED: also needs `ProfileRepository` injectable into `WanderlyRepository` (so a fake returns `Completed` without network) to assert a throwing/inactive contribution doesn't alter `logMissionCompletion`/`discoverGem`. Best landed alongside item **A** (which makes ProfileRepository injectable).
- [ ] Tighten the Kover gate — measure current coverage first (raising `minBound` blindly would fail CI). Add class filters (generated Hilt/serialization, Compose previews, widgets) + a branch rule, then raise the line bound; run `lintRelease` in CI.

---

## A · Split ProfileRepository god class — IN PROGRESS

**Done:**
- [x] Step 2 — extracted `ProfileStateHolder` (`data/ProfileStateHolder.kt`): owns the `MutableStateFlow<Profile?>`; exposes `value` get/set, `asStateFlow()`, `update`, `updateAndGet`. All ~16 ProfileRepository read/write sites route through it. Centralizes the H-3 atomic-update guarantee.
- [x] Step 3a — `ProfileStateHolder` is now shared/injectable: `ProfileRepository(context, prefs, profileState = ProfileStateHolder())` constructor param; `WanderlyRepository` owns the single instance and passes it in. Carved repos can now share one holder.
- [x] Step 3b — extracted `withPostgrestSchemaCacheRetry` + `isPostgrestSchemaCacheError` to `data/PostgrestSchemaCacheRetry.kt` (top-level internal); the 8 call sites + 2 instance refs resolve unchanged (same package); test repointed.
- [x] Step 3c — extracted `ShopRepository(profileState)` (`data/ShopRepository.kt`): getShopItems / purchaseShopItem / equipCosmetic / applyEquippedCosmetic + RPC DTOs (ShopItemParams / PurchaseShopItemRpcResponse / EquipCosmeticRpcResponse) + mappers, sharing the holder. `WanderlyRepository` delegates shop methods to it; `RpcResponseDecodingTest` shop refs repointed to `ShopRepository`. ProfileRepository no longer holds shop code. (Public types stayed in `Shop.kt`.)

**Remaining (heavy carving — own turn each; lean on D's tests as the net):**
- [x] Step 3d-prep — extracted `applyProgressSnapshot` to `ProfileProgressWriter` (`data/ProfileProgressWriter.kt`), shared via `ProfileRepository.progressWriter` (internal); reused by mission completion + the future StreakRepository.
- [x] Step 3d-helper — extracted `mapSensitiveProfileMutationFailure` to `data/SensitiveProfileMutationFailureMapper.kt` (top-level internal).
- [x] Step 3d — carved `StreakRepository(profileState, progressWriter, getCurrentProfile)` (accept/restore/freeze/milestones/claim) and `ReferralRepository(getCurrentProfile)` (claim/hasClaimed). DTOs moved; `WanderlyRepository` delegates to the new repos; `RpcResponseDecodingTest` refs repointed (Streak*/Referral*). **ProfileRepository: 950 → 537 lines.** Public surface unchanged — wiring stayed in WanderlyRepository (no Hilt change needed, step 5 satisfied for these).

**Remaining (optional):**
- [ ] Step 4 (optional further slimming) — ProfileRepository (~537 lines) still holds load/update/username/admin-stats/location/mission-completion/avatar-delegation. Under-300 would mean carving admin-stats or mission-completion (core profile concerns) — diminishing returns; the cohesion goal (separate Shop/Streak/Referral repos, single ProfileStateHolder) is met.
- [ ] D hive fire-and-forget test — still needs `ProfileRepository` itself injectable into `WanderlyRepository` (the carved repos are injectable, but mission-completion's hive trigger lives in WanderlyRepository wrapping `profileRepository.logMissionCompletion`). A small follow-up: inject ProfileRepository (constructor param w/ default) to fake it.

**Done when:** no single repo file > ~300 lines; all profile state mutation goes through `ProfileStateHolder`; tests green.

---

## B · Consolidate dual UI (Fragments→Compose) — IN PROGRESS

Full plan + verified inventory: **[B_MIGRATION_PLAN.md](B_MIGRATION_PLAN.md)**.

**Key finding:** the app is *already Compose-first*. Every screen is a composable; each `*Fragment`
is a thin `ComposeView` shell. So B is NOT a screen rewrite — it is (1) deleting the dead duplicate
Compose stack, then (2) replacing the Fragment + XML-nav-graph + `BottomNavigationView` + View-based
Activity **host skeleton** with a single Navigation-Compose `NavHost`.

**Done:**
- [x] Inventory (read-only fan-out workflow): 10 Fragments mapped to their composables; 2 XML nav
      graphs; Fragment-first host / Compose-first screens; no Compose `NavHost` anywhere.
- [x] **Job 1 — deleted dead duplicate Compose stack** (grep-verified zero live refs):
      `SplashScreen.kt`, `WanderlyBottomBar.kt`, `WanderlyRoutes.kt` + orphaned
      `R.string.splash_tagline`.

**Remaining (Job 2 — the real migration, multi-PR):**
- [x] PR-1 — no-op: Navigation-Compose, hilt-navigation-compose, activity-compose were *already*
      dependencies (`app/build.gradle.kts:422-424`); the deleted `WanderlyRoute` was their would-be
      consumer. Nothing to add; typed routes are introduced per-graph as each cutover lands.
- [x] **PR-2 — auth graph cutover (DONE).** `AuthActivity` now `setContent { Compose NavHost }` over
      type-safe `LoginRoute`/`SignupRoute` (`ui/auth/AuthRoutes.kt`), calling the existing
      `LoginScreen`/`SignupScreen`. Google-OAuth orchestration (browser hop + `onResume` session poll
      + remember-me) kept in the Activity verbatim, sharing an activity-scoped `loginViewModel`;
      Signup uses its own `hiltViewModel()` (matches prior per-fragment VMs). Deeplink callback +
      `resumeStandardAuthFlow` gating unchanged. Deleted `LoginFragment`, `SignupFragment`,
      `auth_nav_graph.xml`, `auth_nav_host` id. **Coverage:** `SupabaseAuthOfflineTest`
      (instrumentation) launches `AuthActivity`, drives the login form, asserts the friendly error —
      validates the migrated UI on CI. **Manual smoke (not test-covered):** Google OAuth browser hop.
- [ ] PR-3…7 per-feature glue extraction (Fragment → pure shell): gems → profile/devDashboard →
      social → missions → map/guide (riskiest last).
- [ ] PR-8 main graph single cutover: `MainActivity` → Compose `NavHost` + `NavigationBar`; replicate
      onboarding start-dest gating + deeplink invite routing; delete all Fragments + nav XML + menu.
- [ ] PR-9 cleanup (collapse Activities? prune fragment/nav deps from version catalog).

**Constraint:** can't mix a Fragment `NavHostFragment` and a Compose `NavHost` on the same back stack
→ cut each graph over in one PR; de-risk by making Fragments pure shells first.

---

## G · Baseline profile — DONE (deleted)

Decision (user): **delete**. The CI emulator is fixed now, but the baseline profile was dead weight
(empty profile installed → zero startup benefit), and wiring would add a ~10-min generation job +
maintenance. Removed:
- [x] `:baselineprofile` module (build.gradle.kts + BaselineProfileGenerator.kt).
- [x] `include(":baselineprofile")` in settings.gradle.kts.
- [x] `implementation(libs.androidx.profileinstaller)` in app/build.gradle.kts.
- [x] The "Baseline profile generator compile check" CI step.
- Unused version-catalog entries (androidxBaselineProfile / benchmark / profileinstaller / uiautomator
  + the androidx-baselineprofile plugin) are harmless and left in place; prune if desired.
- To revisit: re-add the module + plugin + a `generateReleaseBaselineProfile` CI job when optimizing
  Play Store startup (the emulator supports it now).

---

## C · Unify authenticated-proxy HTTP client — DONE

- [x] Extracted `api/AuthedProxyRequest.kt` → `OkHttpClient.awaitWithTokenRefresh(auth, initialToken, buildRequest): Response`: the token → cancellation-aware `await()` → single-401-refresh-and-retry flow, in one place. Each caller keeps its own `OkHttpClient` (timeouts/HTTPS interceptor), its access-token-null handling, response mapping, and retry policy.
- [x] Routed all three: `GeminiClient.executeRequest` (still wrapped in `withRetry`), `PlacesProxyClient.searchText` (→ `NetworkResult`), `DefaultAiAssistantGateway.postGuideRequest` (→ `AiAssistantHttpResponse`). Removed the now-dead per-file `await()` imports. This was the root of the QW-21/QW-22 per-file drift (blocking execute vs await, differing retry).
- Behavior note: on a failed 401-refresh, the helper returns the original 401 Response and each caller maps it as before (401 → exception / HttpError) — only the bespoke "Refresh failed" log/message nuance is dropped; no test asserted it. `withRetry` untouched (HttpRetryTest green).

---

## E · Realtime/FGS + hive-fetch strategy — DONE

- [x] FGS removed while realtime is disabled (done earlier as H-5/H-6).
- [x] Hive-fetch dedup (audit M-7): replaced the per-action **pair** of `contributeIfMatches` calls
      (each of which read the challenge **and** progress rows → ~4 reads/action) with a single
      `HiveChallengeRepository.contribute(Map<goalType, amount>)` that resolves the active challenge
      **once** via a lightweight `activeChallengeRowForContribution()` (challenge row only — **no
      loadProgressRows**) with a 30s TTL cache. WanderlyRepository's mission-completion + gem-discovery
      paths each now do **1** read (cache-amortized further across rapid actions). The UI path
      (`getActiveChallenge`) still loads progress rows for an accurate total.
- [x] Fire-and-forget scope: `HiveChallengeRepository(scope = ...)` is already a constructor param
      (effectively app-lived as a WanderlyRepository @Singleton). A dedicated Hilt-injected app scope
      is an optional further tweak; current form is bounded + cancellable via the supervisor scope.

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
