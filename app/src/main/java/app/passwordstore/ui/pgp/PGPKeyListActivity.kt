/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.pgp

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.widget.CheckBox
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.setFragmentResultListener
import androidx.lifecycle.lifecycleScope
import app.passwordstore.R
import app.passwordstore.crypto.KeyUtils
import app.passwordstore.crypto.PGPIdentifier
import app.passwordstore.crypto.PGPIdentifier.KeyId
import app.passwordstore.crypto.PGPKeyManager
import app.passwordstore.data.crypto.CryptoRepository
import app.passwordstore.ui.APSAppBar
import app.passwordstore.ui.compose.theme.APSTheme
import app.passwordstore.ui.compose.theme.SpacingLarge
import app.passwordstore.ui.dialogs.AddPgpKeyBottomSheet
import app.passwordstore.ui.dialogs.ErrorDialog
import app.passwordstore.ui.dialogs.Notice
import app.passwordstore.ui.dialogs.PasswordDialog
import app.passwordstore.ui.pgp.PGPKeyImportActivity.Companion.EXTRA_IMPORT_FROM_CARD
import app.passwordstore.util.extensions.wipe
import app.passwordstore.util.git.sshj.SshKey
import app.passwordstore.util.viewmodel.PGPKeyListViewModel
import com.github.michaelbull.result.get
import com.github.michaelbull.result.getOrThrow
import com.github.michaelbull.result.onErr
import com.github.michaelbull.result.onOk
import com.github.michaelbull.result.runCatching
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.SecureRandom
import javax.inject.Inject
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.launch
import logcat.LogPriority.ERROR
import logcat.asLog
import logcat.logcat

@AndroidEntryPoint
class PGPKeyListActivity : AppCompatActivity() {

  @Inject lateinit var cryptoRepository: CryptoRepository
  @Inject lateinit var pgpKeyManager: PGPKeyManager

  /* Counter for the user's passphrase attempts */
  private var retries = 0

  private val viewModel: PGPKeyListViewModel by viewModels()

  private val keyAction =
    registerForActivityResult(StartActivityForResult()) {
      if (it.resultCode == RESULT_OK) {
        if (isAddingKeys) keysAdded = true
        it.data?.let { data ->
          /* If we replace a key that is currently registered for SSH auth, drop the SSH
           * registration; the cached key is a snapshot of the previous PGP auth subkey
           * and may no longer match the replacement (different subkey, or no auth
           * capability at all). Forcing a re-setup avoids silent SSH auth breakage. */
          val importedIds = data.getLongArrayExtra("PGP_KEY_IDS") ?: longArrayOf()
          if (SshKey.pgpLongKeyId in importedIds) SshKey.delete()
        }
        viewModel.updateKeySet()
      }
    }

  private var keyNumericId: String? = null
  private var keyContentsWithArmor: ByteArray? = null

  var keysAdded = false
  var isAddingKeys = false

  private val keyExportAction =
    registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) {
      uri ->
      if (uri != null) {
        writeBytesToUri(uri, keyContentsWithArmor)
      }
    }

  override fun onConfigurationChanged(newConfig: Configuration) {
    super.onConfigurationChanged(newConfig)
    @SuppressLint("ChromeOsOnConfigurationChanged") finish()
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    val singleSelection = intent.extras?.getBoolean(EXTRA_KEY_FOR_SSH) ?: false
    val isSelectingKeys = intent.extras?.getBoolean(EXTRA_KEY_SELECTION) ?: false
    // Observable, so the confirm action can follow whether anything is selected.
    val selectedKeyIds = mutableStateSetOf<String>()

    // Keys already in use by whatever opened this screen, so it starts out showing them.
    //
    // Held as this screen's own spelling rather than the caller's: a .gpg-id may name a key by
    // fingerprint, in capitals, or with an 0x in front, and a row that hands back the key it was
    // drawn from can only ever remove the one spelling it knows. Tapping a key off the list left
    // the caller's wording of it sitting in the set, so the key came back on confirmation.
    val preselectedKeys =
      intent
        .getStringExtra(EXTRA_PRESELECTED_KEYS)
        ?.split("\n")
        ?.filter { it.isNotBlank() }
        .orEmpty()
        .map { line -> PGPIdentifier.fromString(line)?.toString() ?: line }
        .distinct()
    selectedKeyIds.addAll(preselectedKeys)
    // What the caller arrived with, to tell an actual change from merely looking.
    val initialKeyIds = preselectedKeys.toSet()
    // A folder with no key of its own follows the folder above it, which this screen offers as a
    // choice of its own — and starts on, when that is what the folder is doing now.
    val offerInherit = intent.extras?.getBoolean(EXTRA_OFFER_INHERIT) ?: false
    val inheritInitially = offerInherit && preselectedKeys.isEmpty()
    var inheritSelected by mutableStateOf(inheritInitially)
    // initial selection of a PGP key for authentication
    if (singleSelection && SshKey.pgpLongKeyId != 0L && selectedKeyIds.isEmpty())
      selectedKeyIds.add(KeyId(SshKey.pgpLongKeyId).toString())

    supportFragmentManager.setFragmentResultListener(PGP_KEY_ADD_REQUEST_KEY, this) { _, bundle ->
      when (bundle.getString(ACTION_KEY)) {
        ACTION_IMPORT_FILE -> {
          keyAction.launch(Intent(this, PGPKeyImportActivity::class.java))
          isAddingKeys = true
        }
        ACTION_IMPORT_CARD -> {
          keyAction.launch(
            Intent(this, PGPKeyImportActivity::class.java).putExtra(EXTRA_IMPORT_FROM_CARD, true)
          )
          isAddingKeys = true
        }
        ACTION_NEW_PGP_KEY -> {
          keyAction.launch(Intent(this, PGPKeyCreationActivity::class.java))
          isAddingKeys = true
        }
      }
    }

    setContent {
      APSTheme {
        Scaffold(
          topBar = {
            APSAppBar(
              title =
                if (isSelectingKeys) {
                  if (singleSelection) stringResource(R.string.activity_label_pgp_key_single_select)
                  else stringResource(R.string.activity_label_pgp_key_select)
                } else stringResource(R.string.activity_label_pgp_key_manager),
              navigationIcon = painterResource(R.drawable.ic_arrow_back_24dp),
              onNavigationIconClick = {
                // Up/back dismisses without applying anything. Committing a selection is the
                // job of the explicit confirm action, so leaving the screen can never change
                // the user's keys behind their back.
                val result = Intent()
                if (!isSelectingKeys && isAddingKeys && keysAdded) {
                  setResult(RESULT_OK, result)
                } else {
                  setResult(RESULT_CANCELED, result)
                }
                finish()
              },
              backgroundColor = MaterialTheme.colorScheme.surface,
            )
          },
          // Outside selection mode the only action is adding a key, which keeps the ordinary
          // trailing position. While selecting, that position belongs to the confirm action and
          // adding moves to the leading edge, as in the folder picker.
          floatingActionButton = {
            if (!isSelectingKeys) {
              FloatingActionButton(onClick = ::showAddKeySheet) {
                Icon(
                  painter = painterResource(R.drawable.ic_add_48dp),
                  stringResource(R.string.pref_import_pgp_key_title),
                )
              }
            }
          },
        ) { paddingValues ->
          Box(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
            // Compared as key IDs rather than as text: a .gpg-id may name a key it holds by
            // fingerprint, in capitals, or with an 0x in front, and none of those spellings match
            // what a KeyId prints as — which showed a key the app has as one it has not.
            val heldKeyIds = viewModel.keys.mapNotNull { it.first }.toSet()
            // Only meaningful once the key set has arrived; before that nothing looks held.
            val missingKeys =
              if (viewModel.keys.isEmpty()) persistentListOf()
              else
                preselectedKeys
                  .mapNotNull { PGPIdentifier.fromString(it) as? KeyId }
                  .filterNot { it in heldKeyIds }
                  .distinct()
                  .toImmutableList()
            KeyList(
              identifiers = viewModel.keys, // Pair<KeyId,UserId>
              isSecretKey = ::isSecretKey,
              isStubKey = ::isStubKey,
              onKeyInfoClick = ::showKeyInfo,
              onChangePassphraseClick = ::changeKeyPassphrase,
              onDeleteItemClick = ::deleteKey,
              onExportItemClick = ::exportKey,
              onExportPublicClick = ::exportPublicKey,
              onKeySelected =
                if (isSelectingKeys) {
                  { identifier, isSelected ->
                    // A row hands back the key id it was drawn from, which is all this needs —
                    // and is the only thing to be had for a key the app no longer holds. Anything
                    // else is looked up, and a lookup that fails is ignored rather than thrown:
                    // asking about a deleted key used to take the screen down with it.
                    val keyId =
                      identifier as? KeyId
                        ?: pgpKeyManager.getKeyById(identifier).get()?.let(KeyUtils::tryGetKeyId)
                    if (keyId != null) {
                      if (singleSelection) selectedKeyIds.clear()
                      if (isSelected) selectedKeyIds.add(keyId.toString())
                      else selectedKeyIds.remove(keyId.toString())
                    }
                  }
                } else null,
              singleSelection = singleSelection,
              initiallySelectedKeys =
                preselectedKeys
                  .mapNotNull { PGPIdentifier.fromString(it) as? KeyId }
                  .toImmutableList(),
              missingKeys = missingKeys,
              offerInherit = offerInherit,
              inheritInitially = inheritInitially,
              onInheritChanged = { inheritSelected = it },
              // Selecting an SSH authentication key (single-selection mode): grey out keys that
              // can't authenticate — public-only keys, and stubs without an associated smartcard.
              isKeyEnabled =
                if (singleSelection) cryptoRepository::canUseForSshAuth
                else {
                  { true }
                },
            )
            if (isSelectingKeys) {
              FloatingActionButton(
                onClick = ::showAddKeySheet,
                modifier = Modifier.align(Alignment.BottomStart).padding(SpacingLarge),
              ) {
                Icon(
                  painter = painterResource(R.drawable.ic_add_48dp),
                  stringResource(R.string.pref_import_pgp_key_title),
                )
              }
              // There is something to confirm only once the selection differs from what the
              // caller came in with, and only while it holds something. Until then the action
              // stays in place but unavailable, so the way out of the screen does not move about
              // as keys are tapped. Leaving without applying anything is what the up arrow and
              // the back gesture are for.
              val selection = selectedKeyIds.toSet()
              val hasChanges =
                if (inheritSelected) !inheritInitially
                else selection.isNotEmpty() && selection != initialKeyIds
              FloatingActionButton(
                onClick = {
                  if (hasChanges) {
                    confirmSelection(if (inheritSelected) emptySet() else selectedKeyIds)
                  }
                },
                containerColor =
                  if (hasChanges) FloatingActionButtonDefaults.containerColor
                  else MaterialTheme.colorScheme.surfaceVariant,
                contentColor =
                  if (hasChanges) contentColorFor(FloatingActionButtonDefaults.containerColor)
                  else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = DISABLED_ALPHA),
                modifier = Modifier.align(Alignment.BottomEnd).padding(SpacingLarge),
              ) {
                Icon(
                  painter = painterResource(R.drawable.ic_done_24dp),
                  contentDescription =
                    if (singleSelection) stringResource(R.string.gpg_key_single_select)
                    else stringResource(R.string.gpg_key_select),
                )
              }
            }
          }
        }
      }
    }
  }

  private fun showAddKeySheet() {
    AddPgpKeyBottomSheet().show(supportFragmentManager, "ADD_PGP_KEY_BOTTOM_SHEET")
  }

  /**
   * Apply the current selection and finish. Deliberately separate from up/back navigation: a
   * selection screen should only commit when the user says so, and only ever with a selection — the
   * confirm action is unavailable until there is one.
   */
  /**
   * Applies the current selection and finishes — an empty one meaning "no keys of its own", which
   * is what the folder above deciding looks like on disk.
   */
  private fun confirmSelection(selectedKeyIds: Set<String>) {
    val result = Intent()
    result.putExtra(EXTRA_SELECTED_KEY, selectedKeyIds.joinToString(separator = "\n"))
    intent.getStringExtra("SUB_PATH")?.let { result.putExtra("SUB_PATH", it) }
    setResult(RESULT_OK, result)
    finish()
  }

  private fun isSecretKey(identifier: PGPIdentifier): Boolean =
    cryptoRepository.isSecretKey(identifier)

  /** A key whose private material lives on hardware (a smartcard) or was otherwise stripped. */
  private fun isStubKey(identifier: PGPIdentifier): Boolean =
    cryptoRepository.isSmartcardBacked(identifier) || cryptoRepository.hasOnlyStubDecKey(identifier)

  private fun showKeyInfo(identifier: PGPIdentifier) {
    val fingerprint =
      pgpKeyManager.getKeyById(identifier).get()?.let { key ->
        KeyUtils.tryGetFingerprints(key).firstOrNull()?.let(::formatFingerprint)
      }
    val type =
      when {
        // Both a registered smartcard and a bare stub mean the private key lives on hardware;
        // mirror isStubKey() so the info label matches the hardware icon shown in the list.
        cryptoRepository.isSmartcardBacked(identifier) ||
          cryptoRepository.hasOnlyStubDecKey(identifier) ->
          getString(R.string.pgp_key_info_type_hardware)
        cryptoRepository.isSecretKey(identifier) -> getString(R.string.pgp_key_info_type_secret)
        else -> getString(R.string.pgp_key_info_type_public)
      }
    val message = buildString {
      cryptoRepository
        .getUserIdFromKeyId(identifier)
        ?.takeIf { it != "null" }
        ?.let {
          appendLine(getString(R.string.pgp_key_info_user_id, it))
        }
      cryptoRepository.getEmailFromKeyId(identifier)?.let {
        appendLine(getString(R.string.pgp_key_info_email, it))
      }
      cryptoRepository.getLongKeyIdFromKeyId(identifier)?.let {
        appendLine(getString(R.string.pgp_key_info_key_id, "0x$it"))
      }
      fingerprint?.let { appendLine(getString(R.string.pgp_key_info_fingerprint, it)) }
      append(getString(R.string.pgp_key_info_type, type))
    }
    MaterialAlertDialogBuilder(this)
      .setTitle(R.string.pgp_key_info_title)
      .setMessage(message)
      .setPositiveButton(R.string.dialog_ok, null)
      .show()
  }

  private fun formatFingerprint(fingerprint: ByteArray): String =
    fingerprint
      .joinToString(separator = "") { "%02X".format(it.toInt() and 0xFF) }
      .chunked(4)
      .joinToString(separator = " ")

  private fun changeKeyPassphrase(identifier: PGPIdentifier) {
    val intent = Intent(this, PGPKeyChangePassphraseActivity::class.java)
    intent.putExtra(PGPKeyChangePassphraseActivity.EXTRA_SELECTED_IDENTIFIER, identifier.toString())
    keyAction.launch(intent)
  }

  private fun deleteKey(identifier: PGPIdentifier) {
    val keyIdPassedIn =
      KeyUtils.tryGetKeyId(pgpKeyManager.getKeyById(identifier).getOrThrow())
        ?: throw NullPointerException()
    if (keyIdPassedIn.id == SshKey.pgpLongKeyId) SshKey.delete()
    viewModel.deleteKey(keyIdPassedIn)
  }

  private fun exportKey(identifier: PGPIdentifier) {
    retries = 0
    lifecycleScope.launch {
      if (cryptoRepository.isPasswordProtected(listOf(identifier), anySubkey = true)) {
        // export as symmetrically encrypted file after passphrase verification
        askPassphrase(identifier)
      } else if (isSecretKey(identifier)) {
        // a secret key without passphrase is symm. encrypted and exported without verification
        confirmBackupCode(identifier, generateBackupCode())
      } else {
        // write public key to file unencrypted
        writeBackupFile(identifier)
      }
    }
  }

  private fun exportPublicKey(identifier: PGPIdentifier) {
    lifecycleScope.launch { writeBackupFile(identifier) }
  }

  private fun askPassphrase(identifier: PGPIdentifier, isError: Boolean = false) {
    if (++retries > MAX_RETRIES) return

    val shortUserId = cryptoRepository.getEmailFromKeyId(identifier) ?: return
    val label = "${resources.getString(R.string.pgp_id_label)} ${shortUserId}"
    val dialog = PasswordDialog.newInstance(label, onCancelFinish = false)
    if (isError) dialog.setError()
    dialog.show(supportFragmentManager, "PASSWORD_DIALOG")
    dialog.setFragmentResultListener(PasswordDialog.PASSWORD_RESULT_KEY) { key, bundle ->
      if (key == PasswordDialog.PASSWORD_RESULT_KEY) {
        val passphrase = bundle.getCharArray(PasswordDialog.PASSWORD_PHRASE_KEY) ?: charArrayOf()
        lifecycleScope.launch {
          if (cryptoRepository.isPasswordCorrect(identifier, null, passphrase, anySubkey = true)) {
            confirmBackupCode(identifier, generateBackupCode())
          } else {
            askPassphrase(identifier, isError = true)
          }
          passphrase.wipe()
        }
      }
    }
  }

  private fun generateBackupCode(numberOfGroups: Int = 9, digitsPerGroup: Int = 4) =
    List(numberOfGroups) { SecureRandom().nextInt(Math.pow(10.0, 1.0 * digitsPerGroup).toInt()) }
      .map { "$it".padStart(digitsPerGroup, '0') }
      .joinToString(separator = "-")

  private fun confirmBackupCode(identifier: PGPIdentifier, code: String) {
    val dialogView = layoutInflater.inflate(R.layout.dialog_with_ckeckbox, null)
    val checkBox = dialogView.findViewById<CheckBox>(R.id.checkbox)

    val dialog =
      MaterialAlertDialogBuilder(this)
        .setTitle(R.string.pgp_key_backupcode_title)
        .setView(dialogView)
        .setMessage(code)
        .setPositiveButton(R.string.dialog_ok) { _, _ ->
          lifecycleScope.launch { writeBackupFile(identifier, code) }
        }
        .setNegativeButton(R.string.dialog_cancel, null)
        .setCancelable(false)
        .create()

    dialog.setOnShowListener {
      val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
      positiveButton.isEnabled = false // start disabled
      checkBox.setText(R.string.pgp_key_backupcode_confirmation)
      checkBox.setOnCheckedChangeListener { _, isChecked -> positiveButton.isEnabled = isChecked }
    }

    dialog.show()
  }

  private fun writeBackupFile(identifier: PGPIdentifier, code: String? = null) {
    val keyIdAndContent = run {
      val key = pgpKeyManager.getKeyById(identifier, withArmor = true).getOrThrow()
      val contents =
        if (code != null) { // encrypt secret keys symmetrically
          val keyContents = ByteArrayOutputStream()
          val result =
            cryptoRepository.encryptSym(
              code.toCharArray(),
              key.contents.inputStream(),
              keyContents,
              withArmor = true,
            )
          if (result.isOk) {
            val encrypted = result.getOrThrow().toByteArray()
            val firstNewline = encrypted.indexOf('\n'.code.toByte())
            val firstLine = encrypted.copyOfRange(0, firstNewline + 1)
            val remainingLines = encrypted.copyOfRange(firstNewline + 1, encrypted.size)
            // OpenKeychain backup format
            firstLine +
              "Passphrase-Format: numeric9x4\n".toByteArray(Charsets.UTF_8) +
              remainingLines
          } else null
        } else {
          KeyUtils.extractPublicKeyData(key)
        }
      Pair(KeyUtils.tryGetKeyId(key), contents)
    }

    keyNumericId = keyIdAndContent.first?.toString()
    keyContentsWithArmor = keyIdAndContent.second

    if (keyContentsWithArmor != null) {
      val fileName = "keyID-${keyNumericId}." + (code?.let { "sec" } ?: "pub") + ".pgp"
      keyExportAction.launch(fileName)
    } else {
      ErrorDialog.show(this@PGPKeyListActivity, R.string.pgp_key_export_failed)
    }
  }

  private fun writeBytesToUri(uri: Uri, source: ByteArray?) {
    runCatching {
      val outputStream = contentResolver.openOutputStream(uri) ?: throw IOException()
      source?.inputStream().use { src -> outputStream.use { dest -> src?.copyTo(dest) } }
    }
      .onOk {
        Notice.show(
          this@PGPKeyListActivity,
          R.string.pgp_key_export_succeeded,
        )
      }
      .onErr { e ->
        logcat(ERROR) { e.asLog() }
        ErrorDialog.show(this@PGPKeyListActivity, R.string.pgp_key_export_failed)
      }
  }

  companion object {
    const val MAX_RETRIES = 3

    const val EXTRA_SELECTED_KEY = "SELECTED_KEY"
    const val EXTRA_KEY_SELECTION = "KEY_SELECTION_MODE"
    const val EXTRA_KEY_FOR_SSH = "EXTRA_KEY_FOR_SSH"
    const val EXTRA_PRESELECTED_KEYS = "PRESELECTED_KEYS"

    /** Whether following the folder above is one of the choices, for the screens where it is. */
    const val EXTRA_OFFER_INHERIT = "OFFER_INHERIT"

    const val PGP_KEY_ADD_REQUEST_KEY = "add_pgp_key"
    const val ACTION_KEY = "action"
    const val ACTION_IMPORT_FILE = "from_file"
    const val ACTION_IMPORT_CARD = "from_card"
    const val ACTION_NEW_PGP_KEY = "generate_new"

    fun newIntent(
      context: Context,
      keySelection: Boolean = false,
      singleSelection: Boolean = false,
      preselectedKeyIds: String? = null,
      offerInherit: Boolean = false,
    ): Intent {
      val intent = Intent(context, PGPKeyListActivity::class.java)
      intent.putExtra(EXTRA_KEY_SELECTION, singleSelection || keySelection)
      intent.putExtra(EXTRA_KEY_FOR_SSH, singleSelection)
      intent.putExtra(EXTRA_OFFER_INHERIT, offerInherit)
      preselectedKeyIds?.let { intent.putExtra(EXTRA_PRESELECTED_KEYS, it) }
      return intent
    }
  }
}
