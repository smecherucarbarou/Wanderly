package com.novahorizon.wanderly.ui.compose.screens.map

import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.novahorizon.wanderly.OsmdroidInitializer
import com.novahorizon.wanderly.R
import com.novahorizon.wanderly.data.Mission
import com.novahorizon.wanderly.ui.compose.components.HoneyButton
import com.novahorizon.wanderly.ui.compose.components.WanderlyCard
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.views.MapView

@Composable
fun MapScreen(
    activeMission: Mission?,
    isMapReady: Boolean,
    onMyLocation: () -> Unit,
    onNavigateToMissions: () -> Unit,
    onMapViewCreated: (MapView) -> Unit,
    onMapViewDisposed: () -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    var isOsmdroidReady by remember { mutableStateOf(false) }

    LaunchedEffect(context) {
        runCatching {
            OsmdroidInitializer.awaitInitialized(context.applicationContext)
        }
        isOsmdroidReady = true
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (isOsmdroidReady) {
            AndroidView(
                factory = { viewContext ->
                    MapView(viewContext).apply {
                        setLayerType(View.LAYER_TYPE_HARDWARE, null)
                        setTileSource(TileSourceFactory.MAPNIK)
                        setMultiTouchControls(true)
                        minZoomLevel = 3.0
                        isVerticalMapRepetitionEnabled = false
                        isHorizontalMapRepetitionEnabled = false
                        setScrollableAreaLimitLatitude(85.0, -85.0, 0)
                        setScrollableAreaLimitLongitude(-180.0, 180.0, 0)
                        controller.setZoom(16.0)
                        onMapViewCreated(this)
                    }
                },
                update = { mapView ->
                    mapView.invalidate()
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                // MapView lifecycle handled by Fragment
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
                onMapViewDisposed()
            }
        }

        if (!isMapReady || !isOsmdroidReady) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(32.dp),
                color = MaterialTheme.colorScheme.primary
            )
        }

        FloatingActionButton(
            onClick = onMyLocation,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 160.dp),
            containerColor = MaterialTheme.colorScheme.primary
        ) {
            Icon(
                imageVector = Icons.Filled.MyLocation,
                contentDescription = stringResource(R.string.cd_my_location),
                tint = MaterialTheme.colorScheme.onPrimary
            )
        }

        WanderlyCard(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
        ) {
            Text(
                text = activeMission?.text?.ifBlank { null }
                    ?: stringResource(R.string.map_preview_default),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(12.dp))
            HoneyButton(
                text = if (activeMission != null) {
                    stringResource(R.string.map_go_to_missions)
                } else {
                    stringResource(R.string.generate_mission)
                },
                onClick = onNavigateToMissions
            )
        }
    }
}
