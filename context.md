# Wanderly — Project Context

Last updated: 2026-05-05

## 1. Overview

**Wanderly** is a gamified urban exploration Android app. Users generate AI-powered local photo missions, verify them with their camera, earn Honey currency, build streaks, unlock ranks/badges/explorer classes, discover curated nearby hidden gems, and compete socially with friends on a leaderboard.

Tagline: *Pollinate the World through Adventure!*

| Property | Value |
|----------|-------|
| Package | `com.novahorizon.wanderly` |
| Min SDK | 26 |
| Target SDK | 36 |
| Compile SDK | 36 |
| Version | 1.0 (code 1) |
| Language | Kotlin |
| UI | Views + ViewBinding (no Compose) |
| Architecture | MVVM + Repository + Hilt DI |
| Modules | `:app`, `:baselineprofile` |

## 2. Tech Stack

| Category | Technology | Version |
|----------|-----------|---------|
| Build | Gradle (Kotlin DSL) | 9.3.1 |
| Android Plugin | AGP | 9.1.0 |
| Kotlin | kotlin-android | 2.3.20 |
| DI | Hilt (Dagger) | 2.59.1 |
| Serialization | kotlinx-serialization-json | 1.11.0 |
| Coroutines | kotlinx-coroutines-android | 1.10.2 |
| Navigation | AndroidX Navigation | 2.9.8 |
| Lifecycle | AndroidX Lifecycle | 2.10.0 |
| Network | OkHttp + Ktor (Supabase) | 4.12.0 / 3.4.3 |
| Backend | Supabase Kotlin SDK | 3.5.0 |
| Map | osmdroid | 6.1.20 |
| Location | Play Services Location | 21.3.0 |
| Images | Glide | 4.16.0 |
| Crop | UCrop | 2.2.8 |
| Background | WorkManager | 2.11.2 |
| Analytics | Firebase (BOM) | 34.7.0 |
| Coverage | Kover | 0.9.8 |
| Testing | JUnit 4 + Robolectric + coroutines-test | 4.13.2 / 4.16.1 |
| UI Material | Material Components | 1.13.0 |

## 3. Architecture

```
Fragment → ViewModel (Hilt) → Repository → API Client / DataStore / Supabase SDK
                                         → Edge Function (Deno/TS) → External API
```

### Layers

- **UI**: Fragments with ViewBinding, observe ViewModels via LiveData/StateFlow
- **Presentation**: ViewModels (Hilt-injected), expose state and handle user actions
- **Domain**: Repositories aggregate data sources, enforce business rules
- **Data**: Supabase PostgREST, Supabase Storage, Edge Functions, Overpass API, SharedPreferences/DataStore
- **Background**: WorkManager periodic tasks, Foreground Service (Realtime)

### DI Modules (Hilt)

- `SupabaseModule` — Supabase client singleton
- `NetworkModule` — OkHttp, API clients
- `RepositoryModule` — Repository bindings
- `ConfigModule` — BuildConfig values
- `MissionModule` — Mission generation pipeline

## 4. Backend Architecture

### Supabase Services Used

- **Auth** (GoTrue): email/password, OAuth deep links, PKCE
- **PostgREST**: profiles, friendships tables
- **Realtime**: live profile updates for social notifications
- **Storage**: avatar image uploads (bucket: `avatars`)
- **Edge Functions**: API proxies with auth + rate limiting

### Edge Functions (TypeScript/Deno)

| Function | Upstream | Key Features |
|----------|----------|-------------|
| `gemini-proxy` | Google Gemini API | Model fallback (3-flash → 2.5-flash), vision support, response sanitization, 30s timeout |
| `google-places-proxy` | Google Places API (New) | Field allowlisting, body sanitization, maxResultCount cap (20), 30s timeout |

Both enforce: JWT validation, API quota tracking via `consume_api_quota` RPC, sanitized error responses (no upstream leakage).

### Database RPCs (SECURITY DEFINER)

| RPC | Purpose |
|-----|---------|
| `complete_mission` | Award honey + streak, prevent duplicate per UTC day |
| `update_profile_location` | Validate and store coordinates |
| `accept_streak_loss` | Reset streak to 0 |
| `restore_streak` | Pay honey to restore (validates cost/eligibility) |
| `admin_update_profile_stats` | Admin-only stat override |
| `update_profile_username` | Trim/validate/update username |
| `consume_api_quota` | Rate limit Edge Function calls |
| `get_social_leaderboard` | Friends + self ranked by honey |
| `get_accepted_friend_profiles` | Friend list |
| `get_pending_friend_request_profiles` | Incoming requests |
| `send_friend_request` | By friend code |
| `accept_friend_request` | Accept pending |
| `reject_friend_request` | Reject pending |

### Security Model

- `authenticated` role has column-level GRANT: only `username, badges, cities_visited, avatar_url, friend_code, explorer_class`
- All progression fields (`honey`, `streak_count`, `hive_rank`, `last_mission_date`) are server-owned via RPCs
- RLS policies enforce row ownership + admin access via `is_current_profile_admin()`
- Edge Functions sanitize both requests and responses — no raw upstream data reaches client

## 5. Features

### Core Loop
1. Open Missions → get AI-generated mission near your location
2. Navigate to target place → take verification photo
3. Gemini vision validates photo → earn Honey + streak
4. Streak grows daily → higher rank → longer-range missions

### Feature Set
- Email/password auth with remember-me gate
- AI mission generation (Gemini) with Places geocoding verification
- Camera photo verification (Gemini vision)
- Hidden Gems discovery (Overpass + Places + Gemini curation)
- Social leaderboard + friend codes + add/remove friends
- Profile: avatar (crop + upload), username, badges, explorer class
- Streak system with notifications (daily reminder, evening alert, streak lost)
- Streak restore (pay Honey) and loss acceptance flows
- Home screen widget with mascot + streak tier visuals
- Realtime notifications for rival activity (overtaken, fight for first)
- Admin dashboard (dev tools for stats, notifications, workers)
- Onboarding flow

### Screens
- `SplashActivity` → auth gate
- `AuthActivity` → login/signup + deep link callback
- `MainActivity` → bottom nav host
  - Map (osmdroid + friend markers + mission preview)
  - Missions (generate → verify → complete)
  - Hidden Gems (AI-curated nearby places)
  - Social (leaderboard + friends tabs)
  - Profile (stats, avatar, badges, class, admin)

## 6. Project Structure

```
app/src/main/java/com/novahorizon/wanderly/
├── api/              HTTP clients (Supabase, Gemini, Places, Overpass)
├── auth/             Auth routing, session coordination, callback matching
├── data/             Repositories, models, data sources
│   └── mission/      Mission generation pipeline
├── di/               Hilt modules
├── notifications/    Notification rules, coordinator, manager
├── observability/    Logging, crash reporting, StrictMode
├── services/         HiveRealtimeService (foreground)
├── streak/           DailyStreakStatusEvaluator
├── ui/
│   ├── auth/         Login, Signup fragments
│   ├── common/       AvatarLoader, shared UI
│   ├── gems/         GemsFragment + ViewModel
│   ├── map/          MapFragment
│   ├── missions/     MissionsFragment + ViewModel + PhotoDecoder
│   ├── onboarding/   OnboardingFragment
│   ├── profile/      ProfileFragment + ViewModel + DevDashboard
│   └── social/       SocialFragment + ViewModel
├── widgets/          Widget provider, alarm scheduler, tier rendering
├── workers/          StreakWorker, SocialWorker
├── Constants.kt
├── MainActivity.kt
├── SplashActivity.kt
└── WanderlyApplication.kt

supabase/
├── functions/
│   ├── gemini-proxy/index.ts
│   └── google-places-proxy/index.ts
└── migrations/
    └── *.sql (8 migration files)
```

## 7. Source Statistics

| Category | Files | Lines |
|----------|-------|-------|
| Main Kotlin | 117 | ~14,300 |
| Test Kotlin | 90 | ~5,000 |
| XML resources | 109 | ~3,500 |
| Edge Functions (TS) | 2 | ~636 |
| SQL Migrations | 8 | ~868 |

## 8. Testing

- **90 unit test files** — JUnit 4 + Robolectric + coroutines-test + Hilt testing
- **Instrumented tests** — Espresso + UIAutomator (emulator API 35)
- **Architectural tests** — verify manifest, source patterns, RPC contracts, permissions
- **Coverage** — Kover enforces ≥15% line coverage on debug

## 9. CI/CD

GitHub Actions (`.github/workflows/ci.yml`):
- Triggers: push to main/develop, PRs
- Permissions: `contents: read` (least privilege)
- Steps: checkout → Gradle wrapper validation → unit tests → instrumented tests (emulator) → lint → release regression → coverage → APK size → debug APK → release APK/AAB
- Artifacts: debug APK, signed release APK/AAB, lint report, coverage report, test results, APK size summary
- Security: SHA pinning deferred, Maven content filtering active

## 10. Current Status

| Area | Status |
|------|--------|
| Debug build | PASS |
| Release build | PASS |
| Unit tests | PASS |
| Lint | PASS |
| CI pipeline | PASS |
| Backend (Supabase) | DEPLOYED |
| Edge Functions | DEPLOYED + WORKING |
| Gemini API | WORKING (gemini-3-flash-preview) |
| Places API | WORKING |
| Release signing | CONFIGURED (local keystore + CI secrets) |
| Play Store | NOT READY (no privacy policy, Data Safety, App Links) |

### Recent Work (2026-05-04/05)
- Security remediation across 8 waves (see SECURITY_REMEDIATION_REPORT.md)
- Fixed Edge Function 502 errors (API key rotation + RPC EXECUTE grants)
- Added `admin_update_profile_stats` RPC (replaces direct UPDATE that hit permission denied)
- Added HEIF/HEIC support to photo decoder (fixes "Buzzy could not read photo")
- Suppressed streak notifications in debug builds
- Redesigned badge, streak fire, and profile streak drawables
