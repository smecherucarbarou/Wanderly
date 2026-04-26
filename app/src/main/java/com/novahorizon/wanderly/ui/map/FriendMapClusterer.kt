package com.novahorizon.wanderly.ui.map

import kotlin.math.PI
import kotlin.math.cos

data class FriendMapPoint(
    val id: String,
    val latitude: Double,
    val longitude: Double
)

data class FriendMapCluster(
    val latitude: Double,
    val longitude: Double,
    val memberIds: List<String>
)

object FriendMapClusterer {

    fun cluster(
        items: List<FriendMapPoint>,
        zoomLevel: Double,
        clusterRadiusPx: Int
    ): List<FriendMapCluster> {
        if (items.isEmpty()) return emptyList()

        val averageLatitude = items.map { it.latitude }.average()
        val radiusMeters = clusterRadiusPx * metersPerPixel(averageLatitude, zoomLevel)
        val remaining = items.toMutableList()
        val clusters = mutableListOf<FriendMapCluster>()

        while (remaining.isNotEmpty()) {
            val seed = remaining.removeAt(0)
            val members = mutableListOf(seed)
            val iterator = remaining.iterator()
            while (iterator.hasNext()) {
                val candidate = iterator.next()
                if (distanceMeters(seed, candidate) <= radiusMeters) {
                    members += candidate
                    iterator.remove()
                }
            }

            clusters += FriendMapCluster(
                latitude = members.map { it.latitude }.average(),
                longitude = members.map { it.longitude }.average(),
                memberIds = members.map { it.id }
            )
        }

        return clusters
    }

    private fun metersPerPixel(latitude: Double, zoomLevel: Double): Double {
        val clampedLatitude = latitude.coerceIn(-85.0, 85.0)
        return 156543.03392 * cos(clampedLatitude * PI / 180.0) / (1 shl zoomLevel.toInt())
    }

    private fun distanceMeters(first: FriendMapPoint, second: FriendMapPoint): Double {
        val latitudeMeters = (first.latitude - second.latitude) * 111_320.0
        val averageLatitude = (first.latitude + second.latitude) / 2.0
        val longitudeMeters = (first.longitude - second.longitude) *
            111_320.0 * cos(averageLatitude * PI / 180.0)
        return kotlin.math.hypot(latitudeMeters, longitudeMeters)
    }
}
