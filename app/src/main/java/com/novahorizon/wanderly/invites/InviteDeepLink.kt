package com.novahorizon.wanderly.invites

import android.net.Uri
import com.novahorizon.wanderly.Constants
import java.util.Locale

object InviteDeepLink {
    private val friendCodePattern = Regex("^[A-Z0-9]{6}$")

    fun extractFriendCode(uri: Uri?): String? {
        return extractFriendCode(
            scheme = uri?.scheme,
            host = uri?.host,
            pathSegments = uri?.pathSegments.orEmpty()
        )
    }

    fun extractFriendCode(
        scheme: String?,
        host: String?,
        pathSegments: List<String>
    ): String? {
        if (scheme != Constants.INVITE_CALLBACK_SCHEME || host != Constants.INVITE_CALLBACK_HOST) {
            return null
        }

        val nonBlankSegments = pathSegments.filter { it.isNotBlank() }
        if (nonBlankSegments.size != 1) {
            return null
        }

        val candidate = nonBlankSegments.first().trim().uppercase(Locale.US)
        return candidate.takeIf { friendCodePattern.matches(it) }
    }
}
