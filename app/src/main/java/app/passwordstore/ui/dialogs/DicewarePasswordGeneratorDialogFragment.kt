/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.dialogs

import android.app.Dialog
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import androidx.core.content.edit
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.lifecycleScope
import app.passwordstore.R
import app.passwordstore.databinding.FragmentPwgenDicewareBinding
import app.passwordstore.passgen.diceware.DicewarePassphraseGenerator
import app.passwordstore.passgen.diceware.PasswordOption
import app.passwordstore.ui.crypto.PasswordCreationActivity
import app.passwordstore.util.crypto.AESEncryption
import app.passwordstore.util.crypto.AESEncryption.KeyType
import app.passwordstore.util.extensions.getString
import app.passwordstore.util.settings.PreferenceKeys.DICEWARE_LENGTH
import app.passwordstore.util.settings.PreferenceKeys.DICEWARE_SEPARATOR
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import reactivecircus.flowbinding.android.widget.afterTextChanges
import reactivecircus.flowbinding.android.widget.checkedChanges

@AndroidEntryPoint
class DicewarePasswordGeneratorDialogFragment : DialogFragment() {

  @Inject lateinit var dicewareGenerator: DicewarePassphraseGenerator
  lateinit var prefs: SharedPreferences

  override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
    prefs = requireContext().getSharedPreferences("PasswordGenerator", Context.MODE_PRIVATE)
    val binding = FragmentPwgenDicewareBinding.inflate(layoutInflater)
    binding.passwordSeparatorText.setText(
      AESEncryption.decrypt(
          prefs.getString(DICEWARE_SEPARATOR, null)?.toCharArray(),
          keyType = KeyType.PERSISTENT,
        )
        ?.let { String(it) } ?: "-"
    )
    binding.passwordLengthText.setText(
      AESEncryption.decrypt(
          prefs.getString(DICEWARE_LENGTH, null)?.toCharArray(),
          keyType = KeyType.PERSISTENT,
        )
        ?.let { String(it) } ?: "5"
    )

    binding.includeNumeral.isChecked = prefs.getBoolean(PasswordOption.Numeral.key, false)
    binding.capitalise.isChecked = prefs.getBoolean(PasswordOption.Capitalise.key, false)

    lifecycleScope.launch {
      merge(
          binding.includeNumeral.checkedChanges().skipInitialValue(),
          binding.capitalise.checkedChanges().skipInitialValue(),
          binding.passwordLengthText.afterTextChanges(),
          binding.passwordSeparatorText.afterTextChanges(),
        )
        .collect { _ -> generatePassword(binding) }
    }
    // A sheet rather than a dialog, as every question with fields is asked. Another password is
    // made from the button beside the one shown, so the footer keeps to the way out and the way on.
    binding.generated.regenerate.setOnClickListener { generatePassword(binding) }
    val sheet =
      InputSheet.create(
        context = requireContext(),
        title = R.string.pwgen_title,
        content = binding.root,
        primaryLabel = R.string.dialog_ok,
        showKeyboard = false,
      ) {
        setFragmentResult(
          PasswordCreationActivity.PASSWORD_RESULT_REQUEST_KEY,
          Bundle().also {
            it.putCharSequence(PasswordCreationActivity.RESULT, binding.generated.passwordText.text)
          },
        )
      }
    sheet.dialog.setOnShowListener { generatePassword(binding) }
    return sheet.dialog
  }

  private fun generatePassword(binding: FragmentPwgenDicewareBinding) {
    val length = binding.passwordLengthText.text?.toString()?.toIntOrNull() ?: 5
    val separator = binding.passwordSeparatorText.text?.toString()?.getOrNull(0) ?: '-'
    val includeNumeral = binding.includeNumeral.isChecked
    val capitalise = binding.capitalise.isChecked

    setPreferences(length, separator, includeNumeral, capitalise)
    binding.generated.passwordText.text =
      dicewareGenerator.generatePassphrase(length, separator, includeNumeral, capitalise)
  }

  private fun setPreferences(
    length: Int,
    separator: Char,
    includeNumeral: Boolean,
    capitalise: Boolean,
  ) {
    prefs.edit {
      putString(
        DICEWARE_LENGTH,
        AESEncryption.encrypt(length.toString().toCharArray(), keyType = KeyType.PERSISTENT)?.let {
          String(it)
        },
      )
      putString(
        DICEWARE_SEPARATOR,
        AESEncryption.encrypt(charArrayOf(separator), keyType = KeyType.PERSISTENT)?.let {
          String(it)
        },
      )
      putBoolean(PasswordOption.Numeral.key, includeNumeral)
      putBoolean(PasswordOption.Capitalise.key, capitalise)
    }
  }
}
