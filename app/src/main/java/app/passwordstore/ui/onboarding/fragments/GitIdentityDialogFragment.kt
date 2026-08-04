/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.passwordstore.ui.onboarding.fragments

import android.app.Dialog
import android.os.Bundle
import android.util.Patterns
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import androidx.core.os.bundleOf
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import app.passwordstore.R
import app.passwordstore.databinding.GitIdentityDialogFragmentBinding
import app.passwordstore.util.extensions.sharedPrefs
import app.passwordstore.util.extensions.unsafeLazy
import app.passwordstore.util.settings.PreferenceKeys
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Who is making these commits, asked before anything is cloned or created.
 *
 * Git records a name and address with every change, and a store whose first commits were made by
 * nobody in particular is awkward to correct afterwards, so this comes first and is required. Which
 * key encrypts what is a separate question, asked once there is a store to answer it for.
 */
class GitIdentityDialogFragment : DialogFragment() {

  private val binding by unsafeLazy { GitIdentityDialogFragmentBinding.inflate(layoutInflater) }
  private val settings by unsafeLazy { requireContext().applicationContext.sharedPrefs }

  override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
    binding.authorName.setText(settings.getString(PreferenceKeys.GIT_CONFIG_AUTHOR_NAME, ""))
    binding.authorEmail.setText(settings.getString(PreferenceKeys.GIT_CONFIG_AUTHOR_EMAIL, ""))
    binding.signCommits.isChecked =
      settings.getBoolean(PreferenceKeys.GIT_CONFIG_SIGN_COMMITS, false)

    val dialog =
      MaterialAlertDialogBuilder(requireContext())
        .setTitle(R.string.setup_identity_title)
        .setView(binding.root)
        .setPositiveButton(R.string.setup_continue, null)
        .setCancelable(false)
        .create()

    dialog.setOnShowListener {
      val proceed = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
      proceed.setOnClickListener { save() }
      fun revalidate() {
        proceed.isEnabled = name().isNotEmpty() && isEmail(email())
      }
      binding.authorName.doOnTextChanged { _, _, _, _ -> revalidate() }
      binding.authorEmail.doOnTextChanged { _, _, _, _ ->
        binding.authorEmailContainer.error =
          if (email().isEmpty() || isEmail(email())) null
          else getString(R.string.invalid_email_dialog_text)
        revalidate()
      }
      revalidate()
    }
    return dialog
  }

  private fun name() = binding.authorName.text.toString().trim()

  private fun email() = binding.authorEmail.text.toString().trim()

  private fun isEmail(address: String) = address.matches(Patterns.EMAIL_ADDRESS.toRegex())

  private fun save() {
    settings.edit {
      putString(PreferenceKeys.GIT_CONFIG_AUTHOR_NAME, name())
      putString(PreferenceKeys.GIT_CONFIG_AUTHOR_EMAIL, email())
      putBoolean(PreferenceKeys.GIT_CONFIG_SIGN_COMMITS, binding.signCommits.isChecked)
    }
    setFragmentResult(IDENTITY_RESULT_KEY, bundleOf())
    dismissAllowingStateLoss()
  }

  companion object {

    const val IDENTITY_RESULT_KEY = "git_identity_set"

    fun newInstance(): GitIdentityDialogFragment = GitIdentityDialogFragment()
  }
}
