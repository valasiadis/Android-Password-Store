/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.passwordstore.ui.dialogs

import androidx.annotation.StringRes
import androidx.core.view.isVisible
import app.passwordstore.R
import app.passwordstore.databinding.ListActionRowBinding
import app.passwordstore.databinding.ListChoiceRowBinding
import app.passwordstore.databinding.ListSectionHeaderBinding
import app.passwordstore.databinding.ListSwitchRowBinding
import app.passwordstore.databinding.ListValueRowBinding

/**
 * The rows a screen is built from, so that every screen is built from the same ones: a value that
 * opens where it is chosen, something to do, a switch, an answer to a choice, and the heading over
 * a block of them. They stand in a `ListBlock`, which gives each its shape.
 */
object ListRows {

  /** A block's heading, with the mark that explains it when there is [tip]. */
  fun header(header: ListSectionHeaderBinding, @StringRes title: Int, @StringRes tip: Int? = null) {
    header.sectionTitle.setText(title)
    header.sectionHelp.isVisible = tip != null
    tip?.let(header.sectionHelp::showsTip)
  }

  /** A row naming a setting, showing its value, and calling [onOpen] to have it chosen. */
  fun value(
    row: ListValueRowBinding,
    @StringRes title: Int,
    value: CharSequence,
    onOpen: () -> Unit,
  ) {
    row.valueTitle.setText(title)
    row.valueText.text = value
    row.root.setOnClickListener { onOpen() }
  }

  /** Something to do, named [title] and explained by [summary], done by [onClick]. */
  fun action(
    row: ListActionRowBinding,
    @StringRes title: Int,
    summary: CharSequence? = null,
    onClick: () -> Unit,
  ) {
    row.actionTitle.setText(title)
    row.actionSummary.text = summary
    row.actionSummary.isVisible = !summary.isNullOrEmpty()
    row.root.setOnClickListener { onClick() }
  }

  /**
   * A setting that is on or off, [checked] to start with; the whole row toggles it and tells
   * [onChange].
   */
  fun switch(
    row: ListSwitchRowBinding,
    @StringRes title: Int,
    checked: Boolean,
    summary: CharSequence? = null,
    onChange: (Boolean) -> Unit,
  ) {
    row.switchTitle.setText(title)
    row.switchSummary.text = summary
    row.switchSummary.isVisible = !summary.isNullOrEmpty()
    row.switchToggle.isChecked = checked
    row.root.setOnClickListener {
      row.switchToggle.toggle()
      onChange(row.switchToggle.isChecked)
    }
  }

  /**
   * An answer to a choice, named [title] and explained by [summary], calling [onChoose] when it is
   * tapped. Whether it is the answer is said with [select], as often as the answer changes.
   */
  fun choice(
    row: ListChoiceRowBinding,
    title: CharSequence,
    summary: CharSequence? = null,
    onChoose: () -> Unit,
  ) {
    row.choiceTitle.text = title
    row.choiceSummary.text = summary
    row.choiceSummary.isVisible = !summary.isNullOrEmpty()
    row.root.setOnClickListener { onChoose() }
  }

  /** Marks [row] as the answer or not: its radio filled in and in colour, or empty and quiet. */
  fun select(row: ListChoiceRowBinding, selected: Boolean) {
    row.root.isSelected = selected
    row.choiceMark.setImageResource(
      if (selected) R.drawable.ic_radio_checked_24dp else R.drawable.ic_radio_unchecked_24dp
    )
  }
}
