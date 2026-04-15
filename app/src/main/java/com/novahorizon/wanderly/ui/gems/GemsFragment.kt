package com.novahorizon.wanderly.ui.gems

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
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
import com.novahorizon.wanderly.api.PlacesGeocoder
import com.novahorizon.wanderly.data.Gem
import com.novahorizon.wanderly.data.WanderlyRepository
import com.novahorizon.wanderly.showSnackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
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
        if (isRefresh) gemsAdapter.submitList(emptyList())

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

                        if (address?.subAdminArea?.contains("Deva", true) == true ||
                            address?.featureName?.contains("Deva", true) == true
                        ) {
                            "Deva"
                        } else {
                            address?.locality ?: address?.subAdminArea?.replace("Municipiul ", "") ?: "this area"
                        }
                    } catch (_: Exception) {
                        "this area"
                    }

                    if (isAdded && view != null) loadGemsWithGemini(location.latitude, location.longitude, searchCity)
                }
            } else {
                if (isAdded && view != null) {
                    loadingIndicator.visibility = View.GONE
                    loadingText.visibility = View.GONE
                    showSnackbar("Could not get location", isError = true)
                }
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

                val localHints = try {
                    repository.fetchHiddenGems(lat, lng, 1000).take(5).joinToString(", ")
                } catch (_: Exception) {
                    ""
                }

                val historyFilter = if (seenGemsHistory.isNotEmpty()) {
                    "Do not suggest these places again: ${seenGemsHistory.toList().takeLast(10).joinToString(", ")}."
                } else {
                    ""
                }
                val hintSection = if (localHints.isNotBlank()) {
                    "Map hints near the user: $localHints. Prioritize places that fit these local clues."
                } else {
                    ""
                }

                val prompt = """
                    You are an elite hyper-local scout for $city near coordinates ($lat, $lng).
                    Find 8 real hidden gems that are genuinely in or immediately around $city.

                    HARD RULES:
                    - Use the EXACT Google Maps / business display name. No translations, nicknames, abbreviations, or rewritten names.
                    - If you are not confident the place exists on Google Maps in $city, omit it.
                    - Prefer current, local, aesthetic, or culturally interesting places.
                    - Avoid anything that is clearly outside $city.
                    $historyFilter
                    $hintSection

                    ALLOWED CATEGORIES ONLY:
                    - Food
                    - Drinks
                    - Viewpoint
                    - Culture

                    FORBIDDEN:
                    - Government offices, public services, utilities, or corporate offices
                    - Hotels, guesthouses, villas, hostels, or other lodging
                    - Hospitals, clinics, dentists, pharmacies, banks, schools
                    - Places outside $city

                    Return ONLY a raw JSON array with objects using exactly:
                    "name": exact Google Maps place name
                    "description": one short stylish sentence
                    "location": neighborhood or area
                    "reason": why it stands out
                    "category": Food, Drinks, Viewpoint, or Culture
                """.trimIndent()

                val response = GeminiClient.generateWithSearch(prompt)
                val startIndex = response.indexOf("[")
                val endIndex = response.lastIndexOf("]")
                if (startIndex == -1 || endIndex == -1) {
                    throw Exception("Invalid JSON array from Gemini")
                }

                val cleanJson = response.substring(startIndex, endIndex + 1)
                val gems = json.decodeFromString<List<Gem>>(cleanJson)
                val verifiedGems = mutableListOf<Gem>()

                for (gem in gems) {
                    if (seenGemsHistory.contains(gem.name)) continue

                    val resolved = PlacesGeocoder.resolveCoordinates(
                        placeName = gem.name,
                        targetCity = city,
                        userLat = lat,
                        userLng = lng,
                        radiusKm = 10.0,
                        categoryHint = gem.category,
                        strictNameMatch = true
                    )

                    if (resolved != null) {
                        val verifiedGem = gem.copy(
                            name = resolved.name,
                            lat = resolved.lat,
                            lng = resolved.lng,
                            location = resolved.formattedAddress ?: gem.location
                        )
                        verifiedGems.add(verifiedGem)
                        seenGemsHistory.add(resolved.name)
                    }
                }

                val filteredGems = mutableListOf<Gem>()
                for (gem in verifiedGems) {
                    val isDuplicate = filteredGems.any { existing ->
                        existing.name.equals(gem.name, ignoreCase = true) ||
                            (kotlin.math.abs(existing.lat - gem.lat) < 0.0001 &&
                                kotlin.math.abs(existing.lng - gem.lng) < 0.0001)
                    }
                    if (!isDuplicate) {
                        filteredGems.add(gem)
                    }
                }

                if (isAdded && view != null) {
                    if (filteredGems.isEmpty()) {
                        android.util.Log.d("GemsFragment", "All results filtered out by quality validation")
                        showSnackbar("Buzzy couldn't find new verified gems here yet.", isError = true)
                    }
                    gemsAdapter.submitList(filteredGems)
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

    private fun openInMaps(gem: Gem) {
        val searchQuery = "${gem.name}, ${gem.location}"
        val encodedQuery = Uri.encode(searchQuery)
        val uri = Uri.parse("geo:0,0?q=$encodedQuery")
        val intent = Intent(Intent.ACTION_VIEW, uri).setPackage("com.google.android.apps.maps")
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=$encodedQuery")))
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
