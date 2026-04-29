package com.novahorizon.wanderly.data

object AvatarPathBuilder {
    fun build(uid: String, mimeType: String): String {
        val extension = when (mimeType) {
            "image/webp" -> "webp"
            "image/png" -> "png"
            else -> "jpg"
        }
        return "profiles/$uid/avatar.$extension"
    }
}
