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
        if (uri.containsTokenFragment()) return false
        return uri.hasCodePayload()
    }

    fun isSecureCallback(uri: Uri?): Boolean = matchesCallbackUri(uri)

    private fun Uri?.hasCodePayload(): Boolean {
        if (this == null) return false
        return !getQueryParameter("code").isNullOrBlank()
    }

    private fun Uri?.containsTokenFragment(): Boolean {
        if (this == null) return false
        val fragment = fragment.orEmpty()
        val query = query.orEmpty()
        return fragment.contains("access_token=") ||
            fragment.contains("refresh_token=") ||
            query.contains("access_token=") ||
            query.contains("refresh_token=") ||
            !getQueryParameter("access_token").isNullOrBlank() ||
            !getQueryParameter("refresh_token").isNullOrBlank()
    }
}
