/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.passwordstore.ui.dialogs

import androidx.core.view.isVisible
import app.passwordstore.R
import app.passwordstore.databinding.ListChoiceRowBinding

/**
 * A block of answers to one question, drawn as the system settings draw a choice: one rounded block
 * of rows, a radio mark on each, the chosen one's filled in.
 *
 * Each answer is shown with [show]; the block's rounding comes from where each row falls in it, so
 * a row is told its place rather than finding it out.
 */
object ChoiceRows {

  /**
   * Shows [row] as the answer at [index] of [count], named [title] and explained by [summary], and
   * calls [onChoose] when it is tapped. Whether it is the answer is said with [select], as often as
   * the answer changes.
   */
  fun show(
    row: ListChoiceRowBinding,
    index: Int,
    count: Int,
    title: CharSequence,
    summary: CharSequence? = null,
    onChoose: () -> Unit,
  ) {
    row.root.setBackgroundResource(
      when {
        count == 1 -> R.drawable.list_choice_row_single
        index == 0 -> R.drawable.list_choice_row_top
        index == count - 1 -> R.drawable.list_choice_row_bottom
        else -> R.drawable.list_choice_row_middle
      }
    )
    row.choiceTitle.text = title
    row.choiceSummary.text = summary
    row.choiceSummary.isVisible = !summary.isNullOrEmpty()
    row.root.setOnClickListener { onChoose() }
  }

  /** Marks [row] as the answer or not: its radio filled in or empty, in colour or not. */
  fun select(row: ListChoiceRowBinding, selected: Boolean) {
    row.root.isSelected = selected
    row.choiceMark.setImageResource(
      if (selected) R.drawable.ic_radio_checked_24dp else R.drawable.ic_radio_unchecked_24dp
    )
  }
}
