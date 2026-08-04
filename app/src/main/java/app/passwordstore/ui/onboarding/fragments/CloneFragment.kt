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
import app.passwordstore.ui.git.config.GitServerConfigActivity
import app.passwordstore.ui.onboarding.activity.GitIdentitySetupActivity
import app.passwordstore.ui.onboarding.activity.PgpSetupActivity
import app.passwordstore.ui.onboarding.activity.SetupStepActivity
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

/**
 * Where a store comes from, and the walk through everything it needs before it can exist.
 *
 * The questions are asked as a flow of screens rather than a stack of dialogs, each step storing
 * its own answer where the settings keep it: the key that encrypts the entries and how they are
 * written, then who the commits belong to, then — for a store that comes from a server — where it
 * lives. A step already answered is skipped, so coming back here goes straight to the repository.
 */
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

  /** Whether the store being set up comes from a server, which adds a step to the flow. */
  private var isCloning = false

  /** The key chosen for this store, remembered until there is a repository to write it into. */
  private var setupKeyIds: String? = null

  private val stepCount
    get() = if (isCloning) STEPS_WITH_REMOTE else STEPS_WITHOUT_REMOTE

  /**
   * Starts the flow, or resumes it where it was left. Steps that already have an answer are not
   * asked again: a store set up over two attempts is still set up once.
   */
  private fun continueSetup() {
    when {
      setupKeyIds == null ->
        startStep(Intent(requireContext(), PgpSetupActivity::class.java), STEP_KEY)
      !hasIdentity() ->
        startStep(
          Intent(requireContext(), GitIdentitySetupActivity::class.java)
            .putExtra(GitIdentitySetupActivity.EXTRA_KEY_IDS, setupKeyIds),
          STEP_IDENTITY,
        )
      isCloning -> cloneAction.launch(cloneIntent())
      else -> createRepository()
    }
  }

  private fun startStep(intent: Intent, step: Int) {
    intent.putExtra(SetupStepActivity.EXTRA_STEP, step)
    intent.putExtra(SetupStepActivity.EXTRA_STEP_COUNT, stepCount)
    currentStep = step
    setupStepAction.launch(intent)
  }

  /** The step being answered, so that leaving one can say why the flow stopped. */
  private var currentStep = 0

  private fun cloneIntent() =
    GitServerConfigActivity.createCloneIntent(
      context = requireContext(),
      step = STEP_REMOTE,
      stepCount = stepCount,
    )

  /**
   * A step answered moves the flow on; a step left without an answer stops it where it is. Nothing
   * is undone by that: what a step stored stays stored, and coming back resumes from there.
   */
  private val setupStepAction =
    registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
      if (result.resultCode != AppCompatActivity.RESULT_OK) {
        // Nothing to encrypt to is the one answer a store cannot do without, and the one worth
        // saying out loud: the other steps are either already answered or asked again next time.
        if (currentStep == STEP_KEY) {
          requireActivity()
            .snackbar(
              message = getString(R.string.gpg_key_select_mandatory),
              length = Snackbar.LENGTH_LONG,
            )
        }
        return@registerForActivityResult
      }
      result.data?.getStringExtra(PGPKeyListActivity.EXTRA_SELECTED_KEY)?.let { setupKeyIds = it }
      continueSetup()
    }

  private fun hasIdentity(): Boolean =
    !settings.getString(PreferenceKeys.GIT_CONFIG_AUTHOR_NAME, "").isNullOrEmpty() &&
      !settings.getString(PreferenceKeys.GIT_CONFIG_AUTHOR_EMAIL, "").isNullOrEmpty()

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
    binding.cloneRemote.setOnClickListener {
      isCloning = true
      continueSetup()
    }
    binding.createLocal.setOnClickListener {
      isCloning = false
      continueSetup()
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
