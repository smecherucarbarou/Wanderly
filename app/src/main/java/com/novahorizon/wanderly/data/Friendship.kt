package com.novahorizon.wanderly.data

import kotlinx.serialization.Serializable

@Serializable
data class Friendship(
    val user_id: String,
    val friend_id: String,
    val status: String = "accepted"
)
