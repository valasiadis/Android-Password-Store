/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.passwordstore.ui.settings

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import android.widget.ImageView
import androidx.appcompat.R as AppCompatR
import androidx.constraintlayout.widget.Barrier
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.res.use
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePaddingRelative
import androidx.recyclerview.widget.RecyclerView
import app.passwordstore.R
import app.passwordstore.util.extensions.redecorateOnChanges
import com.google.android.material.R as MaterialR
import com.google.android.material.color.MaterialColors
import de.Maxr1998.modernpreferences.PreferencesAdapter

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

  private val outerRadius = context.resources.getDimension(R.dimen.list_outer_corner_radius)
  private val innerRadius = context.resources.getDimension(R.dimen.list_inner_corner_radius)
  // The same height and gap as the rows of the password list, so the two read as one design.
  private val entrySpacing = context.resources.getDimensionPixelSize(R.dimen.list_item_gap)
  private val entryHeight = context.resources.getDimensionPixelSize(R.dimen.password_row_height)
  private val groupSpacing = context.resources.getDimensionPixelSize(R.dimen.spacing_large)
  private val contentMargin = context.resources.getDimensionPixelSize(R.dimen.spacing_medium)
  private val iconRowPadding = context.resources.getDimensionPixelSize(R.dimen.spacing_medium)
  private val iconFrameWidth = context.resources.getDimensionPixelSize(R.dimen.settings_icon_frame)
  private val iconFrameGap = context.resources.getDimensionPixelSize(R.dimen.spacing_medium)
  /** What the library's own layout leads with, restored to a recycled row that has no icon. */
  private val plainRowPadding =
    context.obtainStyledAttributes(intArrayOf(android.R.attr.listPreferredItemPaddingStart)).use {
      it.getDimensionPixelSize(0, iconRowPadding)
    }
  private val containers = mutableMapOf<PlaceInGroup, RippleDrawable>()
  private val applied = java.util.WeakHashMap<View, PlaceInGroup>()

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
    recyclerView.redecorateOnChanges()
    recyclerView.addOnChildAttachStateChangeListener(
      object : RecyclerView.OnChildAttachStateChangeListener {
        override fun onChildViewAttachedToWindow(view: View) {
          val place = placeInGroup(recyclerView, recyclerView.getChildAdapterPosition(view))
          if (place != null) {
            view.background = containerDrawable(view, place)
            trimContentMargins(view)
            applied[view] = place
          } else {
            applied.remove(view)
          }
          val icon = view.findViewById<ImageView>(android.R.id.icon)
          icon?.imageTintList =
            ColorStateList.valueOf(
              MaterialColors.getColor(view, AppCompatR.attr.colorPrimary, Color.TRANSPARENT)
            )
          drawIconIn(view, icon)
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
    val place =
      placeInGroup(parent, parent.getChildAdapterPosition(view))
        ?: run {
          applied.remove(view)
          return
        }
    // An entry's place changes without it being attached again — a preference appearing or
    // disappearing makes a new last entry of its neighbour — so the shape is checked on every
    // layout rather than only when the row arrives, and redrawn when it no longer fits.
    if (applied[view] != place) {
      view.background = containerDrawable(view, place)
      trimContentMargins(view)
      applied[view] = place
    }
    outRect.bottom = if (place.endsGroup) groupSpacing else entrySpacing
  }

  /**
   * Pulls an entry that carries an icon in from the left edge.
   *
   * The library reserves a fixed column for the icon and starts the title after it, which puts the
   * icon well inside the container and the title further in still — so a screen with icons sits
   * noticeably further right than one without, and the two read as different lists. The column is
   * narrowed to what a 24dp icon and a gap actually need, and the row's leading padding with it.
   *
   * Asked of the icon itself rather than of the screen, so a sub-screen that grows icons later is
   * treated the same without anything being told about it, and a row with none is left alone.
   */
  private fun drawIconIn(row: View, icon: ImageView?) {
    val frame = icon?.parent as? View
    val carriesIcon = frame != null && frame.isVisible
    if (carriesIcon) {
      frame.updateLayoutParams { width = iconFrameWidth }
      frame.updatePaddingRelative(start = 0, end = iconFrameGap)
    }
    // Said either way: rows are recycled, and one that has just given up its icon would otherwise
    // keep the leading edge it only had because of it.
    row.updatePaddingRelative(start = if (carriesIcon) iconRowPadding else plainRowPadding)
  }

  /**
   * Pulls in the margins the library sets around an entry's title and summary, and stands the entry
   * at least as tall as a row of the password list.
   *
   * The margins are the whole of an entry's height, the layout having no padding of its own, and at
   * their stock 16dp they leave a two-line entry looking half empty. The height is a minimum rather
   * than a fixed one, because a summary can run to several lines and would be cut off. The title
   * and summary are a chain between the entry's top and bottom, which a taller entry would spread
   * apart; packed, they stay together in its middle, as a row's name and folder do.
   *
   * That middle is the library's to say, and it says it with a barrier at the foot of the content
   * rather than the foot of the entry: the summary, the icon and the switch all hang from it, so a
   * taller entry leaves them where the content ends and puts all the extra height below. They are
   * hung from the entry itself instead.
   */
  private fun trimContentMargins(view: View) {
    (view as? ConstraintLayout)?.let { row ->
      row.minHeight = entryHeight
      val barrier = row.children.firstOrNull { it is Barrier }?.id
      if (barrier != null) {
        row.children.forEach { child ->
          val params = child.layoutParams as? ConstraintLayout.LayoutParams ?: return@forEach
          if (params.bottomToBottom == barrier) {
            params.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            child.layoutParams = params
          }
        }
      }
    }
    view.findViewById<View>(android.R.id.title)?.updateLayoutParams<MarginLayoutParams> {
      topMargin = contentMargin
      (this as? ConstraintLayout.LayoutParams)?.verticalChainStyle =
        ConstraintLayout.LayoutParams.CHAIN_PACKED
    }
    view.findViewById<View>(android.R.id.summary)?.updateLayoutParams<MarginLayoutParams> {
      bottomMargin = contentMargin
    }
  }

  /**
   * The container, and a ripple masked to it, as one background.
   *
   * There are four of these — a group's first entry, its last, one that is both, one that is
   * neither — so each is built once and handed out as a new drawable sharing that constant state,
   * rather than three fresh drawables every time a row is attached.
   */
  private fun containerDrawable(view: View, place: PlaceInGroup): Drawable {
    val prototype = containers.getOrPut(place) { newContainerDrawable(view, place) }
    // A drawable with no constant state cannot be shared, so that one is simply built again.
    return prototype.constantState?.newDrawable()?.mutate() ?: newContainerDrawable(view, place)
  }

  private fun newContainerDrawable(view: View, place: PlaceInGroup): RippleDrawable {
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
   * accent buttons stand on their own rather than inside a container, and a preference that is
   * hidden is nowhere at all.
   */
  private fun placeInGroup(parent: RecyclerView, position: Int): PlaceInGroup? {
    if (position == RecyclerView.NO_POSITION || !isGrouped(parent, position)) return null
    val itemCount = parent.adapter?.itemCount ?: 0
    return PlaceInGroup(
      startsGroup = !isGrouped(parent, previousShown(parent, position)),
      endsGroup = !isGrouped(parent, nextShown(parent, position, itemCount)),
    )
  }

  /**
   * The library keeps a hidden preference in the list and collapses its row to nothing, so the
   * neighbours on either side of one are neighbours: they belong to the same group, and the space
   * this decoration puts around entries must not be spent on a row nobody can see.
   */
  private fun isShown(parent: RecyclerView, position: Int): Boolean {
    val adapter = parent.adapter as? PreferencesAdapter ?: return true
    if (position < 0 || position >= adapter.itemCount) return false
    return adapter.currentScreen[position].visible
  }

  private fun previousShown(parent: RecyclerView, position: Int): Int =
    (position - 1 downTo 0).firstOrNull { isShown(parent, it) } ?: -1

  private fun nextShown(parent: RecyclerView, position: Int, itemCount: Int): Int =
    (position + 1 until itemCount).firstOrNull { isShown(parent, it) } ?: itemCount

  private fun isGrouped(parent: RecyclerView, position: Int): Boolean {
    if (!isShown(parent, position)) return false
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
