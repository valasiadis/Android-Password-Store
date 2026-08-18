/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.passwordstore.ui.adapters

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import androidx.appcompat.R as AppCompatR
import androidx.appcompat.content.res.AppCompatResources
import androidx.recyclerview.widget.RecyclerView
import app.passwordstore.R
import com.google.android.material.R as MaterialR
import com.google.android.material.color.MaterialColors
import java.util.WeakHashMap

/**
 * Gives each password row its filled container, rounded where the list ends, in the manner of the
 * settings screen.
 *
 * The list reads as one block: only the top of its first row and the bottom of its last are fully
 * rounded, the edges where rows meet are rounded slightly, and rows are separated by a thin gap
 * rather than by a margin the width of the corners. The container is the row's own background
 * rather than something painted behind it, so the touch ripple is masked to the same shape and
 * cannot spill a square corner over a rounded one.
 */
class PasswordRowDecoration(context: Context) : RecyclerView.ItemDecoration() {

  private val outerRadius = context.resources.getDimension(R.dimen.corner_radius_medium)
  private val innerRadius = context.resources.getDimension(R.dimen.corner_radius_small)
  private val rowSpacing = context.resources.getDimensionPixelSize(R.dimen.list_item_gap)
  private val outlineWidth = context.resources.getDimensionPixelSize(R.dimen.list_item_outline)
  private val containers = mutableMapOf<PlaceInList, RippleDrawable>()
  private val applied = WeakHashMap<View, PlaceInList>()

  /** Adds this decoration to [recyclerView] and takes over its rows' background. */
  fun attachTo(recyclerView: RecyclerView) {
    recyclerView.addItemDecoration(this)
  }

  override fun getItemOffsets(
    outRect: Rect,
    view: View,
    parent: RecyclerView,
    state: RecyclerView.State,
  ) {
    val position = parent.getChildAdapterPosition(view)
    if (position == RecyclerView.NO_POSITION) {
      applied.remove(view)
      return
    }
    val itemCount = parent.adapter?.itemCount ?: 0
    val place = PlaceInList(startsList = position == 0, endsList = position == itemCount - 1)
    // A row's place changes without it being bound again — an entry appearing or disappearing
    // makes a new last row of its neighbour — so the shape is checked on every layout rather than
    // only when the row arrives, and redrawn when it no longer fits.
    if (applied[view] != place) {
      view.background = containerDrawable(view, place)
      applied[view] = place
    }
    outRect.bottom = if (place.endsList) 0 else rowSpacing
  }

  /**
   * The container, and a ripple masked to it, as one background.
   *
   * There are four of these — the list's first row, its last, one that is both, one that is
   * neither — so each is built once and handed out as a new drawable sharing that constant state,
   * rather than a fresh drawable every time a row is laid out.
   */
  private fun containerDrawable(view: View, place: PlaceInList): Drawable {
    val prototype = containers.getOrPut(place) { newContainerDrawable(view, place) }
    // A drawable with no constant state cannot be shared, so that one is simply built again.
    return prototype.constantState?.newDrawable()?.mutate() ?: newContainerDrawable(view, place)
  }

  private fun newContainerDrawable(view: View, place: PlaceInList): RippleDrawable {
    val container =
      GradientDrawable().apply {
        cornerRadii = cornerRadiiFor(place)
        // A colour state list rather than a colour, so selecting a row changes the fill of this
        // one shape rather than laying a tint over it.
        color = AppCompatResources.getColorStateList(view.context, R.color.password_row_container)
        setStroke(
          outlineWidth,
          MaterialColors.getColor(view, MaterialR.attr.colorOutlineVariant, Color.TRANSPARENT),
        )
      }
    val mask =
      GradientDrawable().apply {
        cornerRadii = cornerRadiiFor(place)
        setColor(Color.WHITE)
      }
    val rippleColor =
      MaterialColors.getColor(view, AppCompatR.attr.colorControlHighlight, Color.TRANSPARENT)
    return RippleDrawable(ColorStateList.valueOf(rippleColor), container, mask)
  }

  /**
   * The eight radii [GradientDrawable] asks for: an x and a y per corner, clockwise from the top
   * left. The list's outer edges are rounded fully, the edges facing a neighbour only slightly.
   */
  private fun cornerRadiiFor(place: PlaceInList): FloatArray {
    val top = if (place.startsList) outerRadius else innerRadius
    val bottom = if (place.endsList) outerRadius else innerRadius
    return FloatArray(CORNER_COUNT * 2) { index -> if (index < CORNER_COUNT) top else bottom }
  }

  private data class PlaceInList(val startsList: Boolean, val endsList: Boolean)

  private companion object {
    /** Corners of a rectangle, each of which GradientDrawable wants an x and a y radius for. */
    const val CORNER_COUNT = 4
  }
}
