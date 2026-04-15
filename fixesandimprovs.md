# Wanderly Fixes and Improvements

This is a focused review of the current codebase based on the main app flows, recent logcat warnings, and the files most involved in profile, missions, social, gems, and app startup.

It is not a full static audit of every line in the repo, but it does cover the highest-signal issues worth fixing next.

## Highest Priority

### 1. Gemini API key is bundled into the Android app

Files:
- `app/build.gradle.kts`

Problem:
- `GEMINI_API_KEY` is injected into `BuildConfig`, which means the key ships inside the APK and can be extracted.
- This is fine for public client keys like Supabase publishable/anon, but not for a privileged AI key that can burn quota and be abused.

Fix:
- Move Gemini calls behind a backend, Edge Function, or your own API.
- Keep only a public client key in the app.
- Add server-side rate limiting and request validation.

Why it matters:
- This is the biggest security risk in the current app.

### 2. Repository instances are recreated all over the app and hold raw `Context`

Files:
- `app/src/main/java/com/novahorizon/wanderly/data/WanderlyRepository.kt`
- `app/src/main/java/com/novahorizon/wanderly/ui/WanderlyViewModelFactory.kt`
- multiple fragments/workers/services creating `WanderlyRepository(requireContext())`

Problem:
- Many screens create their own `WanderlyRepository`.
- `WanderlyRepository` stores `val context: Context`, and most callers pass an Activity/Fragment context.
- This makes state sharing inconsistent and increases the chance of leaking short-lived contexts into longer-lived objects.

Fix:
- Use `applicationContext` inside the repository layer.
- Introduce a single shared repository graph or DI container.
- Prefer one shared profile/session source instead of one repository instance per screen.

Why it matters:
- It causes duplicate network work, harder-to-reason state, and potential lifecycle bugs.

### 3. RecyclerView is embedded in a `NestedScrollView` and adapters are attached late

Files:
- `app/src/main/res/layout/fragment_profile.xml`
- `app/src/main/java/com/novahorizon/wanderly/ui/profile/ProfileFragment.kt`

Problem:
- `badges_recycler` is inside a `NestedScrollView`.
- The adapter and layout manager are only assigned inside `updateUI()`, after profile data arrives.
- This lines up with the runtime warnings:
  - `RecyclerView: No adapter attached; skipping layout`
  - `RecyclerView does not support scrolling to an absolute position`

Fix:
- Avoid putting `RecyclerView` inside `NestedScrollView` if possible.
- If the profile page must scroll as one sheet, consider replacing the badges list with a simple flex/grid container for small badge counts.
- At minimum, assign an empty adapter and layout manager in `onViewCreated()` before data loads.
- Do not recreate the adapter on every profile refresh.

Why it matters:
- This is causing visible log noise and can contribute to bad scroll behavior and unnecessary layout work.

### 4. App startup does too much work on the main thread

Files:
- `app/src/main/java/com/novahorizon/wanderly/WanderlyApplication.kt`

Problem:
- `SupabaseClient.init(this)`, notification channel setup, OSMDroid config, and background worker scheduling all happen during app startup.
- This lines up with the log warning:
  - `Skipped 85 frames! The application may be doing too much work on its main thread.`

Fix:
- Keep only truly critical startup work in `Application.onCreate()`.
- Delay non-essential setup until first use.
- Consider lazy init for Supabase and defer worker scheduling until after the first frame or first authenticated session.

Why it matters:
- Startup jank is one of the easiest ways to make the app feel slow even when features work.

## Runtime Warnings Worth Fixing

### 5. Glide compiler is present, but there is no `AppGlideModule`

Files:
- `app/build.gradle.kts`
- `app/src/main/java/com/novahorizon/wanderly/ui/AvatarLoader.kt`

Problem:
- Logcat shows:
  - `Failed to find GeneratedAppGlideModule`
- You already include `kapt(libs.glide.compiler)`, but there is no `@GlideModule` implementation in the app.

Fix:
- Either add a minimal `AppGlideModule`, or remove the compiler dependency if you do not need generated APIs/custom config.

Why it matters:
- Not a crash, but it is unnecessary warning noise and unclear configuration.

### 6. SLF4J provider is missing

Problem:
- Logcat shows:
  - `SLF4J(W): No SLF4J providers were found`

Fix:
- Add an Android-compatible SLF4J binding if one of your dependencies expects it, or accept the warning if logging from that dependency is not needed.

Why it matters:
- Low severity, but it makes debugging harder because library logs are discarded.

### 7. OSMDroid/user-agent related log spam should be reviewed

Files:
- `app/src/main/java/com/novahorizon/wanderly/WanderlyApplication.kt`

Problem:
- You already set:
  - `Configuration.getInstance().userAgentValue = packageName`
- That is better than the default, but the heavy repeated package-name related log traffic suggests the mapping/network layer is still noisy.

Fix:
- Keep a custom, stable, descriptive user-agent like `Wanderly/1.0 (Android)`.
- Recheck OSMDroid cache/network settings once the rest of the app is stable.

Why it matters:
- This is more of a cleanup/perf investigation item than a confirmed bug.

## Code Issues and Logic Improvements

### 8. `addFriendByCode()` uses `ilike` instead of exact match

Files:
- `app/src/main/java/com/novahorizon/wanderly/data/SocialRepository.kt`

Problem:
- Friend codes are normalized to uppercase and appear to be exact 6-character IDs.
- The repository still uses `ilike("friend_code", friendCode)`.

Risk:
- Partial or ambiguous matches are easier than they should be.

Fix:
- Use exact matching (`eq`) after normalizing case.
- Keep the DB unique index on `friend_code`.

### 9. Profile UI rebuilds its badges list from scratch on every refresh

Files:
- `app/src/main/java/com/novahorizon/wanderly/ui/profile/ProfileFragment.kt`

Problem:
- `GridLayoutManager` and `BadgesAdapter` are recreated inside `updateUI()`.

Fix:
- Initialize the layout manager once in `onViewCreated()`.
- Reuse one adapter and only update its data.

Why it matters:
- Cleaner lifecycle, fewer object allocations, easier future animations/state restoration.

### 10. `deleteOnExit()` is not a real Android cleanup strategy

Files:
- `app/src/main/java/com/novahorizon/wanderly/ui/missions/MissionsFragment.kt`

Problem:
- Temporary camera file uses `deleteOnExit()`.
- On Android this is not reliable in the same way it is on desktop JVM apps.

Fix:
- Manually delete temp files after upload/verification or on next startup cleanup.

Why it matters:
- Low severity, but temp file buildup is avoidable.

### 11. Mojibake/encoding corruption is present in `MissionsViewModel`

Files:
- `app/src/main/java/com/novahorizon/wanderly/ui/MissionsViewModel.kt`

Problem:
- There are corrupted characters in comments and strings like:
  - `BUG E â€”`
  - `ðŸ”¥`
  - `ðŸ`

Fix:
- Re-save the file as UTF-8.
- Replace corrupted literals with either proper UTF-8 text or plain ASCII.

Why it matters:
- It makes the codebase look unstable and can leak ugly text into user-facing UI.

### 12. `ProfileFragment` mixes UI refresh, badge unlock logic, and persistence too tightly

Files:
- `app/src/main/java/com/novahorizon/wanderly/ui/profile/ProfileFragment.kt`

Problem:
- `loadProfile()`, `checkAndUnlockBadges()`, `updateUI()`, and profile persistence are all interwoven inside the fragment.

Fix:
- Move badge computation and profile mutation logic into a ViewModel or repository/domain layer.
- Keep the fragment responsible only for rendering and user interaction.

Why it matters:
- This screen will get harder to maintain as profile features grow.

## Build, Release, and Repo Hygiene

### 13. Release build is not hardened yet

Files:
- `app/build.gradle.kts`

Problem:
- `isMinifyEnabled = false` in release.

Fix:
- Enable R8/ProGuard once core flows are stable.
- Add keep rules only where needed.

Why it matters:
- Smaller APK, harder reverse engineering, less exposed app internals.

### 14. IDE metadata is tracked in git

Files:
- `.idea/*`

Problem:
- The repo currently tracks many Android Studio project files.

Fix:
- Keep only the minimal shared IDE config if truly needed.
- Remove machine-specific/editor-noisy files from source control.

Why it matters:
- Less git noise, fewer accidental diffs, easier collaboration.

## Suggested Improvement Roadmap

### Quick wins

1. Move Gemini off-device.
2. Add a minimal `AppGlideModule` or remove the Glide compiler.
3. Attach RecyclerView adapters early and remove the profile `RecyclerView` from `NestedScrollView`.
4. Replace `ilike` friend-code lookup with exact match.
5. Clean UTF-8 corruption in `MissionsViewModel`.

### Medium effort

1. Rework repository creation so screens share a single app-level repository graph.
2. Convert profile and social screens to stable adapters initialized once.
3. Defer non-critical startup work out of `Application.onCreate()`.

### Longer-term upgrades

1. Introduce DI (Hilt/Koin/manual container).
2. Split business logic out of fragments into ViewModels/use-cases.
3. Add a proper backend boundary for AI and other privileged APIs.
4. Add release hardening and a lightweight QA/lint checklist before shipping.

## Best Next Step

If I were prioritizing purely by impact, I would do the next work in this order:

1. Remove Gemini key from the client.
2. Fix profile screen RecyclerView/layout warnings.
3. Refactor repository/context ownership.
4. Clean startup work in `WanderlyApplication`.
5. Clean low-level warning noise like Glide and SLF4J.
