package com.novahorizon.wanderly.ui.gems

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.novahorizon.wanderly.R
import com.novahorizon.wanderly.api.GeminiClient
import com.novahorizon.wanderly.data.DiscoveredPlace
import com.novahorizon.wanderly.data.Gem
import com.novahorizon.wanderly.data.WanderlyRepository
import com.novahorizon.wanderly.showSnackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.text.Normalizer
import java.util.Locale

class GemsFragment : Fragment() {

    private lateinit var gemsRecycler: RecyclerView
    private lateinit var loadingIndicator: View
    private lateinit var loadingText: TextView
    private lateinit var refreshBtn: ImageButton
    private lateinit var repository: WanderlyRepository

    private val gemsAdapter = GemsAdapter { gem ->
        openInMaps(gem)
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val seenGemsHistory = mutableSetOf<String>()

    @Serializable
    private data class GemPick(
        @SerialName("candidateIndex") val candidateIndex: Int,
        @SerialName("description") val description: String,
        @SerialName("reason") val reason: String,
        @SerialName("category") val category: String
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        android.util.Log.d("GemsFragment", "onCreateView called")
        val view = inflater.inflate(R.layout.fragment_gems, container, false)
        gemsRecycler = view.findViewById(R.id.gems_recycler)
        loadingIndicator = view.findViewById(R.id.gems_loading)
        loadingText = view.findViewById(R.id.gems_loading_text)
        refreshBtn = view.findViewById(R.id.refresh_gems_btn)
        repository = WanderlyRepository(requireContext())

        gemsRecycler.layoutManager = LinearLayoutManager(requireContext())
        gemsRecycler.adapter = gemsAdapter
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        refreshBtn.setOnClickListener {
            checkLocationAndLoadGems(isRefresh = true)
        }

        checkLocationAndLoadGems()
    }

    private fun checkLocationAndLoadGems(isRefresh: Boolean = false) {
        android.util.Log.d("GemsFragment", "checkLocationAndLoadGems called, isRefresh=$isRefresh")
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            showSnackbar("Buzzy needs location to find gems!", isError = true)
            return
        }

        loadingIndicator.visibility = View.VISIBLE
        loadingText.visibility = View.VISIBLE
        if (isRefresh) {
            gemsAdapter.submitList(emptyList())
        }

        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity())
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).addOnSuccessListener { location ->
            if (location != null) {
                lifecycleScope.launch {
                    val searchCity = try {
                        val geocoder = Geocoder(requireContext(), Locale.getDefault())
                        val address = withContext(Dispatchers.IO) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                geocoder.getFromLocation(location.latitude, location.longitude, 1)?.firstOrNull()
                            } else {
                                @Suppress("DEPRECATION")
                                geocoder.getFromLocation(location.latitude, location.longitude, 1)?.firstOrNull()
                            }
                        }

                        android.util.Log.d(
                            "GemsFragment",
                            "Geocoder details: locality=${address?.locality}, subLocality=${address?.subLocality}, subAdmin=${address?.subAdminArea}, admin=${address?.adminArea}, feature=${address?.featureName}"
                        )

                        resolveSearchCity(address)
                    } catch (_: Exception) {
                        "this area"
                    }

                    if (isAdded && view != null) {
                        loadGemsWithGemini(location.latitude, location.longitude, searchCity)
                    }
                }
            } else if (isAdded && view != null) {
                loadingIndicator.visibility = View.GONE
                loadingText.visibility = View.GONE
                showSnackbar("Could not get location", isError = true)
            }
        }
    }

    private fun loadGemsWithGemini(lat: Double, lng: Double, city: String) {
        lifecycleScope.launch {
            try {
                if (isAdded && view != null) {
                    loadingIndicator.visibility = View.VISIBLE
                    loadingText.text = "Buzzy is scouting $city..."
                }

                val candidates = repository.fetchHiddenGemCandidates(lat, lng, 2500, city)
                    .filterNot { seenGemsHistory.contains(it.name) }
                    .take(40)

                if (candidates.isEmpty()) {
                    throw Exception("Not enough verified local place candidates nearby yet.")
                }

                val prompt = buildCuratedPrompt(city, candidates)
                val response = GeminiClient.generateWithSearch(prompt)
                val cleanJson = extractJsonArray(response)
                val gemPicks = json.decodeFromString<List<GemPick>>(cleanJson)

                val gems = gemPicks.mapNotNull { pick ->
                    val candidate = candidates.getOrNull(pick.candidateIndex - 1) ?: return@mapNotNull null
                    seenGemsHistory.add(candidate.name)
                    candidate.toGem(
                        description = pick.description,
                        reason = pick.reason,
                        pickedCategory = pick.category,
                        fallbackLocation = city
                    )
                }.distinctBy { it.name.lowercase() }

                if (isAdded && view != null) {
                    if (gems.isEmpty()) {
                        showSnackbar("Buzzy couldn't curate fresh gems here yet.", isError = true)
                    }
                    gemsAdapter.submitList(gems)
                    loadingIndicator.visibility = View.GONE
                    loadingText.visibility = View.GONE
                }
            } catch (e: Exception) {
                android.util.Log.e("GemsFragment", "Error loading gems", e)
                if (isAdded && view != null) {
                    loadingIndicator.visibility = View.GONE
                    loadingText.visibility = View.GONE
                    showSnackbar("Buzzy got lost: ${e.message}", isError = true)
                }
            }
        }
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
                candidate.rating != null && candidate.reviewCount != null -> " | rating=${"%.1f".format(Locale.US, candidate.rating)} | reviews=${candidate.reviewCount}"
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

    private fun extractJsonArray(response: String): String {
        val startIndex = response.indexOf("[")
        val endIndex = response.lastIndexOf("]")
        if (startIndex == -1 || endIndex == -1) {
            throw Exception("Invalid JSON array from Gemini")
        }
        return response.substring(startIndex, endIndex + 1)
    }

    private fun resolveSearchCity(address: Address?): String {
        if (address == null) return "this area"

        val locality = address.locality.cleanedPlaceLabel()
        if (locality != null) return locality

        val subAdmin = address.subAdminArea.cleanedPlaceLabel()
        val admin = address.adminArea.cleanedPlaceLabel()
        val subLocality = address.subLocality.cleanedPlaceLabel()
        val featureName = address.featureName.cleanedPlaceLabel()

        val nonCountyFallback = listOfNotNull(subLocality, featureName, subAdmin).firstOrNull { candidate ->
            !candidate.looksLikeCounty() && !candidate.equals(admin, ignoreCase = true)
        }
        if (nonCountyFallback != null) return nonCountyFallback

        if (subAdmin != null && !subAdmin.equals(admin, ignoreCase = true)) {
            return subAdmin
        }

        return admin ?: "this area"
    }

    private fun String?.cleanedPlaceLabel(): String? {
        val value = this?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val collapsed = value.replace(Regex("\\s+"), " ").trim()
        val normalized = Normalizer.normalize(collapsed, Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .lowercase(Locale.ROOT)

        val prefixes = listOf("municipiul ", "municipiu ", "orasul ", "oras ", "judetul ", "judet ", "comuna ")
        val matchedPrefix = prefixes.firstOrNull { normalized.startsWith(it) } ?: return collapsed.trim(',', '.', '-', ' ')
        return collapsed.drop(matchedPrefix.length).trim(',', '.', '-', ' ').ifBlank { null }
    }

    private fun String.looksLikeCounty(): Boolean {
        val normalized = Normalizer.normalize(this, Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .lowercase(Locale.ROOT)
        return normalized.startsWith("judet") || normalized.endsWith(" county")
    }

    private fun DiscoveredPlace.toGem(
        description: String,
        reason: String,
        pickedCategory: String,
        fallbackLocation: String
    ): Gem {
        val safeDescription = description.trim().ifBlank { "A local spot worth checking out while you wander." }
        val safeReason = reason.trim().ifBlank { "It stands out from the usual route." }
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

    private fun openInMaps(gem: Gem) {
        val geoUri = Uri.parse("geo:${gem.lat},${gem.lng}?q=${Uri.encode(gem.name)}")
        val mapsIntent = Intent(Intent.ACTION_VIEW, geoUri).setPackage("com.google.android.apps.maps")
        try {
            startActivity(mapsIntent)
        } catch (_: ActivityNotFoundException) {
            val webUrl = "https://www.google.com/maps/search/?api=1&query=${gem.lat},${gem.lng}"
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(webUrl)))
        }
    }
}

class GemsAdapter(private val onGemClick: (Gem) -> Unit) : RecyclerView.Adapter<GemsAdapter.ViewHolder>() {
    private var gems = listOf<Gem>()

    fun submitList(list: List<Gem>) {
        gems = list
        notifyDataSetChanged()
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.gem_name)
        val location: TextView = view.findViewById(R.id.gem_location)
        val description: TextView = view.findViewById(R.id.gem_description)
        val reason: TextView = view.findViewById(R.id.gem_reason)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_gem, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val gem = gems[position]
        holder.name.text = gem.name
        holder.location.text = holder.itemView.context.getString(R.string.gem_location_format, gem.location)
        holder.description.text = gem.description
        holder.reason.text = holder.itemView.context.getString(R.string.gem_reason_format, gem.reason)
        holder.itemView.setOnClickListener { onGemClick(gem) }
    }

    override fun getItemCount(): Int = gems.size
}
