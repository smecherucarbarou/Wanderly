# B — Fragments → Compose Consolidation: Migration Plan

Tracks `audits/2026-06-29/big_improvements.md` item **B** ("Consolidate dual UI stack").
Companion to [BIG_IMPROVEMENTS_PROGRESS.md](BIG_IMPROVEMENTS_PROGRESS.md). This is the reviewable
plan for the real migration; the cheap dead-code win (Job 1) is already done.

## TL;DR

The audit framed B as "Fragments → Compose", implying a screen rewrite. **It is not.** Every
screen in Wanderly is *already* a Jetpack Compose composable; each `*Fragment` is a ~30-line shell
whose `onCreateView` returns a `ComposeView.setContent { WanderlyTheme { XScreen(...) } }`. So B is:

1. **Job 1 (DONE):** delete the orphaned *duplicate* Compose stack that was wired to nothing.
2. **Job 2 (this plan):** replace the **host skeleton** — Fragments + XML nav graphs +
   `BottomNavigationView` + the two View-based Activities — with a single **Navigation-Compose**
   `NavHost`. No `*Screen` composable needs rewriting; the work is lifting per-Fragment *glue*
   (permission/ActivityResult launchers, nav callbacks, deeplink routing, conditional start
   destination) into Compose and then cutting each nav graph over in one shot.

---

## Current architecture (verified by inventory workflow, 2026-06-30)

**Boot / host layer — Fragment-first, View-based:**
- `SplashActivity` — LAUNCHER. `AppCompatActivity`, `installSplashScreen()`, **no** `setContent`/
  `setContentView`; routes by intent to `AuthActivity` or `MainActivity`. (Keep as-is.)
- `AuthActivity` — `AppCompatActivity`, `setContentView(programmatic FrameLayout)` hosting a
  `NavHostFragment.create(R.navigation.auth_nav_graph)`.
- `MainActivity` — `AppCompatActivity`, `setContentView(programmatic LinearLayout)` hosting a
  `FragmentContainerView` + `BottomNavigationView` wired via `setupWithNavController()`.
- **No Activity calls `setContent`. There is no Compose `NavHost` anywhere.**

**Screen layer — Compose-first:** 10 Fragments, each a `ComposeView` shell over an existing
composable, with `ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed`.

| Fragment | Wraps composable | Reachability |
|----------|------------------|--------------|
| `LoginFragment` | `LoginScreen` | auth_nav_graph start |
| `SignupFragment` | `SignupScreen` | `action_login_to_signup` |
| `MapFragment` | `MapScreen` | nav_graph **start**, bottom-nav |
| `GemsFragment` | `GemsScreen` (in `MapGemsScreen.kt`) | bottom-nav (leaf) |
| `MissionsFragment` | `MissionsScreen` | bottom-nav; `action_map_to_missions` + from Social |
| `SocialFragment` | `SocialScreen` | bottom-nav; → missions; deeplink invite target |
| `ProfileFragment` | `ProfileScreen` | bottom-nav; → devDashboard |
| `DevDashboardFragment` | `DevDashboardScreen` | from Profile, `BuildConfig.DEBUG`-gated |
| `OnboardingFragment` | `OnboardingScreen` | first-launch start; `action_onboarding_to_map` |
| `WanderlyGuideFragment` | `WanderlyGuideScreen` | from Map |

**Nav backbone (to be deleted):** `res/navigation/auth_nav_graph.xml`,
`res/navigation/nav_graph.xml`, `res/menu/bottom_nav_menu.xml`,
`ui/MainNavigationDestinations.kt` (start-dest + bottom-bar-visibility logic).

**Out of scope — do NOT touch:**
- `res/layout/widget_streak.xml` + `wanderly_widget_info.xml` — live **RemoteViews app widget**,
  not a screen. Easily mistaken for a dead XML layout.
- `shop` — no standalone Fragment/screen/nav destination exists. Cosmetics UI is presumed embedded
  in `ProfileScreen`. **Confirm before assuming nothing to migrate.**

---

## Job 1 — delete dead duplicate Compose stack ✅ DONE

These were a parallel Compose UI wired to **nothing live** (verified by grep, zero reachable refs):

- `ui/compose/screens/splash/SplashScreen.kt` — composable never wrapped by any Fragment/NavHost;
  `SplashActivity` uses the unrelated `androidx.core.splashscreen.SplashScreen`.
- `ui/compose/components/WanderlyBottomBar.kt` — Compose `NavigationBar` referenced only by its own
  `@Preview`; the live bottom bar is `BottomNavigationView` + `bottom_nav_menu.xml`.
- `ui/compose/WanderlyRoutes.kt` (`WanderlyRoute`) — consumed only by the dead `WanderlyBottomBar`;
  several cases (`MissionDetail`, `PhotoVerification`, `Settings`) had no screen at all.
- Also removed the now-orphaned `R.string.splash_tagline`.

> Note: a fresh, **correct** typed-route definition will be reintroduced in Job 2 PR-1 (the deleted
> `WanderlyRoute` was stale/aspirational, not a usable starting point).

---

## Job 2 — host-skeleton migration (the real work)

### Target

- Add **Navigation-Compose** (`androidx.navigation:navigation-compose`).
- Each Activity boundary owns a Compose `NavHost` in `setContent`. **Keep the auth/main Activity
  split** (lower risk than collapsing them; `SessionNavigator.openMain()/openAuth()` cross-activity
  hops stay intact). `SplashActivity` stays the launcher router.
- Every `<fragment>` destination becomes a `composable(route) { XScreen(...) }`.
- `BottomNavigationView` → Material3 `NavigationBar` driven by the `NavController` back stack.
- Replicate exactly: onboarding conditional start destination, deeplink invite routing, debug gate.

### Hard constraint (drives the slicing)

You **cannot** have a Fragment `NavHostFragment` and a Compose `NavHost` owning the **same back
stack** simultaneously. So each *graph* must cut over in **one** PR — you cannot migrate the main
graph one destination at a time. The way to de-risk a single-shot graph cutover is to make every
Fragment a **pure shell first** (move all glue into Compose while the Fragment host still runs),
so the final flip is mechanical.

### PR sequence (each independently shippable & CI-green)

- **PR-0 — Delete dead stack.** ✅ done (Job 1).

- **PR-1 — Add Navigation-Compose + typed routes (no wiring).** Add the dependency; define a fresh
  sealed route type with only the routes that actually exist. No behavior change; nothing consumes
  it yet. Keeps the diff that *does* the work smaller.

- **PR-2 — Auth graph cutover (proof of pattern).** Smallest, fully isolated graph in its own
  Activity. Convert `AuthActivity` to `ComponentActivity` + `setContent { NavHost(login → signup) }`
  calling the existing `LoginScreen`/`SignupScreen`. Delete `LoginFragment`, `SignupFragment`,
  `auth_nav_graph.xml`. Verify: login→signup nav, and `SessionNavigator.openMain()` still fires on
  success.

- **PR-3 … PR-7 — Per-feature glue extraction (still under the Fragment host).** For each feature,
  move its non-UI glue out of the Fragment and into Compose (`rememberLauncherForActivityResult`,
  Compose permission APIs, nav callbacks hoisted to lambdas), leaving the Fragment a pure
  `ComposeView { XScreen(...) }`. Each is small and verifiable in the running app. Safe → risky:
  1. **gems** — leaf, no outbound nav; just location-permission handling to hoist.
  2. **profile + devDashboard** — leaf + debug child; hoist avatar image-crop `ActivityResult`.
  3. **social** — hoist the → missions nav callback and the pending-invite deeplink handling.
  4. **missions** — hoist camera/photo-capture `ActivityResult` + permission flow.
  5. **map + guide** — **riskiest:** osmdroid `MapView` `AndroidView` interop + lifecycle
     (`onResume/onPause/onDetach`) and location permissions. Map is also the start destination.

- **PR-8 — Main graph single cutover.** With all Fragments now pure shells, flip `MainActivity` to
  `ComponentActivity` + `setContent { NavHost(...) }` with `NavigationBar`. Migrate all 7
  destinations at once (mechanical: each becomes `composable { XScreen(...) }`). Replicate:
  - onboarding **conditional start destination** (`MainNavigationDestinations.initialStartDestination`
    + `MainActivity:127-129`) — first-launch users break if this isn't reproduced precisely;
  - deeplink **invite routing** (`MainActivity.routePendingInviteIfNeeded` → social) as a Compose
    nav deeplink / route arg;
  - debug gate for devDashboard.
  Delete: all `*Fragment` shells, `nav_graph.xml`, `bottom_nav_menu.xml`, `MainNavigationDestinations.kt`.

- **PR-9 — Cleanup (optional).** Reconsider collapsing `AuthActivity`/`MainActivity` into one graph;
  prune now-unused fragment/navigation dependencies from the version catalog. Keep `SplashActivity`.

### Risks (carry into the relevant PR)

- **osmdroid MapView** (`MapScreen`) — `AndroidView` interop + manual lifecycle; the single riskiest
  screen, and it's the start destination so it must keep working throughout.
- **Camera/photo-capture** (`MissionsFragment`) — `ActivityResult`/permission APIs anchored to the
  Fragment; re-host via `rememberLauncherForActivityResult`.
- **Avatar image-crop** (`ProfileFragment`) — another Fragment-lifecycle `ActivityResult` flow.
- **Location permissions** (`GemsFragment`, `WanderlyGuideFragment`) → Compose permission APIs.
- **Onboarding runtime start-dest mutation** — replicate the conditional start in the NavHost exactly.
- **Deeplink invite routing** — depends on Fragment destination IDs; re-express as Compose deeplinks.
- **Two Activities + `SessionNavigator` cross-activity hops** — preserve the auth/main boundary.
- **One-shot-per-graph constraint** — no mixed Fragment+Compose back stack; cut over per graph.
- **shop** — confirm cosmetics UI is embedded in `ProfileScreen` (no standalone surface found).
- **Do NOT delete** `widget_streak.xml` / `wanderly_widget_info.xml` (live app widget).

### Definition of done

No `*Fragment`, no `NavHostFragment`/`FragmentContainerView`, no `res/navigation/*.xml`, no
`bottom_nav_menu.xml`; navigation is a single Compose `NavHost` per Activity boundary; bottom bar is
a wired `NavigationBar`; all existing flows (auth, onboarding first-launch, deeplink invite, camera,
avatar crop, map) work in the running app; CI green.
