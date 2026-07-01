package com.novahorizon.wanderly.ui.gems

import android.location.Address
import java.text.Normalizer
import java.util.Locale

object GemsCityResolver {
    fun resolveSearchCity(address: Address?): String {
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
}
