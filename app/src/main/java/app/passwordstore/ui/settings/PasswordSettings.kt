/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.settings

import android.text.InputType
import androidx.annotation.PluralsRes
import androidx.core.content.edit
import androidx.fragment.app.FragmentActivity
import app.passwordstore.R
import app.passwordstore.util.auth.BiometricAuthenticator
import app.passwordstore.util.extensions.persistentPassphrases
import app.passwordstore.util.extensions.sharedPrefs
import app.passwordstore.util.extensions.unlockPins
import app.passwordstore.util.settings.PreferenceKeys
import de.Maxr1998.modernpreferences.PreferenceScreen
import de.Maxr1998.modernpreferences.helpers.switch

class PasswordSettings(private val activity: FragmentActivity) : SettingsProvider {

  override fun provideSettings(builder: PreferenceScreen.Builder) {
    builder.apply {
      val values = activity.resources.getStringArray(R.array.pwgen_provider_values)
      val labels = activity.resources.getStringArray(R.array.pwgen_provider_labels)
      radioChoice(
        PreferenceKeys.PREF_KEY_PWGEN_TYPE,
        R.string.pref_password_generator_type_title,
        values.zip(labels),
        default = "diceware",
        prefs = activity.sharedPrefs,
      )
      val unlockValues = arrayOf("disabled", "fingerprint", "PIN")
      val unlockLabels = activity.resources.getStringArray(R.array.fast_unlock_option_labels)
      val unlockOpts =
        if (BiometricAuthenticator.canAuthenticate(activity)) setOf(0, 1, 2) else setOf(0, 2)
      if (
        !unlockValues
          .slice(unlockOpts)
          .contains(
            activity.sharedPrefs.getString(PreferenceKeys.PREF_FAST_UNLOCK_OPTION, "disabled")
          )
      )
        activity.sharedPrefs.edit { putString(PreferenceKeys.PREF_FAST_UNLOCK_OPTION, "disabled") }
      // Changing how entries are unlocked drops what the old way had kept, so nothing is left
      // behind that the new way would not ask for.
      radioChoice(
        PreferenceKeys.PREF_FAST_UNLOCK_OPTION,
        R.string.fast_unlock_pref_title,
        unlockValues.slice(unlockOpts).zip(unlockLabels.slice(unlockOpts)),
        default = "disabled",
        prefs = activity.sharedPrefs,
      ) {
        activity.persistentPassphrases.edit { clear() }
        activity.unlockPins.edit { clear() }
      }
      // A new period drops what the old one had kept, so nothing outlives the period now set.
      // A new period drops what the old one had kept, so nothing outlives the period now set.
      textSetting(
        PreferenceKeys.BIOMETRICS_AND_PIN_TIMEOUT,
        R.string.pref_biometrics_and_pin_timeout_title,
        activity,
        activity.sharedPrefs,
        summary = { duration(activity, it, R.plurals.duration_days) },
        default = "3",
        inputType = InputType.TYPE_CLASS_NUMBER,
        suffix = R.string.setting_unit_days,
        footer = R.string.pref_biometrics_and_pin_timeout_footer,
      ) {
        activity.persistentPassphrases.edit { clear() }
        activity.unlockPins.edit { clear() }
      }
      switch(PreferenceKeys.SHOW_PASSWORD) {
        titleRes = R.string.show_password_pref_title
        summaryRes = R.string.show_password_pref_summary
        defaultValue = false
      }
      switch(PreferenceKeys.COPY_ON_DECRYPT) {
        titleRes = R.string.pref_copy_title
        summaryRes = R.string.pref_copy_summary
        defaultValue = false
      }
      textSetting(
        PreferenceKeys.GENERAL_SHOW_TIME,
        R.string.pref_clipboard_timeout_title,
        activity,
        activity.sharedPrefs,
        summary = { duration(activity, it, R.plurals.duration_seconds) },
        default = "45",
        inputType = InputType.TYPE_CLASS_NUMBER,
        suffix = R.string.setting_unit_seconds,
        footer = R.string.pref_clipboard_timeout_footer,
      )
      switch(PreferenceKeys.CLEAR_CLIPBOARD_HISTORY) {
        defaultValue = false
        titleRes = R.string.pref_clear_clipboard_title
        summaryRes = R.string.pref_clear_clipboard_summary
      }
      switch(PreferenceKeys.SHOW_EXTRA_CONTENT) {
        defaultValue = true
        titleRes = R.string.show_extra_content_pref_title
        summaryRes = R.string.show_extra_content_pref_summary
      }
    }
  }
}

/** A stored number of units said as a duration: [label] counts them, and 0 is never. */
private fun duration(activity: FragmentActivity, value: String?, @PluralsRes label: Int): String {
  val count = value?.toIntOrNull() ?: return value.orEmpty()
  return if (count == 0) activity.getString(R.string.duration_never)
  else activity.resources.getQuantityString(label, count, count)
}
