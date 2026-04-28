package com.novahorizon.wanderly.ui.common

import android.content.Context
import androidx.annotation.StringRes

sealed class UiText {
    data class DynamicString(val value: String) : UiText()

    data class StringResource(
        @param:StringRes val resId: Int,
        val args: List<Any> = emptyList()
    ) : UiText()

    fun asString(context: Context): String {
        return when (this) {
            is DynamicString -> value
            is StringResource -> context.getString(resId, *args.toTypedArray())
        }
    }

    companion object {
        fun resource(@StringRes resId: Int, vararg args: Any): UiText =
            StringResource(resId, args.toList())
    }
}
