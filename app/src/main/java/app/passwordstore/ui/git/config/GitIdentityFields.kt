/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.passwordstore.ui.git.config

import android.util.Patterns
import androidx.core.widget.doOnTextChanged
import app.passwordstore.R
import app.passwordstore.databinding.GitIdentityFieldsBinding
import app.passwordstore.util.settings.GitSettings

/**
 * The name, address and signing choice Git records with every change, bound to where they are kept.
 *
 * Setting a store up asks for these, and the settings change them afterwards; both do it through
 * this, so an address is judged the same way and stored in the same place either way. Values are
 * stored as they are typed — what the screen shows is what is stored, and leaving the screen is
 * never a step the user has to take for their edit to count. An address is only stored once it is
 * one, and says so under the field until then, leaving the last good one in place until it is.
 */
class GitIdentityFields(
  private val binding: GitIdentityFieldsBinding,
  private val gitSettings: GitSettings,
  private val onValidityChanged: (Boolean) -> Unit = {},
) {

  init {
    binding.gitUserName.setText(gitSettings.authorName)
    binding.gitUserEmail.setText(gitSettings.authorEmail)
    binding.signCommits.isChecked = gitSettings.signCommits

    binding.gitUserName.doOnTextChanged { _, _, _, _ ->
      saveName()
      reportValidity()
    }
    binding.gitUserEmail.doOnTextChanged { _, _, _, _ ->
      saveEmail()
      reportValidity()
    }
    binding.signCommits.setOnCheckedChangeListener { _, isChecked ->
      gitSettings.signCommits = isChecked
    }
    reportValidity()
  }

  /** Puts an identity in the empty fields, for callers that know one before it is typed. */
  fun suggest(name: String?, email: String?) {
    if (!name.isNullOrEmpty() && this.name.isEmpty()) binding.gitUserName.setText(name)
    if (!email.isNullOrEmpty() && this.email.isEmpty()) binding.gitUserEmail.setText(email)
  }

  fun focusFirstEmptyField() {
    when {
      name.isEmpty() -> binding.gitUserName.requestFocus()
      email.isEmpty() -> binding.gitUserEmail.requestFocus()
    }
  }

  /** Catches an edit if the screen is left while a field still has focus. */
  fun save() {
    saveName()
    saveEmail()
  }

  /** Whether Git has both of the things it needs to record a change. */
  val isComplete: Boolean
    get() = name.isNotEmpty() && isAddress(email)

  private val name: String
    get() = binding.gitUserName.text.toString().trim()

  private val email: String
    get() = binding.gitUserEmail.text.toString().trim()

  private fun saveName() {
    gitSettings.authorName = name
  }

  private fun saveEmail() {
    val email = email
    if (email.isNotEmpty() && !isAddress(email)) {
      binding.emailInputLayout.error =
        binding.root.context.getString(R.string.invalid_email_dialog_text)
    } else {
      binding.emailInputLayout.error = null
      gitSettings.authorEmail = email
    }
  }

  private fun isAddress(email: String) = email.matches(Patterns.EMAIL_ADDRESS.toRegex())

  private fun reportValidity() = onValidityChanged(isComplete)
}
