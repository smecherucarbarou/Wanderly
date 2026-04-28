package com.novahorizon.wanderly.util

object GeoMath {
    fun distanceKm(userLat: Double, userLng: Double, placeLat: Double, placeLng: Double): Double {
        val earthRadius = 6371.0
        val dLat = Math.toRadians(placeLat - userLat)
        val dLng = Math.toRadians(placeLng - userLng)
        val a = Math.sin(dLat / 2).let { it * it } +
            Math.cos(Math.toRadians(userLat)) *
            Math.cos(Math.toRadians(placeLat)) *
            Math.sin(dLng / 2).let { it * it }
        return earthRadius * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }
}
