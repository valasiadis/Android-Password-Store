/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.crypto

import android.content.ClipData
import android.content.ClipDescription
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.text.SpannableStringBuilder
import android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
import android.text.style.ForegroundColorSpan
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.annotation.CallSuper
import androidx.annotation.StringRes
import androidx.appcompat.R as AppCompatR
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.fragment.app.setFragmentResultListener
import androidx.lifecycle.lifecycleScope
import app.passwordstore.R
import app.passwordstore.crypto.PGPIdentifier
import app.passwordstore.crypto.displayName
import app.passwordstore.crypto.isHiddenRecipient
import app.passwordstore.data.crypto.CryptoRepository
import app.passwordstore.data.passfile.PasswordEntry
import app.passwordstore.data.repo.PasswordRepository
import app.passwordstore.injection.prefs.PGPPassphrases
import app.passwordstore.injection.prefs.SettingsPreferences
import app.passwordstore.injection.prefs.UnlockPins
import app.passwordstore.ui.dialogs.ErrorDialog
import app.passwordstore.ui.dialogs.Notice
import app.passwordstore.ui.dialogs.PasswordDialog
import app.passwordstore.ui.pgp.PGPKeyListActivity
import app.passwordstore.util.auth.BiometricAuthenticator
import app.passwordstore.util.auth.BiometricAuthenticator.Result as BiometricResult
import app.passwordstore.util.coroutines.DispatcherProvider
import app.passwordstore.util.crypto.AESEncryption
import app.passwordstore.util.crypto.AESEncryption.KeyType
import app.passwordstore.util.extensions.b64Decode
import app.passwordstore.util.extensions.clipboard
import app.passwordstore.util.extensions.commitChange
import app.passwordstore.util.extensions.getString
import app.passwordstore.util.extensions.isInsideRepository
import app.passwordstore.util.extensions.substringBefore
import app.passwordstore.util.extensions.unsafeLazy
import app.passwordstore.util.extensions.wipe
import app.passwordstore.util.passkey.PasskeyCredential
import app.passwordstore.util.settings.Constants
import app.passwordstore.util.settings.PreferenceKeys
import com.github.michaelbull.result.get
import com.github.michaelbull.result.onErr
import com.github.michaelbull.result.runCatching
import com.google.android.material.R as MaterialR
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import java.nio.CharBuffer
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.math.max
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.asLog
import logcat.logcat

@Suppress("Registered")
@AndroidEntryPoint
open class BasePGPActivity : AppCompatActivity() {

  /** Full path to the password file being worked on */
  val fullPath: String by unsafeLazy {
    intent.getStringExtra(EXTRA_FILE_PATH)
      ?: PasswordRepository.getRepositoryDirectory().absolutePath
  }

  /** Full path to the repository */
  val repoPath: String by unsafeLazy {
    intent.getStringExtra(EXTRA_REPO_PATH)
      ?: PasswordRepository.getRepositoryDirectory().absolutePath
  }

  protected val relativeParentPath by unsafeLazy {
    PasswordRepository.getParentPath(fullPath, repoPath)
  }

  /**
   * Name of the password file
   *
   * Converts personal/auth.foo.org/john_doe@example.org.gpg to john_doe.example.org
   */
  val name: String by unsafeLazy { File(fullPath).nameWithoutExtension }

  /**
   * The message of an encryption result, with the key names picked out in colour: the keys it
   * worked for in the theme's primary, the ones it did not in its error colour, so the outcome can
   * be read off the names themselves rather than by parsing the sentence around them.
   */
  protected fun encryptionOutcomeMessage(
    succeededUserIds: List<String>,
    failedUserIds: List<String>,
    onInverseSurface: Boolean = false,
  ): CharSequence {
    // A snackbar draws on the inverse surface, where the scheme's own primary is barely a colour
    // at all; the inverse primary is what it puts an accent in.
    val accent =
      if (onInverseSurface) MaterialR.attr.colorPrimaryInverse else AppCompatR.attr.colorPrimary
    val message = SpannableStringBuilder()
    val succeeded = succeededUserIds.joinToString()
    message.appendHighlighting(
      getString(R.string.password_creation_file_encryption_succeeded_ids_message, succeeded),
      succeeded,
      MaterialColors.getColor(this, accent, Color.TRANSPARENT),
    )
    if (failedUserIds.isNotEmpty()) {
      val failed = failedUserIds.joinToString()
      message.appendHighlighting(
        getString(R.string.password_creation_file_encryption_failed_ids_message, failed),
        failed,
        MaterialColors.getColor(this, AppCompatR.attr.colorError, Color.TRANSPARENT),
      )
    }
    return message
  }

  private fun SpannableStringBuilder.appendHighlighting(text: String, part: String, color: Int) {
    val start = length + text.indexOf(part)
    append(text)
    if (part.isNotEmpty() && text.contains(part)) {
      setSpan(ForegroundColorSpan(color), start, start + part.length, SPAN_EXCLUSIVE_EXCLUSIVE)
    }
  }

  /* Counter for the user's decryption (with passphrase) attempts */
  private var retries = 0

  private var secondsOnPause = 0L // seconds since Epoch upon pause
  private var timeout = 0L

  /**
   * Callback to invoke if [keyImportAction] or [keySelectAction] succeeds. This allows for
   * recursion until matching encryption/decryption keys are available for
   * unlocking/creating/editing the current password item.
   */
  private var onKeyListCallback: (() -> Unit)? = null

  private val keyImportAction =
    registerForActivityResult(StartActivityForResult()) {
      if (it.resultCode == RESULT_OK) {
        onKeyListCallback?.invoke()
      } else {
        leaveIfRefused()
      }
    }

  /**
   * Where a key chosen for a single save is delivered.
   *
   * Separate from [keySelectAction], which answers the question "what does this folder encrypt to?"
   * by writing a .gpg-id. Choosing a key because the folder names one that is not here answers a
   * narrower question — what this entry should be encrypted to — and must not rewrite the folder:
   * the entries already in it are still encrypted to the old key, and repointing the folder only
   * sends every later attempt to open them to a key that was never theirs.
   */
  private var onKeysChosenForSave: ((List<PGPIdentifier>) -> Unit)? = null

  private val keyForSaveAction =
    registerForActivityResult(StartActivityForResult()) { result ->
      val chosen = onKeysChosenForSave
      onKeysChosenForSave = null
      val ids =
        if (result.resultCode == RESULT_OK) {
          result.data
            ?.getStringExtra(PGPKeyListActivity.EXTRA_SELECTED_KEY)
            ?.split("\n")
            ?.filter(String::isNotBlank)
            ?.mapNotNull(PGPIdentifier::fromString)
            .orEmpty()
        } else emptyList()
      if (ids.isEmpty()) leaveIfRefused() else chosen?.invoke(ids)
    }

  private val keySelectAction =
    registerForActivityResult(StartActivityForResult()) { result ->
      if (result.resultCode == RESULT_OK) {
        val data = result.data ?: return@registerForActivityResult
        val selectedKeyId =
          data.getStringExtra(PGPKeyListActivity.EXTRA_SELECTED_KEY)
            ?: return@registerForActivityResult

        val repoRoot = PasswordRepository.getRepositoryDirectory()
        val subPath = data.getStringExtra("SUB_PATH") ?: return@registerForActivityResult

        val gpgIdDir =
          File(repoRoot, subPath)
            .let {
              if (it.isFile() || !it.exists()) it.getParentFile() else it.getAbsoluteFile()
            }
            .also {
              if (!it.exists()) it.mkdirs() // should not be necessary
            }

        File(gpgIdDir, ".gpg-id").let {
          it.writeText(selectedKeyId + "\n")
          // Committing can ask for a signing passphrase, and asking needs the main thread — which
          // runBlocking was holding, so choosing a key on a store that signs its commits hung here
          // instead of writing one.
          lifecycleScope.launch {
            commitChange(getString(R.string.git_commit_gpg_id, getString(R.string.app_name)))
            onKeyListCallback?.invoke()
          }
        }
      } else {
        leaveIfRefused()
      }
    }

  /** [SharedPreferences] instance used by subclasses to persist settings */
  @SettingsPreferences @Inject lateinit var settings: SharedPreferences

  /**
   * [SharedPreferences] instance used by subclasses for persistent caching of encrypted passphrases
   */
  @PGPPassphrases @Inject lateinit var persistentPassphrases: SharedPreferences

  @UnlockPins @Inject lateinit var unlockPins: SharedPreferences

  @Inject lateinit var repository: CryptoRepository
  @Inject lateinit var dispatcherProvider: DispatcherProvider

  /**
   * [onCreate] sets the window up with the right flags to prevent auth leaks through screenshots or
   * recent apps screen.
   */
  @CallSuper
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
  }

  override fun onResume() {
    val secondsNow = Instant.now().getEpochSecond()
    if (timeout > 0 && (secondsNow - secondsOnPause) > timeout) finish()
    super.onResume()
  }

  override fun onPause() {
    timeout =
      settings.getString(PreferenceKeys.GENERAL_SHOW_TIME)?.toLongOrNull()
        ?: Constants.DEFAULT_DECRYPTION_TIMEOUT.toLong()

    if (timeout > 0) secondsOnPause = Instant.now().getEpochSecond()
    super.onPause()
  }

  /**
   * Whether backing out of the key screens takes this one with it.
   *
   * A screen that is only reading an entry has nothing to keep and no reason to stay once the key
   * it needs has been refused. An editor holds everything that has been typed, saved nowhere else,
   * so it stays open and lets the user answer differently or take the entry somewhere else.
   */
  protected open val leavesOnKeyRefusal: Boolean = true

  private fun leaveIfRefused() {
    if (leavesOnKeyRefusal) finish()
  }

  private fun openKeyManagerDialog(
    title: String,
    message: String,
    @StringRes positiveLabel: Int = R.string.no_keys_imported_dialog_open_key_manager,
    onPositiveButtonClick: () -> Unit,
  ) =
    MaterialAlertDialogBuilder(this@BasePGPActivity)
      .setIcon(R.drawable.ic_warning_red_24dp)
      .setTitle(title)
      .setMessage(message)
      .setCancelable(false)
      .setPositiveButton(positiveLabel) { _, _ -> onPositiveButtonClick() }
      .setNegativeButton(R.string.dialog_cancel) { _, _ -> leaveIfRefused() }
      .show()

  /* Function to execute [onKeysExist] only if there are PGP keys imported in the app's key manager.
   */
  protected fun requireKeysExist(onKeysExist: () -> Unit) {
    onKeyListCallback = onKeysExist
    lifecycleScope.launch {
      val hasKeys = repository.hasKeys()
      if (!hasKeys) {
        withContext(dispatcherProvider.main()) {
          openKeyManagerDialog(
            getString(R.string.no_keys_imported_dialog_title),
            getString(R.string.no_keys_imported_dialog_message),
          ) {
            keyImportAction.launch(PGPKeyListActivity.newIntent(this@BasePGPActivity))
          }
        }
      } else {
        onKeysExist()
      }
    }
  }

  protected fun requireEncryptionKeysExist(
    subDir: String,
    onKeysExist: (List<PGPIdentifier>) -> Unit,
  ) {
    val ids = getPGPIdentifiers(subDir)
    if (ids.isNullOrEmpty()) {
      /* Store not initialised properly; open Key Manager in selection mode and
       * let user choose one or multiple keys */
      val (title, message) =
        if (ids == null) {
          // .gpg-id is missing
          getString(R.string.missing_gpg_id_dialog_title) to
            getString(R.string.missing_gpg_id_dialog_message)
        } else {
          // .gpg-id contains no or malformed PGP IDs
          getString(R.string.invalid_gpg_id_dialog_title) to
            getString(R.string.invalid_gpg_id_dialog_message)
        }
      openKeyManagerDialog(title, message) {
        val intent = PGPKeyListActivity.newIntent(this@BasePGPActivity, keySelection = true)
        intent.putExtra("SUB_PATH", subDir)
        keySelectAction.launch(intent)
      }
    } else {
      val idsWithKey = ids.filter { repository.hasKey(it) }

      if (idsWithKey.isEmpty()) {
        // The folder names keys this app does not hold — a store cloned from elsewhere, or a key
        // since deleted. Importing the named key is one answer, but the likelier one is that the
        // entry should go to a key that is actually here, so this offers the picker.
        val title = getString(R.string.folder_key_missing_dialog_title)
        // The list gets lines of its own: interpolated into the sentence, the first key sat on
        // the end of it and only the rest broke away.
        val message =
          getString(R.string.folder_key_missing_dialog_message) +
            "\n\n" +
            ids.joinToString("\n") { "\u2022\u2002${it.displayName}" } +
            "\n\n" +
            getString(R.string.folder_key_missing_dialog_hint)
        openKeyManagerDialog(title, message, R.string.folder_key_missing_dialog_choose) {
          onKeysChosenForSave = onKeysExist
          // No SUB_PATH: the choice applies to what is being saved, and the folder keeps saying
          // what it said.
          keyForSaveAction.launch(
            PGPKeyListActivity.newIntent(this@BasePGPActivity, keySelection = true)
          )
        }
      } else if (idsWithKey.size < ids.size) {
        // Some of the folder's keys are here and some are not. What is here is enough to encrypt
        // to, so the save goes ahead — but it reaches fewer readers than the folder asks for, and
        // that is a fact about who can open a password rather than a decision to be made. So it is
        // said once, on the way through, and acknowledged rather than answered.
        MaterialAlertDialogBuilder(this@BasePGPActivity)
          .setIcon(R.drawable.ic_warning_red_24dp)
          .setTitle(R.string.folder_key_partial_dialog_title)
          .setMessage(
            getString(R.string.folder_key_partial_dialog_message) +
              "\n\n" +
              ids
                .filterNot { it in idsWithKey }
                .joinToString("\n") { "\u2022\u2002${it.displayName}" }
          )
          .setCancelable(false)
          .setPositiveButton(R.string.dialog_ok) { _, _ -> onKeysExist(ids) }
          .show()
      } else {
        onKeysExist(ids)
      }
    }
  }

  protected fun copyPasswordToClipboard(
    password: CharArray?,
    isSensitive: Boolean = true,
    showNotice: Boolean = true,
  ): ScheduledExecutorService? {
    copyTextToClipboard(password, isSensitive = isSensitive, showNotice)

    val clearAfter = settings.getString(PreferenceKeys.GENERAL_SHOW_TIME)?.toIntOrNull() ?: 45
    val deepClear = settings.getBoolean(PreferenceKeys.CLEAR_CLIPBOARD_HISTORY, false)
    val clipboard = clipboard

    if (isSensitive && clearAfter != 0 && clipboard != null) {
      val timer = Executors.newSingleThreadScheduledExecutor()
      timer.schedule(
        {
          logcat { "Clearing the clipboard" }
          var randomNum = (100000000000000000..999999999999999999).random().toString().toCharArray()
          copyTextToClipboard(randomNum, isSensitive = false, showNotice = false)
          if (deepClear) {
            repeat(CLIPBOARD_CLEAR_COUNT) {
              randomNum = (100000000000000000..999999999999999999).random().toString().toCharArray()
              copyTextToClipboard(randomNum, isSensitive = false, showNotice = false)
            }
          }
        },
        clearAfter.toLong(),
        TimeUnit.SECONDS,
      )
      return timer
    }

    return null
  }

  /**
   * Copies provided [text] to the clipboard, saying so unless [showNotice] is false — and never on
   * the versions of Android that say it themselves.
   */
  protected fun copyTextToClipboard(
    text: CharArray?,
    isSensitive: Boolean = true,
    showNotice: Boolean = true,
    @StringRes noticeTextRes: Int = R.string.clipboard_copied_text,
  ) {
    val clipboard = clipboard ?: return
    val charBuf = text?.let { CharBuffer.wrap(it) }
    val clip = ClipData.newPlainText((100000..999999).random().toString(), charBuf)
    clip.description.extras =
      PersistableBundle().apply {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.S_V2)
          putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, isSensitive)
        else putBoolean("android.content.extra.IS_SENSITIVE", isSensitive)
      }
    clipboard.setPrimaryClip(clip)
    charBuf?.array()?.wipe()
    text?.wipe()
    if (showNotice && Build.VERSION.SDK_INT < Build.VERSION_CODES.S_V2) {
      Notice.show(this@BasePGPActivity, noticeTextRes)
    }
  }

  /**
   * The keys the folder's `.gpg-id` names, with nothing said when there is none.
   *
   * Decryption asks the message which keys it was encrypted to, as gpg does; the folder only
   * supplies the wording those keys are cached under, and a message that names its own recipients
   * does not need it at all. An absent or unreadable `.gpg-id` is therefore not a failure here, as
   * it is when encrypting — where it is the only thing that decides.
   */
  protected fun folderIdentifiers(subDir: String): List<PGPIdentifier> {
    val repoRoot = PasswordRepository.getRepositoryDirectory()
    val file = File(repoRoot, subDir).findTillRoot(".gpg-id", repoRoot) ?: return emptyList()
    return file
      .readLines()
      .map { it.substringBefore(Regex("\\s*#|!")) }
      .filter { it.isNotBlank() && it != "gpg-id" }
      .mapNotNull(PGPIdentifier::fromString)
  }

  /**
   * The keys [entryFile] says it was encrypted to, empty where it says nothing.
   *
   * Nothing outside the store is read. The path an entry screen is opened on arrives in an intent —
   * from autofill, from a passkey request, from a home-screen shortcut — and a store's keys are for
   * opening what is in the store.
   */
  protected fun entryRecipients(entryFile: File): List<PGPIdentifier> =
    if (entryFile.isFile && entryFile.isInsideRepository())
      entryFile.inputStream().use { repository.recipientKeyIds(it) }
    else emptyList()

  /**
   * The keys worth trying for [entryFile], best first.
   *
   * Asked of the message, the way gpg asks it: the recipients named in it are what can open it, and
   * the `.gpg-id` under [subDir] has no say over an entry that already exists — it decides only
   * what future saves there are encrypted to. Where the folder names the same key under another
   * spelling, that spelling is kept: it is what cached passphrases are filed under.
   *
   * A message that will not say who it is for is answered the way gpg answers it, by trying the
   * whole keyring. That is a message written with `--throw-keyids` or `--hidden-recipient`, which
   * puts an all-zero key ID where a recipient would go — see [isHiddenRecipient] — and equally one
   * whose recipients could not be read at all.
   *
   * Ordered rather than narrowed, so nothing that could work goes untried, and ordered by how
   * likely and how cheap each answer is:
   * 1. the keys the folder's `.gpg-id` names, its own or the ones it inherits — the store's own
   *    answer to "who reads this?", and so the likeliest to be right;
   * 2. every other key held here, the ones that open without asking before the ones that want a
   *    passphrase;
   * 3. the smartcards, last, since those are the only ones that ask for something to be found and
   *    presented — unless the folder named one, in which case it was already tried in step 1.
   *
   * A file that is not there, or not in the store, has no candidates at all. Trying everything is
   * the answer to a message that will not name its recipients, not to a path that should never have
   * been asked about — and the paths these screens open on arrive in intents.
   */
  protected fun decryptionCandidates(entryFile: File, subDir: String): List<PGPIdentifier> {
    if (!entryFile.isFile || !entryFile.isInsideRepository()) return emptyList()
    val recipients = entryRecipients(entryFile)
    val named = recipients.filterNot(PGPIdentifier::isHiddenRecipient)
    val folderSpelling =
      folderIdentifiers(subDir).associateBy { repository.getLongKeyIdFromKeyId(it) }
    val spelled = named.map { recipient ->
      folderSpelling[repository.getLongKeyIdFromKeyId(recipient)] ?: recipient
    }
    val candidates =
      if (named.size < recipients.size || named.isEmpty()) spelled + repository.allKeyIds()
      else spelled
    val folderKeys = folderSpelling.keys.filterNotNull().toSet()
    return candidates
      .distinct()
      .filter { repository.hasKey(it) && repository.hasDecKey(it) }
      .sortedWith(
        compareBy(
          { if (repository.getLongKeyIdFromKeyId(it) in folderKeys) 0 else 1 },
          ::decryptionCost,
        )
      )
  }

  /** What a key will ask of the user before it opens anything: nothing, a passphrase, or a card. */
  private fun decryptionCost(id: PGPIdentifier): Int =
    when {
      repository.isSmartcardBacked(id) || repository.hasOnlyStubDecKey(id) -> 2
      repository.isPasswordProtected(listOf(id)) -> 1
      else -> 0
    }

  /**
   * Says an entry cannot be opened here, names the keys it is encrypted to, and offers to import
   * one of them — which is the only remedy, since a file already encrypted cannot be redirected.
   */
  protected fun reportUnopenable(recipients: List<PGPIdentifier>) {
    val named =
      // A message that hid its recipients names an all-zero key ID, which is not a key anyone is
      // missing — listing it as one only asks the user to go and find a key that does not exist.
      recipients.filterNot(PGPIdentifier::isHiddenRecipient).joinToString("\n") { id ->
        // Why each one is no help: not here at all, or here but only its public half — which call
        // for different remedies, one an import and the other a restore from a backup.
        val trouble =
          when {
            !repository.hasKey(id) -> getString(R.string.pgp_unknown)
            !repository.hasDecKey(id) -> getString(R.string.pgp_public_only)
            else -> null
          }
        if (trouble == null) "\u2022\u2002${id.displayName}"
        else "\u2022\u2002${id.displayName} \u2014 $trouble"
      }
    openKeyManagerDialog(
      getString(R.string.no_decryption_keys_dialog_title),
      getString(R.string.password_decryption_no_recipient_key) +
        if (named.isEmpty()) "" else "\n\n$named",
    ) {
      keyImportAction.launch(PGPKeyListActivity.newIntent(this@BasePGPActivity))
    }
  }

  protected fun getPGPIdentifiers(subDir: String): List<PGPIdentifier>? {
    var shortIdCount = 0
    var invalidIdCount = 0
    val repoRoot = PasswordRepository.getRepositoryDirectory()
    val gpgIdentifierFile =
      File(repoRoot, subDir).findTillRoot(".gpg-id", repoRoot)
        ?: run {
          ErrorDialog.show(this@BasePGPActivity, R.string.missing_gpg_id)
          return null
        }

    val gpgIdentifiers =
      gpgIdentifierFile
        .readLines()
        .map { // strip trailing comments and GPG subkey ID marker
          it.substringBefore(Regex("\\s*#|!"))
        }
        .filter { it.isNotBlank() && it != "gpg-id" }
        .map { line ->
          if (line.removePrefix("0x").matches("[a-fA-F0-9]{8}".toRegex())) {
            // Short key IDs are not accepted
            shortIdCount++
            null
          } else {
            val id = PGPIdentifier.fromString(line)
            if (id == null) invalidIdCount++
            else if (!repository.hasKey(id))
              persistentPassphrases.edit { remove(passphraseCacheKey(id)) }
            id
          }
        }
        .filterIsInstance<PGPIdentifier>()

    if (gpgIdentifiers.isEmpty()) {
      if (shortIdCount == 0 && invalidIdCount == 0) {
        ErrorDialog.show(this@BasePGPActivity, R.string.empty_gpg_id)
      } else if (shortIdCount > 0 && invalidIdCount == 0) {
        ErrorDialog.show(this@BasePGPActivity, R.string.short_gpg_id)
      } else {
        ErrorDialog.show(this@BasePGPActivity, R.string.invalid_gpg_id)
      }
    }

    return gpgIdentifiers
  }

  /**
   * The name a passphrase is filed under: the key's own ID, whatever the store called it.
   *
   * A `.gpg-id` may name a key by address or by ID, and the two used to be separate entries in the
   * cache — so a passphrase given once was asked for again the moment the same key was reached by
   * its other name. An identifier the store does not hold keeps its own spelling; there is no key
   * to ask for an ID.
   */
  protected fun passphraseCacheKey(identifier: PGPIdentifier): String =
    repository.getLongKeyIdFromKeyId(identifier) ?: identifier.toString()

  protected fun passphraseCacheKey(identifier: String): String =
    PGPIdentifier.fromString(identifier)?.let(::passphraseCacheKey) ?: identifier

  /**
   * Builds a short label naming the key(s) a passphrase/PIN is being requested for, so the prompt
   * makes clear which key is being unlocked. Shows the key's user ID exactly as the key list does,
   * falling back to the email and then the key ID so the label is never empty for a known key.
   */
  protected fun getIdentityLabelForIdentifiers(identifiers: List<PGPIdentifier>): String? {
    if (identifiers.isEmpty()) return null
    return identifiers
      .map { id ->
        repository.getUserIdFromKeyId(id)?.takeIf { it.isNotBlank() && it != "null" }
          ?: repository.getEmailFromKeyId(id)
          // A bare long key ID, which is shown wearing the 0x that says it is hexadecimal — see
          // PGPIdentifier.displayName, which this is the already-flattened form of.
          ?: repository.getLongKeyIdFromKeyId(id)?.let { "0x$it" }
          ?: id.displayName
      }
      .distinct()
      .joinToString(", ")
  }

  protected fun needsSmartcardPin(identifiers: List<PGPIdentifier>): Boolean = identifiers.any {
    repository.hasOnlyStubDecKey(it) || repository.isSmartcardBacked(it)
  }

  @Suppress("ReturnCount")
  private fun File.findTillRoot(fileName: String, rootPath: File): File? {
    val gpgFile = File(this, fileName)
    require(gpgFile.isInsideRepository()) { "Trying to access target outside the repository" }
    if (gpgFile.exists()) return gpgFile

    if (this.absolutePath == rootPath.absolutePath) {
      return null
    }
    val parent = parentFile
    parent?.let {
      require(it.isInsideRepository()) { "Trying to access target outside the repository" }
    }
    return if (parent != null && parent.exists()) {
      parent.findTillRoot(fileName, rootPath)
    } else {
      null
    }
  }

  /** Opens the dialog for passphrase input and then forwards it to the decryption method. */
  private suspend fun askPassphrase(isError: Boolean, identifiers: List<PGPIdentifier>) {
    if (++retries > MAX_RETRIES) finish()

    val dialog =
      PasswordDialog.newInstance(
        getIdentityLabelForIdentifiers(identifiers),
        cacheOptionVisible = true,
      )
    if (isError) dialog.setError()
    dialog.show(supportFragmentManager, "PASSWORD_DIALOG")
    dialog.setFragmentResultListener(PasswordDialog.PASSWORD_RESULT_KEY) { key, bundle ->
      if (key == PasswordDialog.PASSWORD_RESULT_KEY) {
        val passphrase =
          requireNotNull(bundle.getCharArray(PasswordDialog.PASSWORD_PHRASE_KEY)) {
            "returned passphrase is null"
          }
        var cacheEnabled = bundle.getBoolean(PasswordDialog.PASSWORD_CACHE_KEY)
        lifecycleScope.launch(dispatcherProvider.main()) {
          decryptWithPassphrase(mapOf("" to passphrase), identifiers) { decryptedBy -> // onSuccess
            val id = passphraseCacheKey(decryptedBy)
            // The same key under its other name, from before passphrases were filed by key ID.
            if (id != decryptedBy) persistentPassphrases.edit { remove(decryptedBy) }
            var fastUnlockingSetupCompletion: CompletableDeferred<Unit>? = null
            runCatching {
              // update temporary passphrase cache
              val isHardwareBacked = AESEncryption.isHardwareBacked()
              val encryptedPassphrase = AESEncryption.encrypt(passphrase)
              if (isHardwareBacked && cacheEnabled && encryptedPassphrase != null)
                cachedPassphrases.put(id, encryptedPassphrase)
              settings.edit {
                putBoolean(
                  PreferenceKeys.CACHE_PASSPHRASE,
                  isHardwareBacked && cacheEnabled && encryptedPassphrase != null,
                )
              }

              // update persistent passphrase cache
              var cipher = // cipher for encrypting the passphrase with biometrics
                if (
                  AESEncryption.isHardwareBacked(KeyType.PERSISTENT_WITH_AUTHENTICATION) &&
                    BiometricAuthenticator.canAuthenticate(this@BasePGPActivity)
                ) {
                  AESEncryption.getCipher(KeyType.PERSISTENT_WITH_AUTHENTICATION)
                    ?: run {
                      if (
                        settings.getString(PreferenceKeys.PREF_FAST_UNLOCK_OPTION, "disabled") ==
                          "fingerprint"
                      )
                        persistentPassphrases.edit { clear() }
                      // recover from invalidated AES key
                      AESEncryption.deleteKey(KeyType.PERSISTENT_WITH_AUTHENTICATION)
                      AESEncryption.getCipher(KeyType.PERSISTENT_WITH_AUTHENTICATION)
                    }
                } else null

              if (
                settings.getString(PreferenceKeys.PREF_FAST_UNLOCK_OPTION, "disabled") ==
                  "fingerprint" && cipher != null
              ) {
                fastUnlockingSetupCompletion = CompletableDeferred<Unit>()
                BiometricAuthenticator.authenticate(
                  this@BasePGPActivity,
                  dialogDescriptionRes =
                    R.string.biometric_prompt_description_persistently_cache_password,
                  cipher = cipher,
                ) { result ->
                  if (result is BiometricResult.Success) {
                    persistentPassphrases.edit {
                      putString(
                        id,
                        AESEncryption.encrypt(
                            passphrase,
                            keyType = KeyType.PERSISTENT_WITH_AUTHENTICATION,
                            cipher = result.cryptoObject?.cipher,
                          )
                          ?.concatToString(),
                      )
                      putLong(
                        PreferenceKeys.BIOMETRICS_AND_PIN_LAST_USE,
                        Instant.now().toEpochMilli(),
                      )
                    }
                  }
                  passphrase.wipe()
                  if (result !is BiometricResult.Retry) fastUnlockingSetupCompletion?.complete(Unit)
                }
              } else if (
                settings.getString(PreferenceKeys.PREF_FAST_UNLOCK_OPTION, "disabled") == "PIN" &&
                  AESEncryption.isHardwareBacked(KeyType.PERSISTENT)
              ) {
                /* Ask user for setting a PIN if not yet existing, encrypt and store it on the
                 * device, then update passphrase in cache */
                if (unlockPins.getString(id, null) == null) {
                  fastUnlockingSetupCompletion = CompletableDeferred<Unit>()
                  val pinDialog =
                    PinDialog.newInstance(
                      title = getString(R.string.pin_new_entry_title),
                      description = getString(R.string.pin_new_entry_description),
                    )
                  pinDialog.show(supportFragmentManager, "PIN_DIALOG")
                  pinDialog.setFragmentResultListener(PinDialog.PIN_RESULT_KEY) { key, bundle ->
                    if (key == PinDialog.PIN_RESULT_KEY) {
                      val pin = bundle.getCharArray(PinDialog.PIN_KEY)
                      if (pin != null && pin.size >= 4) {
                        unlockPins.edit {
                          putString(
                            id, // reset and prepend PIN attempt counter
                            AESEncryption.encrypt(
                                charArrayOf('0', ':') + pin,
                                keyType = KeyType.PERSISTENT,
                              )
                              ?.concatToString(),
                          )
                        }
                        persistentPassphrases.edit {
                          putString(
                            id,
                            AESEncryption.encrypt(passphrase, keyType = KeyType.PERSISTENT)
                              ?.concatToString(),
                          )
                          putLong(
                            PreferenceKeys.BIOMETRICS_AND_PIN_LAST_USE,
                            Instant.now().toEpochMilli(),
                          )
                        }
                        pin.wipe()
                      }
                    }
                    passphrase.wipe()
                    fastUnlockingSetupCompletion.complete(Unit)
                  }
                } else {
                  persistentPassphrases.edit {
                    putString(
                      id,
                      AESEncryption.encrypt(passphrase, keyType = KeyType.PERSISTENT)
                        ?.concatToString(),
                    )
                  }
                  passphrase.wipe()
                }
              } else {
                passphrase.wipe()
              }
            }
              .onErr { e ->
                logcat { e.asLog() }
                passphrase.wipe()
                fastUnlockingSetupCompletion?.complete(Unit)
              }
            fastUnlockingSetupCompletion?.await()
          }
        }
      }
    }
  }

  /* Find persistent PGP passphrases with matching key ID, unlock the first one
   * with biometrics or after PIN verification */
  protected fun getPersistentAndDecrypt(identifiers: List<PGPIdentifier>, action: String? = null) {
    // Detect AES key invalidation due to enrollment of a new fingerprint and emit warning
    if (
      BiometricAuthenticator.canAuthenticate(this@BasePGPActivity) &&
        AESEncryption.getCipher(KeyType.PERSISTENT_WITH_AUTHENTICATION) == null
    ) {
      MaterialAlertDialogBuilder(this@BasePGPActivity)
        .setTitle(R.string.aes_key_invalidated_dialog_title)
        .setMessage(R.string.aes_key_invalidated_dialog_message)
        .setIcon(R.drawable.ic_warning_red_24dp)
        .setPositiveButton(R.string.dialog_ok) { _, _ -> decrypt(identifiers) }
        .setCancelable(false)
        .show()
      return
    }

    // clear persistently cached passphrases if validity period for biometrics/PIN has expired
    val now = Instant.now().toEpochMilli()
    val biometrics_and_pin_last_use =
      persistentPassphrases.getLong(PreferenceKeys.BIOMETRICS_AND_PIN_LAST_USE, 0L)
    val biometrics_and_pin_timeout =
      settings.getString(PreferenceKeys.BIOMETRICS_AND_PIN_TIMEOUT)?.toLongOrNull()
        ?: Constants.DEFAULT_BIOMETRICS_AND_PIN_TIMEOUT.toLong()
    if (
      biometrics_and_pin_timeout > 0L &&
        now - biometrics_and_pin_last_use >= TimeUnit.DAYS.toMillis(biometrics_and_pin_timeout)
    ) {
      persistentPassphrases.edit { clear() }
      unlockPins.edit { clear() }
    }

    val persistentIds =
      identifiers.map(::passphraseCacheKey).filter(persistentPassphrases::contains)
    val encryptedPins =
      unlockPins
        .getAll()
        .filterKeys { persistentIds.contains(it) }
        .mapValues { (it.value as String).toCharArray() }
    if (
      !persistentIds.none() &&
        identifiers.map(::passphraseCacheKey).none(cachedPassphrases::containsKey) &&
        AESEncryption.isHardwareBacked(KeyType.PERSISTENT_WITH_AUTHENTICATION) &&
        settings.getString(PreferenceKeys.PREF_FAST_UNLOCK_OPTION, "disabled") == "fingerprint" &&
        BiometricAuthenticator.canAuthenticate(this@BasePGPActivity)
    ) {
      val id = persistentIds[0]
      val passEncrypted = persistentPassphrases.getString(id, null)?.toCharArray()
      val cipher = AESEncryption.getCipher(KeyType.PERSISTENT_WITH_AUTHENTICATION, passEncrypted)
      BiometricAuthenticator.authenticate(
        this@BasePGPActivity,
        dialogDescriptionRes = R.string.biometric_prompt_description_unlock_entry,
        cipher = cipher,
      ) { result ->
        if (result is BiometricResult.Success) {
          val passDecrypted = // decrypt persistently cached passphrase with biometrics
            AESEncryption.decrypt(
              passEncrypted,
              keyType = KeyType.PERSISTENT_WITH_AUTHENTICATION,
              cipher = result.cryptoObject?.cipher,
            )
          // re-encrypt passphrase without biometrics for use until screen-off
          val pass = AESEncryption.encrypt(passDecrypted)
          passDecrypted?.wipe()
          if (pass != null) cachedPassphrases.put(id, pass)
          persistentPassphrases.edit {
            putLong(PreferenceKeys.BIOMETRICS_AND_PIN_LAST_USE, Instant.now().toEpochMilli())
          }
        }
        if (result !is BiometricResult.Retry) decrypt(identifiers)
      }
    } else if (
      !encryptedPins.none() &&
        identifiers.map(::passphraseCacheKey).none(cachedPassphrases::containsKey) &&
        AESEncryption.isHardwareBacked(KeyType.PERSISTENT) &&
        settings.getString(PreferenceKeys.PREF_FAST_UNLOCK_OPTION, "disabled") == "PIN"
    ) {
      verifyPin(encryptedPins, identifiers, action)
    } else {
      decrypt(identifiers)
    }
  }

  /* Asks for and verifies the user PIN for unlocking a store entry. */
  private fun verifyPin(
    encryptedPins: Map<String, CharArray>,
    identifiers: List<PGPIdentifier>,
    action: String?,
    isError: Boolean = false,
  ) {
    val pinDialog =
      PinDialog.newInstance(
        title = getString(R.string.pin_entry_title),
        description =
          when (action) {
            "autofill" -> getString(R.string.pin_entry_autofill_description)
            "passkey" -> getString(R.string.pin_entry_passkey_description)
            else -> getString(R.string.pin_entry_description)
          },
      )
    if (isError) pinDialog.setError()
    pinDialog.show(supportFragmentManager, "PIN_DIALOG")
    pinDialog.setFragmentResultListener(PinDialog.PIN_RESULT_KEY) { key, bundle ->
      if (key == PinDialog.PIN_RESULT_KEY) {
        if (bundle.getBoolean(PinDialog.PIN_CANCEL))
          decrypt(identifiers) // decrypt with passphrase verification
        else {
          val pin =
            requireNotNull(bundle.getCharArray(PinDialog.PIN_KEY)) { "returned PIN is null" }
          var pinRetries = 0

          var pinOk = false
          // verify user-entered PIN against cached PINs
          for ((id, encryptedPin) in encryptedPins) {
            var cachedPin =
              AESEncryption.decrypt(encryptedPin, keyType = KeyType.PERSISTENT)?.let { cached ->
                cached.copyOfRange(cached.indexOf(':') + 1, cached.size).also {
                  pinRetries =
                    max(
                      pinRetries,
                      cached.copyOfRange(0, cached.indexOf(':')).concatToString().toIntOrNull()
                        ?: MAX_RETRIES,
                    )
                  cached?.wipe()
                }
              }
            pinOk = cachedPin?.let { it.contentEquals(pin) } ?: false
            cachedPin?.wipe()
            if (pinOk) {
              // PIN verifies successfully against one of the cached ones
              updatePinAttemptCounter(encryptedPins, 0) // reset attempt counter
              // re-encrypt and cache passphrase temporarily for use until screen-off
              persistentPassphrases
                .getString(id, null)
                ?.toCharArray()
                ?.let { passEncrypted ->
                  AESEncryption.decrypt(passEncrypted, keyType = KeyType.PERSISTENT)
                }
                ?.let { pass ->
                  AESEncryption.encrypt(pass)?.let {
                    cachedPassphrases.put(id, it)
                  }
                  pass.wipe()
                }
              break
            }
          }

          pin.wipe()

          if (pinOk) decrypt(identifiers)
          else {
            if (++pinRetries < MAX_RETRIES) { // try again
              val encryptedPinsUpdated = updatePinAttemptCounter(encryptedPins, pinRetries)
              verifyPin(encryptedPinsUpdated, identifiers, action, isError = true)
            } else {
              // reset PIN and cached passphrase(s) to prevent bruteforcing
              encryptedPins.keys.forEach { id ->
                cachedPassphrases.remove(id)
                persistentPassphrases.edit { remove(id) }
                unlockPins.edit { remove(id) }
              }
              decrypt(identifiers)
            }
          }
        }
      }
    }
  }

  // updates attempt counter and prepends it to the cached PINs
  private fun updatePinAttemptCounter(
    encryptedPins: Map<String, CharArray>,
    attempts: Int,
  ): Map<String, CharArray> {
    var updatedEncryptedPins = mutableMapOf<String, CharArray>()
    unlockPins.edit {
      encryptedPins.forEach { id, encryptedPin ->
        AESEncryption.decrypt(encryptedPin, keyType = KeyType.PERSISTENT)
          ?.let { cached ->
            cached.copyOfRange(cached.indexOf(':') + 1, cached.size).also { cached.wipe() }
          }
          ?.let { pin ->
            AESEncryption.encrypt(
                (attempts.toString() + ":").toCharArray() + pin,
                keyType = KeyType.PERSISTENT,
              )
              ?.let { updated ->
                putString(id, updated.concatToString())
                updatedEncryptedPins.put(id, updated)
              }
            pin?.wipe()
          }
          ?: run {
            remove(id)
          }
      }
    }
    if (attempts == 0)
      persistentPassphrases.edit {
        putLong(PreferenceKeys.BIOMETRICS_AND_PIN_LAST_USE, Instant.now().toEpochMilli())
      }
    return updatedEncryptedPins
  }

  protected fun decrypt(identifiers: List<PGPIdentifier>, isError: Boolean = false) {
    val passphrases = cachedPassphrases.filterKeys(identifiers.map(::passphraseCacheKey)::contains)
    // Which branch to take is decided by the keys that could open this without a card, as the
    // decryption itself decides it. Asking only whether a card is among the candidates sent an
    // entry that also has a local key down the PIN branch, which then handed the local path an
    // empty map of passphrases and no way to read it.
    val localIds = identifiers.filterNot {
      repository.hasOnlyStubDecKey(it) || repository.isSmartcardBacked(it)
    }
    lifecycleScope.launch(dispatcherProvider.main()) {
      if (localIds.isEmpty() && needsSmartcardPin(identifiers)) {
        // Smartcard PIN entry and retries are handled inline by the smartcard decrypt flow; just
        // pass any cached (e.g. biometric-unlocked) PIN through for the first attempt.
        val decryptedCachedPins = passphrases.mapValues {
          AESEncryption.decrypt(it.value) ?: charArrayOf()
        }
        decryptWithPassphrase(decryptedCachedPins, identifiers)
        decryptedCachedPins.values.forEach { it.wipe() }
      } else if (!repository.isPasswordProtected(localIds) && !isError) {
        // try passphraseless decryption first
        decryptWithPassphrase(mapOf("" to null), identifiers)
      } else if (!isError && !passphrases.isEmpty()) {
        // try cached passphrases
        val decryptedCachedPassphrases = passphrases.mapValues {
          AESEncryption.decrypt(it.value) ?: charArrayOf()
        }
        decryptWithPassphrase(decryptedCachedPassphrases, identifiers)
        decryptedCachedPassphrases.values.forEach { it.wipe() }
      } else {
        askPassphrase(isError, identifiers)
      }
    }
  }

  /** Subclass-specific implementations */
  open suspend fun decryptWithPassphrase(
    passphrases: Map<String, CharArray?>,
    identifiers: List<PGPIdentifier>,
    onSuccess: suspend (String) -> Unit = {},
  ) {}

  /* parses passkey from data in PasswordEntry's `password' field, returns either
   * a passkey or null if it's not passkey data but a password */
  protected fun retrievePasskey(
    entry: PasswordEntry,
    stripped: Boolean = false, // whether to wipe private key material
  ): PasskeyCredential? =
    entry.password
      ?.let {
        val cbor = it.b64Decode()
        cbor?.let { cb ->
          PasskeyCredential.fromCbor(cb).get().also { cb.wipe() }
        }
      }
      ?.also { if (stripped) it.clearPrivateKey() }

  companion object {

    const val MAX_RETRIES = 3
    const val EXTRA_FILE_PATH = "FILE_PATH"
    const val EXTRA_REPO_PATH = "REPO_PATH"

    /**
     * Temporary cache for PGP key passphrases, unconditionally nulled and cleared when the screen
     * is switched off. Passphrases stored here are AES encrypted with a key that is renewed when
     * the app is restarted.
     */
    val cachedPassphrases = mutableMapOf<String, CharArray>() // pgp id, passphrase

    /**
     * Newest Samsung phones now feature a history of >30 items. To err on the side of caution, push
     * 50 fake ones.
     */
    private const val CLIPBOARD_CLEAR_COUNT = 50
    var clearTimer: ScheduledExecutorService? = null
  }
}
