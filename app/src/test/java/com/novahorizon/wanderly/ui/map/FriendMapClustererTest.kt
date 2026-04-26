package com.novahorizon.wanderly.ui.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FriendMapClustererTest {

    @Test
    fun `clusters nearby friends together at city zoom`() {
        val clusters = FriendMapClusterer.cluster(
            items = listOf(
                FriendMapPoint("a", 44.4350, 26.1020),
                FriendMapPoint("b", 44.4353, 26.1023),
                FriendMapPoint("c", 44.4580, 26.1640)
            ),
            zoomLevel = 13.0,
            clusterRadiusPx = 120
        )

        assertEquals(2, clusters.size)
        assertTrue(clusters.any { it.memberIds.size == 2 && it.memberIds.containsAll(listOf("a", "b")) })
        assertTrue(clusters.any { it.memberIds == listOf("c") })
    }

    @Test
    fun `splits the same nearby friends at street zoom`() {
        val clusters = FriendMapClusterer.cluster(
            items = listOf(
                FriendMapPoint("a", 44.4350, 26.1020),
                FriendMapPoint("b", 44.4353, 26.1023)
            ),
            zoomLevel = 19.0,
            clusterRadiusPx = 120
        )

        assertEquals(2, clusters.size)
        assertTrue(clusters.all { it.memberIds.size == 1 })
    }
}
