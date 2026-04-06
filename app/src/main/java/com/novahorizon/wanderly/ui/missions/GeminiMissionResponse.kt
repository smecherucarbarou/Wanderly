package com.novahorizon.wanderly.ui.missions

import kotlinx.serialization.Serializable

@Serializable
data class GeminiMissionResponse(
    val missionText: String,
    val targetName: String
)
