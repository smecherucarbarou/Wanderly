package com.novahorizon.wanderly.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.novahorizon.wanderly.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BottomNavLabelsTest {

    @Test
    fun `bottom navigation labels stay short enough for large font scale`() {
        val context = ApplicationProvider.getApplicationContext<Application>()

        assertEquals("Map", context.getString(R.string.title_map))
        assertEquals("Gems", context.getString(R.string.title_gems))
        assertEquals("Missions", context.getString(R.string.title_missions))
        assertEquals("Hive", context.getString(R.string.title_social))
        assertEquals("Profile", context.getString(R.string.title_profile))
    }
}
