package com.novahorizon.wanderly.data

object AvatarPathBuilder {
    fun build(uid: String, mimeType: String): String {
        return "profiles/$uid/avatar.jpg"
    }
}
