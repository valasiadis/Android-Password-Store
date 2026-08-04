/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.passwordstore.ui.dialogs

import android.app.Activity
import androidx.annotation.StringRes
import app.passwordstore.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Says that something did not work, and waits to be acknowledged.
 *
 * Failures used to slide past in a snackbar at the foot of the screen, where a message that matters
 * — an entry that was not saved, a key that could not be read — was as easy to miss as one that
 * does not. What went wrong is worth stopping for; what went right is not, and goes to [Notice].
 */
object ErrorDialog {

  fun show(activity: Activity, message: CharSequence, @StringRes titleRes: Int = R.string.error) {
    if (activity.isFinishing || activity.isDestroyed) return
    MaterialAlertDialogBuilder(activity)
      .setIcon(R.drawable.ic_crossmark_red_24dp)
      .setTitle(titleRes)
      .setMessage(message)
      .setPositiveButton(R.string.dialog_ok, null)
      .show()
  }

  fun show(activity: Activity, @StringRes message: Int, @StringRes titleRes: Int = R.string.error) =
    show(activity, activity.getString(message), titleRes)
}
