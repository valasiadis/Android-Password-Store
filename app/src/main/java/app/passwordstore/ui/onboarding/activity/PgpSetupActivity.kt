/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.onboarding.activity

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.core.content.edit
import app.passwordstore.R
import app.passwordstore.data.crypto.CryptoRepository
import app.passwordstore.databinding.SetupStepPgpBinding
import app.passwordstore.ui.dialogs.ListRows
import app.passwordstore.ui.pgp.PGPKeyListActivity
import app.passwordstore.ui.pgp.keyNames
import app.passwordstore.util.extensions.sharedPrefs
import app.passwordstore.util.settings.PreferenceKeys
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * The first thing a store needs: a key to encrypt to, and the shape of the files it writes.
 *
 * Both are asked here because both decide what every entry will look like, and neither can be
 * changed afterwards for what is already written — a store re-keyed later leaves its existing
 * entries encrypted to the old key, and switching the file format does not rewrite them either.
 */
@AndroidEntryPoint
class PgpSetupActivity : SetupStepActivity() {

  @Inject lateinit var cryptoRepository: CryptoRepository

  private lateinit var binding: SetupStepPgpBinding

  /** The keys chosen here, held until there is a store with a `.gpg-id` to write them into. */
  private var selectedKeyIds: String? = null

  private val keySelectAction =
    registerForActivityResult(StartActivityForResult()) { result ->
      if (result.resultCode != RESULT_OK) return@registerForActivityResult
      selectedKeyIds = result.data?.getStringExtra(PGPKeyListActivity.EXTRA_SELECTED_KEY)
      showSelectedKeys()
    }

  override val contentLayout = R.layout.setup_step_pgp
  override val stepTitle = R.string.setup_key_title
  override val stepMessage = R.string.setup_key_message
  override val stepIcon = R.drawable.ic_key_24dp

  override fun onContentInflated(content: View, savedInstanceState: Bundle?) {
    binding = SetupStepPgpBinding.bind(content)
    // Coming back to this step from the one after it finds the keys chosen the first time round.
    selectedKeyIds =
      savedInstanceState?.getString(STATE_SELECTED_KEYS) ?: intent.getStringExtra(EXTRA_KEY_IDS)

    // Stored as it is switched, like every other setting in the app, so that going back a step and
    // coming forward again shows what was chosen rather than what the default is.
    ListRows.header(
      binding.encryptionHeader,
      R.string.setup_encryption_title,
      R.string.setup_encryption_message,
    )
    ListRows.switch(
      binding.asciiArmor,
      R.string.pref_pgp_ascii_armor_title,
      checked = sharedPrefs.getBoolean(PreferenceKeys.ASCII_ARMOR, false),
      summary = getString(R.string.setup_ascii_armor_explanation),
    ) { checked ->
      sharedPrefs.edit { putBoolean(PreferenceKeys.ASCII_ARMOR, checked) }
    }

    // Stored as it is chosen, like the switch above it: the repository is cloned or created where
    // this says, and that happens after the last step.
    ListRows.header(
      binding.repoVisibilityHeader,
      R.string.select_n_repository_visibility,
      R.string.setup_repo_visibility_tip,
    )
    ListRows.choice(
      binding.internalRepo,
      title = getString(R.string.repo_visibility_internal_title),
      summary = getString(R.string.repo_visibility_internal_summary),
    ) {
      chooseVisibility(shared = false)
    }
    ListRows.choice(
      binding.sharedRepo,
      title = getString(R.string.repo_visibility_shared_title),
      summary = getString(R.string.shared_repo_path, Build.MODEL),
    ) {
      chooseVisibility(shared = true)
    }
    showVisibility()

    showSelectedKeys()
  }

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    outState.putString(STATE_SELECTED_KEYS, selectedKeyIds)
  }

  private fun chooseKeys() {
    keySelectAction.launch(
      PGPKeyListActivity.newIntent(this, keySelection = true, preselectedKeyIds = selectedKeyIds)
    )
  }

  private fun chooseVisibility(shared: Boolean) {
    sharedPrefs.edit { putBoolean(PreferenceKeys.PREF_SHARED_REPO, shared) }
    showVisibility()
  }

  private fun showVisibility() {
    val shared = sharedPrefs.getBoolean(PreferenceKeys.PREF_SHARED_REPO, false)
    ListRows.select(binding.internalRepo, selected = !shared)
    ListRows.select(binding.sharedRepo, selected = shared)
  }

  /** Names the chosen keys in their row, and opens the way onwards once there are any. */
  private fun showSelectedKeys() {
    val names = cryptoRepository.keyNames(selectedKeyIds)
    ListRows.value(
      binding.gpgKey,
      R.string.folder_encryption_key,
      if (names.isEmpty()) getString(R.string.setup_key_none_chosen)
      else names.joinToString(separator = ", "),
      onOpen = ::chooseKeys,
    )
    setCanProceed(names.isNotEmpty())
  }

  override fun onNext() {
    proceed(Intent().putExtra(EXTRA_KEY_IDS, selectedKeyIds))
  }

  private companion object {

    const val STATE_SELECTED_KEYS = "SELECTED_KEY_IDS"
  }
}
