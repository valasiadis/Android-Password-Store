/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.passwordstore.ui.dialogs

import android.app.Activity
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import app.passwordstore.R
import app.passwordstore.databinding.ViewTopNoticeBinding

/**
 * Says, briefly and at the top of the screen, what just happened.
 *
 * This replaces the snackbars the app used to raise from the bottom edge. A snackbar sits where the
 * thumb is, covers whatever is under it, and looks the same whether it is announcing a saved
 * password or a failure — while the app already says what it is *doing* at the top. So that is
 * where it now says what it has *done*, in green when the news is good, and a failure is given a
 * dialog instead: something that went wrong is worth stopping for.
 */
object Notice {

  fun show(activity: Activity, message: CharSequence, success: Boolean = false) {
    val binding =
      ViewTopNoticeBinding.inflate(activity.layoutInflater, topBarParent(activity), false)
    binding.root.text = message
    if (success) {
      binding.root.setTextColor(
        ContextCompat.getColor(activity, R.color.git_commit_signature_valid)
      )
    }
    placeAtTopOf(activity, binding.root)
    binding.root.postDelayed({ removeFromTop(binding.root) }, VISIBLE_FOR_MS)
  }

  fun show(activity: Activity, @StringRes message: Int, success: Boolean = false): Unit =
    show(activity, activity.getString(message), success)

  /** Long enough to read a line, short enough not to sit over the screen it is talking about. */
  private const val VISIBLE_FOR_MS = 3_000L
}
