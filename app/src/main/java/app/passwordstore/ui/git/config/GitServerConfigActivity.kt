/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.passwordstore.ui.git.config

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
import androidx.lifecycle.lifecycleScope
import app.passwordstore.R
import app.passwordstore.data.repo.PasswordRepository
import app.passwordstore.databinding.ActivityGitCloneBinding
import app.passwordstore.ui.git.base.BaseGitActivity
import app.passwordstore.ui.sshkeygen.PgpAuthKeySelectionActivity
import app.passwordstore.ui.sshkeygen.SshKeyGenActivity
import app.passwordstore.ui.sshkeygen.SshKeyImportActivity
import app.passwordstore.util.extensions.enableEdgeToEdgeView
import app.passwordstore.util.extensions.launchActivity
import app.passwordstore.util.extensions.snackbar
import app.passwordstore.util.extensions.viewBinding
import app.passwordstore.util.settings.AuthMode
import app.passwordstore.util.settings.GitSettings
import app.passwordstore.util.settings.Protocol
import com.github.michaelbull.result.fold
import com.github.michaelbull.result.onErr
import com.github.michaelbull.result.runCatching
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority.ERROR
import logcat.asLog
import logcat.logcat

/**
 * Activity that encompasses both the initial clone as well as editing the server config for future
 * changes.
 */
class GitServerConfigActivity : BaseGitActivity() {

  private val binding by viewBinding(ActivityGitCloneBinding::inflate)

  private lateinit var oldAuthMode: AuthMode
  private lateinit var newAuthMode: AuthMode
  private var isClone = false

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdgeView(binding.root)
    isClone = intent?.extras?.getBoolean("cloning") ?: false
    if (isClone) {
      binding.saveButton.text = getString(R.string.clone_button)
    }
    // Editing the server config stores as the user goes, so it has no button. Cloning keeps one:
    // it starts work rather than storing a setting, and there is nothing else to trigger it.
    binding.saveButton.isVisible = isClone
    setContentView(binding.root)
    supportActionBar?.setDisplayHomeAsUpEnabled(true)

    oldAuthMode = gitSettings.authMode
    newAuthMode = gitSettings.authMode

    binding.authModeGroup.apply {
      when (oldAuthMode) {
        AuthMode.SshKey -> check(binding.authModeSshKey.id)
        AuthMode.Password -> check(binding.authModePassword.id)
        AuthMode.None -> clearChecked()
      }
      addOnButtonCheckedListener { _, _, _ ->
        if (checkedButtonIds.isEmpty()) {
          newAuthMode = AuthMode.None
        } else {
          when (checkedButtonId) {
            binding.authModeSshKey.id -> newAuthMode = AuthMode.SshKey
            binding.authModePassword.id -> newAuthMode = AuthMode.Password
            View.NO_ID -> newAuthMode = AuthMode.None
          }
        }
        binding.authKeyButton.isVisible = newAuthMode == AuthMode.SshKey
        if (!isClone) applySettings()
      }
    }

    binding.serverUrl.setText(
      gitSettings.url.also {
        if (it.isNullOrEmpty()) return@also
        setAuthModes(it.startsWith("http://") || it.startsWith("https://"))
      }
    )

    binding.serverUrl.doOnTextChanged { text, _, _, _ ->
      if (text.isNullOrEmpty()) return@doOnTextChanged
      setAuthModes(text.startsWith("http://") || text.startsWith("https://"))
      // Checked as it is typed, so what is wrong with an address is said while it is being
      // written. While editing this also stores it, once there is something worth storing.
      if (!isClone) applySettings()
    }

    // Offered only where a key is what authenticates: password authentication needs none.
    binding.authKeyButton.isVisible = newAuthMode == AuthMode.SshKey
    binding.authKeyButton.setOnClickListener { chooseAuthenticationKey() }

    binding.clearHostKeyButton.isVisible = gitSettings.hasSavedHostKey()
    binding.clearHostKeyButton.setOnClickListener {
      gitSettings.clearSavedHostKey()
      Snackbar.make(
          binding.root,
          getString(R.string.clear_saved_host_key_success),
          Snackbar.LENGTH_LONG,
        )
        .show()
      it.isVisible = false
    }
    binding.saveButton.setOnClickListener {
      if (applySettings()) {
        if (PasswordRepository.repository == null) PasswordRepository.initialize()
        cloneRepository()
      }
    }
  }

  override fun onPause() {
    // Catches the address if the screen is left while the field still has focus.
    if (!isClone) applySettings()
    super.onPause()
  }

  /**
   * Stores the address and connection mode if they hold together, and otherwise says what is wrong
   * under the address field. Returns whether anything was stored.
   *
   * The two are validated as a pair, not separately: which connection modes are allowed follows
   * from the URL's scheme, and an SSH URL without a username is only a problem once a mode that
   * needs one is picked.
   */
  private fun applySettings(): Boolean {
    val newUrl = binding.serverUrl.text.toString().trim()
    if (newUrl.isEmpty()) {
      binding.labelServerUrl.error = null
      return false
    }
    if (newUrl.startsWith("git://")) {
      binding.labelServerUrl.error = getString(R.string.git_scheme_disallowed_message)
      return false
    }
    val updateResult =
      gitSettings.updateConnectionSettingsIfValid(
        oldAuthMode = oldAuthMode,
        newAuthMode = newAuthMode,
        newUrl = newUrl,
      )
    binding.labelServerUrl.error =
      when (updateResult) {
        GitSettings.UpdateConnectionSettingsResult.FailedToParseUrl ->
          getString(R.string.git_server_config_save_error)
        is GitSettings.UpdateConnectionSettingsResult.MissingUsername ->
          when (updateResult.newProtocol) {
            Protocol.Https -> getString(R.string.git_server_config_save_missing_username_https)
            Protocol.Ssh -> getString(R.string.git_server_config_save_missing_username_ssh)
          }
        is GitSettings.UpdateConnectionSettingsResult.AuthModeMismatch ->
          getString(
            R.string.git_server_config_save_auth_mode_mismatch,
            updateResult.newProtocol,
            updateResult.validModes.joinToString(", "),
          )
        GitSettings.UpdateConnectionSettingsResult.Valid -> null
      }
    if (updateResult != GitSettings.UpdateConnectionSettingsResult.Valid) return false
    // Changing the mode drops the credentials stored for the old one, so the mode now on record
    // becomes the one to compare against: storing again must not drop anything a second time.
    oldAuthMode = newAuthMode
    return true
  }

  /** The three ways this app can hold an authentication key, as the git operations also offer. */
  private fun chooseAuthenticationKey() {
    MaterialAlertDialogBuilder(this)
      .setTitle(R.string.ssh_preferences_dialog_title)
      .setMessage(R.string.ssh_preferences_dialog_text)
      .setPositiveButton(R.string.ssh_preferences_dialog_pgp_key) { _, _ ->
        launchActivity(PgpAuthKeySelectionActivity::class.java)
      }
      .setNegativeButton(R.string.ssh_preferences_dialog_generate) { _, _ ->
        launchActivity(SshKeyGenActivity::class.java)
      }
      .setNeutralButton(R.string.button_label_import) { _, _ ->
        launchActivity(SshKeyImportActivity::class.java)
      }
      .show()
  }

  override fun onOptionsItemSelected(item: MenuItem): Boolean {
    return when (item.itemId) {
      android.R.id.home -> {
        onBackPressedDispatcher.onBackPressed()
        true
      }
      else -> super.onOptionsItemSelected(item)
    }
  }

  private fun setAuthModes(isHttps: Boolean) =
    with(binding) {
      if (isHttps) {
        authModeSshKey.isVisible = false
        authModePassword.isVisible = true
        if (authModeGroup.checkedButtonId != authModePassword.id) authModeGroup.clearChecked()
      } else {
        authModeSshKey.isVisible = true
        authModePassword.isVisible = true
        if (authModeGroup.checkedButtonId == View.NO_ID) authModeGroup.check(authModeSshKey.id)
      }
    }

  /** Clones the repository, the directory exists, deletes it */
  private fun cloneRepository() {
    val localDir =
      requireNotNull(PasswordRepository.getRepositoryDirectory()) {
        "Repository directory must be set before cloning"
      }
    val localDirFiles = localDir.listFiles() ?: emptyArray()
    // Warn if non-empty folder unless it's a just-initialized store that has just a .git folder
    if (
      localDir.exists() &&
        localDirFiles.isNotEmpty() &&
        !(localDirFiles.size == 1 && localDirFiles[0].name == ".git")
    ) {
      MaterialAlertDialogBuilder(this)
        .setTitle(R.string.dialog_delete_title)
        .setMessage(resources.getString(R.string.dialog_delete_msg, localDir.toString()))
        .setCancelable(false)
        .setPositiveButton(R.string.dialog_delete) { dialog, _ ->
          runCatching {
            lifecycleScope.launch {
              val snackbar =
                snackbar(
                  message = getString(R.string.delete_directory_progress_text),
                  length = Snackbar.LENGTH_INDEFINITE,
                )
              withContext(dispatcherProvider.io()) {
                localDir.deleteRecursively()
                localDir.mkdirs()
              }
              snackbar.dismiss()
              launchGitOperation(GitOp.CLONE)
                .fold(
                  success = {
                    setResult(RESULT_OK)
                    finish()
                  },
                  failure = { err -> promptOnErrorHandler(err) { finish() } },
                )
            }
          }
            .onErr { e ->
              e.printStackTrace()
              MaterialAlertDialogBuilder(this).setMessage(e.message).show()
            }
          dialog.cancel()
        }
        .setNegativeButton(R.string.dialog_do_not_delete) { dialog, _ -> dialog.cancel() }
        .show()
    } else {
      runCatching {
        // Silently delete & replace the lone .git folder if it exists
        if (localDir.exists() && localDirFiles.size == 1 && localDirFiles[0].name == ".git") {
          localDir.deleteRecursively()
        }
      }
        .onErr { e ->
          logcat(ERROR) { e.asLog() }
          MaterialAlertDialogBuilder(this).setMessage(e.message).show()
        }
      lifecycleScope.launch {
        launchGitOperation(GitOp.CLONE)
          .fold(
            success = {
              setResult(RESULT_OK)
              finish()
            },
            failure = { promptOnErrorHandler(it) },
          )
      }
    }
  }

  companion object {

    private val PORT_REGEX = ":[0-9]{1,5}/".toRegex()

    fun createCloneIntent(context: Context): Intent {
      return Intent(context, GitServerConfigActivity::class.java).apply {
        putExtra("cloning", true)
      }
    }
  }
}
