/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.passwordstore.ui.dialogs

import android.app.Activity
import android.os.SystemClock
import androidx.annotation.StringRes
import app.passwordstore.R
import app.passwordstore.databinding.ViewTopNoticeBinding

/**
 * Says, briefly and at the top of the screen, what just happened.
 *
 * This replaces the snackbars the app used to raise from the bottom edge. A snackbar sits where the
 * thumb is, covers whatever is under it, and looks the same whether it is announcing a saved
 * password or a failure — while the app already says what it is *doing* at the top. So that is
 * where it now says what it has *done*, in a line or two words, and a failure is given a dialog
 * instead: something that went wrong is worth stopping for.
 *
 * A notice belongs to the news, not to the screen that happened to raise it. The screens that say
 * something often close in the same breath — an entry deleted, an editor saved and left — so what
 * was said is held for its few seconds and picked up again by whichever screen comes next.
 */
object Notice {

  private var pending: Pending? = null

  fun show(activity: Activity, message: CharSequence) {
    pending = Pending(message, SystemClock.elapsedRealtime() + VISIBLE_FOR_MS)
    display(activity)
  }

  fun show(activity: Activity, @StringRes message: Int): Unit =
    show(activity, activity.getString(message))

  /**
   * Shows again, on [activity], whatever is still being said — called as each screen comes to the
   * front, so a message outlives the screen that raised it.
   */
  fun resumeOn(activity: Activity) {
    val current = pending ?: return
    if (current.until <= SystemClock.elapsedRealtime()) {
      pending = null
      return
    }
    display(activity)
  }

  private fun display(activity: Activity) {
    val current = pending ?: return
    val binding =
      ViewTopNoticeBinding.inflate(activity.layoutInflater, topBarParent(activity), false)
    binding.root.text = current.message
    // Whatever this screen is already showing is the same message; a second copy would only stack.
    topBarParent(activity)
      .findViewById<android.view.View>(R.id.notice_message)
      ?.let(::removeFromTop)
    placeAtTopOf(activity, binding.root)
    binding.root.postDelayed(
      {
        removeFromTop(binding.root)
        if (pending === current) pending = null
      },
      current.until - SystemClock.elapsedRealtime(),
    )
  }

  /**
   * What is being said, and until when — so a screen arriving late shows only what is left of it.
   */
  private data class Pending(val message: CharSequence, val until: Long)

  /** Long enough to read a line, short enough not to sit over the screen it is talking about. */
  private const val VISIBLE_FOR_MS = 3_000L
}
