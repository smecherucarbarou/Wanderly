package com.novahorizon.wanderly.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun coreStartupAndNavigation() = baselineProfileRule.collect(
        packageName = PACKAGE_NAME,
        profileBlock = {
            pressHome()
            startActivityAndWait()
            device.waitForIdle()

            clickBottomNavIfPresent("Map")
            clickBottomNavIfPresent("My Hive")
            clickBottomNavIfPresent("Hidden Gems")
            clickBottomNavIfPresent("Missions")
            clickBottomNavIfPresent("Hive")
        }
    )

    private fun MacrobenchmarkScope.clickBottomNavIfPresent(label: String) {
        val node = device.wait(Until.findObject(By.text(label)), NAV_WAIT_MS) ?: return
        node.click()
        device.waitForIdle()
    }

    private companion object {
        const val PACKAGE_NAME = "com.novahorizon.wanderly"
        const val NAV_WAIT_MS = 2_000L
    }
}
