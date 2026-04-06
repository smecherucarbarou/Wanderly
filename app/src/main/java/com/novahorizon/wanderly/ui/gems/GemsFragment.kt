package com.novahorizon.wanderly.ui.gems

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
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
    private lateinit var refreshBtn: ImageButton
    private lateinit var repository: WanderlyRepository
    
    private val gemsAdapter = GemsAdapter { gem ->
        openInMaps(gem)
    }
    
    private val json = Json { ignoreUnknownKeys = true }
    private val seenGemsHistory = mutableSetOf<String>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_gems, container, false)
        gemsRecycler = view.findViewById(R.id.gems_recycler)
        loadingIndicator = view.findViewById(R.id.gems_loading)
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
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            showSnackbar("Buzzy needs location to find gems!", isError = true)
            return
        }

        loadingIndicator.visibility = View.VISIBLE
        if (isRefresh) gemsAdapter.submitList(emptyList())

        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity())
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).addOnSuccessListener { location ->
            if (location != null) {
                lifecycleScope.launch {
                    val cityName = try {
                        val geocoder = Geocoder(requireContext(), Locale.getDefault())
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            var city: String? = null
                            withContext(Dispatchers.IO) {
                                val addresses = geocoder.getFromLocation(location.latitude, location.longitude, 1)
                                city = addresses?.firstOrNull()?.locality ?: addresses?.firstOrNull()?.adminArea
                            }
                            city
                        } else {
                            @Suppress("DEPRECATION")
                            val addresses = withContext(Dispatchers.IO) { geocoder.getFromLocation(location.latitude, location.longitude, 1) }
                            addresses?.firstOrNull()?.locality ?: addresses?.firstOrNull()?.adminArea
                        }
                    } catch (e: Exception) { null }
                    
                    loadGemsWithGemini(location.latitude, location.longitude, cityName)
                }
            } else {
                loadingIndicator.visibility = View.GONE
                showSnackbar("Could not get location", isError = true)
            }
        }
    }

    private fun loadGemsWithGemini(lat: Double, lng: Double, cityName: String?) {
        lifecycleScope.launch {
            try {
                // Expanding radius to city-wide (5km+)
                val nearbyRaw = repository.fetchHiddenGems(lat, lng, 5000)
                val rawContext = if (nearbyRaw.isNotEmpty()) "Local spots clues: ${nearbyRaw.take(15).joinToString()}" else ""
                val historyContext = if (seenGemsHistory.isNotEmpty()) "EXCLUDE these (already seen): ${seenGemsHistory.joinToString()}" else ""
                val cityContext = if (cityName != null) "The user is in $cityName. Search the ENTIRE city." else "The user is at coordinates ($lat, $lng)."

                val prompt = """
                    You are a city explorer and local guide for $cityName.
                    $cityContext
                    
                    TASK:
                    Find 3 NEW and REAL "hidden gems" anywhere in $cityName. 
                    Variety is key: Include 1 cozy cafe, 1 unique pub/bar, and 1 interesting public spot or secret view. 
                    Avoid famous tourist monuments. Look for places locals love but tourists miss.
                    
                    $rawContext
                    $historyContext
                    
                    CRITICAL: 
                    1. Use Google Search to ensure these places are REAL and currently OPEN in $cityName.
                    2. Provide the exact name and a specific street or neighborhood.
                    3. Return ONLY a JSON list of objects:
                    [{"name": "...", "description": "...", "location": "...", "reason": "..."}]
                """.trimIndent()

                val response = GeminiClient.generateWithSearch(prompt)
                
                val jsonStartIndex = response.indexOf("[")
                val jsonEndIndex = response.lastIndexOf("]")
                if (jsonStartIndex != -1 && jsonEndIndex != -1) {
                    val finalJson = response.substring(jsonStartIndex, jsonEndIndex + 1)
                    val gems = json.decodeFromString<List<Gem>>(finalJson)
                    
                    gems.forEach { seenGemsHistory.add(it.name) }
                    gemsAdapter.submitList(gems)
                }
            } catch (e: Exception) {
                showSnackbar("Buzzy couldn't find gems: ${e.message}", isError = true)
            } finally {
                loadingIndicator.visibility = View.GONE
            }
        }
    }

    private fun openInMaps(gem: Gem) {
        val query = "${gem.name}, ${gem.location}"
        val gmmIntentUri = Uri.parse("geo:0,0?q=${Uri.encode(query)}")
        val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri)
        mapIntent.setPackage("com.google.android.apps.maps")
        
        try {
            startActivity(mapIntent)
        } catch (e: Exception) {
            val webUri = Uri.parse("https://www.google.com/maps/search/?api=1&query=${Uri.encode(query)}")
            startActivity(Intent(Intent.ACTION_VIEW, webUri))
        }
    }
}

class GemsAdapter(private val onGemClick: (Gem) -> Unit) : RecyclerView.Adapter<GemsAdapter.ViewHolder>() {
    private var gems = listOf<Gem>()

    fun submitList(list: List<Gem>) {
        this.gems = list
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
        holder.location.text = "📍 ${gem.location}"
        holder.description.text = gem.description
        holder.reason.text = "✨ ${gem.reason}"
        
        holder.itemView.setOnClickListener { onGemClick(gem) }
    }

    override fun getItemCount() = gems.size
}
