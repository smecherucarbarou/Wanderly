package com.novahorizon.wanderly.auth

import android.net.Uri
import com.novahorizon.wanderly.Constants

object AuthCallbackMatcher {
    fun matches(scheme: String?, host: String?, path: String?): Boolean {
        return scheme == Constants.AUTH_CALLBACK_SCHEME &&
            host == Constants.AUTH_CALLBACK_HOST &&
            path == Constants.AUTH_CALLBACK_PATH
    }

    fun matchesCallbackUri(uri: Uri?): Boolean {
        if (!matches(uri?.scheme, uri?.host, uri?.path)) return false
        return uri.hasAuthPayload()
    }

    private fun Uri?.hasAuthPayload(): Boolean {
        if (this == null) return false
        val fragment = fragment.orEmpty()
        return !getQueryParameter("code").isNullOrBlank() ||
            !getQueryParameter("access_token").isNullOrBlank() ||
            fragment.contains("access_token=") ||
            fragment.contains("refresh_token=")
    }
}
