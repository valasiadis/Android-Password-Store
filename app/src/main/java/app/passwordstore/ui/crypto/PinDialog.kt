/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.crypto

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import app.passwordstore.R
import app.passwordstore.databinding.DialogPinEntryBinding
import app.passwordstore.ui.dialogs.InputSheet
import app.passwordstore.util.extensions.unsafeLazy

/** [DialogFragment] to request a PIN from the user and forward it along. */
class PinDialog : DialogFragment() {

  private val binding by unsafeLazy { DialogPinEntryBinding.inflate(layoutInflater) }
  private var isError: Boolean = false

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    isCancelable = false // disable tapping BACK / fragment cancel
  }

  override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
    binding.descriptionText.setText(requireArguments().getString(DESCRIPTION_TEXT_EXTRA))
    // A sheet rather than a dialog, as every question that wants something typed is asked.
    val sheet =
      InputSheet.create(
        context = requireContext(),
        title = requireArguments().getString(TITLE_TEXT_EXTRA),
        content = binding.root,
        primaryLabel = getString(R.string.dialog_ok),
        dismissOnPrimary = false,
      ) {
        setPinAndDismiss()
      }
    val dialog = sheet.dialog
    dialog.setCanceledOnTouchOutside(false)
    if (isError) binding.pinField.error = getString(R.string.pin_entry_wrong_input)
    sheet.primary.isEnabled = false
    var pinLength = 0
    binding.pinEditText.apply {
      doOnTextChanged { s, _, _, _ ->
        s?.let {
          sheet.primary.isEnabled = it.length >= 4
          pinLength = it.length
        }
        binding.pinField.error = null
      }
      setOnKeyListener { _, keyCode, _ ->
        if (keyCode == KeyEvent.KEYCODE_ENTER && pinLength >= 4) {
          setPinAndDismiss()
          return@setOnKeyListener true // return true to consume Enter
        }
        keyCode == KeyEvent.KEYCODE_BACK // return true to consume Back
      }
    }
    dialog.window?.setFlags(
      WindowManager.LayoutParams.FLAG_SECURE,
      WindowManager.LayoutParams.FLAG_SECURE,
    )
    return dialog
  }

  fun setError() {
    isError = true
  }

  override fun onDismiss(dialog: DialogInterface) {
    binding.pinEditText.text?.clear()
    super.onDismiss(dialog)
  }

  override fun onCancel(dialog: DialogInterface) {
    super.onCancel(dialog)
    setFragmentResult(PIN_RESULT_KEY, Bundle().also { it.putBoolean(PIN_CANCEL, true) })
  }

  private fun setPinAndDismiss() {
    val pin = binding.pinEditText.text?.let { CharArray(it.length) { i -> it[i] } }
    setFragmentResult(
      PIN_RESULT_KEY,
      Bundle().also {
        it.putCharArray(PIN_KEY, pin)
        it.putBoolean(PIN_CANCEL, false)
      },
    )
    dismissAllowingStateLoss()
  }

  companion object {

    private const val TITLE_TEXT_EXTRA = "title_text"
    private const val DESCRIPTION_TEXT_EXTRA = "description_text"

    const val PIN_RESULT_KEY = "pin_result"
    const val PIN_KEY = "pin"
    const val PIN_CANCEL = "cancel"

    fun newInstance(
      title: String,
      description: String,
    ): PinDialog {
      val extras =
        Bundle().also {
          it.apply {
            putString(TITLE_TEXT_EXTRA, title)
            putString(DESCRIPTION_TEXT_EXTRA, description)
          }
        }
      val fragment = PinDialog()
      fragment.arguments = extras
      return fragment
    }
  }
}
