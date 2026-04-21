package com.novahorizon.wanderly.ui.map

import android.content.Context
import org.osmdroid.bonuspack.clustering.RadiusMarkerClusterer
import org.osmdroid.bonuspack.clustering.StaticCluster
import org.osmdroid.bonuspack.overlays.Marker
import org.osmdroid.views.MapView
import kotlin.math.sqrt

class WanderlyRadiusMarkerClusterer(context: Context) : RadiusMarkerClusterer(context) {

    override fun clusterer(mapView: MapView): ArrayList<StaticCluster> {
        val clusters = arrayListOf<StaticCluster>()
        updateRadiusInMeters(mapView)
        val remainingMarkers = ArrayList(items)

        while (remainingMarkers.isNotEmpty()) {
            val seedMarker = remainingMarkers.removeAt(0)
            clusters += createCluster(seedMarker, remainingMarkers, mapView)
        }

        return clusters
    }

    private fun createCluster(
        seedMarker: Marker,
        remainingMarkers: MutableList<Marker>,
        mapView: MapView
    ): StaticCluster {
        val cluster = StaticCluster(seedMarker.position)
        cluster.add(seedMarker)

        if (mapView.zoomLevel <= mMaxClusteringZoomLevel) {
            val iterator = remainingMarkers.iterator()
            while (iterator.hasNext()) {
                val candidate = iterator.next()
                if (seedMarker.position.distanceToAsDouble(candidate.position) <= mRadiusInMeters) {
                    cluster.add(candidate)
                    iterator.remove()
                }
            }
        }

        return cluster
    }

    private fun updateRadiusInMeters(mapView: MapView) {
        val screenRect = mapView.getIntrinsicScreenRect(null)
        val screenWidth = screenRect.right - screenRect.left
        val screenHeight = screenRect.bottom - screenRect.top
        val screenDiagonalPixels = sqrt(
            (screenWidth * screenWidth + screenHeight * screenHeight).toDouble()
        ).coerceAtLeast(1.0)

        val diagonalMeters = mapView.boundingBox.diagonalLengthInMeters
        mRadiusInMeters = mRadiusInPixels * (diagonalMeters / screenDiagonalPixels)
    }
}
