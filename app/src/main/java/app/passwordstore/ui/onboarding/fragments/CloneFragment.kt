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
          finishSetup()
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
          finishSetup()
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

  /**
   * Asks who is committing before anything else is decided, since every route from here ends in a
   * commit. Once answered it stays answered, and the question is not asked again.
   */
  private fun withIdentity(proceed: () -> Unit) {
    val settings = requireContext().applicationContext.sharedPrefs
    val hasIdentity =
      !settings.getString(PreferenceKeys.GIT_CONFIG_AUTHOR_NAME, "").isNullOrEmpty() &&
        !settings.getString(PreferenceKeys.GIT_CONFIG_AUTHOR_EMAIL, "").isNullOrEmpty()
    if (hasIdentity) {
      proceed()
      return
    }
    pendingAfterIdentity = proceed
    GitIdentityDialogFragment.newInstance().show(parentFragmentManager, "GIT_IDENTITY_DIALOG")
  }

  private var pendingAfterIdentity: (() -> Unit)? = null

  /**
   * Asks the last of the questions a store needs answered — who commits, and how entries are
   * written — before the app opens on it.
   */
  private fun finishSetup() {
    SetupDialogFragment.newInstance().show(parentFragmentManager, "SETUP_DIALOG")
  }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)
    ViewCompat.setOnApplyWindowInsetsListener(view, windowInsetsLambda)
    parentFragmentManager.setFragmentResultListener(
      SetupDialogFragment.SETUP_RESULT_KEY,
      viewLifecycleOwner,
    ) { _, _ ->
      finish()
    }
    parentFragmentManager.setFragmentResultListener(
      GitIdentityDialogFragment.IDENTITY_RESULT_KEY,
      viewLifecycleOwner,
    ) { _, _ ->
      pendingAfterIdentity?.invoke()
      pendingAfterIdentity = null
    }

    binding.cloneRemote.setOnClickListener { withIdentity(::cloneToHiddenDir) }
    binding.createLocal.setOnClickListener { withIdentity(::createRepository) }
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
