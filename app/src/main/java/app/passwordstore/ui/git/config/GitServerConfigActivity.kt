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
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
import androidx.lifecycle.lifecycleScope
import app.passwordstore.R
import app.passwordstore.data.repo.PasswordRepository
import app.passwordstore.databinding.ActivityGitCloneBinding
import app.passwordstore.ui.dialogs.AuthKeySourceBottomSheet
import app.passwordstore.ui.dialogs.Notice
import app.passwordstore.ui.dialogs.ProgressOverlay
import app.passwordstore.ui.git.base.BaseGitActivity
import app.passwordstore.ui.onboarding.activity.SetupStepActivity
import app.passwordstore.ui.onboarding.activity.show
import app.passwordstore.ui.sshkeygen.PgpAuthKeySelectionActivity
import app.passwordstore.ui.sshkeygen.ShowSshKeyFragment
import app.passwordstore.ui.sshkeygen.SshKeyGenActivity
import app.passwordstore.ui.sshkeygen.SshKeyImportActivity
import app.passwordstore.util.extensions.enableEdgeToEdgeView
import app.passwordstore.util.extensions.hideKeyboard
import app.passwordstore.util.extensions.viewBinding
import app.passwordstore.util.git.sshj.SshKey
import app.passwordstore.util.settings.AuthMode
import app.passwordstore.util.settings.GitSettings
import app.passwordstore.util.settings.Protocol
import com.github.michaelbull.result.fold
import com.github.michaelbull.result.onErr
import com.github.michaelbull.result.runCatching
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority.ERROR
import logcat.asLog
import logcat.logcat

/**
 * Activity that encompasses both the initial clone as well as editing the server config for future
 * changes.
 *
 * While cloning it is the last step of setting a store up, and dresses accordingly: the step's
 * heading, and its button in the bottom right corner. Opened from the settings there is a store
 * already, nothing to start, and every change stores itself as it is made.
 */
class GitServerConfigActivity : BaseGitActivity() {

  private val binding by viewBinding(ActivityGitCloneBinding::inflate)

  private lateinit var oldAuthMode: AuthMode
  private lateinit var newAuthMode: AuthMode
  private var isClone = false

  /** Whether a clone is under way, which is the one time the button that starts it is shut. */
  private var cloning = false

  private val authKeyAction =
    registerForActivityResult(StartActivityForResult()) { showAuthKeyState() }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdgeView(binding.root)
    isClone = intent?.extras?.getBoolean("cloning") ?: false
    setContentView(binding.root)
    // Read before the chrome is put up: what it draws asks whether the way in is ready, and that
    // question is about these.
    oldAuthMode = gitSettings.authMode
    newAuthMode = gitSettings.authMode
    setUpChrome()

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
        showAuthKeyState()
        if (isClone) reportUrlState() else applySettings()
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
      // written. Editing stores it as well, once there is something worth storing; cloning waits
      // for its button, so it only says what it thinks.
      if (isClone) reportUrlState() else applySettings()
    }

    binding.authKeyRow.setOnClickListener { showAuthKeySources() }
    supportFragmentManager.setFragmentResultListener(
      AuthKeySourceBottomSheet.AUTH_KEY_SOURCE_REQUEST_KEY,
      this,
    ) { _, result ->
      useAuthKeyFrom(result.getString(AuthKeySourceBottomSheet.SOURCE_KEY))
    }
    binding.authKeyShow.setOnClickListener {
      ShowSshKeyFragment().show(supportFragmentManager, "public_key")
    }
    showAuthKeyState()

    binding.clearHostKeyButton.isVisible = gitSettings.hasSavedHostKey()
    binding.clearHostKeyButton.setOnClickListener {
      gitSettings.clearSavedHostKey()
      Notice.show(
        this@GitServerConfigActivity,
        R.string.clear_saved_host_key_success,
      )
      it.isVisible = false
    }
  }

  private inline fun <reified T> intentFor() = Intent(this, T::class.java)

  /** The setup step's heading and corner button while cloning; the plain settings screen after. */
  private fun setUpChrome() {
    binding.header.root.isVisible = isClone
    binding.setupFooter.root.isVisible = isClone
    if (!isClone) {
      supportActionBar?.setDisplayHomeAsUpEnabled(true)
      return
    }
    supportActionBar?.hide()
    binding.header.show(
      icon = R.drawable.ic_cloud_sync_48dp,
      title = R.string.setup_remote_title,
      message = R.string.setup_remote_message,
      step = intent.getIntExtra(SetupStepActivity.EXTRA_STEP, 0),
      stepCount = intent.getIntExtra(SetupStepActivity.EXTRA_STEP_COUNT, 0),
    )
    binding.setupFooter.setupNext.setText(R.string.clone_button)
    binding.setupFooter.setupBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
    binding.setupFooter.setupNext.setOnClickListener {
      if (applySettings()) {
        if (PasswordRepository.repository == null) PasswordRepository.initialize()
        // One clone at a time: it takes as long as the server takes, and a second one started
        // over the first would be cloning into a directory the first is still filling.
        cloning = true
        binding.setupFooter.setupNext.isEnabled = false
        cloneRepository()
      }
    }
    reportUrlState()
  }

  override fun onPause() {
    // Catches the address if the screen is left while the field still has focus.
    if (!isClone) applySettings()
    super.onPause()
  }

  /** What is wrong with [url] as a repository address, or null when nothing is. */
  private fun problemWith(url: String): String? =
    when {
      url.isEmpty() -> null
      url.startsWith("git://") -> getString(R.string.git_scheme_disallowed_message)
      else -> describe(gitSettings.validateConnectionSettings(newAuthMode, url))
    }

  private fun describe(result: GitSettings.UpdateConnectionSettingsResult): String? =
    when (result) {
      GitSettings.UpdateConnectionSettingsResult.FailedToParseUrl ->
        getString(R.string.git_server_config_save_error)
      is GitSettings.UpdateConnectionSettingsResult.MissingUsername ->
        when (result.newProtocol) {
          Protocol.Https -> getString(R.string.git_server_config_save_missing_username_https)
          Protocol.Ssh -> getString(R.string.git_server_config_save_missing_username_ssh)
        }
      is GitSettings.UpdateConnectionSettingsResult.AuthModeMismatch ->
        getString(
          R.string.git_server_config_save_auth_mode_mismatch,
          result.newProtocol,
          result.validModes.joinToString(", "),
        )
      GitSettings.UpdateConnectionSettingsResult.Valid -> null
    }

  private fun reportProblem(problem: String?) {
    binding.labelServerUrl.error = problem
  }

  /**
   * Says what is wrong with the address as it stands, and keeps the way onwards shut until nothing
   * is. Cloning with an address the app cannot use only fails later and less clearly.
   */
  private fun reportUrlState() {
    val url = binding.serverUrl.text.toString().trim()
    val problem = problemWith(url)
    reportProblem(problem)
    binding.setupFooter.setupNext.isEnabled =
      !cloning && url.isNotEmpty() && problem == null && authKeyReady()
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
    val problem = problemWith(newUrl)
    if (newUrl.isEmpty() || problem != null) {
      reportProblem(problem)
      return false
    }
    val updateResult =
      gitSettings.updateConnectionSettingsIfValid(
        oldAuthMode = oldAuthMode,
        newAuthMode = newAuthMode,
        newUrl = newUrl,
      )
    reportProblem(describe(updateResult))
    if (updateResult != GitSettings.UpdateConnectionSettingsResult.Valid) return false
    // Changing the mode drops the credentials stored for the old one, so the mode now on record
    // becomes the one to compare against: storing again must not drop anything a second time.
    oldAuthMode = newAuthMode
    return true
  }

  /**
   * Names the key public-key authentication would use, where the mode that needs one is chosen —
   * which is the question a prompt at clone time was answering far too late.
   */
  private fun showAuthKeyState() {
    binding.authKeySection.isVisible = newAuthMode == AuthMode.SshKey
    binding.authKeyStatus.text = describeAuthKey()
    // Only some kinds of key can show their public half; the others were imported as a private
    // key alone, and the server was told about them elsewhere.
    binding.authKeyShow.isVisible = SshKey.canShowSshPublicKey
    if (isClone) reportUrlState()
  }

  /** The key in use, said as what it is rather than as whether one exists. */
  private fun describeAuthKey(): String =
    when (SshKey.type) {
      null -> getString(R.string.setup_auth_key_none)
      SshKey.Type.Imported -> getString(R.string.setup_auth_key_imported)
      SshKey.Type.KeystoreNative,
      SshKey.Type.KeystoreWrappedEd25519 -> getString(R.string.setup_auth_key_generated)
      SshKey.Type.ImportedPGP ->
        SshKey.pgpLongKeyId
          .takeIf { it != 0L }
          ?.let { getString(R.string.setup_auth_key_pgp, "%016x".format(it)) }
          ?: getString(R.string.setup_auth_key_pgp_unnamed)
    }

  /**
   * Offers the ways a key can come from, in the sheet the key manager uses for the same question
   * about PGP keys. Picking a PGP key opens the list of them, which is where that choice is
   * actually made.
   *
   * The keyboard goes first: this row sits under a text field, and a sheet rising behind an open
   * keyboard would be half covered by it.
   */
  private fun showAuthKeySources() {
    hideKeyboard()
    AuthKeySourceBottomSheet().show(supportFragmentManager, "auth_key_source")
  }

  private fun useAuthKeyFrom(source: String?) {
    when (source) {
      AuthKeySourceBottomSheet.SOURCE_GENERATE ->
        authKeyAction.launch(intentFor<SshKeyGenActivity>())
      AuthKeySourceBottomSheet.SOURCE_IMPORT ->
        authKeyAction.launch(intentFor<SshKeyImportActivity>())
      AuthKeySourceBottomSheet.SOURCE_PGP ->
        authKeyAction.launch(intentFor<PgpAuthKeySelectionActivity>())
    }
  }

  /**
   * Whether the way in that has been chosen is one this app could actually use.
   *
   * A key is something to be set up here and now, so cloning waits until there is one. A password
   * is not: it is asked for by the clone itself, and there is nowhere on this screen to put one, so
   * choosing that mode is as configured as it gets. Choosing nothing at all is only an answer over
   * HTTPS, where it means a public repository nobody has to be let into.
   */
  private fun authKeyReady(): Boolean = newAuthMode != AuthMode.SshKey || SshKey.exists

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
              val progress =
                ProgressOverlay.show(
                  this@GitServerConfigActivity,
                  R.string.delete_directory_progress_text,
                )
              withContext(dispatcherProvider.io()) {
                localDir.deleteRecursively()
                localDir.mkdirs()
              }
              progress.dismiss()
              launchGitOperation(GitOp.CLONE)
                .fold(
                  success = {
                    setResult(RESULT_OK)
                    finish()
                  },
                  failure = { err ->
                    cloning = false
                    reportUrlState()
                    promptOnErrorHandler(err) { finish() }
                  },
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
            failure = {
              cloning = false
              reportUrlState()
              promptOnErrorHandler(it)
            },
          )
      }
    }
  }

  companion object {

    fun createCloneIntent(context: Context, step: Int = 0, stepCount: Int = 0): Intent {
      return Intent(context, GitServerConfigActivity::class.java).apply {
        putExtra("cloning", true)
        putExtra(SetupStepActivity.EXTRA_STEP, step)
        putExtra(SetupStepActivity.EXTRA_STEP_COUNT, stepCount)
      }
    }
  }
}
