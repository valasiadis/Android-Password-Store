/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.pgp

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doOnTextChanged
import app.passwordstore.R
import app.passwordstore.crypto.KeyUtils.tryGetKeyId
import app.passwordstore.crypto.KeyUtils.tryGetSecretSubkeyIdsUsagesIsStripped
import app.passwordstore.crypto.PGPIdentifier
import app.passwordstore.crypto.PGPIdentifier.KeyId
import app.passwordstore.crypto.PGPKeyManager
import app.passwordstore.crypto.displayName
import app.passwordstore.data.crypto.CryptoRepository
import app.passwordstore.databinding.ListChoiceRowBinding
import app.passwordstore.databinding.PgpKeyChangePassphraseActivityBinding
import app.passwordstore.ui.dialogs.AppDialogBuilder
import app.passwordstore.ui.dialogs.ListRows
import app.passwordstore.ui.dialogs.WarningDialog
import app.passwordstore.util.extensions.enableEdgeToEdgeView
import app.passwordstore.util.extensions.getString
import app.passwordstore.util.extensions.viewBinding
import app.passwordstore.util.extensions.wipe
import com.github.michaelbull.result.get
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import logcat.LogPriority.ERROR
import logcat.asLog
import logcat.logcat

@AndroidEntryPoint
class PGPKeyChangePassphraseActivity : AppCompatActivity() {

  private val binding by viewBinding(PgpKeyChangePassphraseActivityBinding::inflate)
  @Inject lateinit var keyManager: PGPKeyManager
  @Inject lateinit var cryptoRepository: CryptoRepository

  private lateinit var identifier: PGPIdentifier
  private var subkeyIdsUsagesIsStripped: List<Triple<KeyId, String, Boolean>>? = null

  /**
   * Which part of the key the passphrase is changed for: 0 for every part that has the current
   * passphrase, otherwise the subkey at that place in [subkeyIdsUsagesIsStripped], one past it.
   */
  private var selectedKeyChoice = 0

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    supportActionBar?.setDisplayHomeAsUpEnabled(true)
    title = getString(R.string.pgp_change_passphrase_title)

    identifier =
      requireNotNull(
        PGPIdentifier.fromString(intent.getStringExtra(EXTRA_SELECTED_IDENTIFIER) ?: "")
      ) {
        "invalid PGP identifier"
      }

    val pgpKey =
      requireNotNull(keyManager.getKeyById(identifier).get()) {
        "invalid PGP identifier, no key found with this ID : ${intent.getStringExtra(EXTRA_SELECTED_IDENTIFIER)}"
      }

    subkeyIdsUsagesIsStripped = tryGetSecretSubkeyIdsUsagesIsStripped(pgpKey)

    with(binding) {
      enableEdgeToEdgeView(root)
      setContentView(root)
      userid.factTitle.setText(R.string.pgp_key_info_user_id_label)
      userid.factValue.text = cryptoRepository.getUserIdFromKeyId(identifier)
      ListRows.header(keyChoicesHeader, R.string.pgp_key_info_section_subkeys)
      ListRows.header(passphraseHeader, R.string.ssh_keygen_passphrase)
      showKeyChoices()

      oldPassphrase.doOnTextChanged { _, _, _, _ -> oldPassphraseInputLayout.error = null }
      passphrase.doOnTextChanged { _, _, _, _ ->
        passphraseInputLayout.error = null
        repeatPassphraseInputLayout.error = null
      }
      repeatPassphrase.doOnTextChanged { _, _, _, _ ->
        passphraseInputLayout.error = null
        repeatPassphraseInputLayout.error = null
      }
      if (cryptoRepository.isPasswordProtected(listOf(identifier), anySubkey = true))
        oldPassphraseInputLayout.visibility = View.VISIBLE
    }
  }

  /**
   * The parts of the key as one choice, as the settings draw a choice: all of them that share the
   * passphrase, then each subkey by its ID and what it is for. A subkey whose secret is not here —
   * on a card, or stripped — has no passphrase to change, and is shown but cannot be chosen.
   */
  private fun showKeyChoices() {
    val rows = mutableListOf<ListChoiceRowBinding>()
    fun addChoice(title: String, summary: String?, enabled: Boolean) {
      val index = rows.size
      val row = ListChoiceRowBinding.inflate(layoutInflater, binding.keyChoices, true)
      ListRows.choice(row, title, summary) {
        selectedKeyChoice = index
        rows.forEachIndexed { i, other -> ListRows.select(other, i == index) }
      }
      row.root.isEnabled = enabled
      if (!enabled) row.root.alpha = DISABLED_ALPHA
      rows += row
    }
    addChoice(getString(R.string.pgp_change_passphrase_matching_subkeys), null, enabled = true)
    subkeyIdsUsagesIsStripped?.forEach { (keyId, usages, isStripped) ->
      addChoice(keyId.displayName, usages, enabled = !isStripped)
    }
    rows.forEachIndexed { i, row -> ListRows.select(row, i == selectedKeyChoice) }
  }

  override fun onCreateOptionsMenu(menu: Menu): Boolean {
    menuInflater.inflate(R.menu.pgp_key_manager_new_key, menu)
    return true
  }

  override fun onOptionsItemSelected(item: MenuItem): Boolean {
    when (item.itemId) {
      android.R.id.home -> {
        setResult(RESULT_CANCELED)
        onBackPressedDispatcher.onBackPressed()
      }
      R.id.save_key -> {
        val selectedIndex = selectedKeyChoice

        val subkeyIdentifier =
          if (selectedIndex > 0) {
            subkeyIdsUsagesIsStripped?.get(selectedIndex - 1)?.first
          } else {
            null
          }

        val oldPassphrase =
          binding.oldPassphrase.text?.let { CharArray(it.length) { i -> it[i] } } ?: charArrayOf()
        val oldPassphraseIsCorrect =
          if (
            cryptoRepository.isPasswordCorrect(
              identifier,
              subkeyIdentifier,
              if (oldPassphrase.isEmpty()) null else oldPassphrase,
              anySubkey = subkeyIdentifier?.let { false } ?: true,
            )
          )
            true
          else {
            binding.oldPassphraseInputLayout.error = getString(R.string.pgp_wrong_passphrase_input)
            false
          }

        val passphrase =
          binding.passphrase.text?.let { CharArray(it.length) { i -> it[i] } } ?: charArrayOf()
        val repeatedPassphrase =
          binding.repeatPassphrase.text?.let { CharArray(it.length) { i -> it[i] } }
            ?: charArrayOf()
        val passphrasesMatch =
          if (passphrase contentEquals repeatedPassphrase) true
          else {
            binding.passphraseInputLayout.error = "≠"
            binding.repeatPassphraseInputLayout.error =
              getString(R.string.pgp_passphrase_input_differ_error)
            false
          }

        repeatedPassphrase.wipe()

        if (oldPassphraseIsCorrect && passphrasesMatch) {
          if (passphrase.size >= 8) {
            changePassphrase(
              identifier,
              subkeyIdentifier,
              if (oldPassphrase.isEmpty()) null else oldPassphrase,
              passphrase,
            )
          } else {
            insecurePassphraseWarning(
              identifier,
              subkeyIdentifier,
              if (oldPassphrase.isEmpty()) null else oldPassphrase,
              if (passphrase.isEmpty()) null else passphrase,
            )
          }
        } else {
          oldPassphrase.wipe()
          passphrase.wipe()
        }
      }
      else -> return super.onOptionsItemSelected(item)
    }
    return true
  }

  private fun insecurePassphraseWarning(
    identifier: PGPIdentifier,
    subkeyIdentifier: KeyId?,
    oldPassphrase: CharArray?,
    passphrase: CharArray?,
  ) {
    val (title, message) =
      passphrase?.let {
        Pair(
          getString(R.string.pgp_key_short_passphrase_warning),
          getString(R.string.pgp_key_short_passphrase_warning_message),
        )
      }
        ?: Pair(
          getString(R.string.pgp_key_empty_passphrase_warning),
          getString(R.string.pgp_key_empty_passphrase_warning_message),
        )
    WarningDialog.show(
      context = this,
      title = title,
      message = message,
      proceedLabel = getString(R.string.pgp_key_insecure_passphrase_warning_confirm),
      onDismiss = {
        oldPassphrase?.wipe()
        passphrase?.wipe()
      },
    ) {
      changePassphrase(identifier, subkeyIdentifier, oldPassphrase, passphrase)
    }
  }

  private fun changePassphrase(
    identifier: PGPIdentifier,
    subkeyIdentifier: KeyId?,
    oldPassphrase: CharArray?,
    passphrase: CharArray?,
  ) {
    val (key, error) =
      keyManager.changeKeyPassphrase(identifier, subkeyIdentifier, oldPassphrase, passphrase)

    if (key != null) {
      AppDialogBuilder(this)
        .setTitle(getString(R.string.pgp_key_change_passphrase_succeeded))
        .setMessage(
          if (subkeyIdentifier == null)
            getString(
              R.string.pgp_key_change_passphrase_succeeded_message,
              tryGetKeyId(key)?.displayName,
            )
          else
            getString(
              R.string.pgp_subkey_change_passphrase_succeeded_message,
              subkeyIdentifier.displayName,
            )
        )
        .setPositiveButton(android.R.string.ok) { _, _ ->
          setResult(RESULT_OK)
          finish()
        }
        .setOnDismissListener {
          oldPassphrase?.wipe()
          passphrase?.wipe()
        }
        .setCancelable(false)
        .show()
    } else {
      logcat(ERROR) { error?.asLog() ?: "unknown error" }
      AppDialogBuilder(this)
        .setTitle(getString(R.string.pgp_key_change_passphrase_error))
        .setMessage(error?.toString() ?: "unknown error")
        .setPositiveButton(android.R.string.ok) { _, _ ->
          setResult(RESULT_CANCELED)
          finish()
        }
        .setOnDismissListener {
          oldPassphrase?.wipe()
          passphrase?.wipe()
        }
        .setCancelable(false)
        .show()
    }
  }

  companion object {

    const val EXTRA_SELECTED_IDENTIFIER = "SELECTED_IDENTIFIER"
  }
}
