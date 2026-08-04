/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.onboarding.activity

import android.os.Bundle
import android.view.View
import app.passwordstore.R
import app.passwordstore.crypto.PGPIdentifier
import app.passwordstore.data.crypto.CryptoRepository
import app.passwordstore.databinding.GitIdentityFieldsBinding
import app.passwordstore.ui.git.config.GitIdentityFields
import app.passwordstore.util.settings.GitSettings
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Who the commits belong to, asked before there is anything to commit.
 *
 * Git records a name and address with every change, and invents one from the device's hostname
 * rather than fail, which is how stores end up with commits by "root@localhost". Both are therefore
 * required to leave this step. When the key chosen a step earlier names someone, that is offered as
 * the answer: a store's entries and its history usually belong to the same person.
 */
@AndroidEntryPoint
class GitIdentitySetupActivity : SetupStepActivity() {

  @Inject lateinit var gitSettings: GitSettings
  @Inject lateinit var cryptoRepository: CryptoRepository

  private lateinit var identity: GitIdentityFields

  override val contentLayout = R.layout.git_identity_fields
  override val stepTitle = R.string.setup_identity_title
  override val stepMessage = R.string.setup_identity_message
  override val stepIcon = R.drawable.ic_person_black_24dp

  override fun onContentInflated(content: View, savedInstanceState: Bundle?) {
    identity =
      GitIdentityFields(GitIdentityFieldsBinding.bind(content), gitSettings, ::setCanProceed)
    // Only on the way in: a field the user has since emptied is their answer, not a gap to fill.
    if (savedInstanceState == null) suggestIdentityFromKey()
    identity.focusFirstEmptyField()
  }

  /** The name and address on the chosen key, when exactly one key was chosen to read them off. */
  private fun suggestIdentityFromKey() {
    val keyId =
      intent
        .getStringExtra(EXTRA_KEY_IDS)
        ?.split("\n")
        ?.filter(String::isNotBlank)
        ?.singleOrNull()
        ?.let(PGPIdentifier::fromString) ?: return
    val userId = cryptoRepository.getUserIdFromKeyId(keyId)?.takeIf { it != "null" } ?: return
    identity.suggest(
      name = userId.substringBefore(" <").substringBefore(" (").trim(),
      email = cryptoRepository.getEmailFromKeyId(keyId),
    )
  }

  override fun onPause() {
    identity.save()
    super.onPause()
  }

  override fun onNext() {
    identity.save()
    proceed()
  }
}
