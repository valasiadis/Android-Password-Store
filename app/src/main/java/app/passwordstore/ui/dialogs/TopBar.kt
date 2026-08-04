/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.passwordstore.ui.dialogs

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.R as AppCompatR
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.marginTop
import androidx.core.view.updateLayoutParams

/**
 * Puts [view] at the top of [activity]'s window, under whatever the screen already has up there.
 *
 * Everything the app says about itself — that something is running, that something is done — is
 * said in this one place, so it is worth placing in one place too. The window's own root is used
 * rather than its content, because an action bar is drawn over the top of the content on some
 * screens and would swallow a bar that sat there.
 */
internal fun placeAtTopOf(activity: Activity, view: View) {
  val root = topBarParent(activity)
  val margin = view.marginTop
  ViewCompat.setOnApplyWindowInsetsListener(view) { placed, windowInsets ->
    placed.updateLayoutParams<ViewGroup.MarginLayoutParams> {
      topMargin = topOf(root) + margin
    }
    windowInsets
  }
  root.addView(view)
  view.updateLayoutParams<ViewGroup.MarginLayoutParams> { topMargin = topOf(root) + margin }
}

/**
 * What a top bar is laid out in: the window's own root. Bars are inflated against it so that the
 * width, the gravity and the margins written in their layout survive.
 */
internal fun topBarParent(activity: Activity): ViewGroup = activity.window.decorView as ViewGroup

/**
 * Where the screen's own furniture ends: below its title bar, or failing that below the status bar.
 * Read off the window rather than waited for, since these bars are added long after the window
 * handed its insets out — otherwise the first thing one does is sit on the clock.
 */
private fun topOf(root: ViewGroup): Int {
  val actionBar =
    root.findViewById<View>(AppCompatR.id.action_bar_container)?.takeIf {
      it.isVisible && it.height > 0
    }
  return actionBar?.bottom
    ?: ViewCompat.getRootWindowInsets(root)?.getInsets(WindowInsetsCompat.Type.systemBars())?.top
    ?: 0
}

/** Takes a bar down again, if it is still up. */
internal fun removeFromTop(view: View) {
  (view.parent as? ViewGroup)?.removeView(view)
}
