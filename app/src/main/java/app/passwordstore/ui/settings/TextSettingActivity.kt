/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.passwordstore.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.MenuItem
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.view.isVisible
import androidx.core.view.updatePaddingRelative
import androidx.core.widget.doOnTextChanged
import app.passwordstore.databinding.ActivityTextSettingBinding
import app.passwordstore.util.extensions.enableEdgeToEdgeView
import app.passwordstore.util.extensions.sharedPrefs
import app.passwordstore.util.extensions.viewBinding

/**
 * A setting whose value is typed, on a page of its own, as the system settings give one: the field
 * opened on what is stored and with the keyboard up, what the setting does beneath it, and the
 * value stored as it is typed, so leaving the page is all there is to do.
 */
class TextSettingActivity : AppCompatActivity() {

  private val binding by viewBinding(ActivityTextSettingBinding::inflate)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdgeView(binding.root)
    setContentView(binding.root)
    supportActionBar?.setDisplayHomeAsUpEnabled(true)
    val key = requireNotNull(intent.getStringExtra(EXTRA_KEY)) { "No setting to edit was named" }
    setTitle(intent.getIntExtra(EXTRA_TITLE, 0))

    with(binding) {
      val hint = intent.resource(EXTRA_HINT)
      hint?.let { settingFieldLayout.hint = getString(it) }
      // The room above the text is where a label floats to; a field with no label centres its text.
      if (hint == null) {
        settingField.updatePaddingRelative(top = settingField.paddingBottom * 2)
        settingField.updatePaddingRelative(bottom = settingField.paddingTop)
      }
      intent.resource(EXTRA_PLACEHOLDER)?.let { settingFieldLayout.placeholderText = getString(it) }
      intent.resource(EXTRA_SUFFIX)?.let { settingFieldLayout.suffixText = getString(it) }
      footer.root.isVisible = intent.resource(EXTRA_FOOTER) != null
      intent.resource(EXTRA_FOOTER)?.let { footer.footerText.setText(it) }
      settingField.inputType = intent.getIntExtra(EXTRA_INPUT_TYPE, InputType.TYPE_CLASS_TEXT)
      if (savedInstanceState == null) {
        settingField.setText(sharedPrefs.getString(key, intent.getStringExtra(EXTRA_DEFAULT)))
        settingField.setSelection(settingField.text?.length ?: 0)
      }
      settingField.doOnTextChanged { text, _, _, _ ->
        sharedPrefs.edit { putString(key, text?.toString()?.trim().orEmpty()) }
      }
      settingField.setOnEditorActionListener { _, actionId, _ ->
        if (actionId != EditorInfo.IME_ACTION_DONE) return@setOnEditorActionListener false
        finish()
        true
      }
      settingField.requestFocus()
    }
    window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
  }

  override fun onOptionsItemSelected(item: MenuItem): Boolean {
    if (item.itemId == android.R.id.home) {
      onBackPressedDispatcher.onBackPressed()
      return true
    }
    return super.onOptionsItemSelected(item)
  }

  private fun Intent.resource(name: String): Int? = getIntExtra(name, 0).takeIf { it != 0 }

  companion object {

    private const val EXTRA_KEY = "KEY"
    private const val EXTRA_TITLE = "TITLE"
    private const val EXTRA_DEFAULT = "DEFAULT"
    private const val EXTRA_INPUT_TYPE = "INPUT_TYPE"
    private const val EXTRA_HINT = "HINT"
    private const val EXTRA_PLACEHOLDER = "PLACEHOLDER"
    private const val EXTRA_SUFFIX = "SUFFIX"
    private const val EXTRA_FOOTER = "FOOTER"

    @Suppress("LongParameterList")
    fun newIntent(
      context: Context,
      key: String,
      @StringRes title: Int,
      default: String?,
      inputType: Int,
      @StringRes hint: Int?,
      @StringRes placeholder: Int?,
      @StringRes suffix: Int?,
      @StringRes footer: Int?,
    ): Intent =
      Intent(context, TextSettingActivity::class.java)
        .putExtra(EXTRA_KEY, key)
        .putExtra(EXTRA_TITLE, title)
        .putExtra(EXTRA_DEFAULT, default)
        .putExtra(EXTRA_INPUT_TYPE, inputType)
        .putExtra(EXTRA_HINT, hint ?: 0)
        .putExtra(EXTRA_PLACEHOLDER, placeholder ?: 0)
        .putExtra(EXTRA_SUFFIX, suffix ?: 0)
        .putExtra(EXTRA_FOOTER, footer ?: 0)
  }
}
