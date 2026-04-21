# WANDERLY - FULL PROJECT AUDIT

Audit date: 2026-04-21

Scope:
- Read `context.md` and scanned the tracked Android project files matching `*.kt`, `*.java`, `*.xml`, `*.gradle`, `*.gradle.kts`, and `*.properties`.
- Scan inventory: 129 tracked files.
- `.json` files: none tracked in the Android project.
- Also reviewed local-only `local.properties` for security posture without copying secret values.
- Dependency freshness sanity-check was cross-checked against official Android Developers release pages on 2026-04-21.

Blunt verdict:
- Wanderly has strong product direction, but the codebase is not release-ready.
- The biggest risks are security hardening gaps, crash-prone null handling, architecture drift, and very weak automated coverage for the most important user flows.

## 1. ARCHITECTURE & CODE QUALITY

- Architecture consistency: partially MVVM, but not consistently. `ui/MissionsViewModel.kt:64-301` contains orchestration, prompt engineering, verification, reward logic, and notification triggering; `ui/gems/GemsFragment.kt:111-395` and `ui/profile/ProfileFragment.kt:91-346` bypass a proper presentation layer and execute business logic directly from fragments.
- God classes / too many responsibilities:
  - `app/src/main/java/com/novahorizon/wanderly/ui/MissionsViewModel.kt:64-301` is doing mission generation, place verification, Gemini integration, streak progression, and notification side effects.
  - `app/src/main/java/com/novahorizon/wanderly/ui/gems/GemsFragment.kt:111-395` handles permission flow, geocoding, discovery orchestration, Gemini prompt construction, parsing, and UI state.
  - `app/src/main/java/com/novahorizon/wanderly/ui/profile/ProfileFragment.kt:102-346` handles repository access, avatar upload, logout, badge progression, class selection, and badge adapter content decisions.
  - `app/src/main/java/com/novahorizon/wanderly/ui/profile/DevDashboardFragment.kt:60-330` is effectively an admin controller, notification test panel, worker trigger, AI prompt runner, and profile editor all in one file.
  - `app/src/main/java/com/novahorizon/wanderly/auth/AuthSessionCoordinator.kt:11-42` packs `AuthCallbackMatcher`, `AuthRouting`, and `AuthSessionCoordinator` into one file with unrelated responsibilities.
- Circular dependencies between modules/packages: N/A - the app is a single Android module, so there is no Gradle module cycle. I did not find an explicit Kotlin package import cycle, but most features are tightly coupled through `WanderlyGraph` and the broad `WanderlyRepository` facade (`data/WanderlyRepository.kt:10-85`).
- Business logic leaking into UI:
  - `app/src/main/java/com/novahorizon/wanderly/ui/profile/ProfileFragment.kt:184-197` performs badge evaluation and remote persistence from the fragment.
  - `app/src/main/java/com/novahorizon/wanderly/ui/map/MapFragment.kt:206-225` writes user location back to the backend directly from the fragment.
  - `app/src/main/java/com/novahorizon/wanderly/ui/gems/GemsFragment.kt:251-312` builds ranking prompts in the fragment instead of a domain/service layer.
- ViewModels doing too much / too little:
  - Too much: `ui/MissionsViewModel.kt:64-301`.
  - Too thin: there is no feature ViewModel at all for Gems/Profile/Admin, so fragments absorb the missing presentation logic.
- Repository pattern quality:
  - Specialized repositories exist, which is good, but the aggregate `WanderlyRepository` (`data/WanderlyRepository.kt:10-85`) is a catch-all facade mixing profile, social, discovery, mission cache, remember-me state, and app context exposure.
  - `WanderlyRepository.context` (`data/WanderlyRepository.kt:11`) is an architectural smell because it makes it easier for higher layers to reach back into Android framework APIs.
- Anti-patterns:
  - UI layer directly uses `SharedPreferences` in multiple places instead of a single storage abstraction: `AuthActivity.kt:42-43`, `LoginFragment.kt:74-75`, `MapFragment.kt:196-197`, `ProfileFragment.kt:117-120`, `NotificationCheckCoordinator.kt:402-403`, `WanderlyNotificationManager.kt:42-54`.
  - Raw string parsing for AI JSON results instead of schema-safe parsing: `MissionsViewModel.kt:120-127`, `GemsFragment.kt:177-180`, `DevDashboardFragment.kt:226-233`.
  - Direct logging from production code throughout the app instead of a structured debug-only logger.
- Code duplication:
  - UTC date formatting logic is duplicated in `ui/MainViewModel.kt`, `ui/MissionsViewModel.kt:236-245`, and `notifications/NotificationCheckCoordinator.kt:405-415`.
  - Session-to-user-id retrieval with `session.user!!.id` is duplicated across `data/ProfileRepository.kt:57-66` and `data/SocialRepository.kt:15-17`, `51-53`, `80-82`, `106-108`.
  - Preference access patterns are duplicated outside `PreferencesStore`.

## 2. PERFORMANCE

- Main-thread work that should be off the UI thread:
  - I did not find a clear large blocking network/database operation running directly on the main thread.
  - However, UI callbacks are doing too much orchestration before handing off work, especially in `GemsFragment.kt:123-159` and `MissionsFragment.kt:224-270`.
- Coroutines / Flows correctness:
  - `app/src/main/java/com/novahorizon/wanderly/ui/MissionsViewModel.kt:53-61` starts a new `collectLatest` every time `loadProfile()` is called and never cancels prior collectors. That is a duplicate-collector bug waiting to happen.
  - `app/src/main/java/com/novahorizon/wanderly/services/HiveRealtimeService.kt:87-98` launches a realtime status collector inside `serviceScope`, but subscription state is manually tracked and easy to desynchronize.
- Memory leak / lifecycle risk:
  - `app/src/main/java/com/novahorizon/wanderly/services/HiveRealtimeService.kt:139-151` does async cleanup in `onDestroy()` and immediately calls `super.onDestroy()`. The service can be torn down before unsubscribe finishes.
  - `app/src/main/java/com/novahorizon/wanderly/ui/gems/GemsFragment.kt:124-159` and `ui/missions/MissionsFragment.kt:225-270` rely on callback-style location/geocoder APIs that can outlive the screen; there are guard checks, but cancellation is not first-class.
- RecyclerView reuse:
  - Good: `BadgesAdapter`, `GemsAdapter`, and list adapters use `DiffUtil`.
  - Risk: `ProfileFragment` keeps badge presentation logic in the adapter (`ProfileFragment.kt:370-408`) with hardcoded badge names; maintainability is poor, but reuse itself is fine.
- Image loading:
  - Good: `AvatarLoader.kt:43-71` uses Glide with disk cache; `ProfileRepository.kt:200-218` downsamples avatar uploads before compression.
  - Risk: `AvatarLoader.kt:29-31` will fetch arbitrary `http://` URLs, which is a security issue more than a performance one.
- Unnecessary Compose recompositions: N/A - this app uses XML/ViewBinding, not Jetpack Compose.
- Database query optimization / N+1: N/A - there is no Room/SQLite layer. Supabase fetch patterns are simple but chatty.
- Network fan-out pressure:
  - `app/src/main/java/com/novahorizon/wanderly/data/DiscoveryRepository.kt:151-174` fires 15 Google Places queries in parallel with no throttling or backpressure. This is likely to amplify latency, quota burn, and transient failure rates.
- Large files / asset optimization:
  - No severe asset bloat found in `res/`; the largest tracked drawable is `app/src/main/res/drawable/ic_launcher_foreground.png` at about 106 KB, which is acceptable.

## 3. SECURITY

- API keys / secrets hardcoded:
  - No tracked source file contains raw secret values.
  - But `app/build.gradle.kts:27-33` injects `SUPABASE_URL` and `SUPABASE_ANON_KEY` into `BuildConfig`, meaning they are recoverable from the APK.
  - `local.properties` contains real local credentials in plaintext on the developer machine. That is normal for local setup, but still sensitive and easy to mishandle.
- Sensitive data storage:
  - `app/src/main/java/com/novahorizon/wanderly/data/PreferencesStore.kt:8-74` uses plain `SharedPreferences`, not encrypted storage.
  - The same applies to notification state in `notifications/NotificationCheckCoordinator.kt:402-403` and cooldown state in `notifications/WanderlyNotificationManager.kt:42-71`.
  - This does not look like auth-token storage, but it still stores behavioral data and app state in plaintext.
- HTTPS / transport security:
  - Most network endpoints are HTTPS.
  - `app/src/main/java/com/novahorizon/wanderly/ui/AvatarLoader.kt:29-31` explicitly allows `http://` avatar URLs. That is a release blocker.
  - No `app/src/main/res/xml/network_security_config.xml` exists, and the manifest does not reference one. There is no app-level transport hardening policy.
- WebView security: N/A - no WebView usage found.
- Input validation / sanitization:
  - Basic validation exists in `ui/auth/SignupFragment.kt:45-63` and friend-code normalization exists in `data/SocialRepository.kt:143-145`.
  - Validation is inconsistent, and several user-facing errors still echo raw backend text (`SocialRepository.kt:73`, `DevDashboardFragment.kt:250-252`).
- Exported components:
  - `app/src/main/AndroidManifest.xml:55-69` exports `AuthActivity` with browsable deep links using a custom scheme. That is normal for auth callbacks, but it is less trustworthy than verified App Links and deserves stricter URI validation and threat-modeling.
  - `MainActivity` is not exported (`AndroidManifest.xml:78-80`), and `HiveRealtimeService` is not exported (`72-76`), which is good.
- Auth token storage / refresh handling:
  - Supabase SDK auto-refresh is enabled (`api/SupabaseClient.kt:41-47`).
  - Proxy clients retry once on 401 (`GeminiClient.kt:119-137`, `PlacesProxyClient.kt:44-57`), which is good.
  - App-layer code still manually depends on current tokens and throws generic exceptions when auth state is missing.
- SQL injection risks: N/A - no raw SQLite SQL queries found.
- Obfuscation / R8:
  - Release minification is enabled (`app/build.gradle.kts:36-44`), which is good.
  - But `app/proguard-rules.pro:30-44` keeps entire app data classes and third-party packages, which weakens obfuscation more than necessary.
- Sensitive information in logs:
  - `app/src/main/java/com/novahorizon/wanderly/api/SupabaseClient.kt:26` logs the Supabase base URL.
  - `app/src/main/java/com/novahorizon/wanderly/data/ProfileRepository.kt:141-164` logs avatar upload targets, storage responses, and the generated public avatar URL.
  - `app/src/main/java/com/novahorizon/wanderly/ui/profile/DevDashboardFragment.kt:153-161` can display live profile JSON on-device.

## 4. CRASH RISKS & STABILITY

- Unhandled exceptions / swallowed errors:
  - `app/src/main/java/com/novahorizon/wanderly/data/ProfileRepository.kt:112-118` swallows exceptions in `resetMissionDateForTesting()`.
  - `app/src/main/java/com/novahorizon/wanderly/ui/profile/ProfileFragment.kt:121-123`, `163-165`, `177-180` reduce failures to generic snackbars and make troubleshooting harder.
  - `app/src/main/java/com/novahorizon/wanderly/api/PlacesProxyClient.kt:55-63` and `api/GeminiClient.kt:133-145` collapse rich failures into generic `Exception`.
- Nullability / NPE risks:
  - `app/src/main/java/com/novahorizon/wanderly/data/ProfileRepository.kt:62`
  - `app/src/main/java/com/novahorizon/wanderly/data/SocialRepository.kt:16`, `52`, `81`, `107`
  - `app/src/main/java/com/novahorizon/wanderly/ui/profile/ProfileFragment.kt:56`, `278`
  - `app/src/main/java/com/novahorizon/wanderly/ui/missions/MissionsFragment.kt:75`
  - All of those `!!` uses can crash on auth races, malformed results, or inconsistent backend state.
- Lifecycle-aware usage:
  - Fragment binding cleanup is generally correct.
  - Callback APIs are the weak point; `MissionsFragment.kt:225-270` and `GemsFragment.kt:124-159` still depend on manual `isAdded` / `_binding` checks.
- Fragment transactions:
  - N/A - navigation is handled through `NavController`; I did not find unsafe manual fragment transaction code.
- Async cancellation:
  - Fragment coroutines mostly use `viewLifecycleOwner.lifecycleScope`, which is good.
  - `HiveRealtimeService.kt:139-151` cleanup is not lifecycle-safe enough for deterministic teardown.
- Error / loading / empty states:
  - Better than average on missions/gems/social, but inconsistent.
  - `app/src/main/java/com/novahorizon/wanderly/ui/missions/MissionsFragment.kt:190-204` resets some UI on error but does not fully normalize all controls.
  - `ProfileFragment.kt:169-180` has only a snackbar for load failure; there is no dedicated error state in the profile screen.
- Hidden repeated-write risk:
  - `app/src/main/java/com/novahorizon/wanderly/ui/profile/ProfileFragment.kt:184-197` unlocks badges during profile load and immediately writes back to the backend. That can create noisy write churn and hard-to-debug loops if server/client normalization diverges.

## 5. NETWORK & API

- API error handling completeness:
  - Incomplete. `GeminiClient.kt:139-148` and `PlacesProxyClient.kt:60-66` do not preserve structured error bodies for the UI layer.
  - `MissionsViewModel.kt:152-153`, `193-194`, `219-220`, `297-298` surfaces raw exception strings to users.
- Loading / error / success state consistency:
  - Missions and Gems both try to model states, but not consistently. `GemsFragment.kt:218-249` manages UI state manually inside the fragment; `MissionsFragment.kt:124-208` does the same from observer branches.
- Retry logic:
  - Only one 401-refresh retry exists in `GeminiClient.kt:119-137` and `PlacesProxyClient.kt:44-57`.
  - No retry/backoff exists for transient 5xx, timeouts, or overloaded upstream services.
- Caching:
  - Minimal. Active mission state is cached locally, but remote discovery / detail calls are not cached.
  - `GemsFragment.kt:59` uses in-memory `seenGemsHistory`, which is lost on process death and configuration changes.
- Request cancellation on navigation:
  - Suspend calls tied to fragment scopes are okay.
  - Callback-based location/geocoder requests are not truly cancelable from the feature layer.
- Timeouts:
  - Present and reasonable:
    - `api/SupabaseClient.kt:32-38`
    - `api/GeminiClient.kt:22-26`
    - `api/PlacesProxyClient.kt:16-20`
    - `data/ProfileRepository.kt:49-53`
- Pagination:
  - N/A - no paginated list/API flow exists in the current app.
- Parsing brittleness:
  - `app/src/main/java/com/novahorizon/wanderly/api/GeminiClient.kt:156-174` assumes a very specific response shape and can throw if `candidates[0]` or `parts` is absent.
  - `MissionsViewModel.kt:120-127` and `GemsFragment.kt:314-320` parse AI JSON by substring, which is brittle by definition.

## 6. DATABASE & LOCAL STORAGE

- Room schema versioning / migrations: N/A - no Room database exists.
- Missing indexes: N/A - no local SQL schema exists in the Android app.
- Transactions: N/A - no local DB writes to batch transactionally.
- Local storage design quality:
  - `app/src/main/java/com/novahorizon/wanderly/data/PreferencesStore.kt:47-63` stores mission coordinates as strings instead of typed doubles.
  - `PreferencesStore.kt:65-72` clears the active mission but intentionally leaves mission history behind; that may become stale over time.
  - `data/WanderlyRepository.kt:61-73` updates `last_visit_date` when mission data is merely saved, which couples visit tracking to mission generation, not mission completion.
- Data cleanup:
  - Temporary avatar file is cleaned up in `ui/missions/MissionsFragment.kt:318-321` and overwritten in `ProfileFragment.kt:65`, which is okay.
  - There is no persistent cleanup strategy for mission history, notification state, or stale social preference keys.
- Large objects stored in DB: N/A - no local DB, and avatar binary is not stored locally long-term.

## 7. UI & USER EXPERIENCE

- Configuration changes:
  - Acceptable in some areas, but not robust everywhere.
  - `app/src/main/java/com/novahorizon/wanderly/ui/gems/GemsFragment.kt:59` keeps seen-history only in memory, so refresh behavior changes after rotation/process death.
  - `ProfileFragment.kt:51` tracks dialog state in a plain field; it is not saved across configuration changes.
- Dark mode:
  - Supported via `res/values-night/themes.xml` and `res/values-night/colors.xml`.
  - I did not find a major dark-mode breakage.
- Touch targets below 48dp:
  - `app/src/main/res/layout/fragment_profile.xml:85-96` camera icon is `36dp`.
  - `app/src/main/res/layout/fragment_profile.xml:119-127` username edit button is `32dp`.
  - `app/src/main/res/layout/item_social_profile.xml:123-135` remove-friend control is `40dp`.
- Keyboard handling:
  - `app/src/main/res/layout/fragment_login.xml:2-109` and `fragment_signup.xml:2-135` are full-screen `ConstraintLayout`s with no scroll container. On smaller devices / large font sizes, keyboard overlap is likely.
  - I did not find explicit IME actions, focus-next handling, or inset-aware keyboard behavior for auth/admin forms.
- Loading states:
  - Missions/Gems handle loading states explicitly.
  - Profile/admin/logout flows rely mostly on snackbars and immediate button actions, with no loading indicator.
- Error messages:
  - Some are friendly (`strings.xml`-backed), but many are hardcoded or raw:
    - `SignupFragment.kt:46`, `51`, `56`, `61`, `80`
    - `ProfileFragment.kt:179`
    - `DevDashboardFragment.kt:163`, `175-178`, `249-252`, `298-329`
- Back navigation:
  - Mostly okay through `NavController`.
  - No major navigation bug stood out in the scanned code.
- Hardcoded strings:
  - `app/src/main/res/layout/dialog_edit_username.xml:12`, `20`, `31`
  - `app/src/main/res/layout/fragment_dev_dashboard.xml:13`, `26`, `61`, `69`, `77`, `85`, `94`, `114`, `122`, `131`, `139`, `146`, `153`, `160`, `167`, `174`, `181`, `188`, `195`, `202`, `209`, `216`, `223`, `239`, `260`, `268`, `277`, `285`, `317`, `327`, `336`
  - `app/src/main/res/layout/fragment_signup.xml:28`, `50`, `72`, `96`
  - `app/src/main/res/layout/activity_splash.xml:39`
  - `app/src/main/res/layout/item_badge.xml:37`
  - `app/src/main/java/com/novahorizon/wanderly/ui/auth/SignupFragment.kt:46`, `51`, `56`, `61`, `80`
  - `app/src/main/java/com/novahorizon/wanderly/ui/profile/DevDashboardFragment.kt:68`, `79`, `91`, `102`, `114`, `126`, `138`, `150`, `158`, `160`, `163`, `175-178`, `195`, `201-204`, `232-235`, `249-252`, `299`, `304`, `309`, `324`, `328`
- Accessibility:
  - Decorative images correctly use `@null` in several places.
  - The undersized tap targets above are accessibility failures.
  - `fragment_profile.xml:130-140` friend code is a `TextView` acting like a control; it is not obviously button-like.

## 8. DEPENDENCIES & BUILD

- Outdated dependencies with known vulnerabilities:
  - I did not find an obvious red-flag version mismatch in the Android stack.
  - `gradle/wrapper/gradle-wrapper.properties:4` already uses Gradle `9.3.1`.
  - `gradle/libs.versions.toml:2`, `4`, `11`, `14`, `22` aligns AGP/core/navigation/lifecycle/work with current stable Android releases checked on 2026-04-21.
- Duplicate/conflicting dependency versions:
  - None found. The version catalog is centralized in `gradle/libs.versions.toml`.
- Unused dependencies:
  - No clearly unused dependency jumped out from the scan; most declared libraries are referenced in code.
- minSdk / targetSdk / compileSdk:
  - `app/build.gradle.kts:16`, `20-21` uses `compileSdk 36`, `minSdk 26`, `targetSdk 36`, which is appropriate.
- Debug-only tools:
  - N/A - no LeakCanary, Flipper, or similar tooling is present.
- ProGuard / R8:
  - Enabled in release (`app/build.gradle.kts:36-44`).
  - Keep rules are too broad (`app/proguard-rules.pro:30-44`), weakening release hardening.
- Build variants:
  - Only `debug` and `release` exist. No staging/internal flavor exists, which makes it harder to isolate dev/admin behavior from production.
- Deprecated APIs:
  - `app/src/main/java/com/novahorizon/wanderly/ui/gems/GemsFragment.kt:130-135` and `ui/missions/MissionsFragment.kt:242-246` still use the deprecated pre-Tiramisu `Geocoder.getFromLocation` path for older devices. That is acceptable as compatibility code, but it is still technical debt.

## 9. TESTING

- Current test coverage:
  - Unit tests present: 7 focused files under `app/src/test`.
  - Instrumentation tests present: 1 generated smoke test under `app/src/androidTest`.
  - Effective coverage for critical flows is very low.
- ViewModel tests:
  - Missing for `MainViewModel`, `MissionsViewModel`, `SocialViewModel`, and `AuthViewModel`.
- Repository tests:
  - Only light helper/payload tests exist (`ProfileRepositoryPayloadTest`, `SocialRepositoryFriendCodeTest`).
  - There are no meaningful repository integration tests for profile fetch/update, discovery, avatar upload, or social flows.
- UI tests:
  - None for login, signup, mission generation, mission verification, gems, profile, map, or social flows.
- Room integration tests: N/A - no Room layer exists.
- False-positive / trivial tests:
  - `app/src/androidTest/java/com/novahorizon/wanderly/ExampleInstrumentedTest.kt:17-23` is boilerplate and adds almost no confidence.
- Critical flows with zero automated coverage:
  - Auth deep link callback import
  - Mission generation + verification
  - Gemini/Places proxy error handling
  - Avatar upload
  - Badge unlock + class selection
  - Realtime service behavior
  - Notification rule evaluation

## 10. PERMISSIONS

- Unused permissions:
  - `app/src/main/AndroidManifest.xml:7` declares `ACCESS_COARSE_LOCATION`, but current code requests and depends on fine location paths.
  - `AndroidManifest.xml:12` declares `FOREGROUND_SERVICE_SPECIAL_USE`, but no matching special-use foreground service type exists.
- Dangerous permission timing / rationale:
  - `MainActivity.kt:120-125` requests notification permission without rationale or fallback explanation.
  - `MissionsFragment.kt:213-215` and `GemsFragment.kt:113-115` request location permission just-in-time, which is correct, but denial handling is only a snackbar.
  - `MissionsFragment.kt:273-278` requests camera permission just-in-time, which is correct.
- Fallback behavior when denied:
  - Present, but weak. Most denials produce a snackbar and dead-end the feature instead of providing alternate actions.
- Android 13+ granular media permissions:
  - Good: avatar picking uses the photo picker (`ProfileFragment.kt:63-75`) instead of broad storage/media permissions.

## 11. MANIFEST & CONFIGURATION

- Activities / services / providers declared correctly:
  - Mostly yes.
  - `FileProvider` is configured correctly in `AndroidManifest.xml:29-37`.
- Intent restrictions:
  - `AuthActivity` deep links are implicit and browsable by design (`AndroidManifest.xml:55-69`).
  - `MainActivity` is explicit-only (`78-80`), which is good.
- Backup configuration:
  - `android:allowBackup="false"` in `AndroidManifest.xml:18-27` is conservative and safe.
  - `res/xml/backup_rules.xml` and `res/xml/data_extraction_rules.xml` are still default/sample files and currently dead configuration because backup is disabled.
- Deep links / app links:
  - Custom-scheme deep links exist, but there are no verified HTTPS App Links.
  - Security is acceptable for a prototype, not ideal for release-grade auth callback handling.
- Launch mode:
  - No suspicious launchMode usage found.
- Orientation constraint:
  - `AndroidManifest.xml:39-43` forces `UCropActivity` to portrait. That is not a bug, but it is a UX/configuration limitation.

## 12. LOGGING & DEBUGGING

- Production `Log.d` / `Log.v` noise:
  - Present in many release-path files:
    - `api/SupabaseClient.kt:26`
    - `data/ProfileRepository.kt:128`, `141`, `159`, `164`
    - `ui/gems/GemsFragment.kt:83`, `112`, `138`
    - `ui/missions/MissionsFragment.kt:237`, `248`, `264`
    - `notifications/NotificationCheckCoordinator.kt:255`
    - `notifications/WanderlyNotificationManager.kt:49`, `59`, `71`, `106-109`, `140`
    - `services/HiveRealtimeService.kt:80`, `88`, `91`, `123`, `144`
    - `WanderlyApplication.kt:35`, `63`
- Timber / release trees: N/A - Timber is not used.
- Sensitive data printed in logs:
  - Yes. See `ProfileRepository.kt:141-164` and `SupabaseClient.kt:26`.
- TODO / FIXME / HACK comments:
  - `app/src/main/java/com/novahorizon/wanderly/WanderlyApplication.kt:50`
  - `app/src/main/res/xml/data_extraction_rules.xml` contains the default TODO template comment.

---

## AUDIT SUMMARY

### Critical Issues (must fix before release)

1. `app/src/main/java/com/novahorizon/wanderly/ui/AvatarLoader.kt:29-31` - avatar loading accepts `http://` URLs, allowing insecure transport for user media. Suggested fix: reject non-HTTPS URLs and add a `network_security_config` that blocks cleartext traffic.
2. `app/src/main/java/com/novahorizon/wanderly/data/ProfileRepository.kt:141-164` - production logs expose storage endpoints, response bodies, and final public avatar URLs. Suggested fix: remove these logs from release builds and never log storage responses containing user data.
3. `app/src/main/java/com/novahorizon/wanderly/data/ProfileRepository.kt:62` and `app/src/main/java/com/novahorizon/wanderly/data/SocialRepository.kt:16`, `52`, `81`, `107` - `session.user!!` can crash core profile/social flows during auth race conditions. Suggested fix: replace force unwraps with guarded early returns and domain-specific auth errors.
4. `app/src/main/java/com/novahorizon/wanderly/ui/profile/ProfileFragment.kt:56`, `278` and `app/src/main/java/com/novahorizon/wanderly/ui/missions/MissionsFragment.kt:75` - UI force unwraps can crash on malformed activity results, null mission images, or backend badge nullability drift. Suggested fix: remove `!!`, validate inputs defensively, and keep UI state resilient to missing data.
5. `app/src/main/java/com/novahorizon/wanderly/ui/MissionsViewModel.kt:64-301`, `ui/gems/GemsFragment.kt:111-395`, `ui/profile/ProfileFragment.kt:91-346` - major business logic is living in View/UI code, making bugs harder to test and easier to regress. Suggested fix: move mission/gems/profile workflows into dedicated use cases / feature ViewModels and keep fragments thin.
6. `app/proguard-rules.pro:30-44` - broad keep rules sharply reduce release obfuscation. Suggested fix: tighten keep rules to only the serializer/model shapes genuinely required by reflection/serialization.

### Major Issues (should fix soon)

1. `app/src/main/java/com/novahorizon/wanderly/data/PreferencesStore.kt:8-74`, `notifications/NotificationCheckCoordinator.kt:402-403`, `notifications/WanderlyNotificationManager.kt:42-71` - plaintext `SharedPreferences` used for behavioral state and mission data. Suggested fix: centralize state storage and move sensitive local data to encrypted storage or at least DataStore with stricter boundaries.
2. `app/src/main/java/com/novahorizon/wanderly/ui/MissionsViewModel.kt:53-61` - `loadProfile()` adds duplicate collectors. Suggested fix: collect once in init or hold a single job and cancel/restart explicitly.
3. `app/src/main/java/com/novahorizon/wanderly/data/DiscoveryRepository.kt:151-174` - 15 parallel Google Places queries without throttling can hammer quotas and increase failure rates. Suggested fix: batch, limit concurrency, and cache results by city/radius.
4. `app/src/main/java/com/novahorizon/wanderly/ui/profile/ProfileFragment.kt:184-197` - badge unlock logic writes back during profile rendering, risking repeated remote writes and noisy state churn. Suggested fix: move badge normalization server-side or into a repository/domain step with idempotent comparison.
5. `app/src/main/java/com/novahorizon/wanderly/services/HiveRealtimeService.kt:139-151` - async unsubscribe in `onDestroy()` is not deterministic. Suggested fix: cancel collector first, use `runBlocking` only if truly required for teardown, or restructure ownership so cleanup completes before service death.
6. `app/src/main/java/com/novahorizon/wanderly/api/GeminiClient.kt:156-174`, `ui/MissionsViewModel.kt:120-127`, `ui/gems/GemsFragment.kt:314-320` - AI response parsing is brittle and will crash/fail on small response-shape drift. Suggested fix: use validated DTOs and strict schema extraction with safer fallback handling.
7. `app/src/main/AndroidManifest.xml:55-69` - auth relies on custom-scheme deep links only, without verified App Links. Suggested fix: migrate auth callback handling to verified HTTPS App Links if the auth stack permits it.
8. `app/src/main/res/layout/fragment_profile.xml:85-96`, `119-127`, `app/src/main/res/layout/item_social_profile.xml:123-135` - key controls miss the 48dp accessibility minimum. Suggested fix: enlarge tap targets or wrap them in larger clickable containers.
9. `app/src/main/res/layout/fragment_login.xml:2-109` and `fragment_signup.xml:2-135` - auth screens are not scrollable, making keyboard overlap likely on small devices. Suggested fix: move forms into `NestedScrollView`/inset-aware containers and define IME actions.
10. `app/src/androidTest/java/com/novahorizon/wanderly/ExampleInstrumentedTest.kt:17-23` plus missing feature tests elsewhere - automated confidence is far too low for a release. Suggested fix: add ViewModel, repository, and UI tests for auth, missions, profile, and social.

### Minor Issues (nice to have)

1. `app/src/main/java/com/novahorizon/wanderly/auth/AuthSessionCoordinator.kt` - three unrelated auth utilities share one file. Suggested fix: split them into dedicated files.
2. `app/src/main/java/com/novahorizon/wanderly/data/WanderlyRepository.kt` - broad facade mixes concerns and exposes `Context`. Suggested fix: split mission cache/profile/social/discovery concerns and stop exposing app context from the repository.
3. `app/src/main/res/layout/activity_splash.xml:39`, `dialog_edit_username.xml:12`, `20`, `31`, `fragment_signup.xml:28`, `50`, `72`, `96`, `item_badge.xml:37`, and many strings in `fragment_dev_dashboard.xml` / `DevDashboardFragment.kt` - localization debt. Suggested fix: move all user-facing text into `strings.xml`.
4. `app/src/main/AndroidManifest.xml:7`, `12` - unused or mismatched permissions declared. Suggested fix: remove `ACCESS_COARSE_LOCATION` and `FOREGROUND_SERVICE_SPECIAL_USE` unless a real use case is added.
5. `app/src/main/res/layout/fragment_missions.xml:177-222` - hidden debug panel exists in layout but is not wired into the feature. Suggested fix: remove dead debug UI or gate it cleanly behind a debug build flag.
6. `app/src/main/java/com/novahorizon/wanderly/WanderlyApplication.kt:50` and `res/xml/data_extraction_rules.xml` - stale TODO/FIXME noise. Suggested fix: clean up resolved comments and sample config files.

### Overall Score

| Category | Score (1-10) | Notes |
|---|---:|---|
| Architecture & Code Quality | 4 | Clear intent, inconsistent execution, too much feature logic in UI |
| Performance | 6 | Not disastrous, but concurrency and callback orchestration need tightening |
| Security | 3 | Cleartext avatar allowance, verbose logging, weak local hardening |
| Crash Risks & Stability | 4 | Several `!!` crash points and brittle parsing paths |
| Network & API | 5 | Timeouts exist, retries/caching/state handling are incomplete |
| Database & Local Storage | 4 | No DB complexity, but local persistence is simplistic and loosely typed |
| UI & User Experience | 5 | Strong theme, but accessibility/localization/keyboard polish lag behind |
| Dependencies & Build | 7 | Current Android stack is in decent shape; release hardening rules need work |
| Testing | 2 | Critical flows have almost no automated safety net |
| Permissions | 5 | Timing is mostly okay; denial UX and manifest cleanup are weak |
| Manifest & Configuration | 5 | Mostly sane, but auth/deep-link hardening is incomplete |
| Logging & Debugging | 3 | Too much release-path logging, including sensitive details |

Overall project health score: 4.4 / 10

### Top 5 Priority Actions

1. Lock down transport and logging: reject `http://`, add `network_security_config`, and strip sensitive release logs.
2. Remove all crash-prone `!!` usages in auth/profile/social/mission paths and replace them with explicit guarded failure handling.
3. Refactor mission, gems, and profile workflows out of fragments / oversized ViewModels into dedicated presentation/domain layers.
4. Replace ad-hoc preference access with one storage strategy, and harden local state handling for mission, notification, and profile flows.
5. Add real automated coverage for auth, mission generation/verification, avatar upload, profile progression, and notification logic before any release candidate.
