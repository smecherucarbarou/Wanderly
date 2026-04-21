# WANDERLY - FIX LOG
Generated: 2026-04-21

## Phase 1 - Critical

### FIX-C1: Block Cleartext HTTP + Add Network Security Config
Files changed: `app/src/main/java/com/novahorizon/wanderly/ui/AvatarLoader.kt`, `app/src/main/res/xml/network_security_config.xml`, `app/src/main/AndroidManifest.xml`, `app/src/test/java/com/novahorizon/wanderly/ui/AvatarLoaderUrlPolicyTest.kt`
What was done: Avatar loading now rejects any remote URL that is not `https://`, logs a warning, and shows a placeholder instead of attempting a cleartext fetch. A new app-wide network security config now disables cleartext traffic, and the manifest points the application at that config.
How to verify: Try loading an avatar with an `http://` URL and confirm Glide never requests it and the placeholder is shown. Inspect the merged manifest and verify `android:networkSecurityConfig="@xml/network_security_config"` is present.

### FIX-C2: Remove Sensitive Data From Production Logs
Files changed: `app/src/main/java/com/novahorizon/wanderly/data/ProfileRepository.kt`, `app/src/main/java/com/novahorizon/wanderly/api/SupabaseClient.kt`, `app/src/main/java/com/novahorizon/wanderly/ui/gems/GemsFragment.kt`, `app/src/main/java/com/novahorizon/wanderly/ui/missions/MissionsFragment.kt`, `app/src/main/java/com/novahorizon/wanderly/notifications/NotificationCheckCoordinator.kt`, `app/src/main/java/com/novahorizon/wanderly/notifications/WanderlyNotificationManager.kt`, `app/src/main/java/com/novahorizon/wanderly/services/HiveRealtimeService.kt`, `app/src/main/java/com/novahorizon/wanderly/WanderlyApplication.kt`
What was done: The audited release-path logs were wrapped behind `BuildConfig.DEBUG` so storage endpoints, avatar URLs, response bodies, and debug chatter no longer appear in release builds. Where it made sense, helper logging methods were introduced so the guards stay consistent and the production code path stays unchanged.
How to verify: Run a debug build and confirm the logs still appear. Build release and confirm the guarded messages are absent from logcat.

### FIX-C3: Eliminate Crash-Prone Force Unwraps
Files changed: `app/src/main/java/com/novahorizon/wanderly/data/ProfileRepository.kt`, `app/src/main/java/com/novahorizon/wanderly/data/SocialRepository.kt`, `app/src/main/java/com/novahorizon/wanderly/ui/profile/ProfileFragment.kt`, `app/src/main/java/com/novahorizon/wanderly/ui/missions/MissionsFragment.kt`
What was done: All audited `!!` usages were replaced with guarded early returns or null-safe branches. Avatar crop results now show a toast if UCrop returns no URI, badge binding safely handles null badge lists, mission photo verification checks the captured URI before decoding, and auth-race-sensitive repository code no longer dereferences `session.user!!`.
How to verify: Exercise avatar crop cancel/failure, launch profile with null badges, and force an authless social/profile call. The app should now fail gracefully without crashing.

### FIX-C4: Tighten ProGuard Keep Rules
Files changed: `app/proguard-rules.pro`
What was done: The broad `com.novahorizon.wanderly.**` and third-party package keep rules were removed. The release config now keeps only Glide integration, classes annotated with `@Serializable` or `@Keep`, serializer companions/generated serializers, and enums needed by serializer-safe reflection.
How to verify: Build a release variant and confirm minification still succeeds. Inspect the rules file and verify there is no catch-all keep rule for the whole app package.

## Phase 2 - Major

### FIX-M1: Fix Duplicate Flow Collector In MissionsViewModel
Files changed: `app/src/main/java/com/novahorizon/wanderly/ui/MissionsViewModel.kt`
What was done: Profile observation now uses a single stored collector job created from `init {}` and restarted only if needed, instead of adding a new `collectLatest` every time `loadProfile()` ran. This prevents duplicate collectors and duplicated UI updates.
How to verify: Call `loadProfile()` repeatedly and confirm only one profile collector remains active and the UI updates once per profile change.

### FIX-M2: Throttle Parallel Places API Calls
Files changed: `app/src/main/java/com/novahorizon/wanderly/data/DiscoveryRepository.kt`
What was done: The unbounded Google Places async fan-out was replaced with a semaphore-limited `withPermit` flow capped at three concurrent requests. The query list and ranking behavior were left intact.
How to verify: Add temporary instrumentation or debug logs around `fetchCandidatesFromGooglePlacesQuery()` and confirm no more than three concurrent calls are active at once.

### FIX-M3: Make HiveRealtimeService Teardown Deterministic
Files changed: `app/src/main/java/com/novahorizon/wanderly/services/HiveRealtimeService.kt`
What was done: `onDestroy()` now cancels collector jobs first, then performs `unsubscribe()` inside `runBlocking` with a two-second timeout and exception handling. The realtime status collector is also tracked explicitly so teardown is no longer left to a fire-and-forget coroutine.
How to verify: Start and stop the service and confirm `onDestroy()` completes promptly without hanging. In debug, verify the unsubscribe log appears when shutdown succeeds.

### FIX-M4: Fix Badge Write-Back During Profile Render
Files changed: `app/src/main/java/com/novahorizon/wanderly/ui/profile/ProfileFragment.kt`, `app/src/main/java/com/novahorizon/wanderly/ui/profile/ProfileViewModel.kt`, `app/src/main/java/com/novahorizon/wanderly/ui/WanderlyViewModelFactory.kt`
What was done: Badge unlock persistence was moved out of the fragment render path into a dedicated `ProfileViewModel.checkAndUnlockBadges()` flow guarded by a per-session flag. The fragment now renders profile state only and no longer writes back badges while binding UI.
How to verify: Open the profile screen multiple times in the same session and confirm badge persistence happens at most once after the initial load, with no repeated write churn during re-render.

### FIX-M5: Harden AI/Gemini Response Parsing
Files changed: `app/src/main/java/com/novahorizon/wanderly/api/GeminiClient.kt`, `app/src/main/java/com/novahorizon/wanderly/ui/MissionsViewModel.kt`, `app/src/main/java/com/novahorizon/wanderly/ui/gems/GemsFragment.kt`, `app/src/main/java/com/novahorizon/wanderly/util/AiResponseParser.kt`, `app/src/test/java/com/novahorizon/wanderly/util/AiResponseParserTest.kt`
What was done: Gemini candidate parsing now checks for missing candidates, missing content, and empty text parts and returns descriptive failures instead of blindly indexing arrays. Mission and gems parsing moved from substring slicing to regex-based JSON extraction plus `runCatching { Json.decodeFromString(...) }`, with raw-response logging kept debug-only.
How to verify: Feed malformed Gemini responses with missing candidates, missing JSON wrappers, or bad JSON bodies. The app should surface friendly errors instead of crashing or throwing index exceptions.

### FIX-M6: Fix Auth/Login Screens Keyboard Overlap
Files changed: `app/src/main/res/layout/fragment_login.xml`, `app/src/main/res/layout/fragment_signup.xml`, `app/src/main/AndroidManifest.xml`
What was done: Both auth layouts now sit inside `NestedScrollView` containers so the form can move when the keyboard appears. IME actions were added across the fields, and `AuthActivity` now uses `adjustResize`.
How to verify: Open login and signup on a smaller device or emulator, focus the lower fields, and confirm the form scrolls instead of being covered by the keyboard.

### FIX-M7: Fix Undersized Tap Targets
Files changed: `app/src/main/res/layout/fragment_profile.xml`, `app/src/main/res/layout/item_social_profile.xml`
What was done: The audited small tap targets were increased to 48dp with matching padding so the effective hit area now meets accessibility guidance. The visual styling remains the same apart from the safer target bounds.
How to verify: Inspect the rendered profile and friend-row controls in Layout Inspector and confirm the relevant clickable views are at least 48dp by 48dp.

### FIX-M8: Centralize And Guard All SharedPreferences Access
Files changed: `app/src/main/java/com/novahorizon/wanderly/data/PreferencesStore.kt`, `app/src/main/java/com/novahorizon/wanderly/data/WanderlyRepository.kt`, `app/src/main/java/com/novahorizon/wanderly/AuthActivity.kt`, `app/src/main/java/com/novahorizon/wanderly/ui/auth/LoginFragment.kt`, `app/src/main/java/com/novahorizon/wanderly/ui/map/MapFragment.kt`, `app/src/main/java/com/novahorizon/wanderly/ui/profile/ProfileFragment.kt`, `app/src/main/java/com/novahorizon/wanderly/notifications/NotificationCheckCoordinator.kt`, `app/src/main/java/com/novahorizon/wanderly/notifications/WanderlyNotificationManager.kt`
What was done: All audited direct `getSharedPreferences(...)` reads and writes were rerouted through `PreferencesStore` or repository methods backed by it. Notification cooldown/check state and remember-me/mission state are now centrally accessed, which reduces duplicated key handling and makes future storage hardening easier.
How to verify: Search the audited files for `getSharedPreferences(` and confirm those direct calls are gone. Smoke-test remember-me, logout, mission target storage, and notification cooldown resets.

### FIX-M9: Fix Mission Coordinate Storage Typing
Files changed: `app/src/main/java/com/novahorizon/wanderly/Constants.kt`, `app/src/main/java/com/novahorizon/wanderly/data/PreferencesStore.kt`
What was done: Mission target coordinates now persist in typed float-backed keys instead of stringified doubles. A migration fallback reads legacy string keys if the typed keys are missing, writes the typed values back, removes the legacy keys, and safely defaults malformed legacy values to `0.0`.
How to verify: Start with old string-based mission coordinate keys in prefs, launch the app, and confirm the typed keys are written and the legacy string keys are removed. Generate a new mission and confirm the new typed keys are used directly.

### FIX-M10: Remove Unused/Mismatched Permissions From Manifest
Files changed: `app/src/main/AndroidManifest.xml`
What was done: `ACCESS_COARSE_LOCATION` and `FOREGROUND_SERVICE_SPECIAL_USE` were removed because the app uses fine location and a `dataSync` foreground service only. The rest of the manifest declarations were preserved.
How to verify: Inspect the manifest and confirm both permissions are absent. Run a normal location flow and the realtime service to verify no behavior regressed.

## Phase 3 - Minor

### FIX-N1: Move Hardcoded Strings To strings.xml
Files changed: `app/src/main/res/values/strings.xml`, `app/src/main/res/layout/dialog_edit_username.xml`, `app/src/main/res/layout/fragment_signup.xml`, `app/src/main/res/layout/activity_splash.xml`, `app/src/main/res/layout/item_badge.xml`, `app/src/main/res/layout/fragment_dev_dashboard.xml`, `app/src/main/java/com/novahorizon/wanderly/ui/auth/SignupFragment.kt`, `app/src/main/java/com/novahorizon/wanderly/ui/profile/DevDashboardFragment.kt`
What was done: The audited user-facing hardcoded strings were moved into `strings.xml` and the layouts/fragments were updated to reference those resource IDs. This includes the full dev dashboard UI copy, signup validation copy, splash app name text, edit-username dialog text, and badge placeholder text.
How to verify: Search the audited files for user-facing hardcoded text and confirm they now use `@string/...` or `getString(...)`. Open the affected screens and verify the text still renders correctly.

### FIX-N2: Extract Duplicate UTC Date Formatting
Files changed: `app/src/main/java/com/novahorizon/wanderly/util/DateUtils.kt`, `app/src/main/java/com/novahorizon/wanderly/ui/MainViewModel.kt`, `app/src/main/java/com/novahorizon/wanderly/ui/MissionsViewModel.kt`, `app/src/main/java/com/novahorizon/wanderly/notifications/NotificationCheckCoordinator.kt`, `app/src/test/java/com/novahorizon/wanderly/util/DateUtilsTest.kt`
What was done: A shared `DateUtils.formatUtcDate(date: Date)` helper now owns the repeated UTC date formatting logic. The duplicate UTC formatters in the audited classes were replaced with that helper without changing the surrounding streak logic.
How to verify: Run the added `DateUtilsTest` and confirm UTC rollover formatting stays correct. Exercise streak and notification date logic around timezone boundaries if you want a higher-confidence smoke test.

### FIX-N3: Split AuthSessionCoordinator.kt
Files changed: `app/src/main/java/com/novahorizon/wanderly/auth/AuthCallbackMatcher.kt`, `app/src/main/java/com/novahorizon/wanderly/auth/AuthRouting.kt`, `app/src/main/java/com/novahorizon/wanderly/auth/AuthSessionCoordinator.kt`
What was done: The mixed auth utility file was split into three focused files, one per object, without changing their public names or behavior. Existing imports and tests continue to work against the new file structure.
How to verify: Open the `auth/` package and confirm each object lives in its own file. Run the auth unit tests and confirm they still pass.

### FIX-N4: Remove Dead Debug Panel From Layout
Files changed: `app/src/main/res/layout/fragment_missions.xml`
What was done: The hidden missions debug panel was removed from the layout entirely because it was dead UI in the production path. No fragment behavior depended on it.
How to verify: Open the missions layout and confirm the `dev_panel` block is gone. Launch the missions screen and confirm the main mission card layout still renders correctly.

### FIX-N5: Clean Up TODO/FIXME Noise
Files changed: `app/src/main/java/com/novahorizon/wanderly/WanderlyApplication.kt`, `app/src/main/res/xml/data_extraction_rules.xml`
What was done: The stale worker TODO comment in `WanderlyApplication.kt` was removed because the backoff behavior is already implemented. The sample/template extraction rules XML was replaced with a minimal valid config instead of the default Android Studio placeholder comments.
How to verify: Search those files for the old TODO/template comment text and confirm it is gone.

## Fixes NOT Applied And Why
- No audit fix was skipped. The only intentional implementation nuance is FIX-C3 in repository methods: existing method signatures were preserved instead of changing them to `Result<...>` return types, and the unsafe `!!` access was removed with guarded early returns so callers were not broken.
