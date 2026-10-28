/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.passwordstore.ui.autofill

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.view.autofill.AutofillManager
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import app.passwordstore.R
import app.passwordstore.crypto.PGPIdentifier
import app.passwordstore.crypto.errors.IncorrectPassphraseException
import app.passwordstore.crypto.errors.NoDecryptionKeyAvailableException
import app.passwordstore.data.passfile.PasswordEntry
import app.passwordstore.data.repo.PasswordRepository
import app.passwordstore.injection.prefs.PasswordHistory
import app.passwordstore.ui.crypto.BasePGPActivity
import app.passwordstore.ui.dialogs.ErrorDialog
import app.passwordstore.util.autofill.AutofillMatcher
import app.passwordstore.util.autofill.AutofillPreferences
import app.passwordstore.util.autofill.AutofillResponseBuilder
import app.passwordstore.util.crypto.NfcCardReader
import app.passwordstore.util.extensions.base64
import app.passwordstore.util.extensions.toCharArray
import app.passwordstore.util.extensions.wipe
import app.passwordstore.util.settings.PreferenceKeys
import com.github.androidpasswordstore.autofillparser.AutofillAction
import com.github.androidpasswordstore.autofillparser.FormOrigin
import com.github.michaelbull.result.getError
import com.github.michaelbull.result.getOrThrow
import dagger.hilt.android.AndroidEntryPoint
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority.ERROR
import logcat.asLog
import logcat.logcat

@AndroidEntryPoint
class AutofillDecryptActivity : BasePGPActivity() {

  @Inject lateinit var passwordEntryFactory: PasswordEntry.Factory
  @PasswordHistory @Inject lateinit var passwordHistory: SharedPreferences

  private lateinit var filePath: String
  private lateinit var repositoryPath: String
  private lateinit var clientState: Bundle
  private lateinit var action: AutofillAction

  override fun onStart() {
    super.onStart()
    filePath =
      intent?.getStringExtra(EXTRA_FILE_PATH)
        ?: run {
          logcat(ERROR) { "AutofillDecryptActivity started without EXTRA_FILE_PATH" }
          finish()
          return
        }
    repositoryPath = PasswordRepository.getRepositoryDirectory().toString()
    clientState =
      intent?.getBundleExtra(AutofillManager.EXTRA_CLIENT_STATE)
        ?: run {
          logcat(ERROR) { "AutofillDecryptActivity started without EXTRA_CLIENT_STATE" }
          finish()
          return
        }
    val isSearchAction =
      intent?.getBooleanExtra(EXTRA_SEARCH_ACTION, true) ?: throw NullPointerException()
    action = if (isSearchAction) AutofillAction.Search else AutofillAction.Match
    logcat { action.toString() }
    // As on the entry screen: what can open this is asked of the message, not of the folder's
    // .gpg-id, so an entry encrypted to a key this device holds opens whatever its folder says.
    requireKeysExist {
      lifecycleScope.launch {
        val entryFile = File(filePath)
        val subDir = PasswordRepository.getParentPath(filePath, repositoryPath)
        val keys = withContext(dispatcherProvider.io()) { decryptionCandidates(entryFile, subDir) }
        if (keys.isEmpty()) {
          reportUnopenable(withContext(dispatcherProvider.io()) { entryRecipients(entryFile) })
          return@launch
        }
        getPersistentAndDecrypt(keys, action = "autofill")
      }
    }
  }

  override fun onDestroy() {
    // A card held to the phone keeps NFC reader mode on until it is lifted; a screen that goes
    // before then leaves nothing behind it.
    NfcCardReader.disableReaderMode(this)
    super.onDestroy()
  }

  override suspend fun decryptWithPassphrase(
    passphrases: Map<String, CharArray?>,
    identifiers: List<PGPIdentifier>,
    onSuccess: suspend (String) -> Unit,
  ) {
    // As on the entry screen: a card is asked for only when nothing on this device can open the
    // entry, so an entry encrypted to both a card and a local key does not reach for the card.
    val cardKeys = identifiers.filter {
      repository.isSmartcardBacked(it) || repository.hasOnlyStubDecKey(it)
    }
    val localKeys = identifiers - cardKeys.toSet()
    if (localKeys.isEmpty()) {
      decryptWithSmartcard(passphrases, cardKeys, onSuccess)
      return
    }
    val encryptedFile = File(filePath)
    val message = withContext(dispatcherProvider.io()) { encryptedFile.readBytes().inputStream() }
    val outputStream = ByteArrayOutputStream()
    val results = repository.decrypt(passphrases, localKeys, message, outputStream)
    val lastResult = results.last()

    if (lastResult.second.isOk) {
      fillFrom(lastResult.first, lastResult.second.getOrThrow(), onSuccess)
    } else {
      passphrases.values.forEach { it?.wipe() }
      if (
        results
          .filter { result ->
            if (result.second.getError() is IncorrectPassphraseException) {
              /* Remove wrong passphrases from temporary and persistent caches */
              val cacheKey = passphraseCacheKey(result.first)
              persistentPassphrases.edit { remove(cacheKey) }
              cachedPassphrases[cacheKey]?.wipe()
              cachedPassphrases.remove(cacheKey)
              true
            } else false
          }
          .any()
      ) {
        /* Retry */
        decrypt(identifiers, isError = true)
      } else if (
        cardKeys.isNotEmpty() &&
          results.filter { it.second.getError() is NoDecryptionKeyAvailableException }.any()
      ) {
        // The local keys turned out not to fit; the card is the remaining candidate.
        decryptWithSmartcard(passphrases, cardKeys, onSuccess)
        return
      } else if (
        results.filter { it.second.getError() is NoDecryptionKeyAvailableException }.any()
      ) {
        ErrorDialog.show(
          this@AutofillDecryptActivity,
          R.string.password_decryption_no_decryption_key,
        )
        val timer = Executors.newSingleThreadScheduledExecutor()
        timer.schedule({ finish() }, 4.toLong(), TimeUnit.SECONDS)
      } else {
        ErrorDialog.show(
          this@AutofillDecryptActivity,
          R.string.password_decryption_unknown_error,
        )
        val timer = Executors.newSingleThreadScheduledExecutor()
        timer.schedule({ finish() }, 4.toLong(), TimeUnit.SECONDS)
      }

      results
        .filter { it.second.getError() is Throwable }
        .forEach { logcat { it.second.getError()?.asLog() ?: "unknown error" } }

      if (!settings.getBoolean(PreferenceKeys.CACHE_PASSPHRASE, false)) {
        cachedPassphrases.values.forEach { it.wipe() }
        cachedPassphrases.clear()
      }
    }
  }

  /** Opens the entry with the card, and fills the form from it as a local key would. */
  private suspend fun decryptWithSmartcard(
    passphrases: Map<String, CharArray?>,
    identifiers: List<PGPIdentifier>,
    onSuccess: suspend (String) -> Unit,
  ) {
    val messageBytes = withContext(dispatcherProvider.io()) { File(filePath).readBytes() }
    decryptWithSmartcard(messageBytes, passphrases, identifiers) { decryptedBy, plaintext ->
      fillFrom(decryptedBy, plaintext, onSuccess)
    }
  }

  /** Hands the form the credentials in the decrypted entry [plaintext], and goes. */
  private suspend fun fillFrom(
    decryptedBy: String,
    plaintext: ByteArrayOutputStream,
    onSuccess: suspend (String) -> Unit,
  ) {
    val encryptedFile = File(filePath)
    val decryptedEntryBytes = plaintext.toByteArray()
    plaintext.wipe()
    val decryptedEntryChars = decryptedEntryBytes.toCharArray()
    decryptedEntryBytes.wipe()
    val entry = passwordEntryFactory.create(decryptedEntryChars)
    entry.clearExtra()

    // no autofill if it is a passkey
    if (retrievePasskey(entry, stripped = true) != null) {
      entry.clear()

      AutofillMatcher.updateMatches(this@AutofillDecryptActivity, delete = listOf(encryptedFile))

      onSuccess(decryptedBy) // but pass ID for passphrase caching

      if (!settings.getBoolean(PreferenceKeys.CACHE_PASSPHRASE, false)) {
        cachedPassphrases.values.forEach { it.wipe() }
        cachedPassphrases.clear()
      }

      ErrorDialog.show(
        this@AutofillDecryptActivity,
        R.string.password_decryption_no_autofill_with_passkey_error,
      )
      val timer = Executors.newSingleThreadScheduledExecutor()
      timer.schedule({ finish() }, 4.toLong(), TimeUnit.SECONDS)
    } else {
      val directoryStructure = AutofillPreferences.directoryStructure(this)
      val credentials =
        AutofillPreferences.credentialsFromStoreEntry(
          this,
          encryptedFile,
          entry,
          origin,
          directoryStructure,
        )
      val fillInDataset =
        AutofillResponseBuilder.makeFillInDataset(
          this@AutofillDecryptActivity,
          credentials,
          clientState,
          action,
        )
      withContext(dispatcherProvider.main()) {
        setResult(
          RESULT_OK,
          Intent().apply { putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, fillInDataset) },
        )
        if (entry.hasTotp()) {
          val otp = entry.currentOtp
          val remainingTime = otp.remainingTime.inWholeSeconds
          copyTextToClipboard(otp.value.toCharArray(), isSensitive = false)
          otpTimer?.shutdownNow()
          val otpTimerNew = Executors.newSingleThreadScheduledExecutor()
          otpTimer = otpTimerNew
          otpTimerNew.schedule( // refresh otp once
            { copyTextToClipboard(entry.currentOtp.value.toCharArray(), isSensitive = false) },
            remainingTime,
            TimeUnit.SECONDS,
          )
        }
      }

      passwordHistory.edit { // create/update timestamp on the current password file
        putString(
          filePath.base64(),
          System.currentTimeMillis().toString(),
        )
      }

      onSuccess(decryptedBy) // pass ID for passphrase caching

      if (!settings.getBoolean(PreferenceKeys.CACHE_PASSPHRASE, false)) {
        cachedPassphrases.values.forEach { it.wipe() }
        cachedPassphrases.clear()
      }

      // update autofill suggestion order (add/move to front or remove)
      formOrigin?.let {
        if (AutofillPreferences.addQuickSelectButton(this@AutofillDecryptActivity))
          AutofillMatcher.addMatchFor(this@AutofillDecryptActivity, it, encryptedFile)
        else
          AutofillMatcher.updateMatches(
            this@AutofillDecryptActivity,
            delete = listOf(encryptedFile),
          )
      }

      withContext(dispatcherProvider.main()) { finish() }
    }
  }

  companion object {

    var origin: String? = null
    var formOrigin: FormOrigin? = null
    private const val EXTRA_FILE_PATH = "app.passwordstore.autofill.oreo.EXTRA_FILE_PATH"
    private const val EXTRA_SEARCH_ACTION = "app.passwordstore.autofill.oreo.EXTRA_SEARCH_ACTION"

    private var decryptFileRequestCode = 100000
    private var otpTimer: ScheduledExecutorService? = null

    fun makeDecryptFileIntent(
      file: File,
      formOrigin: FormOrigin,
      forwardedExtras: Bundle,
      context: Context,
    ): Intent {
      origin = formOrigin.getPrettyIdentifier(context, untrusted = false) // web origin or app name
      this.formOrigin = formOrigin
      return Intent(context, AutofillDecryptActivity::class.java).apply {
        putExtras(forwardedExtras)
        putExtra(EXTRA_SEARCH_ACTION, true)
        putExtra(EXTRA_FILE_PATH, file.absolutePath)
      }
    }

    fun makeDecryptFileIntentSender(
      file: File,
      context: Context,
      formOrigin: FormOrigin,
    ): IntentSender {
      origin = formOrigin.getPrettyIdentifier(context, untrusted = false) // web origin or app name
      this.formOrigin = formOrigin

      val intent =
        Intent(context, AutofillDecryptActivity::class.java).apply {
          putExtra(EXTRA_SEARCH_ACTION, false)
          putExtra(EXTRA_FILE_PATH, file.absolutePath)
        }
      return PendingIntent.getActivity(
          context,
          decryptFileRequestCode++,
          intent,
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE
          } else {
            PendingIntent.FLAG_CANCEL_CURRENT
          },
        )
        .intentSender
    }
  }
}
