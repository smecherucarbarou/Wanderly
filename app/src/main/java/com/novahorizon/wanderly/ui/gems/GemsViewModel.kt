package com.novahorizon.wanderly.ui.gems

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novahorizon.wanderly.BuildConfig
import com.novahorizon.wanderly.R
import com.novahorizon.wanderly.api.GeminiClient
import com.novahorizon.wanderly.data.DiscoveredPlace
import com.novahorizon.wanderly.data.Gem
import com.novahorizon.wanderly.data.WanderlyRepository
import com.novahorizon.wanderly.util.AiResponseParser
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Locale

class GemsViewModel(private val repository: WanderlyRepository) : ViewModel() {

    private val json = Json { ignoreUnknownKeys = true }
    private val seenGemsHistory = mutableSetOf<String>()

    private val _gemsState = MutableLiveData<GemsState>(GemsState.Idle)
    val gemsState: LiveData<GemsState> = _gemsState

    private val _message = MutableLiveData<String?>()
    val message: LiveData<String?> = _message

    @Serializable
    private data class GemPick(
        @SerialName("candidateIndex") val candidateIndex: Int,
        @SerialName("description") val description: String,
        @SerialName("reason") val reason: String,
        @SerialName("category") val category: String
    )

    sealed class GemsState {
        object Idle : GemsState()
        data class Loading(val message: String) : GemsState()
        data class Loaded(val gems: List<Gem>) : GemsState()
        data class Empty(val message: String) : GemsState()
        data class Error(val message: String) : GemsState()
    }

    fun loadGems(lat: Double, lng: Double, city: String) {
        viewModelScope.launch {
            try {
                _gemsState.postValue(
                    GemsState.Loading(
                        repository.context.getString(R.string.gems_loading_city_format, city)
                    )
                )

                val candidates = repository.fetchHiddenGemCandidates(lat, lng, 2500, city)
                    .filterNot { seenGemsHistory.contains(it.name) }
                    .take(40)

                if (candidates.isEmpty()) {
                    _gemsState.postValue(
                        GemsState.Empty(repository.context.getString(R.string.gems_empty_state))
                    )
                    _message.postValue(repository.context.getString(R.string.gems_no_fresh_results))
                    return@launch
                }

                val prompt = buildCuratedPrompt(city, candidates)
                val response = GeminiClient.generateWithSearch(prompt)
                val cleanJson = AiResponseParser.extractFirstJsonArray(response)
                    ?: throw IllegalStateException(repository.context.getString(R.string.gems_invalid_response))
                val gemPicks = runCatching {
                    json.decodeFromString<List<GemPick>>(cleanJson)
                }.onFailure {
                    logRawResponse(response)
                }.getOrElse {
                    throw IllegalStateException(repository.context.getString(R.string.gems_invalid_response))
                }

                val gems = gemPicks.mapNotNull { pick ->
                    val candidate = candidates.getOrNull(pick.candidateIndex - 1) ?: return@mapNotNull null
                    seenGemsHistory.add(candidate.name)
                    candidate.toGem(
                        description = pick.description,
                        reason = pick.reason,
                        pickedCategory = pick.category,
                        fallbackLocation = city
                    )
                }.distinctBy { it.name.lowercase(Locale.ROOT) }

                if (gems.isEmpty()) {
                    _gemsState.postValue(
                        GemsState.Empty(repository.context.getString(R.string.gems_empty_state))
                    )
                    _message.postValue(repository.context.getString(R.string.gems_no_fresh_results))
                } else {
                    _gemsState.postValue(GemsState.Loaded(gems))
                }
            } catch (e: Exception) {
                Log.e("GemsViewModel", "Error loading gems", e)
                val message = e.message?.takeIf { it.isNotBlank() }
                    ?: repository.context.getString(R.string.gems_loading_failed)
                _gemsState.postValue(GemsState.Error(message))
                _message.postValue(repository.context.getString(R.string.gems_loading_failed))
            }
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    private fun buildCuratedPrompt(city: String, candidates: List<DiscoveredPlace>): String {
        val historyFilter = if (seenGemsHistory.isNotEmpty()) {
            "Avoid places the user has already seen recently: ${seenGemsHistory.toList().takeLast(12).joinToString(", ")}."
        } else {
            ""
        }

        val candidateList = candidates.mapIndexed { index, candidate ->
            val area = candidate.areaLabel?.takeIf { it.isNotBlank() } ?: city
            val ratingInfo = when {
                candidate.rating != null && candidate.reviewCount != null -> {
                    " | rating=${"%.1f".format(Locale.US, candidate.rating)} | reviews=${candidate.reviewCount}"
                }

                else -> ""
            }
            "${index + 1}. ${candidate.name} | category=${candidate.category} | area=$area | source=${candidate.source}$ratingInfo"
        }.joinToString("\n")

        val maxPicks = minOf(6, candidates.size)
        val minPicks = minOf(4, maxPicks).coerceAtLeast(1)

        return """
            You are curating Hidden Gems for a travel app in $city.
            You MUST pick ONLY from the numbered candidate list below.
            Do not invent names, do not translate names, and do not rename any place.
            Return between $minPicks and $maxPicks picks.

            Prioritize:
            - stylish food and drinks spots first
            - specialty coffee, brunch, dessert, bakery, ice cream, terraces, and date-night restaurants
            - bars and lounges only if they feel polished, welcoming, and not too rough
            - memorable culture spots only if they feel genuinely visit-worthy
            - places that feel worth opening in Maps right away
            - places that sound real, public, and visitable today
            - a mix that feels good for both young adults and occasional kid-friendly daytime visits

            Rules:
            - Use each candidate at most once.
            - Make most picks Food or soft daytime-friendly options if possible.
            - Drinks should appear, but should not dominate the list.
            - Culture and Viewpoint should be occasional, not dominant, unless the candidate list is weak.
            - If a candidate sounds weak, skip it instead of forcing it.
            - Prefer source=google and places with ratings/reviews when available.
            - Do NOT write historical claims, backstory, legends, or factual statements you cannot verify from the candidate list itself.
            - Description and reason must stay short, practical, and vibe-based.
            - Avoid places that feel mainly for heavy drinking, rowdy nightlife, or an unsafe/rough vibe unless the list is extremely limited.
            - Bad example: "where Constantin Brancusi was an apprentice".
            - Good example: "A lively cocktail stop with a polished local vibe."
            - Good example: "A stylish coffee pick that feels worth a detour."
            - Good example: "A relaxed dessert stop that works well in the daytime too."
            $historyFilter

            Candidate list:
            $candidateList

            Return ONLY a raw JSON array with objects in this exact shape:
            {
              "candidateIndex": 1,
              "description": "One short stylish sentence.",
              "reason": "Why it stands out in one sentence.",
              "category": "Food"
            }
        """.trimIndent()
    }

    private fun DiscoveredPlace.toGem(
        description: String,
        reason: String,
        pickedCategory: String,
        fallbackLocation: String
    ): Gem {
        val safeDescription = description.trim().ifBlank {
            repository.context.getString(R.string.gems_fallback_description)
        }
        val safeReason = reason.trim().ifBlank {
            repository.context.getString(R.string.gems_fallback_reason)
        }
        val safeCategory = pickedCategory.trim().ifBlank { category }
        val safeLocation = areaLabel?.takeIf { it.isNotBlank() } ?: fallbackLocation

        return Gem(
            name = name,
            description = safeDescription,
            location = safeLocation,
            reason = safeReason,
            category = safeCategory,
            lat = lat,
            lng = lng
        )
    }

    private fun logRawResponse(response: String) {
        if (BuildConfig.DEBUG) {
            Log.d("GemsViewModel", "Raw gem response: $response")
        }
    }
}
