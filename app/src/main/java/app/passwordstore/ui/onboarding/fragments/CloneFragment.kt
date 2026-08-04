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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
        // A cloned pass repository already says which key it uses; anything else is given the
        // key chosen during setup.
        if (File(PasswordRepository.getRepositoryDirectory(), ".gpg-id").isFile()) {
          settings.edit { putBoolean(PreferenceKeys.REPOSITORY_INITIALIZED, true) }
          finish()
        } else {
          writeChosenKey()
        }
      }
    }

  /** Records the key chosen during setup as the store's own, and opens the app on it. */
  private fun writeChosenKey() {
    val keyIds = setupKeyIds ?: return
    lifecycleScope.launch {
      File(PasswordRepository.getRepositoryDirectory(), ".gpg-id").writeText(keyIds + "\n")
      settings.edit { putBoolean(PreferenceKeys.REPOSITORY_INITIALIZED, true) }
      requireActivity()
        .commitChange(getString(R.string.git_commit_gpg_id, getString(R.string.app_name)))
      finish()
    }
  }

  /** What to do once every question this store needs answered has been. */
  private var pendingAfterSetup: (() -> Unit)? = null

  /**
   * Walks the questions in the order they make sense in: how entries are written, which key writes
   * them, and who the commits belong to. Each is asked only while it is unanswered, so a second run
   * through this screen goes straight to the repository.
   */
  private fun askSetupQuestions(proceed: () -> Unit) {
    pendingAfterSetup = proceed
    askEncryptionFormat()
  }

  private fun askEncryptionFormat() {
    SetupDialogFragment.newInstance().show(parentFragmentManager, "SETUP_DIALOG")
  }

  private fun askForKey() {
    if (hasKey()) {
      askForIdentity()
      return
    }
    // Said before the picker opens rather than left to be inferred from a list of keys.
    MaterialAlertDialogBuilder(requireContext())
      .setTitle(R.string.setup_key_title)
      .setMessage(R.string.setup_key_message)
      .setPositiveButton(R.string.setup_key_choose) { _, _ ->
        keySetupAction.launch(PGPKeyListActivity.newIntent(requireContext(), keySelection = true))
      }
      .setCancelable(false)
      .show()
  }

  private fun askForIdentity() {
    if (hasIdentity()) {
      finishSetupQuestions()
      return
    }
    GitIdentityDialogFragment.newInstance().show(parentFragmentManager, "GIT_IDENTITY_DIALOG")
  }

  private fun finishSetupQuestions() {
    val proceed = pendingAfterSetup
    pendingAfterSetup = null
    proceed?.invoke()
  }

  private fun hasIdentity(): Boolean {
    val settings = requireContext().applicationContext.sharedPrefs
    return !settings.getString(PreferenceKeys.GIT_CONFIG_AUTHOR_NAME, "").isNullOrEmpty() &&
      !settings.getString(PreferenceKeys.GIT_CONFIG_AUTHOR_EMAIL, "").isNullOrEmpty()
  }

  private fun hasKey(): Boolean = setupKeyIds != null

  /** The key chosen for this store, remembered until there is a repository to write it into. */
  private var setupKeyIds: String? = null

  /**
   * The key is chosen before the store exists, so it is held until there is a .gpg-id to put it in
   * — written by whichever route created the store.
   */
  private val keySetupAction =
    registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
      setupKeyIds =
        if (result.resultCode == AppCompatActivity.RESULT_OK) {
          result.data?.getStringExtra(PGPKeyListActivity.EXTRA_SELECTED_KEY)
        } else null
      if (setupKeyIds == null) {
        requireActivity()
          .snackbar(
            message = getString(R.string.gpg_key_select_mandatory),
            length = Snackbar.LENGTH_LONG,
          )
        pendingAfterSetup = null
        return@registerForActivityResult
      }
      askForIdentity()
    }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)
    ViewCompat.setOnApplyWindowInsetsListener(view, windowInsetsLambda)
    parentFragmentManager.setFragmentResultListener(
      SetupDialogFragment.SETUP_RESULT_KEY,
      viewLifecycleOwner,
    ) { _, _ ->
      askForKey()
    }
    parentFragmentManager.setFragmentResultListener(
      GitIdentityDialogFragment.IDENTITY_RESULT_KEY,
      viewLifecycleOwner,
    ) { _, _ ->
      finishSetupQuestions()
    }

    // Everything a store needs settled is settled before it exists: how its entries are written,
    // which key encrypts them, and who the commits belong to. The repository comes last, because
    // it is the only step that can be answered differently later without rewriting anything.
    binding.cloneRemote.setOnClickListener { askSetupQuestions(::cloneToHiddenDir) }
    binding.createLocal.setOnClickListener { askSetupQuestions(::createRepository) }
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
      writeChosenKey()
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
