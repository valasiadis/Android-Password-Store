/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.passwordstore.ui.settings

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import android.widget.ImageView
import androidx.appcompat.R as AppCompatR
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.RecyclerView
import app.passwordstore.R
import com.google.android.material.R as MaterialR
import com.google.android.material.color.MaterialColors

/**
 * Gives each preference a filled container, rounded where its group ends, in the manner of the
 * system settings app.
 *
 * Entries are separated by a thin gap. A group's outer corners — the top of its first entry and the
 * bottom of its last — are fully rounded, and the edges where entries meet are rounded slightly, so
 * a group reads as one block whose parts are still distinct. The container is the entry's own
 * background rather than something painted behind it, so the touch ripple is masked to the same
 * shape and cannot spill a square corner over a rounded one.
 */
class PreferenceGroupDecoration(context: Context) : RecyclerView.ItemDecoration() {

  private val outerRadius = context.resources.getDimension(R.dimen.corner_radius_large)
  private val innerRadius = context.resources.getDimension(R.dimen.corner_radius_small)
  private val entrySpacing = context.resources.getDimensionPixelSize(R.dimen.spacing_xsmall)
  private val groupSpacing = context.resources.getDimensionPixelSize(R.dimen.spacing_medium)
  private val contentMargin = context.resources.getDimensionPixelSize(R.dimen.spacing_medium)

  /**
   * Adds this decoration to [recyclerView] and takes over its entries' background, padding and icon
   * tint.
   *
   * Height is added as padding inside an entry rather than as a taller row, because the library
   * lays a row's title and summary out against its top edge: a taller row would leave its contents
   * stranded up there, and the attribute that sets that height is also what sizes the rows of the
   * choice dialogs.
   */
  fun attachTo(recyclerView: RecyclerView) {
    recyclerView.addItemDecoration(this)
    recyclerView.addOnChildAttachStateChangeListener(
      object : RecyclerView.OnChildAttachStateChangeListener {
        override fun onChildViewAttachedToWindow(view: View) {
          val place = placeInGroup(recyclerView, recyclerView.getChildAdapterPosition(view))
          if (place != null) {
            view.background = containerDrawable(view, place)
            trimContentMargins(view)
          }
          view.findViewById<ImageView>(android.R.id.icon)?.imageTintList =
            ColorStateList.valueOf(
              MaterialColors.getColor(view, AppCompatR.attr.colorPrimary, Color.TRANSPARENT)
            )
        }

        override fun onChildViewDetachedFromWindow(view: View) = Unit
      }
    )
  }

  override fun getItemOffsets(
    outRect: Rect,
    view: View,
    parent: RecyclerView,
    state: RecyclerView.State,
  ) {
    val place = placeInGroup(parent, parent.getChildAdapterPosition(view)) ?: return
    outRect.bottom = if (place.endsGroup) groupSpacing else entrySpacing
  }

  /**
   * Pulls in the margins the library sets around an entry's title and summary. They are the whole
   * of an entry's height, the layout having no padding of its own, and at their stock 16dp they
   * leave a two-line entry looking half empty.
   */
  private fun trimContentMargins(view: View) {
    view.findViewById<View>(android.R.id.title)?.updateLayoutParams<MarginLayoutParams> {
      topMargin = contentMargin
    }
    view.findViewById<View>(android.R.id.summary)?.updateLayoutParams<MarginLayoutParams> {
      bottomMargin = contentMargin
    }
  }

  /** The container, and a ripple masked to it, as one background. */
  private fun containerDrawable(view: View, place: PlaceInGroup): RippleDrawable {
    val container =
      GradientDrawable().apply {
        cornerRadii = cornerRadiiFor(place)
        setColor(
          MaterialColors.getColor(view, MaterialR.attr.colorSurfaceVariant, Color.TRANSPARENT)
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
   * left. A group's outer edges are rounded fully, the edges facing a neighbour only slightly.
   */
  private fun cornerRadiiFor(place: PlaceInGroup): FloatArray {
    val top = if (place.startsGroup) outerRadius else innerRadius
    val bottom = if (place.endsGroup) outerRadius else innerRadius
    return FloatArray(CORNER_COUNT * 2) { index -> if (index < CORNER_COUNT) top else bottom }
  }

  /**
   * Where the entry at [position] sits in its group, or null if it is in none: category headers and
   * accent buttons stand on their own rather than inside a container.
   */
  private fun placeInGroup(parent: RecyclerView, position: Int): PlaceInGroup? {
    if (position == RecyclerView.NO_POSITION || !isGrouped(parent, position)) return null
    return PlaceInGroup(
      startsGroup = position == 0 || !isGrouped(parent, position - 1),
      endsGroup =
        position == (parent.adapter?.itemCount ?: 0) - 1 || !isGrouped(parent, position + 1),
    )
  }

  private fun isGrouped(parent: RecyclerView, position: Int): Boolean {
    val viewType = parent.adapter?.getItemViewType(position) ?: return false
    return viewType != CATEGORY_HEADER_VIEW_TYPE && viewType != ACCENT_BUTTON_VIEW_TYPE
  }

  private data class PlaceInGroup(val startsGroup: Boolean, val endsGroup: Boolean)

  private companion object {
    /** Corners of a rectangle, each of which GradientDrawable wants an x and a y radius for. */
    const val CORNER_COUNT = 4

    /**
     * The preferences library identifies its item types by layout resource, using these negative
     * constants for the types that have no layout of their own. They are internal to it, so they
     * are repeated here.
     */
    const val CATEGORY_HEADER_VIEW_TYPE = -2
    const val ACCENT_BUTTON_VIEW_TYPE = -3
  }
}
