package com.novahorizon.wanderly.ui.profile

import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.novahorizon.wanderly.R

object ProfileDialogs {
    fun showEditUsernameDialog(
        fragment: Fragment,
        currentUsername: String?,
        onUpdate: (String) -> Unit
    ) {
        val context = fragment.requireContext()
        val container = FrameLayout(context).apply {
            val dp16 = (16 * resources.displayMetrics.density).toInt()
            setPadding(dp16, dp16, dp16, 0)
        }
        val editText = EditText(context).apply {
            setText(currentUsername)
            setSelectAllOnFocus(true)
        }
        container.addView(editText)

        AlertDialog.Builder(context, R.style.Wanderly_AlertDialog)
            .setTitle(R.string.profile_edit_username)
            .setView(container)
            .setPositiveButton(R.string.profile_edit_username_confirm) { _, _ ->
                onUpdate(editText.text.toString().trim())
            }
            .setNegativeButton(R.string.profile_edit_username_cancel, null)
            .show()
    }

    fun showClassSelectionDialog(
        fragment: Fragment,
        onDismiss: () -> Unit,
        onClassSelected: (String, AlertDialog) -> Unit
    ) {
        val context = fragment.requireContext()
        val dp16 = (16 * context.resources.displayMetrics.density).toInt()
        val dp12 = (12 * context.resources.displayMetrics.density).toInt()

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp16, dp16, dp16, dp16)
        }

        val classes = listOf(
            "EXPLORER" to "Explorer",
            "SOCIAL BEE" to "Social Bee",
            "ADVENTURER" to "Adventurer"
        )

        val dialog = AlertDialog.Builder(context, R.style.Wanderly_AlertDialog)
            .setTitle(R.string.profile_class_confirm_title)
            .setView(layout)
            .setCancelable(false)
            .setOnDismissListener { onDismiss() }
            .create()

        classes.forEach { (classId, label) ->
            val button = com.google.android.material.button.MaterialButton(context).apply {
                text = label
                setOnClickListener { onClassSelected(classId, dialog) }
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp12 }
            layout.addView(button, params)
        }

        dialog.window?.setWindowAnimations(R.style.Wanderly_DialogAnimation)
        dialog.show()
    }

    fun showClassConfirmationDialog(
        fragment: Fragment,
        className: String,
        onConfirm: () -> Unit
    ) {
        AlertDialog.Builder(fragment.requireContext(), R.style.Wanderly_AlertDialog)
            .setTitle(R.string.profile_class_confirm_title)
            .setMessage(fragment.getString(R.string.profile_class_confirm_message, className))
            .setPositiveButton(R.string.profile_class_confirm_positive) { _, _ ->
                onConfirm()
            }
            .setNegativeButton(R.string.profile_class_confirm_negative, null)
            .show()
    }
}
