/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.settings

import androidx.fragment.app.FragmentActivity
import app.passwordstore.BuildConfig
import app.passwordstore.R
import app.passwordstore.util.settings.PreferenceKeys
import de.Maxr1998.modernpreferences.PreferenceScreen
import de.Maxr1998.modernpreferences.helpers.onClick
import de.Maxr1998.modernpreferences.helpers.pref
import de.Maxr1998.modernpreferences.helpers.switch

class MiscSettings(private val activity: FragmentActivity) : SettingsProvider {

  override fun provideSettings(builder: PreferenceScreen.Builder) {
    builder.apply {
      // Kept for the times the store is changed from underneath the app. Leaving the settings is
      // what re-reads it — the list refreshes itself whenever it comes back to the front — so this
      // closes them rather than reaching across to a screen it does not own.
      pref(PreferenceKeys.REFRESH_FOLDER_LIST) {
        titleRes = R.string.refresh_list
        summaryRes = R.string.refresh_list_summary
        onClick {
          activity.finish()
          true
        }
      }
      switch(PreferenceKeys.ENABLE_DEBUG_LOGGING) {
        defaultValue = false
        titleRes = R.string.pref_debug_logging_title
        summaryRes = R.string.pref_debug_logging_summary
        visible = !BuildConfig.DEBUG
      }
    }
  }
}
