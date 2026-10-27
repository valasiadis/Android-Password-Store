/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.passwordstore.ui.util

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.view.children
import app.passwordstore.R
import com.google.android.material.textfield.TextInputLayout

/**
 * Rows that read as one block, as the system settings set theirs: each row filled, the block
 * rounded fully where it starts and ends and only slightly where its rows meet, the rows a thin gap
 * apart.
 *
 * The password list and the settings draw their blocks this way from their adapters; this is the
 * same for a screen laid out by hand, so a form, a choice and a list of facts all look like the
 * settings do. A row's shape comes from where it falls among the rows that are shown, so a row that
 * comes and goes reshapes its neighbours rather than leaving a square corner behind. A text field
 * in the block is given the same corners on its own filled box, since that is what it draws.
 */
class ListBlock @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
  LinearLayout(context, attrs) {

  private val gap = resources.getDimensionPixelSize(R.dimen.list_item_gap)
  private val outerRadius = resources.getDimension(R.dimen.list_outer_corner_radius)
  private val innerRadius = resources.getDimension(R.dimen.list_inner_corner_radius)

  init {
    orientation = VERTICAL
  }

  override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
    shapeRows()
    super.onMeasure(widthMeasureSpec, heightMeasureSpec)
  }

  /**
   * Gives each shown row the shape and the gap its place calls for. Done as the block is measured,
   * which is after any row has been shown or hidden, and only where a row's place has changed.
   */
  private fun shapeRows() {
    val shown = children.filter { it.visibility != View.GONE }.toList()
    shown.forEachIndexed { index, row ->
      val place = Place(startsBlock = index == 0, endsBlock = index == shown.lastIndex)
      // Margins are read as the block measures its rows, so they are set in place rather than
      // handed back as new parameters, which would ask for another layout pass.
      (row.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin =
        if (place.startsBlock) 0 else gap
      if (row.getTag(R.id.list_block_place) == place) return@forEachIndexed
      row.setTag(R.id.list_block_place, place)
      val top = if (place.startsBlock) outerRadius else innerRadius
      val bottom = if (place.endsBlock) outerRadius else innerRadius
      if (row is TextInputLayout) {
        row.setBoxCornerRadii(top, top, bottom, bottom)
      } else {
        row.setBackgroundResource(
          when {
            place.startsBlock && place.endsBlock -> R.drawable.list_row_single
            place.startsBlock -> R.drawable.list_row_top
            place.endsBlock -> R.drawable.list_row_bottom
            else -> R.drawable.list_row_middle
          }
        )
      }
    }
  }

  private data class Place(val startsBlock: Boolean, val endsBlock: Boolean)
}
