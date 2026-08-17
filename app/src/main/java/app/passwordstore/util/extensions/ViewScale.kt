/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.util.extensions

import android.view.View
import androidx.core.view.isVisible

/**
 * Takes a view away by shrinking it out of the screen, and brings it back the same way.
 *
 * Driven through the view's own scale properties rather than through a view animation loaded from
 * `res/anim`. A view animation carries a transform of its own that is applied over the view's
 * layout position, and anything along the bottom of these screens is being moved every frame while
 * the keyboard travels: the two do not compose, so a button would shrink away at the height it
 * started from while the keyboard climbed over it.
 *
 * Calling this with the state the view is already settled in does nothing, and calling it against
 * an animation that is still running turns that animation around rather than letting it finish
 * somewhere the caller no longer wants.
 */
fun View.scalesAway(show: Boolean) {
  if (isVisible == show && scaleX == (if (show) 1f else 0f)) return
  animate().cancel()
  if (show) {
    scaleX = 0f
    scaleY = 0f
    isVisible = true
  }
  animate()
    .scaleX(if (show) 1f else 0f)
    .scaleY(if (show) 1f else 0f)
    .setStartDelay(if (show) SCALE_APPEAR_DELAY_MS else 0)
    .setDuration(SCALE_MS)
    // Only on a run that finishes: a turned-around animation has already been given the state it
    // is heading for, and must not have the state it was heading for written over the top.
    .withEndAction { if (!show) isVisible = false }
    .start()
}

/** As long as the search field's own resize, so the two move as one. */
const val SCALE_MS = 300L

/** The wait a view takes before it starts coming back, which the search field shares. */
const val SCALE_APPEAR_DELAY_MS = 100L
