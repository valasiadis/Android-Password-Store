/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.util.extensions

import androidx.recyclerview.widget.RecyclerView

/**
 * Has every row's decoration taken again whenever the rows of this list's adapter change.
 *
 * A decoration that shapes a row by its neighbours — rounded where its group starts or ends — is
 * only asked again for the rows that themselves changed. A row added at the end changes nobody
 * else, and the row that used to be last keeps its rounded bottom; a row hidden in place leaves its
 * neighbours shaped as though it were still there. So any change is followed by asking every row
 * again, in the same layout that puts the change on screen, rather than in a second one after it.
 *
 * The adapter must already be set.
 */
fun RecyclerView.redecorateOnChanges() {
  val adapter = adapter ?: return
  adapter.registerAdapterDataObserver(
    object : RecyclerView.AdapterDataObserver() {
      private fun redecorate() {
        // Asked from within a layout or a scroll, it would be refused; it is then asked just after.
        if (isComputingLayout) post(::invalidateItemDecorations)
        else
          try {
            invalidateItemDecorations()
          } catch (e: IllegalStateException) {
            post(::invalidateItemDecorations)
          }
      }

      override fun onChanged() = redecorate()

      override fun onItemRangeChanged(positionStart: Int, itemCount: Int) = redecorate()

      override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) =
        redecorate()

      override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = redecorate()

      override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = redecorate()

      override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) =
        redecorate()
    }
  )
}
