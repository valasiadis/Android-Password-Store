/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.settings

import android.annotation.SuppressLint
import android.content.Intent
import android.provider.Settings
import android.text.InputType
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import app.passwordstore.BuildConfig
import app.passwordstore.R
import app.passwordstore.ui.dialogs.AppDialogBuilder
import app.passwordstore.util.extensions.autofillManager
import app.passwordstore.util.extensions.sharedPrefs
import app.passwordstore.util.settings.PreferenceKeys
import com.github.androidpasswordstore.autofillparser.BrowserAutofillSupportLevel
import com.github.androidpasswordstore.autofillparser.getInstalledBrowsersWithAutofillSupportLevel
import de.Maxr1998.modernpreferences.PreferenceScreen
import de.Maxr1998.modernpreferences.helpers.onClick
import de.Maxr1998.modernpreferences.helpers.switch
import de.Maxr1998.modernpreferences.preferences.SwitchPreference
import kotlinx.coroutines.launch

class AutofillSettings(private val activity: FragmentActivity) : SettingsProvider {

  val isAutofillServiceEnabled: Boolean
    get() = activity.autofillManager?.hasEnabledAutofillServices() == true

  var autofillSwitchPreference: SwitchPreference? = null
  private val requestSetAutofillServiceAction =
    activity.registerForActivityResult(StartActivityForResult()) { _ ->
      autofillSwitchPreference?.run { checked = isAutofillServiceEnabled }
    }

  private fun showAutofillDialog() {
    AppDialogBuilder(activity).run {
      setTitle(R.string.pref_autofill_enable_title)
      @SuppressLint("InflateParams")
      val layout = activity.layoutInflater.inflate(R.layout.oreo_autofill_instructions, null)
      val supportedBrowsersTextView = layout.findViewById<AppCompatTextView>(R.id.supportedBrowsers)
      supportedBrowsersTextView.text =
        getInstalledBrowsersWithAutofillSupportLevel(context).joinToString(separator = "\n") {
          val appLabel = it.first
          val supportDescription =
            when (it.second) {
              BrowserAutofillSupportLevel.None ->
                activity.getString(R.string.oreo_autofill_no_support)
              BrowserAutofillSupportLevel.FlakyFill ->
                activity.getString(R.string.oreo_autofill_flaky_fill_support)
              BrowserAutofillSupportLevel.PasswordFill ->
                activity.getString(R.string.oreo_autofill_password_fill_support)
              BrowserAutofillSupportLevel.PasswordFillAndSaveIfNoAccessibility ->
                activity.getString(
                  R.string.oreo_autofill_password_fill_and_conditional_save_support
                )
              BrowserAutofillSupportLevel.GeneralFill ->
                activity.getString(R.string.oreo_autofill_general_fill_support)
              BrowserAutofillSupportLevel.GeneralFillAndSave ->
                activity.getString(R.string.oreo_autofill_general_fill_and_save_support)
            }
          "$appLabel: $supportDescription"
        }
      setView(layout)
      setPositiveButton(R.string.dialog_ok) { _, _ ->
        val intent =
          Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE).apply {
            data = "package:${BuildConfig.APPLICATION_ID}".toUri()
          }
        requestSetAutofillServiceAction.launch(intent)
      }
      setNegativeButton(R.string.dialog_cancel, null)
      show()
    }
  }

  override fun provideSettings(builder: PreferenceScreen.Builder) {
    builder.apply {
      switch(PreferenceKeys.AUTOFILL_ENABLE) {
        titleRes = R.string.pref_autofill_enable_title
        autofillSwitchPreference = this
        onClick {
          if (checked) showAutofillDialog() else activity.autofillManager?.disableAutofillServices()
          false
        }
      }
      switch(PreferenceKeys.AUTOFILL_ASK_TO_SAVE_PASSWORDS) {
        titleRes = R.string.pref_autofill_ask_save_passwords_title
        defaultValue = true
        dependency = PreferenceKeys.AUTOFILL_ENABLE
      }
      textSetting(
        PreferenceKeys.OREO_AUTOFILL_DEFAULT_USERNAME,
        R.string.preference_default_username_title,
        activity,
        activity.sharedPrefs,
        summary = { it.orNotSet(activity) },
        hint = R.string.crypto_username_hint,
        footer = R.string.preference_default_username_summary,
        configure = { dependency = PreferenceKeys.AUTOFILL_ENABLE },
      )
      textSetting(
        PreferenceKeys.OREO_AUTOFILL_CUSTOM_PUBLIC_SUFFIXES,
        R.string.preference_custom_public_suffixes_title,
        activity,
        activity.sharedPrefs,
        summary = {
          it?.lines()?.filter(String::isNotBlank)?.joinToString(", ").orNotSet(activity)
        },
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
        hint = R.string.setting_field_domains,
        placeholder = R.string.preference_custom_public_suffixes_hint,
        footer = R.string.preference_custom_public_suffixes_summary,
        configure = { dependency = PreferenceKeys.AUTOFILL_ENABLE },
      )
    }
  }
}

/** A typed setting's value as its row says it, or that there is none. */
private fun String?.orNotSet(activity: FragmentActivity): String =
  takeUnless { it.isNullOrBlank() } ?: activity.getString(R.string.setting_not_set)
