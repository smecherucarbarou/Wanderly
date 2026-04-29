package com.novahorizon.wanderly.data

import com.novahorizon.wanderly.BuildConfig
import com.novahorizon.wanderly.api.SupabaseClient
import com.novahorizon.wanderly.auth.AuthSessionCoordinator
import com.novahorizon.wanderly.observability.AppLogger
import com.novahorizon.wanderly.observability.LogRedactor
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
private data class FriendCodeLookupParams(
    val code: String
)

class SocialRepository {
    suspend fun getLeaderboard(): List<Profile> = withContext(Dispatchers.IO) {
        try {
            val session = AuthSessionCoordinator.awaitResolvedSessionOrNull() ?: return@withContext emptyList()
            session.user?.id ?: return@withContext emptyList()

            SupabaseClient.client.postgrest
                .rpc("get_social_leaderboard")
                .decodeList<Profile>()
                .map { it.withDerivedHiveRank() }
        } catch (e: Exception) {
            logError("Error getting leaderboard", e)
            emptyList()
        }
    }

    suspend fun addFriendByCode(friendCode: String): String = withContext(Dispatchers.IO) {
        try {
            val session = AuthSessionCoordinator.awaitResolvedSessionOrNull() ?: return@withContext "Not authenticated"
            val currentUserId = session.user?.id ?: return@withContext "User not authenticated"
            val normalizedCode = normalizeFriendCode(friendCode)
                ?: return@withContext "Friend code must be 6 letters or digits"

            val targetUsers = SupabaseClient.client.postgrest
                .rpc("find_profile_by_friend_code", FriendCodeLookupParams(normalizedCode))
                .decodeList<Profile>()

            if (targetUsers.isEmpty()) return@withContext "Friend code not found"

            val friendId = targetUsers.first().id
            if (friendId == currentUserId) return@withContext "You cannot add yourself"

            SupabaseClient.client.postgrest["friendships"].insert(
                Friendship(user_id = currentUserId, friend_id = friendId, status = "pending")
            )
            "Friend request sent!"
        } catch (e: Exception) {
            if (e.message?.contains("duplicate key") == true || e.message?.contains("unique constraint") == true) {
                "Already requested or friends with this user"
            } else {
                logError("Error adding friend", e)
                "Could not add friend. Please try again."
            }
        }
    }

    suspend fun removeFriend(friendId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val session = AuthSessionCoordinator.awaitResolvedSessionOrNull() ?: return@withContext false
            val currentUserId = session.user?.id ?: return@withContext false

            SupabaseClient.client.postgrest["friendships"].delete {
                filter {
                    or {
                        and {
                            eq("user_id", currentUserId)
                            eq("friend_id", friendId)
                        }
                        and {
                            eq("user_id", friendId)
                            eq("friend_id", currentUserId)
                        }
                    }
                }
            }
            true
        } catch (e: Exception) {
            logError("Error removing friend", e)
            false
        }
    }

    suspend fun getFriends(): List<Profile> = withContext(Dispatchers.IO) {
        try {
            val friendIds = getAcceptedFriendIds()

            if (friendIds.isEmpty()) return@withContext emptyList()

            SupabaseClient.client.postgrest
                .rpc("get_accepted_friend_profiles")
                .decodeList<Profile>()
                .map { it.withDerivedHiveRank() }
        } catch (e: Exception) {
            logError("Error getting friends", e)
            emptyList()
        }
    }

    suspend fun getAcceptedFriendIds(): List<String> = withContext(Dispatchers.IO) {
        try {
            val session = AuthSessionCoordinator.awaitResolvedSessionOrNull() ?: return@withContext emptyList()
            val currentUserId = session.user?.id ?: return@withContext emptyList()

            SupabaseClient.client.postgrest["friendships"]
                .select {
                    filter {
                        eq("status", "accepted")
                        or {
                            eq("user_id", currentUserId)
                            eq("friend_id", currentUserId)
                        }
                    }
                }
                .decodeList<Friendship>()
                .flatMap { friendship -> listOf(friendship.user_id, friendship.friend_id) }
                .filter { userId -> userId != currentUserId }
                .distinct()
        } catch (e: Exception) {
            logError("Error getting accepted friend ids", e)
            emptyList()
        }
    }

    companion object {
        private val FRIEND_CODE_REGEX = Regex("^[A-Z0-9]{6}$")

        internal fun normalizeFriendCode(friendCode: String): String? {
            val normalizedCode = friendCode.trim().uppercase()
            return normalizedCode.takeIf { FRIEND_CODE_REGEX.matches(it) }
        }
    }

    private fun logError(message: String, throwable: Throwable) {
        if (BuildConfig.DEBUG) {
            AppLogger.e(
                "SocialRepository",
                "${LogRedactor.redact(message)} [${throwable.javaClass.simpleName}: ${LogRedactor.redact(throwable.message)}]"
            )
        } else {
            AppLogger.e("SocialRepository", message)
        }
    }
}
