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
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.novahorizon.wanderly.BuildConfig
import com.novahorizon.wanderly.R
import com.novahorizon.wanderly.WanderlyGraph
import com.novahorizon.wanderly.data.Gem
import com.novahorizon.wanderly.ui.common.LocationPermissionGate
import com.novahorizon.wanderly.ui.common.WanderlyViewModelFactory
import com.novahorizon.wanderly.ui.common.showSnackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Normalizer
import java.util.Locale

class GemsFragment : Fragment() {
    private val logTag = "GemsFragment"

    private lateinit var gemsRecycler: RecyclerView
    private lateinit var loadingIndicator: View
    private lateinit var loadingText: TextView
    private lateinit var refreshBtn: ImageButton
    private lateinit var emptyStateText: TextView
    private lateinit var retryBtn: Button
    private val viewModel: GemsViewModel by viewModels {
        WanderlyViewModelFactory(WanderlyGraph.repository(requireContext()))
    }

    private val gemsAdapter = GemsAdapter { gem ->
        openInMaps(gem)
    }

    private val requestLocationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            checkLocationAndLoadGems()
        } else {
            showLocationPermissionFeedback()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        logDebug("onCreateView called")
        val view = inflater.inflate(R.layout.fragment_gems, container, false)
        gemsRecycler = view.findViewById(R.id.gems_recycler)
        loadingIndicator = view.findViewById(R.id.gems_loading)
        loadingText = view.findViewById(R.id.gems_loading_text)
        refreshBtn = view.findViewById(R.id.refresh_gems_btn)
        emptyStateText = view.findViewById(R.id.gems_empty_state)
        retryBtn = view.findViewById(R.id.gems_retry_button)

        gemsRecycler.layoutManager = LinearLayoutManager(requireContext())
        gemsRecycler.adapter = gemsAdapter
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel.gemsState.observe(viewLifecycleOwner) { state ->
            when (state) {
                is GemsViewModel.GemsState.Idle -> Unit
                is GemsViewModel.GemsState.Loading -> showLoadingState(state.message)
                is GemsViewModel.GemsState.Loaded -> {
                    hideEmptyState()
                    gemsRecycler.visibility = View.VISIBLE
                    gemsAdapter.submitList(state.gems)
                    loadingIndicator.visibility = View.GONE
                    loadingText.visibility = View.GONE
                    refreshBtn.isEnabled = true
                }

                is GemsViewModel.GemsState.Empty -> {
                    gemsAdapter.submitList(emptyList())
                    showEmptyStateText(state.message, showRetry = true)
                }

                is GemsViewModel.GemsState.Error -> {
                    gemsAdapter.submitList(emptyList())
                    showErrorState(state.message)
                }
            }
        }

        viewModel.message.observe(viewLifecycleOwner) { message ->
            if (message.isNullOrBlank()) return@observe
            showSnackbar(message, isError = true)
            viewModel.clearMessage()
        }

        refreshBtn.setOnClickListener {
            requestGemsLoad()
        }
        retryBtn.setOnClickListener {
            requestGemsLoad()
        }

        showEmptyStateText(getString(R.string.gems_permission_prompt), showRetry = true)
    }

    private fun requestGemsLoad() {
        when (resolveLocationPermissionState()) {
            LocationPermissionGate.State.GRANTED -> checkLocationAndLoadGems(isRefresh = true)
            LocationPermissionGate.State.REQUEST,
            LocationPermissionGate.State.RATIONALE -> {
                if (resolveLocationPermissionState() == LocationPermissionGate.State.RATIONALE) {
                    showEmptyStateText(getString(R.string.gems_location_permission_rationale), showRetry = true)
                    showSnackbar(getString(R.string.gems_location_permission_rationale), isError = true)
                }
                launchLocationPermissionRequest()
            }

            LocationPermissionGate.State.SETTINGS -> {
                showEmptyStateText(getString(R.string.gems_location_permission_settings), showRetry = true)
                showSnackbar(getString(R.string.gems_location_permission_settings), isError = true)
                openAppSettings()
            }
        }
    }

    private fun checkLocationAndLoadGems(isRefresh: Boolean = false) {
        logDebug("checkLocationAndLoadGems called, isRefresh=$isRefresh")
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestLocationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            return
        }

        showLoadingState()
        if (isRefresh) {
            gemsAdapter.submitList(emptyList())
        }

        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity())
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).addOnSuccessListener { location ->
            if (location != null) {
                viewLifecycleOwner.lifecycleScope.launch {
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

                        logDebug(
                            "Geocoder details: locality=${address?.locality}, subLocality=${address?.subLocality}, subAdmin=${address?.subAdminArea}, admin=${address?.adminArea}, feature=${address?.featureName}"
                        )

                        resolveSearchCity(address)
                    } catch (_: Exception) {
                        "this area"
                    }

                    if (isAdded && view != null) {
                        viewModel.loadGems(location.latitude, location.longitude, searchCity)
                    }
                }
            } else if (isAdded && view != null) {
                showErrorState(getString(R.string.gems_location_failed))
            }
        }.addOnFailureListener {
            if (isAdded && view != null) {
                showErrorState(getString(R.string.gems_location_failed))
            }
        }
    }

    private fun resolveLocationPermissionState(): LocationPermissionGate.State {
        val hasPermission = ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return LocationPermissionGate.resolveState(
            hasPermission = hasPermission,
            hasRequestedBefore = LocationPermissionGate.hasRequestedBefore(requireContext()),
            shouldShowRationale = shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)
        )
    }

    private fun launchLocationPermissionRequest() {
        LocationPermissionGate.markRequestedBefore(requireContext())
        requestLocationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun showLocationPermissionFeedback() {
        val messageRes = when (resolveLocationPermissionState()) {
            LocationPermissionGate.State.RATIONALE -> R.string.gems_location_permission_rationale
            LocationPermissionGate.State.SETTINGS -> R.string.gems_location_permission_settings
            else -> R.string.gems_location_permission_required
        }
        showEmptyStateText(getString(messageRes), showRetry = true)
        showSnackbar(getString(messageRes), isError = true)
    }

    private fun openAppSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", requireContext().packageName, null)
        )
        startActivity(intent)
    }

    private fun showLoadingState(message: String = getString(R.string.gems_loading_default)) {
        gemsRecycler.visibility = View.VISIBLE
        refreshBtn.isEnabled = false
        loadingIndicator.visibility = View.VISIBLE
        loadingText.visibility = View.VISIBLE
        loadingText.text = message
        hideEmptyState()
    }

    private fun showErrorState(message: String) {
        gemsAdapter.submitList(emptyList())
        showEmptyStateText(message, showRetry = true)
    }

    private fun showEmptyStateText(message: String, showRetry: Boolean) {
        gemsRecycler.visibility = View.GONE
        refreshBtn.isEnabled = true
        loadingIndicator.visibility = View.GONE
        loadingText.visibility = View.GONE
        emptyStateText.text = message
        emptyStateText.visibility = View.VISIBLE
        retryBtn.visibility = if (showRetry) View.VISIBLE else View.GONE
    }

    private fun hideEmptyState() {
        emptyStateText.visibility = View.GONE
        retryBtn.visibility = View.GONE
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

    private fun logDebug(message: String) {
        if (BuildConfig.DEBUG) {
            android.util.Log.d(logTag, message)
        }
    }

}

class GemsAdapter(private val onGemClick: (Gem) -> Unit) : ListAdapter<Gem, GemsAdapter.ViewHolder>(GemDiffCallback()) {

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
        val gem = getItem(position)
        holder.name.text = gem.name
        holder.location.text = holder.itemView.context.getString(R.string.gem_location_format, gem.location)
        holder.description.text = gem.description
        holder.reason.text = holder.itemView.context.getString(R.string.gem_reason_format, gem.reason)
        holder.itemView.setOnClickListener { onGemClick(gem) }
    }

    private class GemDiffCallback : DiffUtil.ItemCallback<Gem>() {
        override fun areItemsTheSame(oldItem: Gem, newItem: Gem): Boolean {
            return oldItem.name == newItem.name && oldItem.lat == newItem.lat && oldItem.lng == newItem.lng
        }

        override fun areContentsTheSame(oldItem: Gem, newItem: Gem): Boolean = oldItem == newItem
    }
}
