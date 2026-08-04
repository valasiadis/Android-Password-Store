/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.passwordstore.ui.onboarding.fragments

import android.app.Dialog
import android.os.Bundle
import androidx.core.content.edit
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import app.passwordstore.R
import app.passwordstore.databinding.SetupDialogFragmentBinding
import app.passwordstore.util.extensions.sharedPrefs
import app.passwordstore.util.extensions.unsafeLazy
import app.passwordstore.util.settings.PreferenceKeys
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * The last step of setting a store up: how entries are written to it.
 *
 * Asked here rather than left in the settings for later because it decides the shape of every file
 * the store will hold, and changing it afterwards does not rewrite what is already there. Who is
 * making the commits is asked earlier, in [GitIdentityDialogFragment].
 */
class SetupDialogFragment : DialogFragment() {

  private val binding by unsafeLazy { SetupDialogFragmentBinding.inflate(layoutInflater) }
  private val settings by unsafeLazy { requireContext().applicationContext.sharedPrefs }

  override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
    binding.asciiArmor.isChecked = settings.getBoolean(PreferenceKeys.ASCII_ARMOR, false)
    return MaterialAlertDialogBuilder(requireContext())
      .setTitle(R.string.setup_encryption_title)
      .setView(binding.root)
      .setPositiveButton(R.string.setup_finish) { _, _ ->
        settings.edit { putBoolean(PreferenceKeys.ASCII_ARMOR, binding.asciiArmor.isChecked) }
        setFragmentResult(SETUP_RESULT_KEY, bundleOf())
      }
      .setCancelable(false)
      .create()
  }

  companion object {

    const val SETUP_RESULT_KEY = "setup_complete"

    fun newInstance(): SetupDialogFragment = SetupDialogFragment()
  }
}
