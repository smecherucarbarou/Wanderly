package com.novahorizon.wanderly.api

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlacesGeocoderNameMatchTest {

    @Test
    fun strictMatchAcceptsCloselyRelatedNames() {
        assertTrue(
            PlacesGeocoder.isPlaceNameCompatible(
                requestedName = "Gate of the Kiss",
                candidateName = "The Kissing Gate",
                strict = true
            )
        )
    }

    @Test
    fun strictMatchRejectsHallucinatedOfficeFallback() {
        assertFalse(
            PlacesGeocoder.isPlaceNameCompatible(
                requestedName = "Penne & Co",
                candidateName = "Complexul Energetic Oltenia",
                strict = true
            )
        )
    }

    @Test
    fun strictMatchRejectsUnrelatedNearbyFallback() {
        assertFalse(
            PlacesGeocoder.isPlaceNameCompatible(
                requestedName = "G-Point Lounge",
                candidateName = "Margit",
                strict = true
            )
        )
    }
}
