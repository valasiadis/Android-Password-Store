/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.settings

import android.content.pm.ShortcutManager
import androidx.core.content.edit
import androidx.core.content.getSystemService
import androidx.fragment.app.FragmentActivity
import app.passwordstore.R
import app.passwordstore.util.auth.BiometricAuthenticator
import app.passwordstore.util.auth.BiometricAuthenticator.Result
import app.passwordstore.util.extensions.sharedPrefs
import app.passwordstore.util.settings.DirectoryStructure
import app.passwordstore.util.settings.PreferenceKeys
import de.Maxr1998.modernpreferences.PreferenceScreen
import de.Maxr1998.modernpreferences.helpers.onClick
import de.Maxr1998.modernpreferences.helpers.switch

class GeneralSettings(private val activity: FragmentActivity) : SettingsProvider {

  override fun provideSettings(builder: PreferenceScreen.Builder) {
    builder.apply {
      val themeValues = activity.resources.getStringArray(R.array.app_theme_values)
      val themeOptions = activity.resources.getStringArray(R.array.app_theme_options)
      radioChoice(
        PreferenceKeys.APP_THEME,
        R.string.pref_app_theme_title,
        themeValues.zip(themeOptions),
        default = activity.resources.getString(R.string.app_theme_def),
        prefs = activity.sharedPrefs,
      )

      val values = activity.resources.getStringArray(R.array.directory_structure_values)
      val titles = activity.resources.getStringArray(R.array.directory_structure_entries)
      radioChoice(
        PreferenceKeys.DIRECTORY_STRUCTURE,
        R.string.pref_directory_structure,
        values.zip(titles),
        default = DirectoryStructure.DEFAULT.value,
        prefs = activity.sharedPrefs,
      )

      val sortValues = activity.resources.getStringArray(R.array.sort_order_values)
      val sortOptions = activity.resources.getStringArray(R.array.sort_order_entries)
      radioChoice(
        PreferenceKeys.SORT_ORDER,
        R.string.pref_sort_order_title,
        sortValues.zip(sortOptions),
        default = sortValues[0],
        prefs = activity.sharedPrefs,
      )

      switch(PreferenceKeys.DISABLE_SYNC_ACTION) {
        titleRes = R.string.pref_disable_sync_on_pull_title
        summaryRes = R.string.pref_disable_sync_on_pull_summary
        defaultValue = false
      }

      switch(PreferenceKeys.FILTER_RECURSIVELY) {
        titleRes = R.string.pref_recursive_filter_title
        summaryRes = R.string.pref_recursive_filter_summary
        defaultValue = true
      }

      switch(PreferenceKeys.SEARCH_ON_START) {
        titleRes = R.string.pref_search_on_start_title
        summaryRes = R.string.pref_search_on_start_summary
        defaultValue = false
      }

      val filterModeValues = arrayOf("exact", "fuzzy")
      val filterModeLabels = activity.resources.getStringArray(R.array.search_filter_mode_labels)
      radioChoice(
        PreferenceKeys.SEARCH_FILTER_MODE,
        R.string.pref_search_filter_mode_title,
        filterModeValues.zip(filterModeLabels),
        default = "exact",
        prefs = activity.sharedPrefs,
      )

      switch(PreferenceKeys.SHOW_HIDDEN_CONTENTS) {
        titleRes = R.string.pref_show_hidden_title
        summaryRes = R.string.pref_show_hidden_summary
        defaultValue = false
      }

      // See https://github.com/android-password-store/Android-Password-Store/issues/2802
      val canAuthenticate = BiometricAuthenticator.canAuthenticate(activity, allowPin = true)
      switch(PreferenceKeys.BIOMETRIC_AUTH_2) {
        titleRes = R.string.pref_biometric_auth_title
        defaultValue = false
        enabled = canAuthenticate
        summaryRes =
          if (canAuthenticate) R.string.pref_biometric_auth_summary
          else R.string.pref_biometric_auth_summary_error
        onClick {
          enabled = false
          val isChecked = checked
          activity.sharedPrefs.edit {
            BiometricAuthenticator.authenticate(activity, allowPin = true) { result ->
              when (result) {
                is Result.Success -> {
                  // Apply the changes
                  putBoolean(PreferenceKeys.BIOMETRIC_AUTH_2, checked)
                  enabled = true
                }
                is Result.Retry -> {}
                else -> {
                  // If any error occurs, revert back to the previous
                  // state. This
                  // catch-all clause includes the cancellation case.
                  putBoolean(PreferenceKeys.BIOMETRIC_AUTH_2, !checked)
                  checked = !isChecked
                  enabled = true
                }
              }
            }
          }
          activity.getSystemService<ShortcutManager>()?.apply {
            removeDynamicShortcuts(dynamicShortcuts.map { it.id }.toMutableList())
          }
          false
        }
      }
    }
  }
}
