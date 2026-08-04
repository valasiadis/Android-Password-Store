/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.crypto

import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.InputType
import android.view.MenuItem
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.core.content.edit
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import app.passwordstore.R
import app.passwordstore.crypto.PGPIdentifier
import app.passwordstore.crypto.errors.NoKeysProvidedException
import app.passwordstore.crypto.errors.UnusableKeyException
import app.passwordstore.data.passfile.PasswordEntry
import app.passwordstore.data.passfile.joinToCharArray
import app.passwordstore.data.passfile.splitToCharArrayListAt
import app.passwordstore.data.passfile.trimEnd
import app.passwordstore.data.repo.PasswordRepository
import app.passwordstore.databinding.PasswordCreationActivityBinding
import app.passwordstore.injection.prefs.PasswordHistory
import app.passwordstore.ui.dialogs.DicewarePasswordGeneratorDialogFragment
import app.passwordstore.ui.dialogs.ErrorDialog
import app.passwordstore.ui.dialogs.Notice
import app.passwordstore.ui.dialogs.OtpImportDialogFragment
import app.passwordstore.ui.dialogs.PasswordGeneratorDialogFragment
import app.passwordstore.ui.folderselect.SelectFolderActivity
import app.passwordstore.ui.passwords.PasswordStore
import app.passwordstore.util.autofill.AutofillPreferences
import app.passwordstore.util.crypto.AESEncryption
import app.passwordstore.util.extensions.asLog
import app.passwordstore.util.extensions.base64
import app.passwordstore.util.extensions.enableEdgeToEdgeView
import app.passwordstore.util.extensions.getString
import app.passwordstore.util.extensions.isInsideRepository
import app.passwordstore.util.extensions.toByteArray
import app.passwordstore.util.extensions.unsafeLazy
import app.passwordstore.util.extensions.viewBinding
import app.passwordstore.util.extensions.wipe
import app.passwordstore.util.settings.DirectoryStructure
import app.passwordstore.util.settings.PreferenceKeys
import com.github.michaelbull.result.getOrThrow
import com.github.michaelbull.result.onErr
import com.github.michaelbull.result.runCatching
import com.github.michaelbull.result.unwrapError
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.zxing.BinaryBitmap
import com.google.zxing.LuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.integration.android.IntentIntegrator
import com.google.zxing.integration.android.IntentIntegrator.QR_CODE
import com.google.zxing.qrcode.QRCodeReader
import dagger.hilt.android.AndroidEntryPoint
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.CharBuffer
import java.nio.file.Paths
import javax.inject.Inject
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.pathString
import kotlin.io.path.writeBytes
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority.ERROR
import logcat.asLog
import logcat.logcat

@AndroidEntryPoint
class PasswordCreationActivity : BasePGPActivity() {

  @PasswordHistory @Inject lateinit var passwordHistory: SharedPreferences

  private val binding by viewBinding(PasswordCreationActivityBinding::inflate)
  @Inject lateinit var passwordEntryFactory: PasswordEntry.Factory

  private val suggestedName by unsafeLazy { intent.getStringExtra(EXTRA_FILE_NAME) }
  private val suggestedEntryChars by unsafeLazy { intent.getCharArrayExtra(EXTRA_ENTRY) }
  private val shouldGeneratePassword by unsafeLazy {
    intent.getBooleanExtra(EXTRA_GENERATE_PASSWORD, false)
  }
  private val editing by unsafeLazy { intent.getBooleanExtra(EXTRA_EDITING, false) }

  private val otpImportAction =
    registerForActivityResult(StartActivityForResult()) { result ->
      if (result.resultCode == RESULT_OK) {
        binding.otpImportButton.isVisible = false
        val intentResult = IntentIntegrator.parseActivityResult(RESULT_OK, result.data)
        val contents = "${intentResult.contents}\n"
        binding.extraContent.text?.let { currentExtras ->
          if (currentExtras.isNotEmpty() && currentExtras.last() != '\n')
            binding.extraContent.append("\n$contents")
          else binding.extraContent.append(contents)
        }
        Notice.show(this@PasswordCreationActivity, R.string.otp_import_success, success = true)
      } else {
        ErrorDialog.show(this@PasswordCreationActivity, R.string.otp_import_failure_generic)
      }
    }

  private val imageImportAction =
    registerForActivityResult(ActivityResultContracts.GetContent()) { imageUri ->
      if (imageUri == null) {
        ErrorDialog.show(this@PasswordCreationActivity, R.string.otp_import_failure_no_selection)
        return@registerForActivityResult
      }
      val bitmap =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
          ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, imageUri))
            .copy(Bitmap.Config.ARGB_8888, true)
        } else {
          @Suppress("DEPRECATION") MediaStore.Images.Media.getBitmap(contentResolver, imageUri)
        }
      val intArray = IntArray(bitmap.width * bitmap.height)
      // copy pixel data from the Bitmap into the 'intArray' array
      bitmap.getPixels(intArray, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
      val source: LuminanceSource = RGBLuminanceSource(bitmap.width, bitmap.height, intArray)
      val binaryBitmap = BinaryBitmap(HybridBinarizer(source))

      val reader = QRCodeReader()
      runCatching {
        val result = reader.decode(binaryBitmap)
        val text = result.text
        binding.extraContent.text?.let { currentExtras ->
          if (currentExtras.isNotEmpty() && currentExtras.last() != '\n')
            binding.extraContent.append("\n$text")
          else binding.extraContent.append(text)
        }
        Notice.show(this@PasswordCreationActivity, R.string.otp_import_success, success = true)
        binding.otpImportButton.isVisible = false
      }
        .onErr {
          ErrorDialog.show(this@PasswordCreationActivity, R.string.otp_import_failure_generic)
        }
    }

  override fun onDestroy() {
    with(binding) {
      username.text?.clear()
      password.text?.clear()
      extraContent.text?.clear()
    }
    super.onDestroy()
  }

  private val selectFolderAction =
    registerForActivityResult(StartActivityForResult()) { result ->
      if (result.resultCode == RESULT_OK) {
        result.data?.getStringExtra(SelectFolderActivity.SELECTED_FOLDER_PATH)?.let { fullPath ->
          val relPath = PasswordRepository.getRelativePath(fullPath, repoPath)
          binding.directory.setText(if (!relPath.isEmpty()) relPath else "/")
        }
      }
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    supportActionBar?.setDisplayHomeAsUpEnabled(true)
    title =
      if (editing) getString(R.string.edit_password) else getString(R.string.new_password_title)
    with(binding) {
      enableEdgeToEdgeView(root)
      setContentView(root)

      saveFab.setOnClickListener { save() }
      generatePassword.setOnClickListener { generatePassword() }
      otpImportButton.setOnClickListener {
        supportFragmentManager.setFragmentResultListener(
          OTP_RESULT_REQUEST_KEY,
          this@PasswordCreationActivity,
        ) { requestKey, bundle ->
          if (requestKey == OTP_RESULT_REQUEST_KEY) {
            val contents = bundle.getString(RESULT)
            extraContent.text?.let { currentExtras ->
              if (currentExtras.isNotEmpty() && currentExtras.last() != '\n')
                extraContent.append("\n$contents")
              else extraContent.append(contents)
            }
          }
        }
        val hasCamera = packageManager?.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) == true
        if (hasCamera) {
          val items =
            arrayOf(
              getString(R.string.otp_import_qr_code),
              getString(R.string.otp_import_from_file),
              getString(R.string.otp_import_manual_entry),
            )
          MaterialAlertDialogBuilder(this@PasswordCreationActivity)
            .setItems(items) { _, index ->
              when (index) {
                0 ->
                  otpImportAction.launch(
                    IntentIntegrator(this@PasswordCreationActivity)
                      .setOrientationLocked(false)
                      .setBeepEnabled(false)
                      .setDesiredBarcodeFormats(QR_CODE)
                      .createScanIntent()
                  )
                1 -> {
                  runCatching { imageImportAction.launch("image/*") }
                    .onErr { e ->
                      logcat(ERROR) { e.asLog() }
                      e.message?.let { message ->
                        ErrorDialog.show(this@PasswordCreationActivity, message)
                      }
                    }
                }
                2 -> OtpImportDialogFragment().show(supportFragmentManager, "OtpImport")
              }
            }
            .show()
        } else {
          OtpImportDialogFragment().show(supportFragmentManager, "OtpImport")
        }
      }

      val suggestedEntry: PasswordEntry? = suggestedEntryChars?.let { encrypted ->
        AESEncryption.decrypt(encrypted)?.let { decrypted ->
          passwordEntryFactory.create(decrypted).also { decrypted.wipe() }
        }
      }

      directory.inputType = InputType.TYPE_NULL
      val relPath = PasswordRepository.getRelativePath(fullPath, repoPath)
      directory.setText(if (relPath.isEmpty()) "/" else relPath)

      directory.setOnClickListener {
        val intent = Intent(this@PasswordCreationActivity, SelectFolderActivity::class.java)
        intent.putExtra(PasswordStore.REQUEST_ARG_PATH, directory.text.toString().trimEnd('/'))
        selectFolderAction.launch(intent)
      }

      if (suggestedName != null) {
        filename.setText(suggestedName)
      } else {
        filename.requestFocus()
      }

      if (
        AutofillPreferences.directoryStructure(this@PasswordCreationActivity) ==
          DirectoryStructure.EncryptedUsername || suggestedEntry?.username != null
      ) {
        usernameInputLayout.visibility = View.VISIBLE
        if (suggestedEntry?.username != null) {
          val charBuf = CharBuffer.wrap(suggestedEntry?.username)
          username.setText(charBuf)
          charBuf.array().wipe()
        } else if (suggestedName != null) username.requestFocus()
      }

      // Allow the user to quickly switch between storing the username as the filename or
      // in the encrypted extras. This only makes sense if the directory structure is
      // FileBased.
      if (
        suggestedName == null &&
          AutofillPreferences.directoryStructure(this@PasswordCreationActivity) ==
            DirectoryStructure.FileBased
      ) {
        encryptUsername.apply {
          visibility = View.VISIBLE
          setOnClickListener {
            if (isChecked) {
              // User wants to enable username encryption, so we use the filename
              // as username and insert it into the username input field.
              val login = filename.text.toString()
              filename.text?.clear()
              username.setText(login)
              usernameInputLayout.apply { visibility = View.VISIBLE }
            } else {
              // User wants to disable username encryption, so we take the username
              // from the username text field and insert it into the filename input field.
              val login = username.text.toString()
              username.text?.clear()
              filename.setText(login)
              usernameInputLayout.apply { visibility = View.GONE }
            }
          }
        }
      }
      suggestedEntry?.password?.let {
        val charBuf = CharBuffer.wrap(it)
        password.setText(charBuf)
        charBuf.array()?.wipe()
        password.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
      }
      suggestedEntry?.extraContentChars?.let {
        val charBuf =
          if (it.last() == '\n') CharBuffer.wrap(it.copyOfRange(0, it.size - 1))
          else CharBuffer.wrap(it)
        extraContent.setText(charBuf)
        charBuf.array().wipe()
      }
      suggestedEntry?.clear()
      if (shouldGeneratePassword) {
        generatePassword()
        password.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
      }
    }
    listOf(binding.filename, binding.username, binding.extraContent).forEach {
      it.doAfterTextChanged { updateViewState() }
    }
    updateViewState()
  }

  /** Encrypts what has been typed, or says why there is nowhere to put it. */
  private fun save() {
    if (PasswordRepository.isEmpty()) {
      MaterialAlertDialogBuilder(this)
        .setCancelable(false)
        .setTitle(R.string.error)
        .setIcon(R.drawable.ic_crossmark_red_24dp)
        .setMessage(R.string.creation_dialog_text)
        .setPositiveButton(R.string.dialog_ok) { _, _ ->
          setResult(RESULT_CANCELED)
          finish()
        }
        .show()
      return
    }
    requireKeysExist {
      requireEncryptionKeysExist(binding.directory.text.toString()) { ids -> encrypt(ids) }
    }
  }

  override fun onOptionsItemSelected(item: MenuItem): Boolean {
    when (item.itemId) {
      android.R.id.home -> {
        setResult(RESULT_CANCELED)
        onBackPressedDispatcher.onBackPressed()
      }
      else -> return super.onOptionsItemSelected(item)
    }
    return true
  }

  private fun generatePassword() {
    supportFragmentManager.setFragmentResultListener(PASSWORD_RESULT_REQUEST_KEY, this) {
      requestKey,
      bundle ->
      if (requestKey == PASSWORD_RESULT_REQUEST_KEY) {
        binding.password.setText(bundle.getCharSequence(RESULT))
      }
    }
    when (settings.getString(PreferenceKeys.PREF_KEY_PWGEN_TYPE) ?: KEY_PWGEN_TYPE_DICEWARE) {
      KEY_PWGEN_TYPE_CLASSIC ->
        PasswordGeneratorDialogFragment().show(supportFragmentManager, "generator")
      KEY_PWGEN_TYPE_DICEWARE ->
        DicewarePasswordGeneratorDialogFragment().show(supportFragmentManager, "generator")
    }
  }

  private fun updateViewState() =
    with(binding) {
      encryptUsername.apply {
        if (visibility != View.VISIBLE) return@apply
        val hasUsernameInFileName = filename.text.toString().isNotBlank()
        val usernameIsEncrypted = username.text.toString().isNotEmpty()
        isEnabled = hasUsernameInFileName xor usernameIsEncrypted
        isChecked = usernameIsEncrypted
      }
      // Use PasswordEntry to parse extras for OTP
      val entry = passwordEntryFactory.create("PLACEHOLDER\n${extraContent.text}".toCharArray())
      val hasTotp = entry.hasTotp()
      entry.clear()
      otpImportButton.isVisible = !hasTotp
    }

  /** Encrypts the password and the extra content */
  /** Opens a newly written entry on the copy kept from writing it, rather than decrypting it. */
  private fun openSavedEntry(path: String, savedEntry: CharArray?, commitMessage: String) {
    startActivity(
      Intent(this, DecryptActivity::class.java)
        .putExtra(EXTRA_FILE_PATH, path)
        .putExtra(EXTRA_REPO_PATH, repoPath)
        .putExtra(EXTRA_ENTRY, savedEntry)
        .putExtra(RETURN_EXTRA_MESSAGE, savedMessage)
        .putExtra(RETURN_EXTRA_COMMIT_MESSAGE, commitMessage)
    )
  }

  /** What the screen that follows should say about the save that just happened. */
  private var savedMessage: String? = null

  private fun encrypt(identifiers: List<PGPIdentifier>) {
    with(binding) {
      val editName = filename.text.toString().trim()
      var editUsername = username.text?.let { CharArray(it.length) { i -> it[i] } } ?: charArrayOf()
      val editPass = password.text?.let { CharArray(it.length) { i -> it[i] } } ?: charArrayOf()
      var editExtra =
        extraContent.text?.let { CharArray(it.length) { i -> it[i] } } ?: charArrayOf()

      if (editName.isEmpty()) {
        ErrorDialog.show(this@PasswordCreationActivity, R.string.file_toast_text)
        return@with
      } else if (editName.contains('/')) {
        ErrorDialog.show(this@PasswordCreationActivity, R.string.invalid_filename_text)
        return@with
      }

      if (!editUsername.isEmpty()) {
        editUsername = editUsername.let {
          val withPrefix = "\nusername: ".toCharArray() + it
          it.wipe()
          withPrefix
        }
      }

      if (editPass.isEmpty() && editExtra.isEmpty()) {
        ErrorDialog.show(this@PasswordCreationActivity, R.string.empty_toast_text)
        return@with
      }

      // fix extra content formatting
      if (!editExtra.isEmpty()) {
        editExtra = editExtra.let {
          val extraLines = it.splitToCharArrayListAt('\n').map { it.trimEnd() }
          it?.wipe()
          val editExtra = extraLines.joinToCharArray('\n')?.trimEnd()
          val editExtraPlusLineFeed = editExtra?.let { it + '\n' }
          editExtra?.wipe()
          editExtraPlusLineFeed ?: charArrayOf()
        }
      }

      // An entry can be encrypted to keys of its own, and editing it is no reason to drop them
      // back to whatever the folder's .gpg-id says. The folder decides only where the entry has
      // none of its own — a new entry, or one written before any of this.
      val entryKeys =
        if (editing && suggestedName != null) {
          val previous = File("${fullPath.trimEnd('/')}/$suggestedName.gpg")
          if (previous.isFile) {
            previous
              .inputStream()
              .use { repository.recipientKeyIds(it) }
              .mapNotNull { repository.getLongKeyIdFromKeyId(it) }
              .mapNotNull(PGPIdentifier::fromString)
              .distinct()
          } else emptyList()
        } else emptyList()
      // pass enters the key ID into `.gpg-id`.
      val gpgIdentifiers = entryKeys.ifEmpty { getPGPIdentifiers(directory.text.toString()) }
      if (gpgIdentifiers.isNullOrEmpty()) return@with

      val path = run { // password item's full file path string
        val editRelativePath = directory.text.toString().trim()
        val passwordDirectory = Paths.get(repoPath, editRelativePath.trim('/'))
        passwordDirectory.createDirectories() // ensure destination dir exists
        if (!passwordDirectory.exists()) { // should not happen
          ErrorDialog.show(
            this@PasswordCreationActivity,
            "Failed to create directory ${editRelativePath.trimEnd('/')}",
          )
          return
        }

        "${passwordDirectory.pathString}/$editName.gpg"
      }

      lifecycleScope.launch(dispatcherProvider.main()) {
        runCatching {
          val contentChars = (editPass + editUsername + '\n' + editExtra)
          val contentBytes = contentChars.toByteArray()
          contentChars.wipe()
          val savedEntry = AESEncryption.encrypt(contentBytes)

          val (succeededUserEmails, result) =
            withContext(dispatcherProvider.io()) {
              repository.encrypt(
                gpgIdentifiers,
                ByteArrayInputStream(contentBytes),
                ByteArrayOutputStream(),
              )
            }
          contentBytes.wipe()

          if (result.isErr) throw result.unwrapError()
          if (succeededUserEmails.isNullOrEmpty()) throw UnusableKeyException

          val failedUserEmails =
            gpgIdentifiers
              .map { id ->
                repository.getEmailFromKeyId(id)
                  ?: run {
                    if (!repository.hasKey(id))
                      "\n${id}: ${getString(R.string.pgp_unknown_key_identifier)}"
                    else
                      "\n${id}: ${getString(R.string.password_creation_file_encryption_failed_expired_key)}"
                  }
              }
              .distinct()
              .filter { it !in succeededUserEmails ?: emptyList() }

          val passwordFile = Paths.get(path)
          // If we're not editing, this file should not already exist!
          // Additionally, if we were editing and the incoming and outgoing
          // file paths differ, it means we renamed. Ensure that the target
          // doesn't already exist to prevent an accidental overwrite.
          if (
            (!editing ||
              (editing &&
                "${fullPath.trimEnd('/')}/$suggestedName.gpg" !=
                  passwordFile.absolutePathString())) && passwordFile.exists()
          ) {
            ErrorDialog.show(
              this@PasswordCreationActivity,
              R.string.password_creation_duplicate_error,
            )
            return@runCatching
          }

          if (!passwordFile.toFile().isInsideRepository()) {
            ErrorDialog.show(
              this@PasswordCreationActivity,
              R.string.message_error_destination_outside_repo,
            )
            return@runCatching
          }

          // Renaming an entry moves it: the file it was is the file it becomes, so the old name
          // goes away with the same save rather than lingering as a second copy of the entry.
          val renamedFrom =
            suggestedName
              ?.takeIf { editing }
              ?.let { File("${fullPath.trimEnd('/')}/$it.gpg") }
              ?.takeIf { it.isFile && it.absolutePath != passwordFile.absolutePathString() }

          val newFilePathHash = passwordFile.absolutePathString().base64()
          val oldFilePathHash = suggestedName?.let { oldFile ->
            "${fullPath.trimEnd('/')}/$oldFile.gpg".base64()
          }

          withContext(dispatcherProvider.io()) {
            passwordFile.writeBytes(result.getOrThrow().toByteArray())
            renamedFrom?.delete()
          }

          // create/update timestamp on the current password file
          passwordHistory.edit {
            oldFilePathHash?.let(::remove)
            putString(newFilePathHash, System.currentTimeMillis().toString())
          }

          val returnIntent = Intent()
          returnIntent.putExtra(RETURN_EXTRA_CREATED_FILE, path)
          returnIntent.putExtra(RETURN_EXTRA_NAME, editName)
          returnIntent.putExtra(
            RETURN_EXTRA_LONG_NAME,
            PasswordRepository.getLongName(fullPath, repoPath, editName),
          )
          // The screen that asked for this edit shows the entry: handing back the copy just
          // written lets it show the new one without decrypting it again — which for an entry
          // whose only key is on a smartcard means asking for the card a second time.
          returnIntent.putExtra(EXTRA_ENTRY, savedEntry)

          if (shouldGeneratePassword) {
            val directoryStructure = AutofillPreferences.directoryStructure(applicationContext)
            val entry = passwordEntryFactory.create(editPass + editUsername + '\n' + editExtra)

            entry.password?.let {
              val password = it.copyOf(it.size)
              returnIntent.putExtra(RETURN_EXTRA_PASSWORD, password)
            }

            val username =
              entry.username?.let { it.copyOf(it.size) }
                ?: directoryStructure.getUsernameFor(passwordFile.toFile())
            returnIntent.putExtra(RETURN_EXTRA_USERNAME, username)

            entry.clear()
          }

          // Committing is left to the screen this one returns to, which does it in the background
          // while the entry is already on show. Waiting for git here held the editor open over the
          // entry it had just written, for as long as a commit takes — signing prompt and all.
          val commitMessageRes =
            if (editing) R.string.git_commit_edit_text else R.string.git_commit_add_text
          val commitMessage =
            resources.getString(
              commitMessageRes,
              PasswordRepository.getLongName(fullPath, repoPath, editName),
            )

          editPass?.wipe()
          editUsername?.wipe()
          editExtra?.wipe()
          // A new entry opens on itself, so the password can be copied or read without finding it
          // in the list again. Editing returns to the entry it came from instead, which is already
          // behind this screen.
          // Whoever ends up in front does the committing, and only one of them: a new entry is
          // opened here and hands it that screen, while an edit goes back to the entry it came
          // from, which is waiting behind this one.
          val leave = {
            if (editing) returnIntent.putExtra(RETURN_EXTRA_COMMIT_MESSAGE, commitMessage)
            setResult(RESULT_OK, returnIntent)
            if (!editing) {
              openSavedEntry(passwordFile.absolutePathString(), savedEntry, commitMessage)
            }
            finish()
          }
          if (failedUserEmails.isEmpty()) {
            // Nothing went wrong, so it is said in passing — on whichever screen comes next, since
            // this one is on its way out and would take the message with it.
            val message =
              encryptionOutcomeMessage(succeededUserEmails, failedUserEmails, true).toString()
            returnIntent.putExtra(RETURN_EXTRA_MESSAGE, message)
            savedMessage = message
            leave()
            return@runCatching
          }
          MaterialAlertDialogBuilder(this@PasswordCreationActivity)
            .setCancelable(false)
            .setPositiveButton(android.R.string.ok) { _, _ -> leave() }
            .setTitle(R.string.password_creation_file_encryption_partial_success_title)
            .setMessage(encryptionOutcomeMessage(succeededUserEmails, failedUserEmails))
            .show()
        }
          .onErr { e ->
            logcat(ERROR) { e.asLog() }
            setResult(RESULT_CANCELED)
            val errMessage =
              when (e) {
                is IOException -> getString(R.string.password_creation_file_write_fail_message)
                is NoKeysProvidedException ->
                  getString(R.string.password_creation_no_keys_provided_message)
                is UnusableKeyException ->
                  getString(R.string.password_creation_unusable_encryption_key_error_message)
                else -> e.message ?: e.toString()
              }
            MaterialAlertDialogBuilder(this@PasswordCreationActivity)
              .setIcon(R.drawable.ic_crossmark_red_24dp)
              .setTitle(getString(R.string.error))
              .setMessage(errMessage)
              .setCancelable(false)
              .setPositiveButton(android.R.string.ok) { _, _ -> finish() }
              .show()
          }
      }
    }
  }

  companion object {

    private const val KEY_PWGEN_TYPE_CLASSIC = "classic"
    private const val KEY_PWGEN_TYPE_DICEWARE = "diceware"
    const val PASSWORD_RESULT_REQUEST_KEY = "PASSWORD_GENERATOR"
    const val OTP_RESULT_REQUEST_KEY = "OTP_IMPORT"
    const val RESULT = "RESULT"
    const val RETURN_EXTRA_CREATED_FILE = "CREATED_FILE"

    /** What the screen this returns to should commit, once it is showing the saved entry. */
    const val RETURN_EXTRA_COMMIT_MESSAGE = "COMMIT_MESSAGE"
    const val RETURN_EXTRA_MESSAGE = "SAVE_MESSAGE"
    const val RETURN_EXTRA_NAME = "NAME"
    const val RETURN_EXTRA_LONG_NAME = "LONG_NAME"
    const val RETURN_EXTRA_USERNAME = "USERNAME"
    const val RETURN_EXTRA_PASSWORD = "PASSWORD"
    const val EXTRA_FILE_NAME = "EXTRA_FILENAME"
    const val EXTRA_ENTRY = "EXTRA_ENTRY"
    const val EXTRA_GENERATE_PASSWORD = "EXTRA_GENERATE_PASSWORD"
    const val EXTRA_EDITING = "EXTRA_EDITING"
  }
}
