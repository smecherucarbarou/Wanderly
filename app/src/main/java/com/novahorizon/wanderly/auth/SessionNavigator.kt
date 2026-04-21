package com.novahorizon.wanderly.auth

import android.app.Activity
import android.content.Intent
import com.novahorizon.wanderly.AuthActivity
import com.novahorizon.wanderly.MainActivity

object SessionNavigator {
    @Volatile
    private var openMainOverride: ((Activity) -> Unit)? = null

    @Volatile
    private var openAuthOverride: ((Activity) -> Unit)? = null

    fun openMain(activity: Activity) {
        openMainOverride?.invoke(activity)?.let { return }
        activity.startActivity(
            Intent(activity, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
        )
        activity.finish()
    }

    fun openAuth(activity: Activity) {
        openAuthOverride?.invoke(activity)?.let { return }
        activity.startActivity(
            Intent(activity, AuthActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
        )
        activity.finish()
    }

    fun setOpenMainOverrideForTesting(handler: ((Activity) -> Unit)?) {
        openMainOverride = handler
    }

    fun setOpenAuthOverrideForTesting(handler: ((Activity) -> Unit)?) {
        openAuthOverride = handler
    }

    fun resetTestOverrides() {
        openMainOverride = null
        openAuthOverride = null
    }
}
