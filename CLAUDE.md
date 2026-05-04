# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

```bash
# Debug build
./gradlew assembleDebug

# Release build (requires signing config via env vars or local.properties)
./gradlew assembleRelease

# Run all unit tests
./gradlew testDebugUnitTest

# Run a single test class
./gradlew testDebugUnitTest --tests "com.novahorizon.wanderly.data.ProfileRepositoryPayloadTest"

# Run a single test method
./gradlew testDebugUnitTest --tests "com.novahorizon.wanderly.data.ProfileRepositoryPayloadTest.testMethod"

# Lint
./gradlew lintDebug

# Coverage report (Kover, enforces ≥15% line coverage on debug)
./gradlew koverVerifyDebug

# Release regression check (blocks exact alarms, aggressive widget refresh)
./gradlew releaseRegressionCheck

# Validate release config (Supabase URL/key must be present and HTTPS)
./gradlew validateReleaseConfig

# APK size report
./gradlew apkSizeReport

# Build + install via batch script (Windows, interactive device selection)
build_and_install.bat
```

## Local Setup

Copy `local.properties.template` → `local.properties` and fill in real values. Build settings resolve in order: Gradle properties → environment variables → `local.properties`. Firebase Crashlytics only activates when `app/google-services.json` is present.

Required secrets for Edge Functions (set via `supabase secrets set`):
- `GEMINI_API_KEY` — Google Gemini API key
- `MAPS_API_KEY` — Google Places API (New) key

## Architecture

**MVVM + Repository pattern with Hilt DI.** Kotlin, View-based UI (no Compose), ViewBinding.

- **Fragments** observe **ViewModels** (Hilt-injected, LiveData/Flow) → **Repositories** → API clients / DataStore
- Navigation: AndroidX Navigation Component with `nav_graph.xml` (main) and `auth_nav_graph.xml`
- DI modules in `di/`: `SupabaseModule`, `NetworkModule`, `RepositoryModule`, `ConfigModule`, `MissionModule`
- Startup flow: `SplashActivity` → `WanderlyApplication` (Hilt `@HiltAndroidApp`) → `MainActivity` → nav graph routes based on auth/onboarding state via `AuthRouting`

### Data Layer

- **Supabase** (Kotlin SDK v3.5.0): auth (GoTrue), database (PostgREST), realtime subscriptions, storage
- **Edge Functions** (TypeScript/Deno, in `supabase/functions/`): `gemini-proxy` (Gemini AI for mission generation + photo verification), `google-places-proxy` (Google Places for discovery)
- **Overpass API**: OSM data queries for map features (4 mirror endpoints with fallback)
- **DataStore/SharedPreferences**: local preferences, notification state, mission cache (no Room)
- **Migrations**: SQL in `supabase/migrations/`, deployed via Supabase CLI

### Security Model

- Edge Functions validate Supabase JWT, enforce request allowlists, sanitize upstream responses, and apply 30s timeouts
- Sensitive profile mutations (`honey`, `streak_count`, location) use SECURITY DEFINER RPCs — never direct UPDATE
- Column-level GRANTs restrict `authenticated` role to user-editable fields only
- RLS policies enforce row ownership; admin operations require `is_current_profile_admin()` check
- Avatar uploads strip EXIF metadata via bitmap recompression
- All backup/data extraction excluded via XML rules

### Background Work

- `StreakWorker` and `SocialWorker`: periodic WorkManager tasks (15-min intervals)
- `HiveRealtimeService`: foreground service for real-time data sync via Supabase Realtime
- `WanderlyStreakWidgetProvider`: home screen widget (hourly refresh via `AlarmManager`)
- Streak notifications are suppressed in debug builds

### Key Packages (under `com.novahorizon.wanderly`)

| Package | Purpose |
|---------|---------|
| `api/` | HTTP clients: Supabase, Gemini proxy, Places proxy, Overpass |
| `auth/` | Auth repository, session coordination, deep-link callback routing |
| `data/` | Repositories, models, data sources (Google Places, Overpass) |
| `data/mission/` | Mission generation pipeline: Gemini candidates → Places geocoding → selection |
| `di/` | Hilt modules |
| `ui/` | Fragments + ViewModels by feature: auth, map, missions, profile, social, gems, onboarding |
| `services/` | HiveRealtimeService |
| `workers/` | WorkManager workers (Streak, Social) |
| `streak/` | Streak evaluation logic (DailyStreakStatusEvaluator) |
| `widgets/` | Widget provider, alarm scheduler, state helpers, tier rendering |
| `notifications/` | Notification rules, coordinator, manager, state store |
| `observability/` | AppLogger, CrashReporter, StrictMode (debug only) |

## Testing

Unit tests use **JUnit 4 + Robolectric + coroutines-test**. Hilt testing support is available via `hilt-android-testing`. Instrumented tests (in `androidTest/`) use Espresso + UIAutomator.

Tests are in `app/src/test/` mirroring the main source structure. Test categories:
- **Behavioral**: ViewModel logic, repository payloads, streak evaluation, widget state
- **Architectural**: manifest entries, source patterns, RPC contract alignment, permission declarations
- **Security**: Edge Function auth/sanitization contracts, release regression checks

## CI

GitHub Actions (`.github/workflows/ci.yml`): runs on push to main/develop and PRs.

Pipeline steps: unit tests → instrumented tests (emulator API 35) → lint → release regression check → coverage verification → APK size report → debug APK → release APK/AAB (signed if secrets available).

Security: `permissions: contents: read`, Gradle wrapper validation, Maven content filtering for JitPack/OPF.

## Supabase Edge Functions

TypeScript functions in `supabase/functions/`. Each has its own `index.ts`.

- `gemini-proxy`: Proxies to Gemini API (`gemini-3-flash-preview` with `gemini-2.5-flash` fallback). Supports text generation and vision (photo verification). Includes request sanitization, response allowlisting, 30s timeout, and quota tracking.
- `google-places-proxy`: Proxies to Google Places API (New). Allowlists request fields (`textQuery`, `locationBias`, `locationRestriction`, `includedType`, `maxResultCount` capped at 20). Sanitizes responses to only return `places` array.

Deploy: `npx supabase functions deploy <function-name>`

## Supabase Migrations

SQL migrations in `supabase/migrations/`. Key RPCs (all SECURITY DEFINER):
- `complete_mission` — awards honey, manages streak, prevents duplicate completions
- `update_profile_location` — validates coordinates, updates lat/lng
- `accept_streak_loss` — resets streak to 0
- `restore_streak` — pay honey to restore (validates cost, eligibility)
- `admin_update_profile_stats` — admin-only honey/streak/rank override
- `update_profile_username` — validates and updates username
- `consume_api_quota` — rate limiting for Edge Function calls

Deploy: `npx supabase db push`

## Conventions

- Data classes mapping to Supabase use `snake_case` property names matching column names
- ViewModels expose state via `LiveData` or `StateFlow`; one-shot events via `LiveData`
- Repositories use `withContext(Dispatchers.IO)` for all network/disk operations
- Error handling: `CancellationException` is always re-thrown; non-fatal errors go to `CrashReporter`
- UI text: use `UiText.resource(R.string.*)` — never expose raw exception messages to users
- Notifications: cooldown-based dedup via DataStore timestamps
- Profile mutations that touch `honey`/`streak_count`/`hive_rank` must go through RPCs, not direct PostgREST UPDATE
