package com.novahorizon.wanderly.observability

import android.util.Log
import com.novahorizon.wanderly.BuildConfig

object AppLogger {
    fun d(tag: String, message: String?) {
        if (!BuildConfig.DEBUG) return
        Log.d(tag, LogRedactor.redact(message))
    }

    fun i(tag: String, message: String?) {
        if (!BuildConfig.DEBUG) return
        Log.i(tag, LogRedactor.redact(message))
    }

    fun w(tag: String, message: String?, throwable: Throwable? = null) {
        if (!BuildConfig.DEBUG) return
        val safeMessage = LogRedactor.redact(message)
        if (throwable != null) {
            Log.w(tag, safeMessage, throwable)
        } else {
            Log.w(tag, safeMessage)
        }
    }

    fun e(tag: String, message: String?, throwable: Throwable? = null) {
        if (!BuildConfig.DEBUG) return
        val safeMessage = LogRedactor.redact(message)
        if (throwable != null) {
            Log.e(tag, safeMessage, throwable)
        } else {
            Log.e(tag, safeMessage)
        }
    }
}
