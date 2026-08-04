/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.passwordstore.ui.dialogs

import android.content.Context
import android.graphics.Color
import androidx.appcompat.R as AppCompatR
import androidx.appcompat.app.AlertDialog
import app.passwordstore.R
import com.google.android.material.R as MaterialR
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Says what will go wrong, and lets the user do it anyway.
 *
 * The app asks this question in several places — a passphrase too weak to protect a key, a folder
 * whose existing entries will not be re-encrypted, an entry about to be encrypted to keys that
 * cannot open it — and each had grown its own wording and its own buttons. They are the same
 * question, so they now look the same: the warning icon, the reason, a button naming the thing
 * being done, and a cancel that does not compete with it for attention.
 */
object WarningDialog {

  fun show(
    context: Context,
    title: CharSequence,
    message: CharSequence,
    proceedLabel: CharSequence,
    onDismiss: () -> Unit = {},
    onProceed: () -> Unit,
  ) {
    val dialog =
      MaterialAlertDialogBuilder(context)
        .setIcon(R.drawable.ic_warning_red_24dp)
        .setTitle(title)
        .setMessage(message)
        .setPositiveButton(proceedLabel) { _, _ -> onProceed() }
        .setNegativeButton(R.string.dialog_cancel, null)
        .setCancelable(false)
        .setOnDismissListener { onDismiss() }
        .create()
    // Going back is not the action being offered, so it does not wear the colour of the one that
    // is — the same distinction the rest of the app's prompts draw.
    dialog.setOnShowListener {
      dialog
        .getButton(AlertDialog.BUTTON_NEGATIVE)
        .setTextColor(
          MaterialColors.getColor(
            dialog.listView ?: dialog.window?.decorView ?: return@setOnShowListener,
            MaterialR.attr.colorOnSurfaceVariant,
            Color.TRANSPARENT,
          )
        )
    }
    dialog.show()
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
