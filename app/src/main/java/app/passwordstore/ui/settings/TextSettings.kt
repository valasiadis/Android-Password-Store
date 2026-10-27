/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.passwordstore.ui.settings

import android.content.SharedPreferences
import android.text.InputType
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.annotation.StringRes
import androidx.fragment.app.FragmentActivity
import de.Maxr1998.modernpreferences.Preference
import de.Maxr1998.modernpreferences.PreferenceScreen
import de.Maxr1998.modernpreferences.helpers.onClick
import de.Maxr1998.modernpreferences.helpers.pref

/**
 * A setting whose value is typed: a row naming it and saying what it holds, opening a page of its
 * own with the field to type it into and what the setting does beneath — as the system settings
 * give one — rather than a dialog.
 *
 * The preferences library's own dialog for this drew a bare underlined line that opened empty, the
 * value being changed nowhere in it, and looked like no other field in the app.
 *
 * [summary] says what the setting holds, given the stored value, or [default] while none is stored
 * — which is also what the field opens on. [hint] names what is typed, [placeholder] shows an
 * example while the field is empty, [suffix] is the unit the number is in, and [footer] explains
 * the setting under the field. [onChange] runs once the page is left with a different value.
 *
 * Built while the settings screen is created, since that is when it may still ask to hear back from
 * the page it opens.
 */
@Suppress("LongParameterList")
fun PreferenceScreen.Builder.textSetting(
  key: String,
  @StringRes titleRes: Int,
  activity: FragmentActivity,
  prefs: SharedPreferences,
  summary: (String?) -> CharSequence,
  default: String? = null,
  inputType: Int = InputType.TYPE_CLASS_TEXT,
  @StringRes hint: Int? = null,
  @StringRes placeholder: Int? = null,
  @StringRes suffix: Int? = null,
  @StringRes footer: Int? = null,
  configure: Preference.() -> Unit = {},
  onChange: (String) -> Unit = {},
): Preference {
  lateinit var preference: Preference
  var before: String? = null
  val page =
    activity.registerForActivityResult(StartActivityForResult()) {
      val after = prefs.getString(key, default)
      preference.summary = summary(after)
      preference.requestRebind()
      if (after != before) onChange(after.orEmpty())
    }
  preference =
    pref(key) {
      this.titleRes = titleRes
      persistent = false
      this.summary = summary(prefs.getString(key, default))
      configure()
      onClick {
        before = prefs.getString(key, default)
        page.launch(
          TextSettingActivity.newIntent(
            activity,
            key,
            titleRes,
            default,
            inputType,
            hint,
            placeholder,
            suffix,
            footer,
          )
        )
        false
      }
    }
  return preference
}
