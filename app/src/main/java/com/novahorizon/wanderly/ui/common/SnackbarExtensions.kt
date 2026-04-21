package com.novahorizon.wanderly.ui.common

import android.app.Activity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.snackbar.Snackbar
import com.novahorizon.wanderly.R

fun Fragment.showSnackbar(message: String, isError: Boolean = false) {
    val snackbar = Snackbar.make(requireView(), message, Snackbar.LENGTH_LONG)
    styleSnackbar(snackbar, isError)
    snackbar.show()
}

fun Activity.showSnackbar(message: String, isError: Boolean = false) {
    val snackbar = Snackbar.make(findViewById(android.R.id.content), message, Snackbar.LENGTH_LONG)
    styleSnackbar(snackbar, isError)
    snackbar.show()
}

private fun styleSnackbar(snackbar: Snackbar, isError: Boolean) {
    val context = snackbar.context
    if (isError) {
        snackbar.setBackgroundTint(ContextCompat.getColor(context, R.color.error))
        snackbar.setTextColor(ContextCompat.getColor(context, R.color.card_background))
    } else {
        snackbar.setBackgroundTint(ContextCompat.getColor(context, R.color.primary))
        snackbar.setTextColor(ContextCompat.getColor(context, R.color.secondary))
    }
}
