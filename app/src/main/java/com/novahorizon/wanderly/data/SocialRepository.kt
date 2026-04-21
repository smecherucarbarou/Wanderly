package com.novahorizon.wanderly.data

import android.util.Log
import com.novahorizon.wanderly.Constants
import com.novahorizon.wanderly.api.SupabaseClient
import com.novahorizon.wanderly.auth.AuthSessionCoordinator
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SocialRepository {
    suspend fun getLeaderboard(): List<Profile> = withContext(Dispatchers.IO) {
        try {
            val session = AuthSessionCoordinator.awaitResolvedSessionOrNull() ?: return@withContext emptyList()
            val currentUserId = session.user!!.id

            val friendships = SupabaseClient.client.postgrest["friendships"]
                .select {
                    filter {
                        or {
                            eq("user_id", currentUserId)
                            eq("friend_id", currentUserId)
                        }
                    }
                }
                .decodeList<Friendship>()

            val relevantIds = friendships.map {
                if (it.user_id == currentUserId) it.friend_id else it.user_id
            }.toMutableList()
            relevantIds.add(currentUserId)

            SupabaseClient.client.postgrest[Constants.TABLE_PROFILES]
                .select {
                    filter {
                        isIn("id", relevantIds)
                    }
                    order("honey", Order.DESCENDING)
                }
                .decodeList<Profile>()
                .map { it.withDerivedHiveRank() }
        } catch (e: Exception) {
            Log.e("SocialRepository", "Error getting leaderboard", e)
            emptyList()
        }
    }

    suspend fun addFriendByCode(friendCode: String): String = withContext(Dispatchers.IO) {
        try {
            val session = AuthSessionCoordinator.awaitResolvedSessionOrNull() ?: return@withContext "Not authenticated"
            val currentUserId = session.user!!.id
            val normalizedCode = normalizeFriendCode(friendCode)
                ?: return@withContext "Friend code must be 6 letters or digits"

            val targetUsers = SupabaseClient.client.postgrest[Constants.TABLE_PROFILES]
                .select { filter { eq("friend_code", normalizedCode) } }
                .decodeList<Profile>()

            if (targetUsers.isEmpty()) return@withContext "Friend code not found"

            val friendId = targetUsers.first().id
            if (friendId == currentUserId) return@withContext "You cannot add yourself"

            SupabaseClient.client.postgrest["friendships"].insert(
                Friendship(user_id = currentUserId, friend_id = friendId)
            )
            "Friend added successfully!"
        } catch (e: Exception) {
            if (e.message?.contains("duplicate key") == true || e.message?.contains("unique constraint") == true) {
                "Already friends with this user"
            } else {
                "Failed to add friend: ${e.message}"
            }
        }
    }

    suspend fun removeFriend(friendId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val session = AuthSessionCoordinator.awaitResolvedSessionOrNull() ?: return@withContext false
            val currentUserId = session.user!!.id

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
            Log.e("SocialRepository", "Error removing friend: ${e.message}", e)
            false
        }
    }

    suspend fun getFriends(): List<Profile> = withContext(Dispatchers.IO) {
        try {
            val session = AuthSessionCoordinator.awaitResolvedSessionOrNull() ?: return@withContext emptyList()
            val currentUserId = session.user!!.id

            val friendships = SupabaseClient.client.postgrest["friendships"]
                .select {
                    filter {
                        or {
                            eq("user_id", currentUserId)
                            eq("friend_id", currentUserId)
                        }
                    }
                }
                .decodeList<Friendship>()

            if (friendships.isEmpty()) return@withContext emptyList()

            val friendIds = friendships.map {
                if (it.user_id == currentUserId) it.friend_id else it.user_id
            }

            SupabaseClient.client.postgrest[Constants.TABLE_PROFILES]
                .select {
                    filter {
                        isIn("id", friendIds)
                    }
                }
                .decodeList<Profile>()
                .map { it.withDerivedHiveRank() }
        } catch (e: Exception) {
            Log.e("SocialRepository", "Error getting friends", e)
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
}
