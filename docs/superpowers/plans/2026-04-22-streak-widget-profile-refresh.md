# Streak Widget And Profile Refresh Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebuild the streak widget and profile streak section so the widget refreshes on a 15-second alarm chain with API-backed saves, both surfaces share one 7-tier streak model, and both render upgraded fire visuals with graceful stale-cache fallback.

**Architecture:** Separate the work into five focused layers: persisted widget snapshot state, shared tier logic, widget presentation helpers, widget scheduling/fetch/render orchestration, and profile-only UI rendering. Keep the widget provider thin by pushing pure logic into small helpers that are easy to unit test, and keep profile animations in normal view code rather than trying to force widget constraints onto the in-app screen.

**Tech Stack:** Kotlin, Android App Widgets (`RemoteViews`, `AppWidgetProvider`, `AlarmManager`), Android Views/XML, DataStore Preferences, Supabase-backed `ProfileRepository`, AndroidX Fragment/ViewBinding, JUnit

---

## File Structure

**Create:**
- `app/src/main/java/com/novahorizon/wanderly/widgets/StreakTierHelper.kt`
- `app/src/main/java/com/novahorizon/wanderly/widgets/WidgetStreakSnapshot.kt`
- `app/src/main/java/com/novahorizon/wanderly/widgets/StreakWidgetAlarmScheduler.kt`
- `app/src/main/res/layout/widget_layout.xml`
- `app/src/main/res/layout/profile_streak_section.xml`
- `app/src/main/res/drawable/ic_fire_animated.xml`
- `app/src/main/res/drawable/fire_morph_1.xml`
- `app/src/main/res/drawable/fire_morph_2.xml`
- `app/src/main/res/drawable/fire_morph_3.xml`
- `app/src/main/res/drawable/widget_background.xml`
- `app/src/main/res/drawable/tier_badge_background.xml`
- `app/src/main/res/animator/fire_path_morph.xml`
- `app/src/main/res/values/streak_colors.xml`
- `app/src/test/java/com/novahorizon/wanderly/widgets/StreakTierHelperTest.kt`
- `app/src/test/java/com/novahorizon/wanderly/widgets/StreakWidgetAlarmSchedulerTest.kt`

**Create widget fire frames:**
- `app/src/main/res/drawable/ic_fire_widget_broken_1.xml`
- `app/src/main/res/drawable/ic_fire_widget_broken_2.xml`
- `app/src/main/res/drawable/ic_fire_widget_broken_3.xml`
- `app/src/main/res/drawable/ic_fire_widget_starter_1.xml`
- `app/src/main/res/drawable/ic_fire_widget_starter_2.xml`
- `app/src/main/res/drawable/ic_fire_widget_starter_3.xml`
- `app/src/main/res/drawable/ic_fire_widget_rising_1.xml`
- `app/src/main/res/drawable/ic_fire_widget_rising_2.xml`
- `app/src/main/res/drawable/ic_fire_widget_rising_3.xml`
- `app/src/main/res/drawable/ic_fire_widget_blazing_1.xml`
- `app/src/main/res/drawable/ic_fire_widget_blazing_2.xml`
- `app/src/main/res/drawable/ic_fire_widget_blazing_3.xml`
- `app/src/main/res/drawable/ic_fire_widget_legendary_1.xml`
- `app/src/main/res/drawable/ic_fire_widget_legendary_2.xml`
- `app/src/main/res/drawable/ic_fire_widget_legendary_3.xml`
- `app/src/main/res/drawable/ic_fire_widget_epic_1.xml`
- `app/src/main/res/drawable/ic_fire_widget_epic_2.xml`
- `app/src/main/res/drawable/ic_fire_widget_epic_3.xml`
- `app/src/main/res/drawable/ic_fire_widget_god_1.xml`
- `app/src/main/res/drawable/ic_fire_widget_god_2.xml`
- `app/src/main/res/drawable/ic_fire_widget_god_3.xml`

**Modify:**
- `app/src/main/java/com/novahorizon/wanderly/Constants.kt`
- `app/src/main/java/com/novahorizon/wanderly/data/DataStoreManager.kt`
- `app/src/main/java/com/novahorizon/wanderly/data/PreferencesStore.kt`
- `app/src/main/java/com/novahorizon/wanderly/data/ProfileRepository.kt`
- `app/src/main/java/com/novahorizon/wanderly/ui/profile/ProfileFragment.kt`
- `app/src/main/java/com/novahorizon/wanderly/widgets/StreakWidgetStateHelper.kt`
- `app/src/main/java/com/novahorizon/wanderly/widgets/WanderlyStreakWidgetProvider.kt`
- `app/src/main/res/layout/fragment_profile.xml`
- `app/src/main/res/values/strings.xml`
- `app/src/main/res/values/colors.xml`
- `app/src/main/res/values/dimens.xml`
- `app/src/main/res/xml/wanderly_widget_info.xml`
- `app/src/main/AndroidManifest.xml`
- `app/src/test/java/com/novahorizon/wanderly/ui/profile/ProfileFragmentAvatarPresentationTest.kt`
- `app/src/test/java/com/novahorizon/wanderly/widgets/StreakWidgetStateHelperTest.kt`

### Task 1: Add the shared streak tier model and persisted widget snapshot

**Files:**
- Create: `app/src/main/java/com/novahorizon/wanderly/widgets/StreakTierHelper.kt`
- Create: `app/src/main/java/com/novahorizon/wanderly/widgets/WidgetStreakSnapshot.kt`
- Create: `app/src/test/java/com/novahorizon/wanderly/widgets/StreakTierHelperTest.kt`
- Modify: `app/src/main/java/com/novahorizon/wanderly/Constants.kt`
- Modify: `app/src/main/java/com/novahorizon/wanderly/data/DataStoreManager.kt`
- Modify: `app/src/main/java/com/novahorizon/wanderly/data/PreferencesStore.kt`
- Modify: `app/src/main/java/com/novahorizon/wanderly/data/ProfileRepository.kt`

- [ ] **Step 1: Write the failing tier-boundary tests**

```kotlin
package com.novahorizon.wanderly.widgets

import com.novahorizon.wanderly.R
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class StreakTierHelperTest {

    @Test
    fun `maps zero streak to broken tier`() {
        val tier = StreakTierHelper.resolve(0)

        assertEquals(StreakTierId.BROKEN, tier.id)
        assertEquals("Broken", tier.label)
        assertEquals("\uD83D\uDC80", tier.emoji)
        assertEquals(R.drawable.ic_fire_animated, tier.animFile)
        assertArrayEquals(
            intArrayOf(
                R.drawable.ic_fire_widget_broken_1,
                R.drawable.ic_fire_widget_broken_2,
                R.drawable.ic_fire_widget_broken_3
            ),
            tier.frameSet
        )
    }

    @Test
    fun `maps blazing boundary streak to blazing tier`() {
        val tier = StreakTierHelper.resolve(58)

        assertEquals(StreakTierId.BLAZING, tier.id)
        assertEquals("Blazing", tier.label)
        assertEquals("\uD83D\uDD25", tier.emoji)
        assertArrayEquals(
            intArrayOf(
                R.drawable.ic_fire_widget_blazing_1,
                R.drawable.ic_fire_widget_blazing_2,
                R.drawable.ic_fire_widget_blazing_3
            ),
            tier.frameSet
        )
    }

    @Test
    fun `maps epic and god upper boundaries`() {
        assertEquals(StreakTierId.EPIC, StreakTierHelper.resolve(100).id)
        assertEquals(StreakTierId.EPIC, StreakTierHelper.resolve(199).id)
        assertEquals(StreakTierId.GOD, StreakTierHelper.resolve(200).id)
    }
}
```

- [ ] **Step 2: Run the tier test to verify it fails**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.novahorizon.wanderly.widgets.StreakTierHelperTest"
```

Expected: FAIL with unresolved symbols for `StreakTierHelper`, `StreakTierId`, and the new widget frame drawables.

- [ ] **Step 3: Add new widget cache keys and the persisted snapshot model**

```kotlin
// app/src/main/java/com/novahorizon/wanderly/Constants.kt
const val KEY_LOCAL_WIDGET_HONEY = "local_widget_honey"
const val KEY_LOCAL_WIDGET_FLIGHTS = "local_widget_flights"
const val KEY_LOCAL_WIDGET_SAVED_AT_MILLIS = "local_widget_saved_at_millis"
const val KEY_LOCAL_WIDGET_LAST_SYNC_SUCCEEDED = "local_widget_last_sync_succeeded"
```

```kotlin
// app/src/main/java/com/novahorizon/wanderly/widgets/WidgetStreakSnapshot.kt
package com.novahorizon.wanderly.widgets

data class WidgetStreakSnapshot(
    val streakCount: Int,
    val lastMissionDate: String?,
    val honey: Int,
    val flights: Int,
    val savedAtMillis: Long,
    val lastSyncSucceeded: Boolean
) {
    val hasFreshSave: Boolean
        get() = savedAtMillis > 0L
}
```

```kotlin
// app/src/main/java/com/novahorizon/wanderly/data/DataStoreManager.kt
suspend fun getMainLong(key: String, default: Long = 0L): Long =
    getLong(StoreType.MAIN, key, default)

suspend fun putMainLong(key: String, value: Long) {
    putLong(StoreType.MAIN, key, value)
}
```

```kotlin
// app/src/main/java/com/novahorizon/wanderly/data/PreferencesStore.kt
suspend fun saveWidgetSnapshot(profile: Profile, savedAtMillis: Long = System.currentTimeMillis()) {
    val honey = profile.honey ?: 0
    val flights = honey / Constants.MISSION_HONEY_REWARD
    dataStoreManager.putMainInt(Constants.KEY_LOCAL_STREAK_COUNT, profile.streak_count ?: 0)
    dataStoreManager.putMainString(Constants.KEY_LOCAL_LAST_MISSION_DATE, profile.last_mission_date)
    dataStoreManager.putMainInt(Constants.KEY_LOCAL_WIDGET_HONEY, honey)
    dataStoreManager.putMainInt(Constants.KEY_LOCAL_WIDGET_FLIGHTS, flights)
    dataStoreManager.putMainLong(Constants.KEY_LOCAL_WIDGET_SAVED_AT_MILLIS, savedAtMillis)
    dataStoreManager.putMainBoolean(Constants.KEY_LOCAL_WIDGET_LAST_SYNC_SUCCEEDED, true)
}

suspend fun markWidgetSyncFailed() {
    dataStoreManager.putMainBoolean(Constants.KEY_LOCAL_WIDGET_LAST_SYNC_SUCCEEDED, false)
}

suspend fun getWidgetSnapshot(): WidgetStreakSnapshot {
    return WidgetStreakSnapshot(
        streakCount = dataStoreManager.getMainInt(Constants.KEY_LOCAL_STREAK_COUNT, 0),
        lastMissionDate = dataStoreManager.getMainString(Constants.KEY_LOCAL_LAST_MISSION_DATE, null),
        honey = dataStoreManager.getMainInt(Constants.KEY_LOCAL_WIDGET_HONEY, 0),
        flights = dataStoreManager.getMainInt(Constants.KEY_LOCAL_WIDGET_FLIGHTS, 0),
        savedAtMillis = dataStoreManager.getMainLong(Constants.KEY_LOCAL_WIDGET_SAVED_AT_MILLIS, 0L),
        lastSyncSucceeded = dataStoreManager.getMainBoolean(Constants.KEY_LOCAL_WIDGET_LAST_SYNC_SUCCEEDED, false)
    )
}
```

- [ ] **Step 4: Implement the shared tier helper and update repository persistence**

```kotlin
// app/src/main/java/com/novahorizon/wanderly/widgets/StreakTierHelper.kt
package com.novahorizon.wanderly.widgets

import android.graphics.Color
import androidx.annotation.ColorInt
import com.novahorizon.wanderly.R

enum class StreakTierId {
    BROKEN, STARTER, RISING, BLAZING, LEGENDARY, EPIC, GOD
}

data class StreakTierVisuals(
    val id: StreakTierId,
    @ColorInt val color: Int,
    @ColorInt val glowColor: Int,
    val label: String,
    val emoji: String,
    val animFile: Int,
    val frameSet: IntArray
)

object StreakTierHelper {
    fun resolve(streakCount: Int): StreakTierVisuals = when {
        streakCount <= 0 -> visuals(
            id = StreakTierId.BROKEN,
            color = "#6b7280",
            glowColor = "#406b7280",
            label = "Broken",
            emoji = "\uD83D\uDC80",
            frames = intArrayOf(
                R.drawable.ic_fire_widget_broken_1,
                R.drawable.ic_fire_widget_broken_2,
                R.drawable.ic_fire_widget_broken_3
            )
        )
        streakCount <= 6 -> visuals(StreakTierId.STARTER, "#f97316", "#40f97316", "Starter", "\uD83D\uDD25", intArrayOf(R.drawable.ic_fire_widget_starter_1, R.drawable.ic_fire_widget_starter_2, R.drawable.ic_fire_widget_starter_3))
        streakCount <= 29 -> visuals(StreakTierId.RISING, "#eab308", "#40eab308", "Rising", "\uD83D\uDD25", intArrayOf(R.drawable.ic_fire_widget_rising_1, R.drawable.ic_fire_widget_rising_2, R.drawable.ic_fire_widget_rising_3))
        streakCount <= 59 -> visuals(StreakTierId.BLAZING, "#f59e0b", "#40f59e0b", "Blazing", "\uD83D\uDD25", intArrayOf(R.drawable.ic_fire_widget_blazing_1, R.drawable.ic_fire_widget_blazing_2, R.drawable.ic_fire_widget_blazing_3))
        streakCount <= 99 -> visuals(StreakTierId.LEGENDARY, "#a855f7", "#40a855f7", "Legendary", "\uD83D\uDC9C", intArrayOf(R.drawable.ic_fire_widget_legendary_1, R.drawable.ic_fire_widget_legendary_2, R.drawable.ic_fire_widget_legendary_3))
        streakCount <= 199 -> visuals(StreakTierId.EPIC, "#3b82f6", "#403b82f6", "Epic", "\uD83D\uDCA7", intArrayOf(R.drawable.ic_fire_widget_epic_1, R.drawable.ic_fire_widget_epic_2, R.drawable.ic_fire_widget_epic_3))
        else -> visuals(StreakTierId.GOD, "#ec4899", "#40ec4899", "GOD", "\u2728", intArrayOf(R.drawable.ic_fire_widget_god_1, R.drawable.ic_fire_widget_god_2, R.drawable.ic_fire_widget_god_3))
    }

    private fun visuals(
        id: StreakTierId,
        color: String,
        glowColor: String,
        label: String,
        emoji: String,
        frames: IntArray
    ) = StreakTierVisuals(
        id = id,
        color = Color.parseColor(color),
        glowColor = Color.parseColor(glowColor),
        label = label,
        emoji = emoji,
        animFile = R.drawable.ic_fire_animated,
        frameSet = frames
    )
}
```

```kotlin
// app/src/main/java/com/novahorizon/wanderly/data/ProfileRepository.kt
_currentProfile.value = profile
preferencesStore.cacheProfileStreakState(
    lastMissionDate = profile.last_mission_date,
    streakCount = profile.streak_count
)
preferencesStore.saveWidgetSnapshot(profile)
```

- [ ] **Step 5: Run the tier test to verify it passes**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.novahorizon.wanderly.widgets.StreakTierHelperTest"
```

Expected: PASS with 3 tests completed successfully.

- [ ] **Step 6: Commit the shared tier and widget snapshot foundation**

```powershell
git add app/src/main/java/com/novahorizon/wanderly/Constants.kt `
        app/src/main/java/com/novahorizon/wanderly/data/DataStoreManager.kt `
        app/src/main/java/com/novahorizon/wanderly/data/PreferencesStore.kt `
        app/src/main/java/com/novahorizon/wanderly/data/ProfileRepository.kt `
        app/src/main/java/com/novahorizon/wanderly/widgets/StreakTierHelper.kt `
        app/src/main/java/com/novahorizon/wanderly/widgets/WidgetStreakSnapshot.kt `
        app/src/test/java/com/novahorizon/wanderly/widgets/StreakTierHelperTest.kt
git commit -m "feat: add shared streak tier model and widget snapshot cache"
```

### Task 2: Rebuild widget state and scheduling helpers around stale-state and frame rotation

**Files:**
- Create: `app/src/main/java/com/novahorizon/wanderly/widgets/StreakWidgetAlarmScheduler.kt`
- Create: `app/src/test/java/com/novahorizon/wanderly/widgets/StreakWidgetAlarmSchedulerTest.kt`
- Modify: `app/src/main/java/com/novahorizon/wanderly/widgets/StreakWidgetStateHelper.kt`
- Modify: `app/src/test/java/com/novahorizon/wanderly/widgets/StreakWidgetStateHelperTest.kt`

- [ ] **Step 1: Write the failing stale-state and scheduler tests**

```kotlin
package com.novahorizon.wanderly.widgets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreakWidgetAlarmSchedulerTest {

    @Test
    fun `uses exact alarms below android s`() {
        assertTrue(StreakWidgetAlarmScheduler.shouldUseExactAlarm(30, canScheduleExactAlarms = false))
    }

    @Test
    fun `falls back when android s exact alarm permission is unavailable`() {
        assertFalse(StreakWidgetAlarmScheduler.shouldUseExactAlarm(31, canScheduleExactAlarms = false))
    }
}
```

```kotlin
@Test
fun resolveVisualStateShowsBrokenCopyWhenStreakIsZero() {
    val snapshot = WidgetStreakSnapshot(
        streakCount = 0,
        lastMissionDate = null,
        honey = 0,
        flights = 0,
        savedAtMillis = 0L,
        lastSyncSucceeded = false
    )

    val state = StreakWidgetStateHelper.resolveVisualState(
        snapshot = snapshot,
        nowMillis = 1_000L,
        fetchSucceeded = false
    )

    assertEquals("Start again today.", state.subtitle)
    assertFalse(state.showStaleIndicator)
}

@Test
fun resolveVisualStateShowsStaleIndicatorWhenCacheIsOlderThanFiveMinutes() {
    val snapshot = WidgetStreakSnapshot(
        streakCount = 58,
        lastMissionDate = "2026-04-21",
        honey = 2900,
        flights = 58,
        savedAtMillis = 1_000L,
        lastSyncSucceeded = false
    )

    val state = StreakWidgetStateHelper.resolveVisualState(
        snapshot = snapshot,
        nowMillis = 1_000L + StreakWidgetStateHelper.STALE_AFTER_MILLIS + 1L,
        fetchSucceeded = false
    )

    assertTrue(state.showStaleIndicator)
}
```

- [ ] **Step 2: Run the focused widget helper tests to verify they fail**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.novahorizon.wanderly.widgets.StreakWidgetAlarmSchedulerTest" --tests "com.novahorizon.wanderly.widgets.StreakWidgetStateHelperTest"
```

Expected: FAIL with unresolved helper methods and mismatched constructor signatures.

- [ ] **Step 3: Add the scheduling helper**

```kotlin
package com.novahorizon.wanderly.widgets

import android.os.Build

object StreakWidgetAlarmScheduler {
    const val REFRESH_INTERVAL_MILLIS = 15_000L

    fun nextTriggerAt(nowMillis: Long): Long = nowMillis + REFRESH_INTERVAL_MILLIS

    fun shouldUseExactAlarm(
        sdkInt: Int = Build.VERSION.SDK_INT,
        canScheduleExactAlarms: Boolean
    ): Boolean {
        return sdkInt < Build.VERSION_CODES.S || canScheduleExactAlarms
    }
}
```

- [ ] **Step 4: Replace the old widget state helper with snapshot-driven state**

```kotlin
package com.novahorizon.wanderly.widgets

data class StreakWidgetVisualState(
    val tier: StreakTierVisuals,
    val frameRes: Int,
    val subtitle: String,
    val streakText: String,
    val honeyText: String,
    val flightsText: String,
    val lastUpdatedText: String,
    val showStaleIndicator: Boolean
)

object StreakWidgetStateHelper {
    const val STALE_AFTER_MILLIS = 5 * 60 * 1000L

    fun resolveVisualState(
        snapshot: WidgetStreakSnapshot,
        nowMillis: Long,
        fetchSucceeded: Boolean
    ): StreakWidgetVisualState {
        val tier = StreakTierHelper.resolve(snapshot.streakCount)
        val stale = !fetchSucceeded && snapshot.savedAtMillis > 0L && nowMillis - snapshot.savedAtMillis > STALE_AFTER_MILLIS
        val frameRes = tier.frameSet[((nowMillis / StreakWidgetAlarmScheduler.REFRESH_INTERVAL_MILLIS) % tier.frameSet.size).toInt()]

        return StreakWidgetVisualState(
            tier = tier,
            frameRes = frameRes,
            subtitle = if (snapshot.streakCount <= 0) "Start again today." else "One flight keeps it glowing.",
            streakText = snapshot.streakCount.toString(),
            honeyText = snapshot.honey.toString(),
            flightsText = snapshot.flights.toString(),
            lastUpdatedText = formatLastUpdated(snapshot.savedAtMillis),
            showStaleIndicator = stale
        )
    }

    internal fun formatLastUpdated(savedAtMillis: Long): String {
        if (savedAtMillis <= 0L) return "--:--"
        val formatter = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
        return formatter.format(java.util.Date(savedAtMillis))
    }
}
```

- [ ] **Step 5: Run the widget helper tests to verify they pass**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.novahorizon.wanderly.widgets.StreakWidgetAlarmSchedulerTest" --tests "com.novahorizon.wanderly.widgets.StreakWidgetStateHelperTest"
```

Expected: PASS with stale-state, broken-state, and scheduler tests green.

- [ ] **Step 6: Commit the widget helper refactor**

```powershell
git add app/src/main/java/com/novahorizon/wanderly/widgets/StreakWidgetAlarmScheduler.kt `
        app/src/main/java/com/novahorizon/wanderly/widgets/StreakWidgetStateHelper.kt `
        app/src/test/java/com/novahorizon/wanderly/widgets/StreakWidgetAlarmSchedulerTest.kt `
        app/src/test/java/com/novahorizon/wanderly/widgets/StreakWidgetStateHelperTest.kt
git commit -m "feat: add stale-aware widget state and alarm scheduling helpers"
```

### Task 3: Implement the widget fetch-save-render-schedule pipeline

**Files:**
- Modify: `app/src/main/java/com/novahorizon/wanderly/widgets/WanderlyStreakWidgetProvider.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/res/xml/wanderly_widget_info.xml`

- [ ] **Step 1: Write the provider-facing failing tests as pure helper assertions**

```kotlin
@Test
fun `uses best effort alarm path when exact alarms are denied on android s`() {
    assertFalse(StreakWidgetAlarmScheduler.shouldUseExactAlarm(31, canScheduleExactAlarms = false))
}

@Test
fun `keeps stale dot hidden when fetch succeeded with fresh save`() {
    val state = StreakWidgetStateHelper.resolveVisualState(
        snapshot = WidgetStreakSnapshot(
            streakCount = 12,
            lastMissionDate = "2026-04-22",
            honey = 600,
            flights = 12,
            savedAtMillis = 10_000L,
            lastSyncSucceeded = true
        ),
        nowMillis = 20_000L,
        fetchSucceeded = true
    )

    assertFalse(state.showStaleIndicator)
}
```

- [ ] **Step 2: Run the focused test command to verify the pipeline assumptions still hold**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.novahorizon.wanderly.widgets.StreakWidgetStateHelperTest"
```

Expected: PASS before provider edits so the helper contract is fixed in place.

- [ ] **Step 3: Add exact-alarm permission and disable framework periodic updates**

```xml
<!-- app/src/main/AndroidManifest.xml -->
<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />
```

```xml
<!-- app/src/main/res/xml/wanderly_widget_info.xml -->
<appwidget-provider
    android:description="@string/widget_streak_description"
    android:initialLayout="@layout/widget_layout"
    android:label="@string/widget_streak_name"
    android:minWidth="250dp"
    android:minHeight="120dp"
    android:targetCellWidth="4"
    android:targetCellHeight="2"
    android:resizeMode="horizontal|vertical"
    android:updatePeriodMillis="0"
    android:widgetCategory="home_screen" />
```

- [ ] **Step 4: Rewrite the provider around fetch -> save -> update**

```kotlin
override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
    updateAsync {
        refreshAndRender(context, appWidgetManager, appWidgetIds)
        scheduleNextUpdate(context)
    }
}

private suspend fun refreshAndRender(
    context: Context,
    appWidgetManager: AppWidgetManager,
    appWidgetIds: IntArray
) {
    val repository = WanderlyGraph.repository(context)
    val preferencesStore = PreferencesStore(context)

    val fetchSucceeded = repository.getCurrentProfile()?.let { profile ->
        preferencesStore.saveWidgetSnapshot(profile)
        true
    } ?: run {
        preferencesStore.markWidgetSyncFailed()
        false
    }

    val snapshot = preferencesStore.getWidgetSnapshot()
    val visualState = StreakWidgetStateHelper.resolveVisualState(
        snapshot = snapshot,
        nowMillis = System.currentTimeMillis(),
        fetchSucceeded = fetchSucceeded
    )

    appWidgetIds.forEach { appWidgetId ->
        val views = RemoteViews(context.packageName, R.layout.widget_layout).apply {
            setTextViewText(R.id.widget_streak_count, visualState.streakText)
            setTextViewText(R.id.widget_message, visualState.subtitle)
            setTextViewText(R.id.widget_honey_value, visualState.honeyText)
            setTextViewText(R.id.widget_streak_value, visualState.streakText)
            setTextViewText(R.id.widget_flights_value, visualState.flightsText)
            setTextViewText(R.id.widget_last_updated, visualState.lastUpdatedText)
            setImageViewResource(R.id.widget_fire_icon, visualState.frameRes)
            setTextColor(R.id.widget_streak_count, visualState.tier.color)
            setInt(R.id.widget_border_top, "setBackgroundColor", visualState.tier.color)
            setInt(R.id.widget_border_bottom, "setBackgroundColor", visualState.tier.color)
            setViewVisibility(
                R.id.widget_stale_dot,
                if (visualState.showStaleIndicator) View.VISIBLE else View.GONE
            )
            setOnClickPendingIntent(R.id.widget_container, mainActivityPendingIntent(context))
        }
        appWidgetManager.updateAppWidget(appWidgetId, views)
    }
}

private fun scheduleNextUpdate(context: Context) {
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val triggerAtMillis = StreakWidgetAlarmScheduler.nextTriggerAt(System.currentTimeMillis())
    val pendingIntent = refreshPendingIntent(context)
    val canUseExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        alarmManager.canScheduleExactAlarms()
    } else {
        true
    }

    if (StreakWidgetAlarmScheduler.shouldUseExactAlarm(Build.VERSION.SDK_INT, canUseExact)) {
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
    } else {
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
    }
}
```

- [ ] **Step 5: Run unit tests to verify the provider-supporting behavior still passes**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.novahorizon.wanderly.widgets.StreakTierHelperTest" --tests "com.novahorizon.wanderly.widgets.StreakWidgetAlarmSchedulerTest" --tests "com.novahorizon.wanderly.widgets.StreakWidgetStateHelperTest"
```

Expected: PASS with all widget helper suites still green after provider integration.

- [ ] **Step 6: Commit the widget pipeline**

```powershell
git add app/src/main/java/com/novahorizon/wanderly/widgets/WanderlyStreakWidgetProvider.kt `
        app/src/main/AndroidManifest.xml `
        app/src/main/res/xml/wanderly_widget_info.xml
git commit -m "feat: refresh streak widget from api-backed alarm ticks"
```

### Task 4: Build the widget layout, strings, colors, and 21 fire-frame drawables

**Files:**
- Create: `app/src/main/res/layout/widget_layout.xml`
- Create: `app/src/main/res/drawable/widget_background.xml`
- Create: `app/src/main/res/values/streak_colors.xml`
- Create all 21 `ic_fire_widget_*_*.xml` drawables
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values/colors.xml`
- Modify: `app/src/main/res/values/dimens.xml`

- [ ] **Step 1: Write the resource-backed failing test by referencing new drawables from the tier helper suite**

```kotlin
@Test
fun `starter tier exposes the three starter widget frames`() {
    val tier = StreakTierHelper.resolve(3)

    assertArrayEquals(
        intArrayOf(
            R.drawable.ic_fire_widget_starter_1,
            R.drawable.ic_fire_widget_starter_2,
            R.drawable.ic_fire_widget_starter_3
        ),
        tier.frameSet
    )
}
```

- [ ] **Step 2: Run the tier helper test to verify it fails on missing resources**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.novahorizon.wanderly.widgets.StreakTierHelperTest"
```

Expected: FAIL from missing `R.drawable.ic_fire_widget_*` references and the new `widget_layout` resource.

- [ ] **Step 3: Add the shared strings, colors, and dimensions**

```xml
<!-- app/src/main/res/values/streak_colors.xml -->
<resources>
    <color name="streak_broken">#6b7280</color>
    <color name="streak_broken_glow">#406b7280</color>
    <color name="streak_starter">#f97316</color>
    <color name="streak_starter_glow">#40f97316</color>
    <color name="streak_rising">#eab308</color>
    <color name="streak_rising_glow">#40eab308</color>
    <color name="streak_blazing">#f59e0b</color>
    <color name="streak_blazing_glow">#40f59e0b</color>
    <color name="streak_legendary">#a855f7</color>
    <color name="streak_legendary_glow">#40a855f7</color>
    <color name="streak_epic">#3b82f6</color>
    <color name="streak_epic_glow">#403b82f6</color>
    <color name="streak_god">#ec4899</color>
    <color name="streak_god_glow">#40ec4899</color>
    <color name="widget_panel_start">#110900</color>
    <color name="widget_panel_end">#2a1800</color>
    <color name="widget_stale_dot">#6b7280</color>
</resources>
```

```xml
<!-- app/src/main/res/values/dimens.xml -->
<dimen name="widget_fire_size">44dp</dimen>
<dimen name="widget_border_radius">28dp</dimen>
<dimen name="profile_streak_badge_size">48dp</dimen>
```

```xml
<!-- app/src/main/res/values/strings.xml -->
<string name="widget_streak_heading">CURRENT STREAK</string>
<string name="widget_streak_message_active">One flight keeps it glowing.</string>
<string name="widget_streak_message_broken">Start again today.</string>
<string name="widget_last_updated_prefix">Updated %1$s</string>
<string name="profile_streak_badge_format">%1$s %2$s</string>
```

- [ ] **Step 4: Create the new widget layout and frame drawables**

```xml
<!-- app/src/main/res/layout/widget_layout.xml -->
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/widget_container"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@drawable/widget_background"
    android:padding="16dp">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:orientation="vertical">

        <TextView
            android:id="@+id/widget_label"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="@string/widget_streak_heading"
            android:textColor="@color/pollen_white"
            android:textStyle="bold" />

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="0dp"
            android:layout_weight="1"
            android:gravity="center_vertical"
            android:orientation="horizontal">

            <TextView
                android:id="@+id/widget_streak_count"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:textColor="@color/streak_blazing"
                android:textSize="40sp"
                android:textStyle="bold" />

            <ImageView
                android:id="@+id/widget_fire_icon"
                android:layout_width="@dimen/widget_fire_size"
                android:layout_height="@dimen/widget_fire_size"
                android:contentDescription="@null" />
        </LinearLayout>

        <TextView
            android:id="@+id/widget_message"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:textColor="@color/pollen_white" />

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="12dp"
            android:orientation="horizontal">

            <LinearLayout android:layout_width="0dp" android:layout_height="wrap_content" android:layout_weight="1" android:orientation="vertical">
                <TextView android:layout_width="wrap_content" android:layout_height="wrap_content" android:text="Honey" android:textColor="@color/text_secondary" />
                <TextView android:id="@+id/widget_honey_value" android:layout_width="wrap_content" android:layout_height="wrap_content" android:textColor="@color/pollen_white" />
            </LinearLayout>

            <LinearLayout android:layout_width="0dp" android:layout_height="wrap_content" android:layout_weight="1" android:orientation="vertical">
                <TextView android:layout_width="wrap_content" android:layout_height="wrap_content" android:text="Streak" android:textColor="@color/text_secondary" />
                <TextView android:id="@+id/widget_streak_value" android:layout_width="wrap_content" android:layout_height="wrap_content" android:textColor="@color/pollen_white" />
            </LinearLayout>

            <LinearLayout android:layout_width="0dp" android:layout_height="wrap_content" android:layout_weight="1" android:orientation="vertical">
                <TextView android:layout_width="wrap_content" android:layout_height="wrap_content" android:text="Flights" android:textColor="@color/text_secondary" />
                <TextView android:id="@+id/widget_flights_value" android:layout_width="wrap_content" android:layout_height="wrap_content" android:textColor="@color/pollen_white" />
            </LinearLayout>
        </LinearLayout>

        <TextView
            android:id="@+id/widget_last_updated"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_gravity="end"
            android:textColor="@color/text_secondary"
            android:textSize="10sp" />
    </LinearLayout>

    <View
        android:id="@+id/widget_stale_dot"
        android:layout_width="8dp"
        android:layout_height="8dp"
        android:layout_gravity="top|end"
        android:background="@android:color/darker_gray"
        android:visibility="gone" />

    <View
        android:id="@+id/widget_border_top"
        android:layout_width="match_parent"
        android:layout_height="2dp"
        android:layout_gravity="top" />

    <View
        android:id="@+id/widget_border_bottom"
        android:layout_width="match_parent"
        android:layout_height="2dp"
        android:layout_gravity="bottom" />
</FrameLayout>
```

```xml
<!-- app/src/main/res/drawable/widget_background.xml -->
<shape xmlns:android="http://schemas.android.com/apk/res/android">
    <gradient
        android:angle="270"
        android:startColor="@color/widget_panel_start"
        android:endColor="@color/widget_panel_end" />
    <corners android:radius="@dimen/widget_border_radius" />
</shape>
```

```xml
<!-- app/src/main/res/drawable/ic_fire_widget_blazing_1.xml -->
<layer-list xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:alpha="0.25">
        <shape android:shape="oval">
            <size android:width="32dp" android:height="32dp" />
            <solid android:color="@color/streak_blazing_glow" />
        </shape>
    </item>
    <item>
        <vector
            android:width="32dp"
            android:height="32dp"
            android:viewportWidth="32"
            android:viewportHeight="32">
            <path android:fillColor="@color/streak_blazing" android:pathData="M16,2 C12,8 8,11 8,18 C8,25 11.8,30 16,30 C20.2,30 24,25 24,18 C24,12 21,8 16,2 Z" />
            <path android:fillColor="#00000000" android:fillType="evenOdd" android:pathData="M16,2 C12,8 8,11 8,18 C8,25 11.8,30 16,30 C20.2,30 24,25 24,18 C24,12 21,8 16,2 Z M16,12 A4,4 0 1,1 15.99,12 Z" />
        </vector>
    </item>
</layer-list>
```

Create the remaining 20 frame files by keeping the same layer-list structure, varying the flame `pathData` slightly for `_2` and `_3`, and swapping the color resources to the matching tier family for `broken`, `starter`, `rising`, `legendary`, `epic`, and `god`.

- [ ] **Step 5: Run the tier helper test to verify resources now compile**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.novahorizon.wanderly.widgets.StreakTierHelperTest"
```

Expected: PASS with resource-backed drawable ids resolved.

- [ ] **Step 6: Commit the widget visuals**

```powershell
git add app/src/main/res/layout/widget_layout.xml `
        app/src/main/res/drawable/widget_background.xml `
        app/src/main/res/drawable/ic_fire_widget_*.xml `
        app/src/main/res/values/streak_colors.xml `
        app/src/main/res/values/strings.xml `
        app/src/main/res/values/colors.xml `
        app/src/main/res/values/dimens.xml
git commit -m "feat: add tiered streak widget visuals and fire frames"
```

### Task 5: Replace the profile halo with the new streak section and animated badge

**Files:**
- Create: `app/src/main/res/layout/profile_streak_section.xml`
- Create: `app/src/main/res/drawable/ic_fire_animated.xml`
- Create: `app/src/main/res/drawable/fire_morph_1.xml`
- Create: `app/src/main/res/drawable/fire_morph_2.xml`
- Create: `app/src/main/res/drawable/fire_morph_3.xml`
- Create: `app/src/main/res/drawable/tier_badge_background.xml`
- Create: `app/src/main/res/animator/fire_path_morph.xml`
- Modify: `app/src/main/res/layout/fragment_profile.xml`
- Modify: `app/src/main/java/com/novahorizon/wanderly/ui/profile/ProfileFragment.kt`
- Modify: `app/src/test/java/com/novahorizon/wanderly/ui/profile/ProfileFragmentAvatarPresentationTest.kt`

- [ ] **Step 1: Write the failing profile helper tests for the new badge state**

```kotlin
@Test
fun `builds broken badge text for zero streak`() {
    val badgeText = ProfileFragment.buildTierBadgeText(0)

    assertEquals("\uD83D\uDC80 Broken", badgeText)
}

@Test
fun `exposes blazing tier color targets for profile streak section`() {
    val visuals = ProfileFragment.resolveProfileTierVisuals(58)

    assertEquals("Blazing", visuals.label)
    assertEquals("\uD83D\uDD25", visuals.emoji)
    assertEquals(R.drawable.ic_fire_animated, visuals.animDrawableRes)
}
```

- [ ] **Step 2: Run the profile helper test to verify it fails**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.novahorizon.wanderly.ui.profile.ProfileFragmentAvatarPresentationTest"
```

Expected: FAIL with missing helper methods for badge text and resolved tier visuals.

- [ ] **Step 3: Add the profile streak section layout and animated fire drawable**

```xml
<!-- app/src/main/res/layout/profile_streak_section.xml -->
<merge xmlns:android="http://schemas.android.com/apk/res/android">
    <FrameLayout
        android:id="@+id/profile_streak_avatar_frame"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content">

        <ImageView
            android:id="@+id/profile_fire_badge"
            android:layout_width="@dimen/profile_streak_badge_size"
            android:layout_height="@dimen/profile_streak_badge_size"
            android:layout_gravity="bottom|end"
            android:contentDescription="@null"
            android:src="@drawable/ic_fire_animated" />
    </FrameLayout>

    <TextView
        android:id="@+id/profile_tier_badge"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:background="@drawable/tier_badge_background"
        android:paddingHorizontal="16dp"
        android:paddingVertical="8dp" />

    <TextView
        android:id="@+id/profile_streak_value"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:textSize="28sp"
        android:textStyle="bold" />
</merge>
```

```xml
<!-- app/src/main/res/drawable/tier_badge_background.xml -->
<shape xmlns:android="http://schemas.android.com/apk/res/android">
    <solid android:color="@color/streak_blazing" />
    <corners android:radius="999dp" />
</shape>
```

```xml
<!-- app/src/main/res/drawable/fire_morph_1.xml -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="48dp"
    android:height="48dp"
    android:viewportWidth="48"
    android:viewportHeight="48">
    <path
        android:name="flame"
        android:fillColor="@color/streak_blazing"
        android:fillType="evenOdd"
        android:pathData="M24,4 C18,12 12,18 12,28 C12,38 17.5,44 24,44 C30.5,44 36,38 36,28 C36,20 31.5,12 24,4 Z M24,19 A5,5 0 1,1 23.99,19 Z" />
</vector>
```

```xml
<!-- app/src/main/res/drawable/ic_fire_animated.xml -->
<animated-vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:drawable="@drawable/fire_morph_1">
    <target
        android:name="flame"
        android:animation="@animator/fire_path_morph" />
</animated-vector>
```

```xml
<!-- app/src/main/res/animator/fire_path_morph.xml -->
<set xmlns:android="http://schemas.android.com/apk/res/android">
    <objectAnimator
        android:duration="450"
        android:propertyName="pathData"
        android:repeatCount="infinite"
        android:repeatMode="reverse"
        android:valueFrom="M24,4 C18,12 12,18 12,28 C12,38 17.5,44 24,44 C30.5,44 36,38 36,28 C36,20 31.5,12 24,4 Z M24,19 A5,5 0 1,1 23.99,19 Z"
        android:valueTo="M24,5 C16,13 10,20 10,29 C10,39 16.5,44 24,44 C31.5,44 38,39 38,29 C38,21 33,13 24,5 Z M24,18 A4.5,4.5 0 1,1 23.99,18 Z"
        android:valueType="pathType" />
</set>
```

- [ ] **Step 4: Replace the old halo logic with tier-aware profile rendering**

```kotlin
internal data class ProfileTierVisuals(
    val label: String,
    val emoji: String,
    val animDrawableRes: Int,
    val color: Int
)

internal fun buildTierBadgeText(streakCount: Int): String {
    val tier = StreakTierHelper.resolve(streakCount)
    return "${tier.emoji} ${tier.label}"
}

internal fun resolveProfileTierVisuals(streakCount: Int): ProfileTierVisuals {
    val tier = StreakTierHelper.resolve(streakCount)
    return ProfileTierVisuals(
        label = tier.label,
        emoji = tier.emoji,
        animDrawableRes = tier.animFile,
        color = tier.color
    )
}

override fun onResume() {
    super.onResume()
    refreshLocalStreakSection()
    startProfileFireAnimation()
    viewModel.loadProfile()
}

private fun refreshLocalStreakSection() {
    viewLifecycleOwner.lifecycleScope.launch {
        val snapshot = PreferencesStore(requireContext()).getWidgetSnapshot()
        applyStreakSection(snapshot.streakCount, snapshot.honey, snapshot.flights)
    }
}

private fun startProfileFireAnimation() {
    (binding.profileFireBadge.drawable as? android.graphics.drawable.AnimatedVectorDrawable)?.start()
}

private fun applyStreakSection(streakCount: Int, honey: Int, flights: Int) {
    val tier = resolveProfileTierVisuals(streakCount)
    binding.profileTierBadge.text = buildTierBadgeText(streakCount)
    binding.profileStreakValue.text = streakCount.toString()
    binding.profileFireBadge.setImageResource(tier.animDrawableRes)
    animateTierColor(tier.color)
    binding.honeyTotal.text = formatWholeNumber(honey)
    binding.streakCount.text = formatWholeNumber(streakCount)
    binding.missionsCompletedCount.text = formatWholeNumber(flights)
}

private fun animateTierColor(targetColor: Int) {
    val startColor = (binding.profileStreakValue.currentTextColor)
    android.animation.ValueAnimator.ofArgb(startColor, targetColor).apply {
        duration = 600L
        addUpdateListener { animator ->
            val color = animator.animatedValue as Int
            binding.profileStreakValue.setTextColor(color)
            binding.profileTierBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
        }
        start()
    }
}
```

- [ ] **Step 5: Run the profile unit test suite to verify it passes**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.novahorizon.wanderly.ui.profile.ProfileFragmentAvatarPresentationTest"
```

Expected: PASS with the new badge text and tier helper assertions green.

- [ ] **Step 6: Commit the profile streak section**

```powershell
git add app/src/main/res/layout/profile_streak_section.xml `
        app/src/main/res/layout/fragment_profile.xml `
        app/src/main/res/drawable/ic_fire_animated.xml `
        app/src/main/res/drawable/fire_morph_1.xml `
        app/src/main/res/drawable/fire_morph_2.xml `
        app/src/main/res/drawable/fire_morph_3.xml `
        app/src/main/res/drawable/tier_badge_background.xml `
        app/src/main/res/animator/fire_path_morph.xml `
        app/src/main/java/com/novahorizon/wanderly/ui/profile/ProfileFragment.kt `
        app/src/test/java/com/novahorizon/wanderly/ui/profile/ProfileFragmentAvatarPresentationTest.kt
git commit -m "feat: add animated streak section to profile"
```

### Task 6: Run the full verification sweep

**Files:**
- Verify only

- [ ] **Step 1: Run the complete debug unit test suite**

Run:

```powershell
.\gradlew.bat testDebugUnitTest
```

Expected: PASS with widget, profile, repository, and existing unit suites all green.

- [ ] **Step 2: Build the debug app to verify resources, manifest, and widget layout wiring**

Run:

```powershell
.\gradlew.bat assembleDebug
```

Expected: BUILD SUCCESSFUL with all new drawables, layouts, and manifest changes compiled.

- [ ] **Step 3: Manually verify the widget and profile behavior on a device or emulator**

Check:

```text
1. Add the widget at 4x2 size and wait one 15-second interval.
2. Confirm the widget fetches fresh profile data and updates the timestamp.
3. Disable exact alarm access on Android 12+ and confirm the widget still refreshes without a crash.
4. Force a failed network fetch and confirm the stale gray dot appears only after cached data is older than 5 minutes.
5. Set streak to 0 and confirm the widget shows Broken + "Start again today."
6. Open the profile screen, background it, return, and confirm the fire badge starts animating again.
7. Set streak to 58 and confirm the tier label shows Blazing on profile and the widget count tint changes.
```

- [ ] **Step 4: Stage and commit the verified implementation**

```powershell
git add app/src/main
git add app/src/test
git commit -m "feat: refresh streak widget and profile tier visuals"
```

## Self-Review

- Spec coverage: this plan covers the API-backed widget tick, exact-alarm fallback, 7-tier model, stale indicator, 21 widget fire frames, new profile streak section, and test coverage.
- Placeholder scan: no `TODO`, `TBD`, or deferred implementation notes remain in task steps.
- Type consistency: `StreakTierHelper.resolve`, `PreferencesStore.getWidgetSnapshot`, `StreakWidgetStateHelper.resolveVisualState`, and `ProfileFragment.buildTierBadgeText` use one consistent naming scheme across tasks.

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-04-22-streak-widget-profile-refresh.md`. Two execution options:

**1. Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints

**Which approach?**
