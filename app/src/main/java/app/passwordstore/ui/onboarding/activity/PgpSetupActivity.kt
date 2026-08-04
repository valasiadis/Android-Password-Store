/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.onboarding.activity

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.core.content.edit
import app.passwordstore.R
import app.passwordstore.data.crypto.CryptoRepository
import app.passwordstore.databinding.SetupStepPgpBinding
import app.passwordstore.ui.dialogs.showsTip
import app.passwordstore.ui.pgp.PGPKeyListActivity
import app.passwordstore.ui.pgp.keyNames
import app.passwordstore.util.extensions.setInlineText
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

    binding.gpgKeyValue.setOnClickListener { chooseKeys() }
    binding.gpgKeyContainer.setEndIconOnClickListener { chooseKeys() }

    binding.asciiArmor.isChecked = sharedPrefs.getBoolean(PreferenceKeys.ASCII_ARMOR, false)
    // Stored as it is switched, like every other setting in the app, so that going back a step and
    // coming forward again shows what was chosen rather than what the default is.
    binding.asciiArmor.setOnCheckedChangeListener { _, isChecked ->
      sharedPrefs.edit { putBoolean(PreferenceKeys.ASCII_ARMOR, isChecked) }
    }
    binding.asciiArmorHelp.showsTip(R.string.setup_ascii_armor_explanation)
    binding.encryptionHelp.showsTip(R.string.setup_encryption_message)

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

  /** Names the chosen keys in the field, and opens the way onwards once there are any. */
  private fun showSelectedKeys() {
    val names = cryptoRepository.keyNames(selectedKeyIds)
    binding.gpgKeyValue.setInlineText(
      if (names.isEmpty()) getString(R.string.setup_key_none_chosen)
      else names.joinToString(separator = ", ")
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
