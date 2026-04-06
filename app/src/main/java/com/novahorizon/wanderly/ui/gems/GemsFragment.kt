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
import java.util.*

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

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
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
                        
                        android.util.Log.d("GemsFragment", "Geocoder details: locality=${address?.locality}, subLocality=${address?.subLocality}, subAdmin=${address?.subAdminArea}, admin=${address?.adminArea}, feature=${address?.featureName}")
                        
                        if (address?.subAdminArea?.contains("Deva", true) == true || 
                            address?.featureName?.contains("Deva", true) == true) {
                            "Deva"
                        } else {
                            address?.locality ?: address?.subAdminArea?.replace("Municipiul ", "") ?: "this area"
                        }
                    } catch (e: Exception) { 
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

                // Get some local hints from Overpass to help Gemini be more accurate
                val localHints = try {
                    repository.fetchHiddenGems(lat, lng, 1000).take(5).joinToString(", ")
                } catch (e: Exception) { "" }

                val historyFilter = if (seenGemsHistory.isNotEmpty()) {
                    "Do NOT suggest these places again: ${seenGemsHistory.toList().takeLast(10).joinToString(", ")}."
                } else ""

                val prompt = """
                    You are an elite, picky, and world-class Lifestyle Scout for $city ($lat, $lng). 
                    Your reputation depends on finding ONLY high-vibe, sleek, and trendy spots. 
                    
                    STRICT CATEGORY FILTER (ONLY THESE):
                    - Specialty Coffee / Aesthetic Cafes
                    - Upscale Lounge Bars / Craft Cocktail Spots
                    - Fine Dining or "Hole-in-the-wall" authentic local food (no fast food chains)
                    - Hidden Viewpoints / Secret Rooftops
                    - Concept Stores or Independent Art Galleries

                    ABSOLUTELY FORBIDDEN (IMMEDIATE FAILURE IF INCLUDED):
                    - NO Government or Public Offices (Social Assistance/Asistenta Sociala, City Hall, Post Office).
                    - NO Medical/Health: No dentists, clinics, doctors, or pharmacies.
                    - NO Lodging: No hotels, pensions (pensiuni), or villas.
                    - NO Banks, Real Estate, or Corporate Offices.
                    - NO schools or religious administrative offices.

                    CONTEXT FOR 2026: 
                    Ensure the place is currently trendy and exists on Google Maps. If a place has "Social" in the name, double-check that it is a BAR/LOUNGE, not a government department.
                    
                    FORMAT: Return ONLY a raw JSON array of objects with:
                    "name": (Exact commercial name),
                    "description": (One sleek, alluring sentence),
                    "location": (The specific area),
                    "reason": (The "wow" factor),
                    "category": (Food, Drinks, Viewpoint, or Culture)
                """.trimIndent()
                
                val response = GeminiClient.generateWithSearch(prompt)

                val startIndex = response.indexOf("[")
                val endIndex = response.lastIndexOf("]")
                if (startIndex == -1 || endIndex == -1) {
                    throw Exception("Invalid JSON Array from Gemini")
                }
                val cleanJson = response.substring(startIndex, endIndex + 1)

                val gems = Json { ignoreUnknownKeys = true }.decodeFromString<List<Gem>>(cleanJson)
                val verifiedGems = mutableListOf<Gem>()

                for (gem in gems) {
                    // Check if we've seen this exact gem name recently to avoid repetition
                    if (seenGemsHistory.contains(gem.name)) continue
                    
                    val resolved = PlacesGeocoder.resolveCoordinates(gem.name, city, lat, lng, 10.0)
                    if (resolved != null) {
                        val verifiedGem = gem.copy(
                            name = resolved.name, // Use Google Maps verified name
                            lat = resolved.lat,
                            lng = resolved.lng,
                            location = resolved.formattedAddress ?: gem.location
                        )
                        verifiedGems.add(verifiedGem)
                        seenGemsHistory.add(gem.name)
                    }
                }

                // MAXIMUM FILTERING PROTOCOL: Deduplication and Anti-Fallback Logic
                val filteredGems = mutableListOf<Gem>()
                for (gem in verifiedGems) {
                    val isDuplicate = filteredGems.any { existing ->
                        // Filter 1: Name exact match (Google Maps fallback name)
                        existing.name.equals(gem.name, ignoreCase = true) ||
                        // Filter 2: Coordinate proximity (approx. 10 meters)
                        (Math.abs(existing.lat - gem.lat) < 0.0001 && Math.abs(existing.lng - gem.lng) < 0.0001)
                    }
                    if (!isDuplicate) {
                        filteredGems.add(gem)
                    }
                }

                if (isAdded && view != null) {
                    if (filteredGems.isEmpty()) {
                        android.util.Log.d("GemsFragment", "All results filtered out by quality check")
                        showSnackbar("Buzzy couldn't find new verified gems here yet.", isError = true)
                    }
                    (gemsRecycler.adapter as GemsAdapter).submitList(filteredGems)
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
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=$encodedQuery")))
        }
    }
}

class GemsAdapter(private val onGemClick: (Gem) -> Unit) : RecyclerView.Adapter<GemsAdapter.ViewHolder>() {
    private var gems = listOf<Gem>()
    fun submitList(list: List<Gem>) { this.gems = list; notifyDataSetChanged() }
    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.gem_name)
        val location: TextView = view.findViewById(R.id.gem_location)
        val description: TextView = view.findViewById(R.id.gem_description)
        val reason: TextView = view.findViewById(R.id.gem_reason)
    }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_gem, parent, false))
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val gem = gems[position]
        holder.name.text = gem.name
        holder.location.text = "📍 ${gem.location}"
        holder.description.text = gem.description
        holder.reason.text = "✨ ${gem.reason}"
        holder.itemView.setOnClickListener { onGemClick(gem) }
    }
    override fun getItemCount() = gems.size
}
