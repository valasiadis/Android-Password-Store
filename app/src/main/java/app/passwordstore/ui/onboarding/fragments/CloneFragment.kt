/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.onboarding.fragments

import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import app.passwordstore.R
import app.passwordstore.data.repo.PasswordRepository
import app.passwordstore.databinding.FragmentCloneBinding
import app.passwordstore.ui.git.config.GitServerConfigActivity
import app.passwordstore.ui.pgp.PGPKeyListActivity
import app.passwordstore.util.extensions.commitChange
import app.passwordstore.util.extensions.finish
import app.passwordstore.util.extensions.sharedPrefs
import app.passwordstore.util.extensions.snackbar
import app.passwordstore.util.extensions.unsafeLazy
import app.passwordstore.util.extensions.viewBinding
import app.passwordstore.util.extensions.windowInsetsLambda
import app.passwordstore.util.settings.PreferenceKeys
import com.github.michaelbull.result.onErr
import com.github.michaelbull.result.runCatching
import com.google.android.material.snackbar.Snackbar
import java.io.File
import kotlinx.coroutines.launch
import logcat.LogPriority.ERROR
import logcat.asLog
import logcat.logcat

class CloneFragment : Fragment(R.layout.fragment_clone) {

  private val binding by viewBinding(FragmentCloneBinding::bind)

  private val settings by unsafeLazy { requireActivity().applicationContext.sharedPrefs }

  private val cloneAction =
    registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
      if (result.resultCode == AppCompatActivity.RESULT_OK) {
        if (File(PasswordRepository.getRepositoryDirectory(), ".gpg-id").isFile()) {
          settings.edit { putBoolean(PreferenceKeys.REPOSITORY_INITIALIZED, true) }
          finish()
        } else {
          // non-pass repository --> go to key selection
          selectGpgKey()
        }
      }
    }

  /**
   * Picks the key the repository will encrypt to, then records it. Launches the key list directly
   * rather than routing through a screen whose only content is a button that opens it.
   */
  private val gpgKeySelectAction =
    registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
      if (result.resultCode == AppCompatActivity.RESULT_OK) {
        val selectedKeyId =
          result.data?.getStringExtra(PGPKeyListActivity.EXTRA_SELECTED_KEY)
            ?: return@registerForActivityResult
        lifecycleScope.launch {
          File(PasswordRepository.getRepositoryDirectory(), ".gpg-id")
            .writeText(selectedKeyId + "\n")
          settings.edit { putBoolean(PreferenceKeys.REPOSITORY_INITIALIZED, true) }
          requireActivity()
            .commitChange(getString(R.string.git_commit_gpg_id, getString(R.string.app_name)))
          finish()
        }
      } else {
        requireActivity()
          .snackbar(
            message = getString(R.string.gpg_key_select_mandatory),
            length = Snackbar.LENGTH_LONG,
          )
      }
    }

  private fun selectGpgKey() {
    gpgKeySelectAction.launch(PGPKeyListActivity.newIntent(requireContext(), keySelection = true))
  }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)
    ViewCompat.setOnApplyWindowInsetsListener(view, windowInsetsLambda)

    binding.cloneRemote.setOnClickListener { cloneToHiddenDir() }
    binding.createLocal.setOnClickListener { createRepository() }
  }

  /** Clones a remote Git repository to the app's private directory */
  private fun cloneToHiddenDir() {
    cloneAction.launch(GitServerConfigActivity.createCloneIntent(requireContext()))
  }

  private fun createRepository() {
    val localDir = PasswordRepository.getRepositoryDirectory()
    runCatching {
      check(localDir.exists() || localDir.mkdir()) { "Failed to create directory!" }
      PasswordRepository.createRepository(localDir)
      if (!PasswordRepository.isInitialized) {
        PasswordRepository.initialize()
      }
      selectGpgKey()
    }
      .onErr { e ->
        logcat(ERROR) { e.asLog() }
        if (!localDir.delete()) {
          logcat { "Failed to delete local repository: $localDir" }
        }
        finish()
      }
  }

  companion object {

    fun newInstance(): CloneFragment = CloneFragment()
  }
}
