/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.passwordstore.ui.dialogs

import android.content.Context
import androidx.annotation.StringRes
import androidx.appcompat.R as AppCompatR
import app.passwordstore.R

/**
 * Says what will go wrong, and lets the user do it anyway.
 *
 * The app asks this question in several places — a passphrase too weak to protect a key, a folder
 * whose existing entries will not be re-encrypted, an entry about to be encrypted to keys that
 * cannot open it, a key about to be deleted — and each had grown its own wording and its own
 * buttons. They are the same question, so they now look the same: the warning icon, the reason, a
 * button naming the thing being done in the colour of something that cannot be undone, and a cancel
 * that does not compete with it for attention.
 */
object WarningDialog {

  /** A builder for such a question, for the places that need more than [show] offers. */
  fun builder(context: Context): AppDialogBuilder =
    AppDialogBuilder(context, R.style.APSThemeM3_MaterialAlertDialog_Destructive).apply {
      setIcon(R.drawable.ic_warning_red_24dp)
    }

  fun show(
    context: Context,
    title: CharSequence,
    message: CharSequence?,
    proceedLabel: CharSequence,
    @StringRes cancelLabelRes: Int = R.string.dialog_cancel,
    onDismiss: () -> Unit = {},
    onProceed: () -> Unit,
  ) {
    builder(context)
      .setTitle(title)
      .setMessage(message)
      .setPositiveButton(proceedLabel) { _, _ -> onProceed() }
      .setNegativeButton(cancelLabelRes, null)
      .setCancelable(false)
      .setOnDismissListener { onDismiss() }
      .show()
  }

  fun show(
    context: Context,
    titleRes: Int,
    messageRes: Int,
    proceedLabelRes: Int = AppCompatR.string.abc_action_mode_done,
    onDismiss: () -> Unit = {},
    onProceed: () -> Unit,
  ): Unit =
    show(
      context = context,
      title = context.getString(titleRes),
      message = context.getString(messageRes),
      proceedLabel = context.getString(proceedLabelRes),
      onDismiss = onDismiss,
      onProceed = onProceed,
    )
}
