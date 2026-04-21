package com.novahorizon.wanderly.ui.profile

import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.novahorizon.wanderly.R
import com.novahorizon.wanderly.databinding.DialogClassSelectionBinding
import com.novahorizon.wanderly.databinding.DialogEditUsernameBinding

object ProfileDialogs {
    fun showEditUsernameDialog(
        fragment: Fragment,
        currentUsername: String?,
        onUpdate: (String) -> Unit
    ) {
        val dialogBinding = DialogEditUsernameBinding.inflate(LayoutInflater.from(fragment.requireContext()))
        dialogBinding.usernameEditText.setText(currentUsername)
        AlertDialog.Builder(fragment.requireContext(), R.style.Wanderly_AlertDialog)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.profile_edit_username_confirm) { _, _ ->
                onUpdate(dialogBinding.usernameEditText.text.toString().trim())
            }
            .setNegativeButton(R.string.profile_edit_username_cancel, null)
            .show()
    }

    fun showClassSelectionDialog(
        fragment: Fragment,
        onDismiss: () -> Unit,
        onClassSelected: (String, AlertDialog) -> Unit
    ) {
        val dialogBinding = DialogClassSelectionBinding.inflate(LayoutInflater.from(fragment.requireContext()))
        val dialog = AlertDialog.Builder(fragment.requireContext(), R.style.Wanderly_AlertDialog)
            .setView(dialogBinding.root)
            .setCancelable(false)
            .setOnDismissListener { onDismiss() }
            .create()

        dialog.window?.setWindowAnimations(R.style.Wanderly_DialogAnimation)

        dialogBinding.classExplorer.setOnClickListener {
            onClassSelected("EXPLORER", dialog)
        }
        dialogBinding.classSocial.setOnClickListener {
            onClassSelected("SOCIAL BEE", dialog)
        }
        dialogBinding.classAdventurer.setOnClickListener {
            onClassSelected("ADVENTURER", dialog)
        }

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
