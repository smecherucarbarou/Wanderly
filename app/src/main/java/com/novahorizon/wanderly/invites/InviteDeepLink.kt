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
            pathSegments = uri?.pathSegments.orEmpty(),
            inviteQueryCode = uri?.getQueryParameter(Constants.INVITE_QUERY_PARAMETER)
        )
    }

    fun extractFriendCode(
        scheme: String?,
        host: String?,
        pathSegments: List<String>,
        inviteQueryCode: String? = null
    ): String? {
        val nonBlankSegments = pathSegments.filter { it.isNotBlank() }
        val candidate = when {
            scheme == Constants.INVITE_CALLBACK_SCHEME &&
                host == Constants.INVITE_CALLBACK_HOST &&
                nonBlankSegments.size == 1 -> nonBlankSegments.first()

            scheme == Constants.INVITE_WEB_SCHEME &&
                host == Constants.INVITE_WEB_HOST &&
                nonBlankSegments.size == 2 &&
                nonBlankSegments.first() == Constants.INVITE_PATH_SEGMENT -> nonBlankSegments.last()

            scheme == Constants.INVITE_WEB_SCHEME &&
                host == Constants.INVITE_WEB_HOST &&
                nonBlankSegments.isEmpty() &&
                !inviteQueryCode.isNullOrBlank() -> inviteQueryCode

            else -> return null
        }

        return candidate.trim()
            .uppercase(Locale.US)
            .takeIf { friendCodePattern.matches(it) }
    }
}
