package com.novahorizon.wanderly.data.mission

import javax.inject.Inject

class DeterministicMissionQueryProvider @Inject constructor() {

    fun getQueries(city: String): List<String> {
        val safeCity = city.trim().ifBlank { "nearby" }
        return listOf(
            "tourist attractions in $safeCity",
            "parks in $safeCity",
            "monuments in $safeCity",
            "museums in $safeCity",
            "public art in $safeCity",
            "historic landmarks in $safeCity",
            "scenic viewpoints in $safeCity",
            "cultural landmarks in $safeCity",
            "walking-friendly places in $safeCity",
            "hidden gems in $safeCity",
            "points of interest in $safeCity",
            "landmarks near $safeCity center"
        )
    }
}
