package com.novahorizon.wanderly.ui.gems

import android.app.Application
import android.location.Address
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class GemsCityResolverTest {

    private fun address(
        locality: String? = null,
        subAdminArea: String? = null,
        adminArea: String? = null,
        subLocality: String? = null,
        featureName: String? = null
    ): Address {
        return Address(Locale.getDefault()).apply {
            this.locality = locality
            this.subAdminArea = subAdminArea
            this.adminArea = adminArea
            this.subLocality = subLocality
            this.featureName = featureName
        }
    }

    @Test
    fun `null address falls back to this area`() {
        assertEquals("this area", GemsCityResolver.resolveSearchCity(null))
    }

    @Test
    fun `blank address falls back to this area`() {
        assertEquals("this area", GemsCityResolver.resolveSearchCity(address()))
    }

    @Test
    fun `locality is used as-is when present`() {
        assertEquals(
            "Targu Jiu",
            GemsCityResolver.resolveSearchCity(address(locality = "Targu Jiu"))
        )
    }

    @Test
    fun `municipiul prefix is stripped from locality`() {
        assertEquals(
            "Targu Jiu",
            GemsCityResolver.resolveSearchCity(address(locality = "Municipiul Targu Jiu"))
        )
    }

    @Test
    fun `falls back to adminArea when only adminArea present`() {
        assertEquals(
            "Gorj",
            GemsCityResolver.resolveSearchCity(address(adminArea = "Gorj"))
        )
    }

    @Test
    fun `falls back to subLocality when it differs from adminArea and is not county-like`() {
        assertEquals(
            "Centru",
            GemsCityResolver.resolveSearchCity(address(subLocality = "Centru", adminArea = "Gorj"))
        )
    }
}
