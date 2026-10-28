/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.dialogs

import android.app.Dialog
import android.net.Uri
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import app.passwordstore.R
import app.passwordstore.databinding.FragmentManualOtpEntryBinding
import app.passwordstore.ui.crypto.PasswordCreationActivity

class OtpImportDialogFragment : DialogFragment() {

  override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
    val binding = FragmentManualOtpEntryBinding.inflate(layoutInflater)
    // A sheet rather than a dialog, as every question that wants something typed is asked.
    return InputSheet.create(
        context = requireContext(),
        title = R.string.add_otp,
        content = binding.root,
        primaryLabel = R.string.dialog_ok,
      ) {
        setFragmentResult(
          PasswordCreationActivity.OTP_RESULT_REQUEST_KEY,
          Bundle().also { it.putString(PasswordCreationActivity.RESULT, getTOTPUri(binding)) },
        )
      }
      .dialog
  }

  private fun getTOTPUri(binding: FragmentManualOtpEntryBinding): String {
    val secret = binding.secret.text.toString()
    val account = binding.account.text.toString()
    if (secret.isBlank()) return ""
    val builder = Uri.Builder()
    builder.scheme("otpauth")
    builder.authority("totp")
    builder.appendQueryParameter("secret", secret)
    if (account.isNotBlank()) builder.appendQueryParameter("issuer", account)
    return builder.build().toString()
  }
}
