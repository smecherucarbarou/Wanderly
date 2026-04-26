package com.novahorizon.wanderly.invites

import com.novahorizon.wanderly.Constants
import java.util.Locale

object InviteShareFormatter {

    fun format(friendCode: String): String {
        val normalizedFriendCode = normalizeFriendCode(friendCode)
        val inviteUrl = buildInviteUrl(normalizedFriendCode)
        return "Join me on Wanderly!\n$inviteUrl\nFriend code: $normalizedFriendCode"
    }

    fun buildInviteUrl(friendCode: String): String {
        return "${Constants.INVITE_CALLBACK_SCHEME}://${Constants.INVITE_CALLBACK_HOST}/${normalizeFriendCode(friendCode)}"
    }

    private fun normalizeFriendCode(friendCode: String): String {
        return friendCode.trim().uppercase(Locale.US)
    }
}
