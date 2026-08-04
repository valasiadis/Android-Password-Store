/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.onboarding.fragments

import android.content.Intent
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
import app.passwordstore.ui.dialogs.showsTip
import app.passwordstore.ui.git.config.GitServerConfigActivity
import app.passwordstore.ui.onboarding.activity.GitIdentitySetupActivity
import app.passwordstore.ui.onboarding.activity.PgpSetupActivity
import app.passwordstore.ui.onboarding.activity.SetupStepActivity
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

/**
 * Where a store comes from, and the walk through everything it needs before it can exist.
 *
 * The questions are asked as a flow of screens rather than a stack of dialogs, each step storing
 * its own answer where the settings keep it: the key that encrypts the entries and how they are
 * written, then who the commits belong to, then — for a store that comes from a server — where it
 * lives. Every step shows what is already stored, so a second walk through the flow is a matter of
 * pressing on rather than answering again.
 */
class CloneFragment : Fragment(R.layout.fragment_clone) {

  private val binding by viewBinding(FragmentCloneBinding::bind)

  private val settings by unsafeLazy { requireActivity().applicationContext.sharedPrefs }

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

  /** Whether the store being set up comes from a server, which adds a step to the flow. */
  private var isCloning = false

  /** The key chosen for this store, remembered until there is a repository to write it into. */
  private var setupKeyIds: String? = null

  /**
   * Builds the flow and starts it. The steps carry each other, so back walks it in reverse rather
   * than dropping the user here, and only the first step reports to this screen — by then the store
   * has been cloned or is about to be created, and the key it belongs to is in hand.
   */
  private fun startSetup() {
    val stepCount = if (isCloning) STEPS_WITH_REMOTE else STEPS_WITHOUT_REMOTE
    val remote =
      if (isCloning) {
        GitServerConfigActivity.createCloneIntent(
          context = requireContext(),
          step = STEP_REMOTE,
          stepCount = stepCount,
        )
      } else null
    val identity =
      Intent(requireContext(), GitIdentitySetupActivity::class.java)
        .asStep(STEP_IDENTITY, stepCount)
        .putExtra(SetupStepActivity.EXTRA_NEXT_STEP, remote)
    val key =
      Intent(requireContext(), PgpSetupActivity::class.java)
        .asStep(STEP_KEY, stepCount)
        .putExtra(SetupStepActivity.EXTRA_KEY_IDS, setupKeyIds)
        .putExtra(SetupStepActivity.EXTRA_NEXT_STEP, identity)
    setupAction.launch(key)
  }

  private fun Intent.asStep(step: Int, stepCount: Int) =
    putExtra(SetupStepActivity.EXTRA_STEP, step)
      .putExtra(SetupStepActivity.EXTRA_STEP_COUNT, stepCount)

  /**
   * The flow is through: everything the store needs has been answered, and a cloned store already
   * exists. What is left is the store this screen was asked for.
   */
  private val setupAction =
    registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
      if (result.resultCode != AppCompatActivity.RESULT_OK) {
        // Backing out of the first step ends setup, and the one thing a store cannot do without
        // is a key to encrypt to — so that, and not the flow, is what is worth saying.
        requireActivity()
          .snackbar(
            message = getString(R.string.gpg_key_select_mandatory),
            length = Snackbar.LENGTH_LONG,
          )
        return@registerForActivityResult
      }
      setupKeyIds = result.data?.getStringExtra(SetupStepActivity.EXTRA_KEY_IDS)
      if (!isCloning) {
        createRepository()
        return@registerForActivityResult
      }
      // A cloned pass repository already says which key it uses; anything else is given the key
      // chosen during setup.
      if (File(PasswordRepository.getRepositoryDirectory(), ".gpg-id").isFile()) {
        settings.edit { putBoolean(PreferenceKeys.REPOSITORY_INITIALIZED, true) }
        finish()
      } else {
        writeChosenKey()
      }
    }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)
    ViewCompat.setOnApplyWindowInsetsListener(view, windowInsetsLambda)
    savedInstanceState?.let {
      isCloning = it.getBoolean(STATE_CLONING)
      setupKeyIds = it.getString(STATE_KEY_IDS)
    }

    // Everything a store needs settled is settled before it exists: which key encrypts its
    // entries and how they are written, and who the commits belong to. The repository comes last,
    // because it is the only step that can be answered differently later without rewriting
    // anything.
    binding.repoTypeHelp.showsTip(R.string.setup_repo_tip)
    binding.cloneRemote.setOnClickListener {
      isCloning = true
      startSetup()
    }
    binding.createLocal.setOnClickListener {
      isCloning = false
      startSetup()
    }
  }

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    outState.putBoolean(STATE_CLONING, isCloning)
    outState.putString(STATE_KEY_IDS, setupKeyIds)
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

    private const val STEP_KEY = 1
    private const val STEP_IDENTITY = 2
    private const val STEP_REMOTE = 3
    private const val STEPS_WITH_REMOTE = 3
    private const val STEPS_WITHOUT_REMOTE = 2

    private const val STATE_CLONING = "SETUP_IS_CLONING"
    private const val STATE_KEY_IDS = "SETUP_KEY_IDS"

    fun newInstance(): CloneFragment = CloneFragment()
  }
}
