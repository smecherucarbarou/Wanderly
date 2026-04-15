# Wanderly Codex Guide

## What this repo is
Wanderly is a native Android app written in Kotlin that blends location discovery, social competition, AI-generated recommendations, and mission-based progression. The app flow is:

1. `SplashActivity` decides whether the user goes to auth or the main app.
2. `AuthActivity` hosts login/signup and handles Supabase deep-link auth callbacks.
3. `MainActivity` hosts the primary navigation shell, keeps the normal bottom nav for all users, and admin-only tools are exposed from the profile screen when the signed-in profile has `admin_role = true`.
4. Feature fragments handle maps, gems, missions, social features, profile management, and a dev dashboard.

The active app code lives under `app/src/main/`. Treat that as the source of truth. Top-level files like `context.md`, `notification_paths.txt`, `data/WanderlyRepository.kt`, and `Test.kt` are helper/reference artifacts, not the main runtime path.

## Stack and runtime model
- Android app module only; Gradle Kotlin DSL build.
- Kotlin 2.2.x, Android Gradle Plugin 9.1.x, JVM toolchain 17.
- View-based UI with fragments, XML layouts, ViewBinding, and Navigation Component.
- Supabase powers auth, profile storage, friendships, and realtime updates.
- Gemini is used for text generation and AI-assisted copy/curation.
- osmdroid provides the map UI; Google Play Services provides device location.
- Google Places is used as the final verification layer for AI-generated locations.
- WorkManager plus a foreground service drive background streak/social notifications.

## Project layout
- `app/build.gradle.kts`: Android config, dependency wiring, and `BuildConfig` secrets from `local.properties`.
- `app/src/main/AndroidManifest.xml`: permissions, activities, `HiveRealtimeService`, `FileProvider`, and maps API metadata.
- `app/src/main/java/com/novahorizon/wanderly/`: app entry points, shared constants, utilities, and application bootstrap.
- `app/src/main/java/com/novahorizon/wanderly/api/`: Gemini, Supabase, and Google Places clients.
- `app/src/main/java/com/novahorizon/wanderly/data/`: repository and core models such as `Profile`, `Mission`, `Gem`, and `Friendship`.
- `app/src/main/java/com/novahorizon/wanderly/ui/`: feature fragments and view models.
- `app/src/main/java/com/novahorizon/wanderly/services/`: long-lived realtime listener service.
- `app/src/main/java/com/novahorizon/wanderly/workers/`: periodic background jobs.
- `app/src/main/res/`: layouts, drawables, navigation graphs, themes, and menu definitions.

## App architecture
The codebase follows a lightweight MVVM-ish structure:

- Activities are thin entry points.
- Fragments own UI orchestration and user interaction.
- ViewModels hold async UI state where a feature has enough complexity to justify one.
- `WanderlyRepository` is now a facade over smaller focused data classes:
  - `ProfileRepository`
  - `SocialRepository`
  - `DiscoveryRepository`
  - `PreferencesStore`
- Network integrations are split into focused objects:
  - `SupabaseClient` for auth/database/realtime bootstrapping.
  - `GeminiClient` for AI generation.
  - `PlacesGeocoder` for validating and enriching place results.
  - `AuthSessionCoordinator` for waiting on a resolved auth/session state during startup and background entry points.

There is no DI framework yet; repository/client instances are created directly from features.

## Feature map

### Entry and session flow
- `WanderlyApplication` initializes Supabase, configures osmdroid, creates notification channels, and schedules periodic workers.
- `SplashActivity` waits briefly, runs splash animation, then waits for a resolved auth state before forwarding users.
- `AuthActivity` hosts the auth nav graph and imports Supabase callback URLs from the canonical `wanderly://auth/callback` deep link.
- The manifest and auth handler also accept the legacy `wanderly://login/callback` host so already-issued links do not break.

### Main shell
- `MainActivity` hosts the main nav graph and bottom navigation.
- The bottom menu stays on the standard five-tab layout so `Hidden Gems` remains available.
- It waits for the auth state to resolve before configuring the bottom nav or starting `HiveRealtimeService`, and requests notification permission on Android 13+.

### Map
- `ui/map/MapFragment.kt` renders osmdroid, centers on the user, persists last known profile coordinates to Supabase, and overlays friend markers from social data.
- Mission generation now caches a verified mission target name plus coordinates in shared preferences, and the map pivots into a mission-preview state when that cache exists.

### Hidden gems
- `ui/gems/GemsFragment.kt` gets a precise location, reverse geocodes a city, fetches real nearby Overpass candidates with coordinates, and asks Gemini to curate from that numbered local list instead of inventing venue names from scratch.
- The screen now uses the exact local candidate name and coordinates for map launches, which avoids translation/alias mismatches between Gemini and Google Places.
- This feature is intentionally opinionated: it filters hard toward "high-vibe" venues and avoids civic/medical/lodging/irrelevant places.

### Missions
- `ui/missions/MissionsFragment.kt` drives mission generation, camera capture, mission verification, and completion flow.
- Mission state is exposed through `MissionsViewModel`.
- The fragment uses `FileProvider` and a temporary cache file to take photos for verification.
- Mission context is cached locally in shared preferences via repository constants.

### Social
- `ui/SocialFragment.kt` exposes two tabs: leaderboard and friends.
- Social data comes from Supabase through `WanderlyRepository`.
- Avatars are uploaded to Supabase Storage and the `avatar_url` profile field now stores a URL; UI code still supports legacy base64 avatars while older profiles transition.

### Profile
- `ui/profile/ProfileFragment.kt` loads and updates the current user profile, avatar, username, badges, streak/rank progress, and explorer class.
- Explorer class becomes a one-time choice after enough mission progress.
- Logout clears local preferences and signs out from Supabase.

### Dev tools
- `ui/profile/DevDashboardFragment.kt` is reachable from the profile nav graph and is intended for internal testing.
- Admin users get to it from an admin-only button on the profile screen instead of a special bottom-nav replacement.
- The dev dashboard now self-checks `profiles.admin_role` and navigates non-admin users away even if the destination is reached directly.
- It can mutate honey/streak values, trigger notification types, force workers, inspect raw profile JSON, reset dates, and run an AI notification test flow.

## Data and integrations

### Supabase
- Configured in `api/SupabaseClient.kt`.
- Uses `Postgrest`, `Auth`, and `Realtime`.
- Auth uses the custom URL scheme `wanderly` with canonical host `auth`.
- Primary known table from app constants: `profiles`.
- Friendship queries directly reference the `friendships` table name in repository code.
- Avatar uploads assume a public Supabase Storage bucket named `avatars`.

### Repository responsibilities
`data/WanderlyRepository.kt` is the highest-leverage file in the codebase. It currently handles:

- composition over the smaller repositories/stores listed above
- a stable facade for existing fragments/view models that still depend on `WanderlyRepository`

Because the repository owns both remote and local state, most feature work ends up touching it.

### Gemini
- `api/GeminiClient.kt` calls the Gemini REST API directly with `google_search` enabled.
- It expects raw JSON back and does minimal response shaping.
- Image verification and dev-dashboard AI testing also go through the same direct REST client, which avoids the Ktor runtime conflict introduced by the Google Android SDK dependency.

### Google Places
- `api/PlacesGeocoder.kt` is the trust boundary for venue verification.
- It rejects closed places, public-service locations, and places too far from the user.
- This filter is still important for mission/location verification, but hidden gems now lean primarily on local Overpass candidates plus Gemini curation to avoid alias mismatches.

### Maps and location
- osmdroid is used for rendering and user/friend markers.
- Google location APIs provide the actual device position.
- The app writes `last_lat` and `last_lng` into the current profile when movement is significant.

## Background behavior
- `workers/StreakWorker.kt`: periodic streak reminder logic, UTC date comparison, and time-of-day notification branching.
- `workers/SocialWorker.kt`: periodic leaderboard/friend polling and aggregated social alerts.
- `services/HiveRealtimeService.kt`: foreground realtime listener for profile updates and immediate rival/overtaken notifications. It re-fetches the current user profile during rival updates so overtaken checks do not drift off a stale startup snapshot.

`WanderlyApplication` enqueues the two workers every 15 minutes using unique periodic work with `KEEP`.

## Navigation
- Auth flow is defined in `app/src/main/res/navigation/auth_nav_graph.xml`.
- Main flow is defined in `app/src/main/res/navigation/nav_graph.xml`.
- Main destinations:
  - map
  - gems
  - missions
  - social
  - profile
  - dev dashboard

## Secrets and local setup
The app expects secrets in `local.properties`. The checked-in template shows these keys:

```properties
SUPABASE_URL=
SUPABASE_ANON_KEY=
GEMINI_API_KEY=
```

`app/build.gradle.kts` also reads `MAPS_API_KEY`, so local setup should include all four values:

```properties
SUPABASE_URL=...
SUPABASE_ANON_KEY=...
GEMINI_API_KEY=...
MAPS_API_KEY=...
```

Without these values, auth, AI, maps, and place verification will fail or partially degrade.

## Useful commands
Run these from the repo root:

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat test
```

If you are working from Android Studio, use the `app` run configuration and keep `local.properties` populated.

## Working conventions for future edits
- Prefer editing files under `app/src/main/`; that is the production code path.
- Check whether logic already exists in `WanderlyRepository` before adding a parallel data path.
- Preserve the current custom auth callback scheme unless backend auth config changes with it.
- Be careful with background behavior changes: workers, notifications, and realtime service overlap by design.
- Treat profile fields as app-wide shared state. Many features derive rank, streak, badges, missions, and map presence from the same record.
- When touching gems or missions, remember the app mixes AI output with deterministic validation; both sides matter.

## Current caveats
- There are visible in-progress edits across the worktree, so read before changing nearby files.
- A few legacy hardcoded strings may still need cleanup and migration into `strings.xml`.
- There is little formal test coverage in the repo; most confidence currently comes from manual/device validation.

## Good starting points by task
- Auth/session issues: `AuthActivity.kt`, `SplashActivity.kt`, `api/SupabaseClient.kt`
- Main app shell/navigation: `MainActivity.kt`, `nav_graph.xml`
- Profile/social bugs: `data/WanderlyRepository.kt`, `ui/SocialFragment.kt`, `ui/profile/ProfileFragment.kt`
- Mission flow: `ui/missions/MissionsFragment.kt`, `ui/MissionsViewModel.kt`
- Hidden gems quality: `ui/gems/GemsFragment.kt`, `api/GeminiClient.kt`, `api/PlacesGeocoder.kt`
- Map behavior: `ui/map/MapFragment.kt`
- Notifications/background behavior: `WanderlyApplication.kt`, `workers/`, `services/HiveRealtimeService.kt`, `notifications/WanderlyNotificationManager.kt`

## Bottom line
This codebase is a single-module Android app with most of its complexity concentrated in a few seams: auth/session bootstrapping, `WanderlyRepository`, AI plus Places validation, and background notification logic. When in doubt, trace a feature from fragment -> view model -> repository/client -> shared profile state.
