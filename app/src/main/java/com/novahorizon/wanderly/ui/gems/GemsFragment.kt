package com.novahorizon.wanderly.ui.gems

import com.novahorizon.wanderly.observability.AppLogger

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
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.novahorizon.wanderly.BuildConfig
import com.novahorizon.wanderly.R
import com.novahorizon.wanderly.WanderlyGraph
import com.novahorizon.wanderly.data.Gem
import com.novahorizon.wanderly.databinding.FragmentGemsBinding
import com.novahorizon.wanderly.ui.common.LocationPermissionController
import com.novahorizon.wanderly.ui.common.LocationPermissionGate
import com.novahorizon.wanderly.ui.common.UiText
import com.novahorizon.wanderly.ui.common.WanderlyViewModelFactory
import com.novahorizon.wanderly.ui.common.showSnackbar
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GemsFragment : Fragment() {
    private val logTag = "GemsFragment"

    private var _binding: FragmentGemsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: GemsViewModel by viewModels {
        WanderlyViewModelFactory(WanderlyGraph.repository(requireContext()))
    }

    private var gemsAdapter: GemsAdapter? = null
    private val locationPermissionController = LocationPermissionController(this)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        logDebug("onCreateView called")
        val currentBinding = FragmentGemsBinding.inflate(inflater, container, false)
        _binding = currentBinding
        return currentBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val currentBinding = binding
        val adapter = GemsAdapter { gem ->
            _binding ?: return@GemsAdapter
            openInMaps(gem)
        }
        gemsAdapter = adapter

        currentBinding.gemsRecycler.layoutManager = LinearLayoutManager(requireContext())
        currentBinding.gemsRecycler.adapter = adapter

        observeViewModel()

        currentBinding.refreshGemsBtn.setOnClickListener {
            requestGemsLoad(isUserInitiated = true)
        }
        currentBinding.gemsRetryButton.setOnClickListener {
            requestGemsLoad()
        }

        if (GemsLoadGate.shouldAutoLoad(viewModel.gemsState.value)) {
            requestGemsLoad()
        }
    }

    private fun observeViewModel() {
        val owner = viewLifecycleOwner
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val stateObserver = Observer<GemsViewModel.GemsState> { state ->
                    renderGemsState(state)
                }
                val messageObserver = Observer<UiText?> messageObserver@{ message ->
                    val text = message?.asString(requireContext()).orEmpty()
                    if (text.isBlank()) return@messageObserver
                    _binding ?: return@messageObserver
                    showGemsSnackbar(text, isError = true)
                    viewModel.clearMessage()
                }

                viewModel.gemsState.observe(owner, stateObserver)
                viewModel.message.observe(owner, messageObserver)
                try {
                    awaitCancellation()
                } finally {
                    viewModel.gemsState.removeObserver(stateObserver)
                    viewModel.message.removeObserver(messageObserver)
                }
            }
        }
    }

    private fun renderGemsState(state: GemsViewModel.GemsState) {
        val currentBinding = _binding ?: return
        val adapter = gemsAdapter ?: return
        when (state) {
            is GemsViewModel.GemsState.Idle -> Unit
            is GemsViewModel.GemsState.Loading -> showLoadingState(state.message.asString(requireContext()))
            is GemsViewModel.GemsState.Loaded -> {
                hideEmptyState()
                currentBinding.gemsRecycler.visibility = View.VISIBLE
                adapter.submitList(state.gems)
                currentBinding.gemsLoading.visibility = View.GONE
                currentBinding.gemsLoadingText.visibility = View.GONE
                currentBinding.refreshGemsBtn.isEnabled = true
            }

            is GemsViewModel.GemsState.Empty -> {
                adapter.submitList(emptyList())
                showEmptyStateText(state.message.asString(requireContext()), showRetry = true)
            }

            is GemsViewModel.GemsState.Error -> {
                adapter.submitList(emptyList())
                showErrorState(state.message.asString(requireContext()))
            }
        }
    }

    private fun requestGemsLoad(isUserInitiated: Boolean = false) {
        if (_binding == null || !isAdded) return

        when (val permissionState = locationPermissionController.resolveState()) {
            LocationPermissionGate.State.GRANTED -> checkLocationAndLoadGems(isRefresh = isUserInitiated)
            LocationPermissionGate.State.REQUEST,
            LocationPermissionGate.State.RATIONALE -> {
                if (permissionState == LocationPermissionGate.State.RATIONALE) {
                    showEmptyStateText(getString(R.string.gems_location_permission_rationale), showRetry = true)
                    showGemsSnackbar(getString(R.string.gems_location_permission_rationale), isError = true)
                }
                locationPermissionController.requestPermission { granted ->
                    val binding = _binding ?: return@requestPermission
                    if (!isAdded || !isCurrentBinding(binding)) return@requestPermission
                    if (granted) {
                        checkLocationAndLoadGems(isRefresh = isUserInitiated)
                    } else {
                        showLocationPermissionFeedback()
                    }
                }
            }

            LocationPermissionGate.State.SETTINGS -> {
                showEmptyStateText(getString(R.string.gems_location_permission_settings), showRetry = true)
                showGemsSnackbar(getString(R.string.gems_location_permission_settings), isError = true)
                locationPermissionController.openAppSettings()
            }
        }
    }

    private fun checkLocationAndLoadGems(isRefresh: Boolean = false) {
        logDebug("checkLocationAndLoadGems called, isRefresh=$isRefresh")
        val currentBinding = _binding ?: return
        val fragmentContext = context ?: return
        val fragmentActivity = activity ?: return

        val hasFineLocation = ContextCompat.checkSelfPermission(
            fragmentContext,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarseLocation = ContextCompat.checkSelfPermission(
            fragmentContext,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasFineLocation && !hasCoarseLocation) {
            locationPermissionController.requestPermission { granted ->
                val binding = _binding ?: return@requestPermission
                if (!isAdded || !isCurrentBinding(binding)) return@requestPermission
                if (granted) {
                    checkLocationAndLoadGems(isRefresh)
                } else {
                    showLocationPermissionFeedback()
                }
            }
            return
        }

        showLoadingState()
        if (isRefresh) {
            gemsAdapter?.submitList(emptyList())
        }

        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(fragmentActivity)
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { location ->
                val binding = _binding ?: return@addOnSuccessListener
                if (location == null) {
                    if (GemsLocationCallbackGuard.shouldHandleLocationFailure(
                            isFragmentAdded = isAdded,
                            hasBinding = isCurrentBinding(binding)
                        )
                    ) {
                        showErrorState(getString(R.string.gems_location_failed))
                    }
                    return@addOnSuccessListener
                }

                val lifecycleOwner = viewLifecycleOwnerLiveData.value
                val hasActiveLifecycleOwner =
                    lifecycleOwner?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.INITIALIZED) == true
                if (!GemsLocationCallbackGuard.shouldHandleLocationSuccess(
                        hasLocation = true,
                        isFragmentAdded = isAdded,
                        hasBinding = isCurrentBinding(binding),
                        hasLifecycleOwner = hasActiveLifecycleOwner
                    )
                ) {
                    return@addOnSuccessListener
                }

                lifecycleOwner ?: return@addOnSuccessListener
                val callbackContext = context ?: return@addOnSuccessListener
                lifecycleOwner.lifecycleScope.launch {
                    val searchCity = try {
                        val geocoder = Geocoder(callbackContext, Locale.getDefault())
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

                    if (_binding == null || !isAdded) return@launch
                    viewModel.loadGems(location.latitude, location.longitude, searchCity)
                }
            }
            .addOnFailureListener {
                val binding = _binding ?: return@addOnFailureListener
                if (GemsLocationCallbackGuard.shouldHandleLocationFailure(
                        isFragmentAdded = isAdded,
                        hasBinding = isCurrentBinding(binding)
                    )
                ) {
                    showErrorState(getString(R.string.gems_location_failed))
                }
            }

        currentBinding.gemsRecycler.visibility = View.VISIBLE
    }

    private fun showLocationPermissionFeedback() {
        if (_binding == null || !isAdded) return
        val messageRes = when (locationPermissionController.resolveState()) {
            LocationPermissionGate.State.RATIONALE -> R.string.gems_location_permission_rationale
            LocationPermissionGate.State.SETTINGS -> R.string.gems_location_permission_settings
            else -> R.string.gems_location_permission_required
        }
        showEmptyStateText(getString(messageRes), showRetry = true)
        showGemsSnackbar(getString(messageRes), isError = true)
    }

    private fun showLoadingState(message: String? = null) {
        val currentBinding = _binding ?: return
        if (!isAdded) return
        currentBinding.gemsRecycler.visibility = View.VISIBLE
        currentBinding.refreshGemsBtn.isEnabled = false
        currentBinding.gemsLoading.visibility = View.VISIBLE
        currentBinding.gemsLoadingText.visibility = View.VISIBLE
        currentBinding.gemsLoadingText.text = message ?: getString(R.string.gems_loading_default)
        hideEmptyState()
    }

    private fun showErrorState(message: String) {
        gemsAdapter?.submitList(emptyList())
        showEmptyStateText(message, showRetry = true)
    }

    private fun showEmptyStateText(message: String, showRetry: Boolean) {
        val currentBinding = _binding ?: return
        currentBinding.gemsRecycler.visibility = View.GONE
        currentBinding.refreshGemsBtn.isEnabled = true
        currentBinding.gemsLoading.visibility = View.GONE
        currentBinding.gemsLoadingText.visibility = View.GONE
        currentBinding.gemsEmptyState.text = message
        currentBinding.gemsEmptyState.visibility = View.VISIBLE
        currentBinding.gemsRetryButton.visibility = if (showRetry) View.VISIBLE else View.GONE
    }

    private fun hideEmptyState() {
        val currentBinding = _binding ?: return
        currentBinding.gemsEmptyState.visibility = View.GONE
        currentBinding.gemsRetryButton.visibility = View.GONE
    }

    private fun showGemsSnackbar(message: String, isError: Boolean = false) {
        _binding ?: return
        if (!isAdded) return
        showSnackbar(message, isError)
    }

    private fun isCurrentBinding(binding: FragmentGemsBinding): Boolean {
        return _binding === binding
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
        if (_binding == null || !isAdded) return
        val geoUri = "geo:${gem.lat},${gem.lng}?q=${Uri.encode(gem.name)}".toUri()
        val mapsIntent = Intent(Intent.ACTION_VIEW, geoUri).setPackage("com.google.android.apps.maps")
        try {
            startActivity(mapsIntent)
        } catch (_: ActivityNotFoundException) {
            val webUrl = "https://www.google.com/maps/search/?api=1&query=${gem.lat},${gem.lng}"
            startActivity(Intent(Intent.ACTION_VIEW, webUrl.toUri()))
        }
    }

    override fun onDestroyView() {
        val currentBinding = _binding
        currentBinding?.refreshGemsBtn?.setOnClickListener(null)
        currentBinding?.gemsRetryButton?.setOnClickListener(null)
        currentBinding?.gemsRecycler?.adapter = null
        gemsAdapter?.clearOnGemClick()
        gemsAdapter = null
        _binding = null
        super.onDestroyView()
    }

    private fun logDebug(message: String) {
        if (BuildConfig.DEBUG) {
            AppLogger.d(logTag, message)
        }
    }
}

class GemsAdapter(onGemClick: (Gem) -> Unit) : ListAdapter<Gem, GemsAdapter.ViewHolder>(GemDiffCallback()) {
    private var onGemClick: ((Gem) -> Unit)? = onGemClick

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.gem_name)
        val location: TextView = view.findViewById(R.id.gem_location)
        val description: TextView = view.findViewById(R.id.gem_description)
        val reason: TextView = view.findViewById(R.id.gem_reason)
    }

    fun clearOnGemClick() {
        onGemClick = null
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
        holder.itemView.setOnClickListener { onGemClick?.invoke(gem) }
    }

    private class GemDiffCallback : DiffUtil.ItemCallback<Gem>() {
        override fun areItemsTheSame(oldItem: Gem, newItem: Gem): Boolean {
            return oldItem.name == newItem.name && oldItem.lat == newItem.lat && oldItem.lng == newItem.lng
        }

        override fun areContentsTheSame(oldItem: Gem, newItem: Gem): Boolean = oldItem == newItem
    }
}
