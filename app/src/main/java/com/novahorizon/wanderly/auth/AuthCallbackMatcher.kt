package com.novahorizon.wanderly.auth

import com.novahorizon.wanderly.Constants

object AuthCallbackMatcher {
    fun matches(scheme: String?, host: String?, path: String?): Boolean {
        val validHost = host == Constants.AUTH_CALLBACK_HOST ||
            host == Constants.AUTH_CALLBACK_LEGACY_HOST

        return scheme == Constants.AUTH_CALLBACK_SCHEME &&
            validHost &&
            path == Constants.AUTH_CALLBACK_PATH
    }
}
