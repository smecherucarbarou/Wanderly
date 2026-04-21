package com.novahorizon.wanderly.auth

import android.app.Activity
import android.content.Intent
import com.novahorizon.wanderly.AuthActivity
import com.novahorizon.wanderly.MainActivity

object SessionNavigator {
    fun openMain(activity: Activity) {
        activity.startActivity(
            Intent(activity, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
        )
        activity.finish()
    }

    fun openAuth(activity: Activity) {
        activity.startActivity(
            Intent(activity, AuthActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
        )
        activity.finish()
    }
}
