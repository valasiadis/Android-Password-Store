/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.passwordstore.ui.dialogs

import android.app.Dialog
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import app.passwordstore.R
import app.passwordstore.data.crypto.CryptoRepository
import app.passwordstore.databinding.FolderDialogFragmentBinding
import app.passwordstore.ui.folderselect.SelectFolderActivity
import app.passwordstore.ui.passwords.PasswordStore
import app.passwordstore.ui.pgp.PGPKeyListActivity
import app.passwordstore.ui.pgp.keyNames
import app.passwordstore.util.extensions.commitChange
import app.passwordstore.util.extensions.hideKeyboard
import app.passwordstore.util.extensions.isInsideRepository
import app.passwordstore.util.extensions.setInlineText
import app.passwordstore.util.extensions.unsafeLazy
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.launch

@AndroidEntryPoint
class FolderCreationDialogFragment : DialogFragment() {

  @Inject lateinit var repository: CryptoRepository

  private val binding by unsafeLazy { FolderDialogFragmentBinding.inflate(layoutInflater) }

  /**
   * The key the folder will be encrypted to, or null to inherit the one above it. Chosen in another
   * activity, which can take this one down behind it, so it is kept in saved state.
   */
  private var chosenKeyIds: String? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    chosenKeyIds = savedInstanceState?.getString(CHOSEN_KEYS_STATE)
  }

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    chosenKeyIds?.let { outState.putString(CHOSEN_KEYS_STATE, it) }
  }

  override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
    binding.gpgKeyContainer.isVisible = requireArguments().getBoolean(SET_GPG_KEY_EXTRA)
    val chooseKeys = View.OnClickListener {
      // The dialog opens with the keyboard up for the name field; it has no business staying
      // over the screen that opens next.
      requireActivity().hideKeyboard()
      gpgKeySelectAction.launch(
        PGPKeyListActivity.newIntent(
          requireContext(),
          keySelection = true,
          preselectedKeyIds = chosenKeyIds,
          offerInherit = true,
        )
      )
    }
    // The field takes the taps; the end icon needs its own, having consumed them itself.
    binding.gpgKeyValue.setOnClickListener(chooseKeys)
    binding.gpgKeyContainer.setEndIconOnClickListener(chooseKeys)
    showChosenKeys()
    // A sheet rather than a dialog, as every question that wants something typed is asked. Creating
    // closes it only once the folder exists, so a name that cannot be used leaves it open.
    val sheet =
      InputSheet.create(
        context = requireContext(),
        title = R.string.title_create_folder,
        content = binding.root,
        primaryLabel = R.string.button_create,
        dismissOnPrimary = false,
      ) {
        createDirectory(
          requireArguments().getString(CURRENT_DIR_EXTRA) ?: throw NullPointerException()
        )
      }
    sheet.primary.isEnabled = false
    binding.folderNameText.doOnTextChanged { s, _, _, _ ->
      s?.let { sheet.primary.isEnabled = it.isNotEmpty() }
    }
    return sheet.dialog
  }

  private val gpgKeySelectAction =
    registerForActivityResult(StartActivityForResult()) { result ->
      if (result.resultCode == AppCompatActivity.RESULT_OK) {
        // An empty selection is the choice of having no key of one's own.
        chosenKeyIds =
          result.data?.getStringExtra(PGPKeyListActivity.EXTRA_SELECTED_KEY)?.takeIf {
            it.isNotEmpty()
          }
        showChosenKeys()
      }
    }

  private fun showChosenKeys() {
    val names = repository.keyNames(chosenKeyIds)
    binding.gpgKeyValue.setInlineText(
      if (names.isEmpty()) getString(R.string.folder_encryption_key_inherited)
      else names.joinToString(separator = ", ")
    )
  }

  private fun createDirectory(currentDir: String) {
    val dialog = requireDialog()
    val folderNameView = dialog.findViewById<TextInputEditText>(R.id.folder_name_text)
    val folderNameViewContainer = dialog.findViewById<TextInputLayout>(R.id.folder_name_container)
    val newFolder = File("$currentDir/${folderNameView.text}")
    folderNameViewContainer.error =
      when {
        !newFolder.isInsideRepository() ->
          getString(R.string.message_error_destination_outside_repo)
        newFolder.isFile -> getString(R.string.folder_creation_err_file_exists)
        newFolder.isDirectory -> getString(R.string.folder_creation_err_folder_exists)
        else -> null
      }
    if (folderNameViewContainer.error != null) return
    newFolder.mkdirs()
    // The key is picked before the folder is made, so a folder is never left behind half
    // configured by a cancelled or failed key selection.
    val keyIds = chosenKeyIds
    if (keyIds == null) {
      refreshHost(newFolder)
      dismiss()
      return
    }
    File(newFolder, ".gpg-id").writeText(keyIds + "\n")
    // Committing can ask for a signing passphrase, so it stays on the main thread — but it no
    // longer blocks it, as runBlocking did, freezing the screen for the length of a commit.
    lifecycleScope.launch {
      activity?.commitChange(getString(R.string.git_commit_gpg_id, getString(R.string.app_name)))
      refreshHost(newFolder)
      dismissAllowingStateLoss()
    }
  }

  private fun refreshHost(folder: File) {
    when (val host = activity) {
      is PasswordStore -> host.refreshPasswordList(folder)
      is SelectFolderActivity -> host.refreshPasswordList(folder)
      else -> Unit
    }
  }

  companion object {

    private const val CURRENT_DIR_EXTRA = "CURRENT_DIRECTORY"
    private const val CHOSEN_KEYS_STATE = "CHOSEN_KEYS"
    private const val SET_GPG_KEY_EXTRA = "SET_GPG_KEY"

    fun newInstance(
      startingDirectory: String,
      setGpgKey: Boolean = false,
    ): FolderCreationDialogFragment {
      val extras =
        Bundle().also {
          it.apply {
            putString(CURRENT_DIR_EXTRA, startingDirectory)
            putBoolean(SET_GPG_KEY_EXTRA, setGpgKey)
          }
        }
      val fragment = FolderCreationDialogFragment()
      fragment.arguments = extras
      return fragment
    }
  }
}
