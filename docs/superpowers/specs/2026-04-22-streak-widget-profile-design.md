# Streak Widget And Profile Refresh Design

## Goal

Upgrade both streak surfaces in the Hive travel app so the home screen widget and the in-app profile screen share one streak tier model, use richer fire visuals, and refresh streak data more reliably.

## Scope

This design covers:

- the home screen widget refresh pipeline
- widget scheduling behavior and exact-alarm fallback handling
- shared streak tier modeling across widget and profile
- animated fire assets for widget and profile
- profile streak section layout and refresh behavior
- stale-cache signaling for widget fallback renders
- test coverage for tier mapping, widget state, scheduling, and profile presentation

This design does not cover:

- migrating the app to Compose
- adding new backend fields beyond the existing profile streak data
- making honey or flights live-updated in the widget

## Current Context

The current implementation already has:

- `WanderlyStreakWidgetProvider.kt` updating the widget from locally stored streak data
- `PreferencesStore.kt` caching streak count and last mission date
- `ProfileFragment.kt` loading the profile on resume and rendering a flame halo around the avatar
- `StreakWidgetStateHelper.kt` deriving widget mood and current presentation from a limited tier model
- widget XML using `updatePeriodMillis="3600000"` and exact alarms on a one-hour cadence

The current implementation does not yet have:

- a shared 7-tier streak model
- widget-side API fetch on every alarm tick
- cache age tracking or stale-state signaling
- the requested profile streak section layout
- enough widget fire frames to support tiered frame rotation

## Product Decisions

- Widget freshness is the highest priority.
- On every `AlarmManager` tick, the widget should fetch fresh profile data from the API, save the streak snapshot locally, then update the widget.
- Only streak-related data needs to be treated as live in the widget. Honey and flights can remain derived from the most recent saved profile snapshot rather than independently synchronized.
- If exact alarms are unavailable on Android 12+ and above, the widget must silently degrade to a best-effort schedule without crashing.
- The profile screen should refresh streak presentation from local saved state every time the screen resumes.
- The widget must support a broken-streak state at `0` with distinct copy and visuals.

## Architecture

### Shared Streak Model

Introduce a new helper in `app/src/main/java/com/novahorizon/wanderly/widgets/StreakTierHelper.kt` that becomes the canonical mapping from streak count to tier metadata for both widget and profile.

The tier model will have seven states:

- `Broken`: `0`
- `Starter`: `1..6`
- `Rising`: `7..29`
- `Blazing`: `30..59`
- `Legendary`: `60..99`
- `Epic`: `100..199`
- `GOD`: `200+`

`StreakTierHelper` will expose a value object that includes:

- `color: Int`
- `glowColor: Int`
- `label: String`
- `emoji: String`
- `animFile: Int`
- `frameSet: IntArray`

The `frameSet` addition supports widget frame rotation, while `animFile` supports the profile screen's live drawable.

### Widget Data Flow

The widget provider becomes an active sync pipeline instead of a passive cache reader.

Each 15-second tick follows this sequence:

1. receive alarm tick
2. fetch fresh profile data from the existing repository/API path
3. extract streak-related fields from the API response
4. save the result to `PreferencesStore`
5. compute widget presentation from the saved state
6. update the widget
7. schedule the next alarm

If the API fetch fails:

1. read the last saved widget state from `PreferencesStore`
2. compute whether the cache is stale
3. update the widget from cached data
4. schedule the next alarm

The widget should never render directly from transient in-memory network results. It should always render from the state that has been persisted into `PreferencesStore` so the save-and-render flow stays consistent.

### Profile Data Flow

The profile screen should refresh its streak presentation from local saved state whenever the fragment becomes active again.

The refresh sequence is:

1. `onStart()` or `onResume()` starts the fire animation
2. `onResume()` pulls the latest locally saved streak snapshot from `PreferencesStore`
3. the streak section is updated immediately from that local state
4. the existing profile load path continues fetching the full profile and can overwrite with newer server-backed data once available

This makes the streak visuals feel responsive without waiting for the full profile request to complete.

## Data Storage

Extend `PreferencesStore.kt` to persist widget sync metadata.

Required saved fields:

- `local_streak_count`
- `local_last_mission_date`
- `local_widget_saved_at_millis`
- `local_widget_last_sync_succeeded`
- `local_widget_honey`
- `local_widget_flights`

Although only streak is required to be truly live, saving honey and flights alongside the fetched profile snapshot allows the widget footer to stay coherent without introducing a second fetch path.

`savedAtMillis` is the authoritative age marker for stale detection.

Stale behavior:

- cache younger than or equal to 5 minutes after a failed fetch: render normally
- cache older than 5 minutes after a failed fetch: render cached values and show a small gray stale dot
- no saved data and failed fetch: render safe `Broken` defaults

## Alarm Scheduling

### Widget Metadata

Change `app/src/main/res/xml/wanderly_widget_info.xml` so:

- `android:updatePeriodMillis="0"`
- minimum size targets a 4x2 widget footprint

The provider fully owns refresh cadence through alarms.

### Manifest

Add `android.permission.SCHEDULE_EXACT_ALARM` to the manifest.

The widget provider receiver remains exported for launcher interaction.

### Scheduling Rules

`WanderlyStreakWidgetProvider.kt` schedules alarms every 15 seconds.

On Android 12+:

- call `alarmManager.canScheduleExactAlarms()` before exact scheduling
- if `true`, use `setExactAndAllowWhileIdle()`
- if `false`, silently use `setAndAllowWhileIdle()`

On earlier Android versions:

- use `setExactAndAllowWhileIdle()`

The provider reschedules after:

- `onUpdate()`
- every explicit refresh action
- `onEnabled()`

The provider cancels its pending intent in `onDisabled()`.

### Exact Alarm UX

This design does not require a blocking permission prompt in the widget flow. The widget should work even when exact alarm access is denied, with silent degradation to best-effort scheduling.

## Widget Presentation

Create `app/src/main/res/layout/widget_layout.xml` as the new widget layout.

### Layout Structure

- dark brown rounded background with gradient `#110900 -> #2a1800`
- glowing 2dp border tinted to the current tier color
- top row label: `CURRENT STREAK`
- main row with:
  - large streak number in the tier color
  - right-side fire image
- supporting line: `One flight keeps it glowing.`
- bottom row with three columns:
  - `Honey`
  - `Streak`
  - `Flights`
- bottom-right last-updated timestamp
- small stale indicator dot, hidden by default

### Widget Fire Animation

RemoteViews cannot be treated like a regular in-app animated vector host, so the widget uses frame swapping.

Generate at least three static fire frames per active tier family:

- `Broken`
- `Starter`
- `Rising`
- `Blazing`
- `Legendary`
- `Epic`
- `GOD`

That produces twenty-one practical frame drawables for complete coverage. The user's minimum request of eighteen frames is satisfied by the six non-broken tiers, but `Broken` also receives three dedicated gray frames so its visual state stays consistent with the model.

Each tier family will have:

- frame 1
- frame 2
- frame 3

The widget provider chooses the frame based on the current tick index modulo three.

### Widget Messages

Widget copy comes from presentation state, not directly from the tier helper.

Examples:

- broken streak: `Start again today.`
- active default line: `One flight keeps it glowing.`
- danger and pride variants can remain mood-aware if the existing widget state helper keeps those concepts

## Profile Presentation

Create `app/src/main/res/layout/profile_streak_section.xml` and include it from the existing profile layout.

### Layout Structure

- circular avatar remains the primary anchor
- 48dp animated fire badge overlaid at the avatar's bottom-right
- tier badge pill directly below avatar
- tier-colored streak number beneath the pill
- three stat cards for `Honey`, `Streak`, and `Flights`
- each stat card has a tier-colored top border

The previous multi-image halo treatment should be replaced or reduced so the new fire badge becomes the dominant streak visual.

### Tier Badge

Use `res/drawable/tier_badge_background.xml` as a rounded pill tinted at runtime.

Badge examples:

- `🔥 Blazing`
- `💜 Legendary`
- `💀 Broken`

### Animation

The profile fire uses `res/drawable/ic_fire_animated.xml`.

Behavior:

- start in `onStart()`
- restart in `onResume()`
- stop naturally with the view lifecycle when detached

When the tier changes, use `ValueAnimator` over 600ms to animate color transitions for:

- badge background tint
- streak number text color
- stat-card top border colors

## Fire Asset Design

### Animated Vector

Create:

- `res/drawable/ic_fire_animated.xml`
- `res/drawable/fire_morph_1.xml`
- `res/drawable/fire_morph_2.xml`
- `res/drawable/fire_morph_3.xml`

The profile drawable includes:

- a flame silhouette
- a transparent circular cutout in the center
- a glow layer behind the main flame
- morphing between three keyframes in an infinite loop

Because runtime tinting is easier to maintain than duplicating seven animated-vector files, the profile animation uses one structural animated drawable and applies tier tinting at runtime.

### Static Widget Frames

For widget compatibility, create static drawable frame families derived from the same visual language.

Each frame preserves:

- center cutout
- tier glow
- slight silhouette variation across frames

The widget frames do not need to be implemented as `AnimationDrawable`. They can be plain drawables selected by the provider because the animation effect comes from swapping frames between refreshes.

## Resource Files

Create or replace the following resources:

- `app/src/main/res/layout/widget_layout.xml`
- `app/src/main/res/layout/profile_streak_section.xml`
- `app/src/main/res/drawable/ic_fire_animated.xml`
- `app/src/main/res/drawable/fire_morph_1.xml`
- `app/src/main/res/drawable/fire_morph_2.xml`
- `app/src/main/res/drawable/fire_morph_3.xml`
- `app/src/main/res/drawable/widget_background.xml`
- `app/src/main/res/drawable/tier_badge_background.xml`
- `app/src/main/res/values/streak_colors.xml`

Add static widget frame drawables for each tier family and frame index.

The file naming follows a predictable scheme such as:

- `ic_fire_widget_broken_1.xml`
- `ic_fire_widget_broken_2.xml`
- `ic_fire_widget_broken_3.xml`
- `ic_fire_widget_starter_1.xml`
- `ic_fire_widget_starter_2.xml`
- `ic_fire_widget_starter_3.xml`

Continue the same pattern through `god`.

## Kotlin File Responsibilities

### `StreakTierHelper.kt`

Responsibilities:

- streak-to-tier mapping
- tier labels and emoji
- tier colors and glow colors
- profile animated drawable id
- widget frame set ids

This helper does not know about alarm scheduling, stale cache logic, or widget copy text.

### `StreakWidgetStateHelper.kt`

Responsibilities:

- message selection
- stale-indicator visibility
- last-updated formatting
- frame index selection
- mood logic if the existing widget personality remains

This helper consumes persisted snapshot values and tier metadata.

### `WanderlyStreakWidgetProvider.kt`

Responsibilities:

- receive tick broadcasts
- fetch profile data from the repository/API
- persist widget snapshot state
- compute presentation state
- update `RemoteViews`
- schedule or cancel alarms safely

This provider does not contain hardcoded tier thresholds.

### `ProfileFragment.kt`

Responsibilities:

- load and apply local streak state on resume
- start animated fire badge
- apply tier badge text and colors
- animate color transitions
- keep existing broader profile behavior intact

## Error Handling

### API Failure

If a fetch fails:

- do not crash
- do not clear the last known state
- render from last saved values
- show stale dot only when cache age exceeds five minutes

### Missing Exact Alarm Access

If `canScheduleExactAlarms()` is false:

- do not throw
- do not request permission inline from the widget path
- use `setAndAllowWhileIdle()`

### Missing Cache

If both API and saved cache are unavailable:

- render a safe `Broken` widget state
- hide stale dot
- show the broken-state message
- use the broken-tier gray visuals

## Testing Strategy

### Unit Tests

Add or expand tests for:

- `StreakTierHelper`
- `StreakWidgetStateHelper`
- widget scheduling selection logic
- stale-state visibility logic
- profile streak presentation helpers

Tier boundary cases:

- `0`
- `1`
- `6`
- `7`
- `29`
- `30`
- `59`
- `60`
- `99`
- `100`
- `199`
- `200`

### Widget Behavior Tests

Validate:

- fetch success saves timestamp and clears stale state
- fetch failure uses cached values
- stale dot appears only when cache is older than five minutes after failed fetch
- frame rotation picks a valid frame for the current tier
- broken streak uses broken copy and visuals

### Profile Tests

Validate:

- `onResume()` applies locally saved streak state
- broken tier renders gray badge text and styling
- tier transition animation targets the expected view colors

### Manual Verification

Manual checks include:

- widget installed at 4x2 size
- exact-alarm allowed path on Android 12+
- exact-alarm denied path on Android 12+
- broken streak state in widget and profile
- blazing state around streak 58
- animation starting when returning to the profile screen
- stale dot shown only after failed fetch with cache age over five minutes

## Implementation Notes

- Follow existing repository and profile-loading patterns rather than introducing a second networking stack.
- Prefer a repository-backed widget fetch path so the widget benefits from the same profile normalization already used elsewhere.
- Keep the widget render path resilient by separating fetch, save, and render into small functions.
- Avoid coupling profile animation details to widget RemoteViews limitations.

## Risks And Tradeoffs

- A 15-second cadence is best-effort on modern Android when exact alarm access is denied.
- Widget fire animation will still look stepped because the host only sees discrete frame changes, but three frames per tier materially improves quality over a single icon swap.
- Saving honey and flights alongside streak data slightly broadens the cached widget snapshot, but it keeps the bottom row internally consistent without adding extra sync complexity.

## Success Criteria

The design is successful when:

- the widget schedules a 15-second refresh chain and silently degrades when exact alarms are unavailable
- every widget tick attempts API fetch before rendering
- widget state is persisted with a timestamp and supports stale fallback
- streak `0` displays as `Broken` with gray visuals and restart copy
- both widget and profile use the same tier thresholds and labels
- the profile screen shows the new streak section with animated fire badge and tier transitions
- automated tests cover tier boundaries, stale logic, and scheduling behavior
