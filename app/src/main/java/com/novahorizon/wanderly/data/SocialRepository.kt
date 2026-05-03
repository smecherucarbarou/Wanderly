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

@Serializable
private data class FriendRequestActionParams(
    val p_requester_id: String
)

@Serializable
private data class FriendRequestActionResponse(
    val success: Boolean,
    val error_code: String? = null,
    val error_message: String? = null
)

sealed class AddFriendResult {
    data object FriendRequestSent : AddFriendResult()
    data object AlreadyRequestedOrFriends : AddFriendResult()
    data object NotAuthenticated : AddFriendResult()
    data object InvalidFriendCode : AddFriendResult()
    data object FriendCodeNotFound : AddFriendResult()
    data object SelfFriend : AddFriendResult()
    data object Failure : AddFriendResult()

    fun legacyMessage(): String {
        return when (this) {
            FriendRequestSent -> "Friend request sent!"
            AlreadyRequestedOrFriends -> "Already requested or friends with this user"
            NotAuthenticated -> "Not authenticated"
            InvalidFriendCode -> "Friend code must be 6 letters or digits"
            FriendCodeNotFound -> "Friend code not found"
            SelfFriend -> "You cannot add yourself"
            Failure -> "Could not add friend. Please try again."
        }
    }
}

sealed class FriendRequestActionResult {
    data object Accepted : FriendRequestActionResult()
    data object Rejected : FriendRequestActionResult()
    data object NotAuthenticated : FriendRequestActionResult()
    data object NotPendingRequest : FriendRequestActionResult()
    data object Failure : FriendRequestActionResult()
}

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

    suspend fun addFriendByCode(friendCode: String): String =
        addFriendByCodeResult(friendCode).legacyMessage()

    suspend fun addFriendByCodeResult(friendCode: String): AddFriendResult = withContext(Dispatchers.IO) {
        try {
            val session = AuthSessionCoordinator.awaitResolvedSessionOrNull() ?: return@withContext AddFriendResult.NotAuthenticated
            val currentUserId = session.user?.id ?: return@withContext AddFriendResult.NotAuthenticated
            val normalizedCode = normalizeFriendCode(friendCode)
                ?: return@withContext AddFriendResult.InvalidFriendCode

            val targetUsers = SupabaseClient.client.postgrest
                .rpc("find_profile_by_friend_code", FriendCodeLookupParams(normalizedCode))
                .decodeList<Profile>()

            if (targetUsers.isEmpty()) return@withContext AddFriendResult.FriendCodeNotFound

            val friendId = targetUsers.first().id
            if (friendId == currentUserId) return@withContext AddFriendResult.SelfFriend

            SupabaseClient.client.postgrest["friendships"].insert(
                Friendship(user_id = currentUserId, friend_id = friendId, status = "pending")
            )
            AddFriendResult.FriendRequestSent
        } catch (e: Exception) {
            if (e.message?.contains("duplicate key") == true || e.message?.contains("unique constraint") == true) {
                AddFriendResult.AlreadyRequestedOrFriends
            } else {
                logError("Error adding friend", e)
                AddFriendResult.Failure
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

    suspend fun getIncomingFriendRequests(): List<Profile> = withContext(Dispatchers.IO) {
        try {
            val session = AuthSessionCoordinator.awaitResolvedSessionOrNull() ?: return@withContext emptyList()
            session.user?.id ?: return@withContext emptyList()

            SupabaseClient.client.postgrest
                .rpc("get_pending_friend_request_profiles")
                .decodeList<Profile>()
                .map { it.withDerivedHiveRank() }
        } catch (e: Exception) {
            logError("Error getting incoming friend requests", e)
            emptyList()
        }
    }

    suspend fun acceptFriendRequest(requesterId: String): FriendRequestActionResult =
        updateFriendRequest(
            requesterId = requesterId,
            rpcName = "accept_friend_request",
            successResult = FriendRequestActionResult.Accepted
        )

    suspend fun rejectFriendRequest(requesterId: String): FriendRequestActionResult =
        updateFriendRequest(
            requesterId = requesterId,
            rpcName = "reject_friend_request",
            successResult = FriendRequestActionResult.Rejected
        )

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

    private suspend fun updateFriendRequest(
        requesterId: String,
        rpcName: String,
        successResult: FriendRequestActionResult
    ): FriendRequestActionResult = withContext(Dispatchers.IO) {
        try {
            val session = AuthSessionCoordinator.awaitResolvedSessionOrNull()
                ?: return@withContext FriendRequestActionResult.NotAuthenticated
            session.user?.id ?: return@withContext FriendRequestActionResult.NotAuthenticated
            if (requesterId.isBlank()) return@withContext FriendRequestActionResult.NotPendingRequest

            val response = SupabaseClient.client.postgrest
                .rpc(rpcName, FriendRequestActionParams(requesterId))
                .decodeSingle<FriendRequestActionResponse>()

            if (response.success) {
                successResult
            } else {
                mapFriendRequestActionError(response.error_code)
            }
        } catch (e: Exception) {
            logError("Error updating friend request", e)
            FriendRequestActionResult.Failure
        }
    }

    private fun mapFriendRequestActionError(errorCode: String?): FriendRequestActionResult {
        return when (errorCode) {
            "not_authenticated" -> FriendRequestActionResult.NotAuthenticated
            "not_pending_request" -> FriendRequestActionResult.NotPendingRequest
            else -> FriendRequestActionResult.Failure
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
