/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.crypto

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.core.content.edit
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import app.passwordstore.R
import app.passwordstore.crypto.PGPIdentifier
import app.passwordstore.crypto.errors.IncorrectPassphraseException
import app.passwordstore.crypto.errors.NoDecryptionKeyAvailableException
import app.passwordstore.data.passfile.PasswordEntry
import app.passwordstore.data.password.FieldItem
import app.passwordstore.data.repo.PasswordRepository
import app.passwordstore.databinding.DecryptLayoutBinding
import app.passwordstore.injection.prefs.CredentialUsernames
import app.passwordstore.injection.prefs.PasswordHistory
import app.passwordstore.ui.adapters.FieldItemAdapter
import app.passwordstore.ui.dialogs.WarningDialog
import app.passwordstore.ui.pgp.PGPKeyListActivity
import app.passwordstore.util.crypto.AESEncryption
import app.passwordstore.util.crypto.AESEncryption.KeyType
import app.passwordstore.util.crypto.OpenPgpCardPrompt
import app.passwordstore.util.crypto.OpenPgpNfcCard
import app.passwordstore.util.extensions.base64
import app.passwordstore.util.extensions.commitChange
import app.passwordstore.util.extensions.commitSavedChange
import app.passwordstore.util.extensions.enableEdgeToEdgeView
import app.passwordstore.util.extensions.getString
import app.passwordstore.util.extensions.snackbar
import app.passwordstore.util.extensions.toByteArray
import app.passwordstore.util.extensions.toCharArray
import app.passwordstore.util.extensions.viewBinding
import app.passwordstore.util.extensions.wipe
import app.passwordstore.util.settings.PreferenceKeys
import app.passwordstore.util.shortcuts.ShortcutHandler
import com.github.michaelbull.result.getError
import com.github.michaelbull.result.getOr
import com.github.michaelbull.result.getOrThrow
import com.github.michaelbull.result.onErr
import com.github.michaelbull.result.onOk
import com.github.michaelbull.result.runCatching
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Paths
import javax.inject.Inject
import kotlin.io.path.pathString
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@AndroidEntryPoint
class DecryptActivity : BasePGPActivity() {

  @Inject lateinit var passwordEntryFactory: PasswordEntry.Factory
  @Inject lateinit var shortcutHandler: ShortcutHandler
  @CredentialUsernames @Inject lateinit var credentialUsernames: SharedPreferences
  @PasswordHistory @Inject lateinit var passwordHistory: SharedPreferences

  private var itemsAdapter: FieldItemAdapter? = null
  private val binding by viewBinding(DecryptLayoutBinding::inflate)

  // temporarily AES-encrypted password entry
  private var encryptedEntryChars: CharArray? = null // AES encrypted password entry

  private fun CharArray.isBlank() = this.isEmpty() || this.all { it.isWhitespace() }

  private var isPasskey: Boolean = false

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // The entry may have been deleted since a launcher shortcut was created for it; bail out
    // gracefully (and prune the stale shortcut) instead of crashing when we try to read the file.
    if (!File(fullPath).exists()) {
      Toast.makeText(this, R.string.password_no_longer_exists, Toast.LENGTH_LONG).show()
      shortcutHandler.pruneDynamicShortcuts()
      finish()
      return
    }
    supportActionBar?.setDisplayHomeAsUpEnabled(true)
    title = name
    with(binding) {
      enableEdgeToEdgeView(root)
      setContentView(root)
      passwordCategory.text = relativeParentPath
      passwordFile.text = name
      passwordFile.setOnLongClickListener {
        copyTextToClipboard(name.toCharArray(), isSensitive = false)
        true
      }
      fab.setOnClickListener { copyPassword() }
      editFab.setOnClickListener { if (isPasskey) editPasskey() else editPassword() }
      shareFab.setOnClickListener { shareAsPlaintext() }
    }
    // An entry this process has just written arrives with the copy it kept, so opening it costs
    // nothing: no PGP key, no passphrase, no smartcard touch. The copy travels AES-encrypted
    // under a Keystore key that lives only as long as the process, so no plaintext is ever in an
    // intent — the same way an entry reaches the editing screen.
    // This screen is exported, so the copy is treated as a claim rather than a fact: it is only
    // shown if it decrypts, which nothing outside this process can arrange, and anything else
    // falls through to opening the file the ordinary way.
    // Copied out of the intent and taken off it: this screen wipes the copy it holds when it goes
    // away, and wiping the intent's own array left an empty one behind for the next launch — which
    // is what a relaunch after a configuration change then tried, and failed, to decrypt.
    val cachedEntry =
      intent.getCharArrayExtra(PasswordCreationActivity.EXTRA_ENTRY)?.copyOf()?.let { entry ->
        AESEncryption.decrypt(entry)?.let { decrypted -> entry to decrypted }
      }
    intent.removeExtra(PasswordCreationActivity.EXTRA_ENTRY)
    intent.getStringExtra(PasswordCreationActivity.RETURN_EXTRA_MESSAGE)?.let { message ->
      intent.removeExtra(PasswordCreationActivity.RETURN_EXTRA_MESSAGE)
      binding.root.post { snackbar(message = message) }
    }
    // An entry arrived here straight from being written, so what it changed still wants committing.
    lifecycleScope.launch { commitSavedChange(intent) }
    if (cachedEntry != null) {
      showCachedEntry(cachedEntry.first, cachedEntry.second)
      return
    }
    requireKeysExist {
      requireDecryptionKeysExist(relativeParentPath) { ids ->
        lifecycleScope.launch {
          val keys = withContext(dispatcherProvider.io()) { keysForEntry(ids) }
          getPersistentAndDecrypt(keys)
        }
      }
    }
  }

  override fun onDestroy() {
    OpenPgpNfcCard.disableReaderMode(this)
    encryptedEntryChars?.wipe()
    itemsAdapter?.clearItems()
    super.onDestroy()
  }

  /**
   * The keys to open this entry with, which is a question about the entry rather than about the
   * folder holding it: one saved under another key — moved off a smartcard, say — would otherwise
   * be sent to the key the folder names, and refused by a card it was never a recipient of.
   *
   * Answered with the folder's own identifiers wherever they name the same keys the entry does, so
   * that what is asked for, what is cached against it, and what decrypts stay one and the same
   * list. Only an entry encrypted outside its folder's .gpg-id is described by the message alone.
   */
  private fun keysForEntry(folderIds: List<PGPIdentifier>): List<PGPIdentifier> {
    val recipients = entryRecipients()
    if (recipients.isEmpty()) return preferringLocal(folderIds)
    // Every recipient this store holds a key for, named the way the folder names it where the
    // folder names it at all — the folder's wording is what cached passphrases are filed under, so
    // it is kept where possible. A recipient the folder does not mention is still one of the
    // entry's keys, though, and dropping it for that reason is what kept sending an entry with a
    // local key to the card the folder does mention.
    val folderByKey = folderIds.associateBy { repository.getLongKeyIdFromKeyId(it) }
    val entryKeys =
      recipients
        .mapNotNull { recipient ->
          folderByKey[repository.getLongKeyIdFromKeyId(recipient)]
            ?: recipient.takeIf { repository.hasKey(it) && repository.hasDecKey(it) }
        }
        .distinct()
    return preferringLocal(entryKeys.ifEmpty { folderIds })
  }

  /**
   * Puts [contents] in place of [entryFile], reporting whether it got there.
   *
   * Written elsewhere and moved onto the entry, so a failure part-way through leaves the entry as
   * it was rather than half of each. Elsewhere means outside the repository: a commit stages
   * everything under it, so a staging file left behind by a process that died at the wrong moment
   * would be committed and pushed along with the entry.
   */
  private suspend fun replaceEntry(entryFile: File, contents: ByteArray): Boolean =
    withContext(dispatcherProvider.io()) {
      val staged = File.createTempFile("entry", null, cacheDir)
      runCatching {
          staged.writeBytes(contents)
          staged.renameTo(entryFile) || staged.copyTo(entryFile, overwrite = true).let { true }
        }
        .getOr(false)
        .also { staged.delete() }
    }

  /** The keys this entry names as its recipients, empty when the message will not say. */
  private fun entryRecipients(): List<PGPIdentifier> =
    File(fullPath).inputStream().use { repository.recipientKeyIds(it) }

  /**
   * The keys among [keys] that are held locally, or all of them when none is.
   *
   * A local key is always to hand, while a card has to be found, presented and its PIN entered, so
   * an entry encrypted to both is opened by the local one and never asks for the card. Chosen here
   * rather than at decryption time so that the passphrase gathered, the passphrase cached and the
   * key that decrypts are all the same key.
   */
  private fun preferringLocal(keys: List<PGPIdentifier>): List<PGPIdentifier> =
    keys
      .filter { !repository.isSmartcardBacked(it) && !repository.hasOnlyStubDecKey(it) }
      .ifEmpty { keys }

  override suspend fun decryptWithPassphrase(
    passphrases: Map<String, CharArray?>,
    identifiers: List<PGPIdentifier>,
    onSuccess: suspend (String) -> Unit,
  ) {
    if (identifiers.any { repository.hasOnlyStubDecKey(it) || repository.isSmartcardBacked(it) }) {
      decryptWithSmartcard(passphrases, identifiers, onSuccess)
      return
    }
    val message = withContext(dispatcherProvider.io()) { File(fullPath).readBytes().inputStream() }
    val outputStream = ByteArrayOutputStream()
    val results = repository.decrypt(passphrases, identifiers, message, outputStream)
    val lastResult = results.last()
    if (lastResult.second.isOk) {
      val decryptedEntryBytes = lastResult.second.getOrThrow().toByteArray()
      lastResult.second.getOrThrow().wipe()
      val decryptedEntryChars = decryptedEntryBytes.toCharArray()
      decryptedEntryBytes.wipe()
      val entry = passwordEntryFactory.create(decryptedEntryChars)
      encryptedEntryChars = AESEncryption.encrypt(decryptedEntryChars)
      decryptedEntryChars.wipe()
      entry.clearExtraChars()
      createPasswordUI(entry)

      passwordHistory.edit { // create/update timestamp on the current password file
        putString(
          fullPath.base64(),
          System.currentTimeMillis().toString(),
        )
      }
      onSuccess(lastResult.first) // pass ID for which the entry was successfully decrypted
    } else {
      passphrases.values.forEach { it?.wipe() }
      if (
        results
          .filter { result ->
            if (result.second.getError() is IncorrectPassphraseException) {
              /* Remove wrong passphrases from temporary and persistent caches */
              persistentPassphrases.edit { remove(result.first) }
              cachedPassphrases[result.first]?.wipe()
              cachedPassphrases.remove(result.first)
              true
            } else false
          }
          .any()
      ) {
        /* Retry */
        decrypt(identifiers, isError = true)
      } else if (
        results.filter { it.second.getError() is NoDecryptionKeyAvailableException }.any()
      ) {
        snackbar(message = resources.getString(R.string.password_decryption_no_decryption_key))
      } else {
        snackbar(message = resources.getString(R.string.password_decryption_unknown_error))
      }
    }
    if (!settings.getBoolean(PreferenceKeys.CACHE_PASSPHRASE, false)) {
      cachedPassphrases.values.forEach { it.wipe() }
      cachedPassphrases.clear()
    }
  }

  private suspend fun decryptWithSmartcard(
    passphrases: Map<String, CharArray?>,
    identifiers: List<PGPIdentifier>,
    onSuccess: suspend (String) -> Unit,
  ) {
    val messageBytes = withContext(dispatcherProvider.io()) { File(fullPath).readBytes() }
    val outputStream = ByteArrayOutputStream()
    // Modern smartcard UX: one persistent reader, a reused present/hold-card dialog, the card
    // operation run on the card's own thread, inline PIN entry with retries (so reader mode stays
    // on across wrong PINs and never triggers the NDEF-URL popup), and reader mode released only
    // once the card is physically removed. The shared loop lives in OpenPgpCardPrompt.runWithPin.
    val prompt = OpenPgpCardPrompt(this, R.string.openpgp_nfc_decrypt_title, dispatcherProvider)
    val reader = prompt.createReader()
    if (reader == null) {
      showSmartcardError(getString(R.string.openpgp_nfc_unavailable))
      return
    }
    var readerHandedOff = false
    try {
      val outcome =
        prompt.runWithPin(
          reader = reader,
          // Namespaced so the decryption PIN cache is kept separate from the signing PIN cache.
          cacheKey = "decrypt:${identifiers.firstOrNull()}",
          pinTitleRes = R.string.openpgp_card_pin_title,
          pinHintRes = R.string.openpgp_card_pin_hint,
          identityLabel = getIdentityLabelForIdentifiers(identifiers),
          pinMode = OpenPgpCardPrompt.PinMode.USER,
          presentMessage = getString(R.string.openpgp_nfc_tap_card),
          commFailedMessage = getString(R.string.openpgp_nfc_card_comm_failed),
          // Seed the PIN from a caller-provided (e.g. biometric-unlocked) value.
          seedPin = passphrases.values.firstOrNull()?.takeIf { it.isNotEmpty() },
        ) { card, currentPin ->
          val results =
            repository.decryptWithSmartcard(
              currentPin,
              identifiers,
              messageBytes.inputStream(),
              outputStream,
              card,
            )
          // Surface a decryption failure (wrong PIN, transceive error, ...) as a thrown exception
          // so the prompt can classify it.
          results.last().second.getError()?.let { throw it }
          results
        }
      when (outcome) {
        is OpenPgpCardPrompt.CardOutcome.Success -> {
          readerHandedOff = true
          prompt.releaseReaderWhenCardRemoved(outcome.card, reader)
          val lastResult = outcome.value.last()
          val decryptedEntryBytes = lastResult.second.getOrThrow().toByteArray()
          lastResult.second.getOrThrow().wipe()
          val decryptedEntryChars = decryptedEntryBytes.toCharArray()
          decryptedEntryBytes.wipe()
          val entry = passwordEntryFactory.create(decryptedEntryChars)
          encryptedEntryChars = AESEncryption.encrypt(decryptedEntryChars)
          decryptedEntryChars.wipe()
          entry.clearExtraChars()
          createPasswordUI(entry)
          onSuccess(lastResult.first)
        }
        OpenPgpCardPrompt.CardOutcome.Cancelled -> {
          readerHandedOff = true
          prompt.releaseReaderWhenCardRemoved(null, reader)
          finish()
        }
        is OpenPgpCardPrompt.CardOutcome.Blocked -> {
          readerHandedOff = true
          prompt.releaseReaderWhenCardRemoved(outcome.card, reader)
          showSmartcardError(getString(R.string.openpgp_card_pin_blocked))
        }
        is OpenPgpCardPrompt.CardOutcome.Failed -> {
          readerHandedOff = true
          prompt.releaseReaderWhenCardRemoved(outcome.card, reader)
          showSmartcardError(friendlySmartcardError(outcome.error))
        }
      }
    } finally {
      prompt.dismissDialog()
      if (!readerHandedOff) prompt.releaseReaderWhenCardRemoved(null, reader)
    }
  }

  private fun showSmartcardError(message: String) {
    MaterialAlertDialogBuilder(this)
      .setTitle(R.string.openpgp_nfc_decrypt_failed_title)
      .setMessage(message)
      .setPositiveButton(android.R.string.ok) { _, _ ->
        // Reader mode is disabled by the removal watcher once the card is lifted; just finish.
        finish()
      }
      .setCancelable(false)
      .show()
  }

  private fun friendlySmartcardError(error: Throwable?): String =
    if (OpenPgpCardPrompt.isSmartcardPinFailure(error)) {
      resources.getString(R.string.openpgp_card_wrong_pin)
    } else {
      error?.message ?: resources.getString(R.string.password_decryption_unknown_error)
    }

  override fun onCreateOptionsMenu(menu: Menu): Boolean {
    menuInflater.inflate(R.menu.pgp_handler, menu)
    return true
  }

  override fun onPrepareOptionsMenu(menu: Menu): Boolean {
    encryptedEntryChars?.let { encrypted ->
      menu.findItem(R.id.reencrypt_password).setVisible(true)
      binding.editFab.isVisible = true
      AESEncryption.decrypt(encrypted)?.let { decrypted ->
        val entry = passwordEntryFactory.create(decrypted)
        decrypted.wipe()
        if (!isPasskey && entry.password?.let { !it.isBlank() } ?: false) {
          binding.shareFab.isVisible = true
          binding.fab.isVisible = true
        }
        entry.clear()
      }
    }

    return true
  }

  override fun onOptionsItemSelected(item: MenuItem): Boolean {
    when (item.itemId) {
      android.R.id.home -> onBackPressedDispatcher.onBackPressed()
      R.id.reencrypt_password -> changeKeys()
      R.id.delete_password -> deleteEntry()
      else -> return super.onOptionsItemSelected(item)
    }
    return true
  }

  /** Opens the key picker on the keys this entry is already encrypted to. */
  private fun changeKeys() {
    lifecycleScope.launch {
      // Named by primary key, which is how the picker lists them: a message names the encryption
      // subkey it was addressed to, and that matches nothing in the list.
      val current =
        withContext(dispatcherProvider.io()) {
          entryRecipients().mapNotNull { repository.getLongKeyIdFromKeyId(it) }.distinct()
        }
      reencryptAction.launch(
        PGPKeyListActivity.newIntent(
          this@DecryptActivity,
          keySelection = true,
          preselectedKeyIds = current.joinToString("\n").takeIf { it.isNotEmpty() },
        )
      )
    }
  }

  private val reencryptAction =
    registerForActivityResult(StartActivityForResult()) { result ->
      if (result.resultCode != RESULT_OK) return@registerForActivityResult
      val identifiers =
        result.data
          ?.getStringExtra(PGPKeyListActivity.EXTRA_SELECTED_KEY)
          ?.split("\n")
          ?.filter(String::isNotBlank)
          ?.mapNotNull(PGPIdentifier::fromString)
          .orEmpty()
      if (identifiers.isEmpty()) return@registerForActivityResult
      // A key whose private half is not here — a bare public key, or a card stub with no card —
      // encrypts perfectly well and opens nothing. Choosing only such keys hands the entry to
      // someone else and takes it away from this store, so it is said out loud first.
      if (identifiers.none { repository.hasKey(it) && repository.hasDecKey(it) }) {
        WarningDialog.show(
          context = this,
          titleRes = R.string.change_keys_unopenable_title,
          messageRes = R.string.change_keys_unopenable_message,
          proceedLabelRes = R.string.change_keys_unopenable_confirm,
        ) {
          reencrypt(identifiers)
        }
        return@registerForActivityResult
      }
      reencrypt(identifiers)
    }

  /**
   * Writes this entry back, encrypted to [identifiers] instead of whatever it was saved with.
   *
   * This is where an entry's keys and its folder's .gpg-id part company, deliberately: pass allows
   * one file to be readable by a different key from its neighbours, and the .gpg-id keeps deciding
   * what *new* saves in that folder are encrypted to. Opening an entry therefore asks the entry
   * (see [keysForEntry]) rather than the folder.
   *
   * Only this entry changes: the folder keeps its own key, and pass is content for one file to be
   * readable by a different key from its neighbours. The plaintext never leaves memory — it is the
   * copy this screen already decrypted, and it is wiped either way.
   */
  private fun reencrypt(identifiers: List<PGPIdentifier>) {
    val encrypted = encryptedEntryChars ?: return
    val decrypted =
      AESEncryption.decrypt(encrypted)
        ?: run {
          snackbar(message = getString(R.string.change_keys_failure))
          return
        }
    lifecycleScope.launch(dispatcherProvider.main()) {
      val plaintext = decrypted.toByteArray()
      decrypted.wipe()
      val (succeededUserIds, result) =
        withContext(dispatcherProvider.io()) {
          repository.encrypt(identifiers, plaintext.inputStream(), ByteArrayOutputStream())
        }
      plaintext.wipe()
      // A key that nothing could be encrypted to would leave the entry unreadable, so it is
      // refused rather than written; keys that failed among others are named, as saving does.
      if (result.isErr || succeededUserIds.isNullOrEmpty()) {
        snackbar(message = getString(R.string.change_keys_failure))
        return@launch
      }
      val failedUserIds =
        identifiers.mapNotNull { repository.getEmailFromKeyId(it) } - succeededUserIds.toSet()
      val entryFile = File(fullPath)
      val previousBytes = withContext(dispatcherProvider.io()) { entryFile.readBytes() }
      val written = replaceEntry(entryFile, result.getOrThrow().toByteArray())
      if (!written) {
        snackbar(message = getString(R.string.change_keys_failure))
        return@launch
      }
      commitChange(
          getString(
            R.string.git_commit_edit_text,
            PasswordRepository.getLongName(fullPath, repoPath, name),
          )
        )
        .onErr {
          // Put back what was there, so a refused or failed commit does not leave the entry
          // encrypted to keys the repository knows nothing about.
          withContext(dispatcherProvider.io()) { entryFile.writeBytes(previousBytes) }
          snackbar(message = getString(R.string.change_keys_failure))
        }
        .onOk {
          // The entry stays open: what it says did not change, only what encrypts it, and the
          // copy on screen is still the one that was just written.
          setResult(RESULT_OK)
          snackbar(
            message =
              if (failedUserIds.isEmpty()) getString(R.string.change_keys_success)
              else getString(R.string.change_keys_partial, failedUserIds.joinToString())
          )
        }
    }
  }

  /** Shows an entry from the copy handed over by whatever wrote it, without decrypting again. */
  private fun showCachedEntry(encryptedEntry: CharArray, decrypted: CharArray) {
    encryptedEntryChars = encryptedEntry
    lifecycleScope.launch(dispatcherProvider.main()) {
      val entry = passwordEntryFactory.create(decrypted)
      decrypted.wipe()
      entry.clearExtraChars()
      createPasswordUI(entry)
      invalidateOptionsMenu()
    }
  }

  private fun copyPassword() {
    encryptedEntryChars?.let { encrypted ->
      AESEncryption.decrypt(encrypted)?.let { decrypted ->
        val entry = passwordEntryFactory.create(decrypted)
        decrypted.wipe()
        if (entry.password?.let { !it.isBlank() } ?: false) {
          clearTimer?.shutdownNow()
          clearTimer = copyPasswordToClipboard(entry.password)
        }
        entry.clear()
      }
    }
  }

  private fun editPassword() {
    encryptedEntryChars?.let { encrypted ->
      val intent = Intent(this, PasswordCreationActivity::class.java)
      intent.action = Intent.ACTION_VIEW
      intent.putExtra(EXTRA_FILE_PATH, Paths.get(fullPath).parent.pathString)
      intent.putExtra(EXTRA_REPO_PATH, repoPath)
      intent.putExtra(PasswordCreationActivity.EXTRA_FILE_NAME, name)
      intent.putExtra(PasswordCreationActivity.EXTRA_ENTRY, encrypted)
      intent.putExtra(PasswordCreationActivity.EXTRA_EDITING, true)
      editAction.launch(intent)
    }
  }

  /**
   * Returns to the entry after editing rather than leaving the list behind the editor, whether the
   * edit was saved or abandoned. A saved edit brings back the copy it wrote, so the entry it shows
   * is the new one without decrypting it again; a renamed entry is opened under its new name.
   */
  private val editAction =
    registerForActivityResult(StartActivityForResult()) { result ->
      val data = result.data ?: return@registerForActivityResult
      if (result.resultCode != RESULT_OK) return@registerForActivityResult
      val edited = data.getCharArrayExtra(PasswordCreationActivity.EXTRA_ENTRY)
      val newPath = data.getStringExtra(PasswordCreationActivity.RETURN_EXTRA_CREATED_FILE)
      if (newPath != null && newPath != fullPath) {
        startActivity(
          Intent(this, DecryptActivity::class.java)
            .putExtra(EXTRA_FILE_PATH, newPath)
            .putExtra(EXTRA_REPO_PATH, repoPath)
            .putExtra(PasswordCreationActivity.EXTRA_ENTRY, edited)
            // The entry moved, so the screen that shows it under its new name commits the move.
            .putExtra(
              PasswordCreationActivity.RETURN_EXTRA_COMMIT_MESSAGE,
              data.getStringExtra(PasswordCreationActivity.RETURN_EXTRA_COMMIT_MESSAGE),
            )
        )
        finish()
        return@registerForActivityResult
      }
      val decrypted = edited?.let { AESEncryption.decrypt(it) }
      if (edited != null && decrypted != null) {
        encryptedEntryChars?.wipe()
        showCachedEntry(edited, decrypted)
      } else {
        recreate()
      }
      data.getStringExtra(PasswordCreationActivity.RETURN_EXTRA_MESSAGE)?.let { message ->
        snackbar(message = message)
      }
      lifecycleScope.launch { commitSavedChange(data) }
    }

  /** Deletes this entry, after asking, and leaves — there is nothing left to show. */
  private fun deleteEntry() {
    WarningDialog.show(
      context = this,
      title = getString(R.string.delete_dialog_title),
      message = resources.getQuantityString(R.plurals.delete_dialog_text, 1, 1),
      proceedLabel = getString(R.string.delete),
    ) {
      lifecycleScope.launch {
        withContext(dispatcherProvider.io()) { File(fullPath).delete() }
        passwordHistory.edit { remove(fullPath.base64()) }
        commitChange(
          getString(
            R.string.git_commit_remove_text,
            PasswordRepository.getLongName(fullPath, repoPath, name),
          )
        )
        setResult(RESULT_OK)
        finish()
      }
    }
  }

  private fun editPasskey() {
    encryptedEntryChars?.let { encrypted ->
      val intent = Intent(this, PasskeyCreationActivity::class.java)
      intent.action = Intent.ACTION_VIEW
      intent.putExtra(EXTRA_FILE_PATH, Paths.get(fullPath).parent.pathString)
      intent.putExtra(EXTRA_REPO_PATH, repoPath)
      intent.putExtra(PasswordCreationActivity.EXTRA_FILE_NAME, name)
      intent.putExtra(PasswordCreationActivity.EXTRA_ENTRY, encrypted)
      intent.putExtra(PasswordCreationActivity.EXTRA_EDITING, true)
      startActivity(intent)
      finish()
    }
  }

  private fun shareAsPlaintext() {
    encryptedEntryChars?.let { encrypted ->
      AESEncryption.decrypt(encrypted)?.let { decrypted ->
        val entry = passwordEntryFactory.create(decrypted)
        decrypted.wipe()
        if (entry.password?.let { !it.isBlank() } ?: false) {
          val sendIntent =
            Intent().apply {
              action = Intent.ACTION_SEND
              putExtra(Intent.EXTRA_TEXT, entry.password?.let { String(it) })
              type = "text/plain"
            }
          entry.clear()
          // Always show a picker to give the user a chance to cancel
          startActivity(
            Intent.createChooser(sendIntent, resources.getText(R.string.send_plaintext_password_to))
          )
        }
        entry.clear()
      }
    }
  }

  private suspend fun createPasswordUI(entry: PasswordEntry) =
    withContext(dispatcherProvider.main()) {
      entry.extraContentChars?.wipe() // not used here

      val passkey = retrievePasskey(entry, stripped = true)

      isPasskey = passkey != null

      invalidateOptionsMenu() // redraws/enables menu items in the action bar

      val items = arrayListOf<FieldItem>()

      if (passkey != null) {
        items.add(
          FieldItem.createNoCopyFreeformField(
            getString(R.string.passkey),
            "${getString(R.string.cred_algorithm_hint)}: ${passkey.getAlgorithmString()}, "
              .toCharArray() +
              getString(R.string.created_date, passkey.creationDateTimeString()).toCharArray(),
          )
        )

        items.add(
          FieldItem.createFreeformField(
            getString(R.string.rp_name_hint),
            passkey.rp.id.toCharArray(),
          )
        )

        items.add(
          FieldItem.createUsernameField(
            getString(R.string.username),
            passkey.user.name.toCharArray(),
          )
        )

        if (passkey.user.displayName != null && passkey.user.displayName != passkey.user.name) {
          items.add(
            FieldItem.createUsernameField(
              getString(R.string.fullname_hint),
              passkey.user.displayName.toCharArray(),
            )
          )
        }

        // maintain cred hex ID <-> user name map for display on passkey selector
        credentialUsernames.edit {
          if (passkey.user.revealName) {
            val displayUser =
              if (passkey.user.displayName != null && passkey.user.displayName != passkey.user.name)
                "${passkey.user.name} (${passkey.user.displayName})"
              else passkey.user.name
            putString(
              passkey.idHex(),
              AESEncryption.encrypt(displayUser.toCharArray(), keyType = KeyType.PERSISTENT)
                ?.concatToString(),
            )
          } else {
            remove(passkey.idHex())
          }
        }
      } else if (entry.password?.let { !it.isBlank() } ?: false) {
        // password
        items.add(
          FieldItem.createPasswordField(
            getString(R.string.password),
            entry.password ?: throw NullPointerException(),
          )
        )
        if (settings.getBoolean(PreferenceKeys.COPY_ON_DECRYPT, false)) {
          entry.password?.let {
            clearTimer?.shutdownNow()
            clearTimer = copyPasswordToClipboard(it.copyOf(it.size))
          }
        }
      }
      val labelFormat = resources.getString(R.string.otp_label_format)
      if (entry.hasTotp()) {
        items.add(FieldItem.createOtpField(labelFormat, entry.totp.first()))
      }

      if (entry.username?.isNotEmpty() ?: false) {
        items.add(
          FieldItem.createUsernameField(
            getString(R.string.username),
            entry.username ?: throw NullPointerException(),
          )
        )
      }

      entry.extraContent.forEach { (key, value) ->
        if (key != PasswordEntry.EXTRA_CONTENT) {
          if (key.startsWith("*") && key.endsWith("*"))
            items.add(FieldItem.createPasswordField(key.substring(1, key.length - 1).trim(), value))
          else if (
            key.lowercase() in entry.unsafeKeys ||
              key.lowercase() in PasswordEntry.PASSWORD_FIELDS.map { it.dropLast(1) }
          )
            items.add(FieldItem.createPasswordField(key, value))
          else items.add(FieldItem.createFreeformField(key, value))
        }
      }

      entry.extraContent.forEach { (key, value) ->
        if (key.contentEquals(PasswordEntry.EXTRA_CONTENT))
          items.add(FieldItem.createFreeformField(getString(R.string.crypto_extra_label), value))
      }

      val showPassword = settings.getBoolean(PreferenceKeys.SHOW_PASSWORD, false)
      val adapter =
        FieldItemAdapter(items, showPassword) { text, isSensitive ->
          copyPasswordToClipboard(text, isSensitive)
        }

      itemsAdapter = adapter
      binding.recyclerView.adapter = adapter
      binding.recyclerView.itemAnimator = null

      if (entry.hasTotp()) {
        lifecycleScope.launch { entry.totp.collect { adapter.updateOTPCode(it, labelFormat) } }
      }
    }

  private companion object {}
}
