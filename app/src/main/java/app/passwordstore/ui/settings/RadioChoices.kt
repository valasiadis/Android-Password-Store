/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.passwordstore.ui.settings

import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import androidx.annotation.StringRes
import androidx.appcompat.R as AppCompatR
import androidx.core.content.edit
import androidx.core.view.isVisible
import app.passwordstore.R
import com.google.android.material.R as MaterialR
import com.google.android.material.color.MaterialColors
import de.Maxr1998.modernpreferences.Preference
import de.Maxr1998.modernpreferences.PreferenceScreen
import de.Maxr1998.modernpreferences.PreferencesAdapter
import de.Maxr1998.modernpreferences.helpers.subScreen

/**
 * A choice among a few answers, as the system settings offer one: a row naming the setting and its
 * current answer, opening a page of the answers as radio rows, the chosen one's mark filled in.
 *
 * Choices used to open a dialog of radio buttons that looked like no other part of the app, and
 * vanished on a tap without showing the choice made. The answers here are rows of one block like
 * every other setting, and the page stays open on the answer just chosen.
 *
 * [answers] pairs each stored value with what it is called; [default] is the value that holds while
 * none is stored. [onChange] runs after a new answer is stored.
 */
fun PreferenceScreen.Builder.radioChoice(
  key: String,
  @StringRes titleRes: Int,
  answers: List<Pair<String, CharSequence>>,
  default: String,
  prefs: SharedPreferences,
  onChange: (String) -> Unit = {},
): PreferenceScreen {
  fun labelOf(value: String?) = answers.firstOrNull { it.first == value }?.second
  lateinit var page: PreferenceScreen
  page =
    subScreen("${key}_page") {
      this.titleRes = titleRes
      answers.forEach { (value, label) ->
        addPreferenceItem(
          RadioPreference("${key}_$value", key, value, default, prefs) {
              page.summary = labelOf(prefs.getString(key, default))
              page.requestRebind()
              onChange(value)
            }
            .apply { title = label }
        )
      }
    }
  page.summary = labelOf(prefs.getString(key, default))
  return page
}

/**
 * One answer of a [radioChoice]: chosen when [key] holds [value], and stores it when tapped. It
 * stores nothing under its own key, which is only its name in the list.
 */
class RadioPreference(
  key: String,
  private val storageKey: String,
  private val value: String,
  private val default: String,
  private val prefs: SharedPreferences,
  private val onChoose: () -> Unit,
) : Preference(key) {

  init {
    persistent = false
  }

  private val isChosen: Boolean
    get() = prefs.getString(storageKey, default) == value

  /**
   * A layout of its own for the end of the row, which holds nothing: it is what keeps the list from
   * recycling a radio row's view for an ordinary row, whose icon would then keep the radio's look.
   */
  override fun getWidgetLayoutResource(): Int = R.layout.preference_widget_none

  override fun bindViews(holder: PreferencesAdapter.ViewHolder) {
    super.bindViews(holder)
    holder.iconFrame.isVisible = true
    holder.icon?.apply {
      isVisible = true
      setTag(R.id.list_radio_mark, true)
      setImageResource(
        if (isChosen) R.drawable.ic_radio_checked_24dp else R.drawable.ic_radio_unchecked_24dp
      )
      imageTintList =
        ColorStateList.valueOf(
          MaterialColors.getColor(
            this,
            if (isChosen) AppCompatR.attr.colorPrimary else MaterialR.attr.colorOnSurfaceVariant,
            Color.TRANSPARENT,
          )
        )
    }
  }

  override fun onClick(holder: PreferencesAdapter.ViewHolder) {
    if (isChosen) return
    prefs.edit { putString(storageKey, value) }
    // Every answer on the page redraws its mark, so only the one just chosen is filled in.
    parent?.let { page ->
      for (index in 0 until page.size()) (page[index] as? RadioPreference)?.requestRebind()
    }
    onChoose()
  }
}
