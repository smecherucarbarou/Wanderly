# Wanderly Fixes and Improvements

This file is based on a scan of the current codebase and is meant to be an actionable backlog. It mixes bugs, architectural cleanup, UX improvements, and maintenance work.

## Highest-priority fixes

### 1. Stop logging secrets in `GeminiClient`
File: `app/src/main/java/com/novahorizon/wanderly/api/GeminiClient.kt`

Status:
- Fixed. Request URL logging was removed, prompt/response/error-body logging is debug-gated and sanitized, and release builds no longer emit Gemini exception stack traces by default.

Why it matters:
- The code logs `finalUrl`, which includes the Gemini API key in the query string.
- It also logs prompts and partial responses, which can leak user/location data and clutter production logs.

What to change:
- Remove the URL log entirely.
- Avoid logging raw prompts/responses in production.
- Gate debug logging behind `BuildConfig.DEBUG`.

### 2. Fix the auth deep-link mismatch
Files:
- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/novahorizon/wanderly/api/SupabaseClient.kt`
- `app/src/main/java/com/novahorizon/wanderly/AuthActivity.kt`

Status:
- Fixed. Canonical callback handling is now `wanderly://auth/callback`, with legacy `wanderly://login/callback` still accepted for backward compatibility.

Why it matters:
- The manifest deep link uses `wanderly://auth/callback`.
- `SupabaseClient` configures auth with `scheme = "wanderly"` and `host = "login"`.
- `AuthActivity` only accepts URIs where `uri.host == "auth"`.

This mismatch can break auth callbacks depending on what Supabase actually sends back.

What to change:
- Pick one callback format and use it everywhere.
- Keep manifest, Supabase auth config, and `AuthActivity` URI checks in sync.

### 3. Fix the mission/map preference mismatch
Files:
- `app/src/main/java/com/novahorizon/wanderly/ui/map/MapFragment.kt`
- `app/src/main/java/com/novahorizon/wanderly/data/WanderlyRepository.kt`
- `app/src/main/java/com/novahorizon/wanderly/Constants.kt`

Status:
- Fixed. Mission generation now resolves and persists verified target coordinates, shared preference keys are centralized in `Constants`, the map reads the shared keys through the repository, and mission completion clears the active mission cache.

Why it matters:
- `MapFragment` looks for `mission_target_lat` and `mission_target_lng`.
- `WanderlyRepository.saveMissionData()` never stores those values.
- As written, the "active mission on map" behavior cannot work reliably.

What to change:
- Either persist target coordinates when the mission is created, or remove the map-side expectation.
- Add constants for all mission-related preference keys instead of using raw strings.

### 4. Remove hardcoded developer gating
Files:
- `app/src/main/java/com/novahorizon/wanderly/MainActivity.kt`
- `app/src/main/res/menu/bottom_nav_menu_dev.xml`
- `app/src/main/java/com/novahorizon/wanderly/ui/profile/DevDashboardFragment.kt`

Status:
- Fixed on the Android client. Dev/admin access now reads `profiles.admin_role` instead of a hardcoded email, and the dashboard fragment also rejects non-admin users if they somehow reach the destination directly.

Why it matters:
- Developer access is tied to a hardcoded email address.
- That is brittle, hard to maintain, and easy to forget in production builds.

What to change:
- Gate dev tools by build type, feature flag, or explicit admin role from the backend.
- Hide or disable the dev dashboard entirely in release builds.

### 5. Fix rank as a split source of truth
Files:
- `app/src/main/java/com/novahorizon/wanderly/data/Profile.kt`
- `app/src/main/java/com/novahorizon/wanderly/data/WanderlyRepository.kt`
- `app/src/main/java/com/novahorizon/wanderly/ui/MissionsViewModel.kt`
- `app/src/main/java/com/novahorizon/wanderly/ui/missions/MissionsFragment.kt`
- `app/src/main/java/com/novahorizon/wanderly/ui/profile/ProfileFragment.kt`
- `app/src/main/java/com/novahorizon/wanderly/ui/SocialFragment.kt`

Status:
- Fixed. Rank is now derived from honey through shared helper logic, profile writes are normalized before saving, and the affected screens read the derived rank instead of trusting stale `hive_rank` values.

Why it matters:
- Some screens use `profile.hive_rank`.
- Other screens derive rank from honey totals.
- `hive_rank` is created on profile creation but never recalculated.

That means mission radius, UI progress, and displayed rank can drift out of sync.

What to change:
- Make rank derived from honey everywhere, or update `hive_rank` centrally whenever honey changes.
- Do not maintain both unless there is a clear reason.

### 6. Clean up the encoding/mojibake issues
Files:
- multiple Kotlin and XML files

Status:
- In progress. The most visible corrupted startup/profile/social strings have been cleaned and more UI copy is now sourced from `strings.xml`, but a full sweep is still worth doing.

Why it matters:
- Several strings and logs display corrupted characters.
- This hurts polish, readability, and user-facing trust.

What to change:
- Normalize affected files to UTF-8.
- Replace corrupted literals with clean ASCII or valid Unicode.
- Move more strings into `strings.xml` instead of embedding them directly in Kotlin.

## Important functional improvements

### 7. Move avatars out of the `profiles` row
Files:
- `app/src/main/java/com/novahorizon/wanderly/ui/profile/ProfileFragment.kt`
- `app/src/main/java/com/novahorizon/wanderly/ui/SocialFragment.kt`
- `app/src/main/java/com/novahorizon/wanderly/data/Profile.kt`

Status:
- Fixed on the Android client. New avatar uploads go to Supabase Storage and `avatar_url` now stores a URL instead of base64 image data; UI rendering remains backward-compatible with older base64 avatars already stored in profiles.

Why it matters:
- Avatars are stored as base64 blobs in `avatar_url`.
- That inflates profile payloads, slows leaderboard/friends loads, and increases DB/storage pressure.

What to change:
- Upload images to Supabase Storage.
- Store a public or signed URL in the profile.
- Keep resizing/compression client-side before upload.

### 8. Strengthen mission verification
Files:
- `app/src/main/java/com/novahorizon/wanderly/ui/MissionsViewModel.kt`
- `app/src/main/java/com/novahorizon/wanderly/ui/missions/MissionsFragment.kt`

Why it matters:
- Mission verification currently relies on Gemini judging a photo against text like "Does this image show X in Y?"
- That is fragile and can create false positives or false negatives.

What to change:
- Tie missions to a verified place ID and coordinates.
- Check user proximity before allowing verification.
- Use photo verification as a secondary signal, not the only one.

### 9. Reduce background alert overlap
Files:
- `app/src/main/java/com/novahorizon/wanderly/workers/SocialWorker.kt`
- `app/src/main/java/com/novahorizon/wanderly/workers/StreakWorker.kt`
- `app/src/main/java/com/novahorizon/wanderly/services/HiveRealtimeService.kt`
- `app/src/main/java/com/novahorizon/wanderly/notifications/WanderlyNotificationManager.kt`

Why it matters:
- Notifications come from both periodic workers and a realtime foreground service.
- Cooldown logic helps, but the overall behavior is still spread across multiple sources.

What to change:
- Decide which alerts are realtime and which are periodic fallback alerts.
- Centralize notification policy so each alert type has one owner.

### 10. Fix stale profile assumptions in realtime logic
File: `app/src/main/java/com/novahorizon/wanderly/services/HiveRealtimeService.kt`

Status:
- Fixed. The realtime service now reloads the current user profile while handling incoming rival updates instead of comparing against a one-time startup snapshot.

Why it matters:
- The service captures one `profile` snapshot and compares rival updates against it.
- If the user's honey changes later, the service may keep using stale values for overtaken logic.

What to change:
- Refresh the current user profile when needed, or subscribe to the current user state as well.
- Avoid long-lived comparison logic based on a one-time snapshot.

### 11. Improve auth/session startup consistency
Files:
- `app/src/main/java/com/novahorizon/wanderly/AuthActivity.kt`
- `app/src/main/java/com/novahorizon/wanderly/MainActivity.kt`
- `app/src/main/java/com/novahorizon/wanderly/WanderlyApplication.kt`

Status:
- Fixed for the Android startup paths and workers. `SplashActivity`, `AuthActivity`, `MainActivity`, `SocialWorker`, and `StreakWorker` now wait for a resolved auth state through a shared coordinator instead of mixing immediate checks with ad-hoc delays.

Why it matters:
- Some places correctly wait for auth readiness.
- Other places still use immediate session checks.
- This creates race-condition risk during cold app startup.

What to change:
- Standardize on one auth-ready pattern.
- Wait for a resolved auth state before starting session-dependent flows like the realtime service.

### 12. Make mission persistence less local-only
Files:
- `app/src/main/java/com/novahorizon/wanderly/data/WanderlyRepository.kt`
- `app/src/main/java/com/novahorizon/wanderly/data/Mission.kt`
- `app/src/main/java/com/novahorizon/wanderly/ui/MissionsViewModel.kt`

Why it matters:
- Mission state is stored mostly in shared preferences.
- There is a `Mission` model, but it is not really part of the live mission flow.

What to change:
- Persist active/completed missions in Supabase.
- Use local cache for convenience, not as the source of truth.

## Architecture improvements

### 13. Split `WanderlyRepository`
File: `app/src/main/java/com/novahorizon/wanderly/data/WanderlyRepository.kt`

Status:
- Partially fixed. `WanderlyRepository` is now a facade over `ProfileRepository`, `SocialRepository`, `DiscoveryRepository`, and `PreferencesStore`, which removes several unrelated responsibilities from the old single file while preserving the current call sites.

Why it matters:
- It handles profile CRUD, friendships, Overpass queries, shared preferences, mission caching, and testing helpers.
- This file is becoming the central gravity well of the app.

What to change:
- Break it into smaller units such as:
  - `ProfileRepository`
  - `SocialRepository`
  - `MissionRepository`
  - `DiscoveryRepository`
  - `PreferencesStore`

### 14. Introduce dependency injection or at least shared singletons
Files:
- multiple activities, fragments, view models

Why it matters:
- Many screens create new `WanderlyRepository(requireContext())` instances directly.
- This makes testing harder and encourages inconsistent state ownership.

What to change:
- Add Hilt/Koin, or start small with a manual app container.
- Inject repositories/clients into view models instead of creating them in fragments.

### 15. Standardize on `StateFlow`/UI state instead of mixed patterns
Files:
- multiple view models and fragments

Why it matters:
- The app mixes `LiveData`, `StateFlow`, direct repository collection, and raw fragment-level async logic.
- That makes state harder to reason about.

What to change:
- Pick a consistent pattern for new work.
- Prefer a single immutable UI state per screen where possible.

### 16. Move business rules out of fragments
Files:
- `ui/gems/GemsFragment.kt`
- `ui/map/MapFragment.kt`
- `ui/profile/ProfileFragment.kt`
- `ui/missions/MissionsFragment.kt`

Why it matters:
- A lot of feature logic currently lives directly in fragments.
- That makes testing and reuse harder.

What to change:
- Move filtering, validation, rank calculations, badge logic, and mission orchestration into view models or domain classes.

### 17. Replace stringly-typed keys and table names with stronger models
Files:
- `Constants.kt`
- `WanderlyRepository.kt`
- several feature files

Why it matters:
- The code still uses raw strings for several pref keys, table names, and behavior switches.
- That caused at least one real mismatch already in the mission/map flow.

What to change:
- Expand constants coverage.
- Prefer typed wrappers for prefs and remote payloads.

## Code and config cleanup

### 18. Remove duplicate dependency declarations
File: `app/build.gradle.kts`

Status:
- Fixed. The duplicate `implementation(libs.androidx.work.runtime.ktx)` entry was removed.

What to change:
- Remove the second `implementation(libs.androidx.work.runtime.ktx)`.

### 19. Fix `local.properties.template`
File: `local.properties.template`

Status:
- Fixed. The template now includes `MAPS_API_KEY`, matching the Gradle `BuildConfig` expectations.

What to change:
- Add `MAPS_API_KEY`.
- Consider documenting which features break when each key is missing.

### 20. Remove stale or misleading files
Files:
- `data/WanderlyRepository.kt`
- `Constants.kt` at repo root
- `app/src/main/java/com/novahorizon/wanderly/Test.kt`
- `context.md` if it drifts from the code

Why it matters:
- These files make the repo harder to trust because not all of them are part of the real runtime path.

What to change:
- Delete them if obsolete, or move them into a clear `docs/` or `scratch/` location.

### 21. Clean unused code and imports
Examples:
- `PlacesGeocoder.normalize()` is unused.
- Some imports and debug comments are stale.
- `Mission` exists but is not fully integrated.

What to change:
- Run cleanup passes regularly.
- Add linting to catch this automatically.

### 22. Move hardcoded UI strings into resources
Files:
- multiple fragments and adapters

Status:
- Started. Social/profile copy touched during the cleanup pass now comes from `strings.xml`, including friend-code actions, profile stat labels, and several status messages.

Why it matters:
- There are still many hardcoded strings in Kotlin.
- That hurts localization, consistency, and text cleanup.

What to change:
- Put user-facing strings in `strings.xml`.
- Keep logs/debug text separate from UI copy.

### 23. Hide debug affordances from production UI
Files:
- `app/src/main/res/layout/fragment_missions.xml`
- `DevDashboardFragment.kt`

Why it matters:
- There are visible debug affordances in app resources and dev tooling paths in the main app module.

What to change:
- Guard debug UI by build type.
- Consider a separate debug-only source set for internal tools.

## Product and UX improvements

### 24. Improve the friend-add flow
Files:
- `ui/SocialFragment.kt`
- `data/WanderlyRepository.kt`

Status:
- Partially fixed. The social UI now consistently refers to friend codes, and the profile screen lets users tap their own friend code to copy it quickly.

What to change:
- Rename variables/UI from "username" to "friend code" where that is the real input.
- Add better error messages and success states.
- Consider showing the current user's friend code with copy/share actions.

### 25. Make friend codes collision-safe
File: `data/WanderlyRepository.kt`

Why it matters:
- Friend codes are generated by truncating a UUID to 6 uppercase characters.
- That is simple but not guaranteed unique over time.

What to change:
- Enforce uniqueness server-side.
- Retry on collision or use a stronger generation strategy.

### 26. Improve gem generation resilience
Files:
- `ui/gems/GemsFragment.kt`
- `api/GeminiClient.kt`
- `api/PlacesGeocoder.kt`

What to change:
- Add better fallback behavior if Gemini returns malformed JSON.
- Cache recent verified results by area.
- Consider using place IDs, ratings, and review count to rank results instead of just "first valid match wins."

### 27. Make badges and class progression explicit domain logic
File: `ui/profile/ProfileFragment.kt`

Why it matters:
- Badge unlocking and class milestones are currently tied to UI loading.
- That means profile viewing can trigger writes and progression changes.

What to change:
- Move badge/class unlock evaluation into a domain/service layer or backend trigger.
- Keep the profile screen mostly read-only in behavior.

### 28. Improve map/social performance
Files:
- `ui/map/MapFragment.kt`
- `ui/SocialFragment.kt`

What to change:
- Replace `notifyDataSetChanged()` adapters with `ListAdapter` and `DiffUtil`.
- Reuse drawables/bitmaps where possible instead of recreating them for each marker bind.
- Avoid decoding large avatar payloads on every list refresh.

## Quality and team velocity improvements

### 29. Add real test coverage
Good candidates:
- auth error mapping in `AuthViewModel`
- streak logic in `MainViewModel`
- mission completion math in `MissionsViewModel`
- friend add/remove flows in `WanderlyRepository`
- Places filtering rules in `PlacesGeocoder`
- worker notification conditions

Status:
- Started. Added unit tests for auth callback matching, auth routing decisions, and honey-to-rank derivation.

### 30. Add static checks and formatting
What to change:
- Add Android Lint, ktlint or detekt, and a formatting step in CI.
- Use them to catch unused code, string issues, duplicate dependencies, and inconsistent nullability patterns.

### 31. Add a lightweight docs folder
What to change:
- Keep `codex.md` as the agent-oriented guide.
- Add a `docs/` folder for architecture notes, backend schema assumptions, auth callback rules, and notification behavior.

## Suggested implementation order

### Phase 1: Fix now
- remove secret logging in `GeminiClient`
- align auth deep-link configuration
- fix mission target coordinate persistence
- remove duplicate WorkManager dependency
- add `MAPS_API_KEY` to the template
- clean mojibake in UI strings

### Phase 2: Stabilize behavior
- unify rank calculation
- improve auth-ready startup flow
- reduce notification source overlap
- fix stale profile assumptions in realtime service
- hide dev tooling in release builds

### Phase 3: Improve architecture
- split `WanderlyRepository`
- move mission/profile progression logic out of fragments
- introduce DI/app container
- move mission state to backend-backed storage
- move avatars to Supabase Storage

### Phase 4: Quality and polish
- remove stale files and dead code
- add tests
- add lint/format checks
- improve gem ranking and mission verification quality

## Bottom line
If you only change five things first, change these:

1. Remove API key logging from `GeminiClient`.
2. Fix the auth callback mismatch.
3. Fix the broken mission target coordinate flow used by `MapFragment`.
4. Unify rank so `hive_rank` and honey-based rank cannot drift.
5. Move avatars out of base64-in-profile storage.
