/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.passwordstore.ui.dialogs

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import app.passwordstore.R
import app.passwordstore.databinding.SheetInputBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * A question that wants something typed — a PIN, a passphrase, a name — asked from a sheet that
 * rises from the bottom, with the setup flow's buttons: the way out quiet on the left, the action
 * filled on the right.
 *
 * Such questions used to be dialogs, which squeezed a full-size field into a box in the middle of
 * the screen with two text buttons under it. A sheet gives the field the width of the screen, sits
 * just above the keyboard it brings up, and is dismissed the way every other sheet in the app is.
 * Questions with nothing to type — a warning, a confirmation, a failure — stay dialogs.
 */
class InputSheet
private constructor(
  /** The sheet itself, for whatever a dialog is asked to do: be shown, kept, cancelled, secured. */
  val dialog: BottomSheetDialog,
  /** The action, filled; enabled or not as the fields allow. */
  val primary: Button,
  /** The way out, quiet. */
  val secondary: Button,
) {

  companion object {

    /**
     * A sheet asking [title], explained by [message] where given, holding [content] — the fields,
     * which bring their own room from the sheet's edges.
     *
     * [primaryLabel] names the action and [onPrimary] does it; the sheet closes after it unless
     * [dismissOnPrimary] is false, for an action that checks what was typed and may keep the sheet
     * open. The way out cancels the sheet, as going back does, unless [onSecondary] says otherwise.
     * The keyboard comes up with the sheet unless [showKeyboard] is false, for a sheet whose fields
     * are there to be changed rather than filled in.
     */
    fun create(
      context: Context,
      title: CharSequence?,
      content: View,
      primaryLabel: CharSequence,
      message: CharSequence? = null,
      secondaryLabel: CharSequence = context.getString(R.string.dialog_cancel),
      dismissOnPrimary: Boolean = true,
      showKeyboard: Boolean = true,
      onSecondary: (() -> Unit)? = null,
      onPrimary: () -> Unit,
    ): InputSheet {
      val dialog = BottomSheetDialog(context)
      val binding = SheetInputBinding.inflate(LayoutInflater.from(context))
      binding.sheetTitle.text = title
      binding.sheetTitle.isVisible = !title.isNullOrEmpty()
      binding.sheetMessage.text = message
      binding.sheetMessage.isVisible = !message.isNullOrEmpty()
      binding.sheetContent.addView(content)
      val primary = binding.sheetFooter.setupNext
      val secondary = binding.sheetFooter.setupBack
      primary.text = primaryLabel
      secondary.text = secondaryLabel
      primary.setOnClickListener {
        onPrimary()
        if (dismissOnPrimary) dialog.dismiss()
      }
      secondary.setOnClickListener { onSecondary?.invoke() ?: dialog.cancel() }
      dialog.setContentView(binding.root)
      // Open all the way and stay there: a sheet that can sit half open would leave its buttons
      // below the keyboard.
      dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
      dialog.behavior.skipCollapsed = true
      dialog.window?.setSoftInputMode(
        WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
          if (showKeyboard) WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
          else WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
      )
      return InputSheet(dialog, primary, secondary)
    }

    fun create(
      context: Context,
      @StringRes title: Int,
      content: View,
      @StringRes primaryLabel: Int,
      message: CharSequence? = null,
      dismissOnPrimary: Boolean = true,
      showKeyboard: Boolean = true,
      onSecondary: (() -> Unit)? = null,
      onPrimary: () -> Unit,
    ): InputSheet =
      create(
        context = context,
        title = context.getString(title),
        content = content,
        primaryLabel = context.getString(primaryLabel),
        message = message,
        dismissOnPrimary = dismissOnPrimary,
        showKeyboard = showKeyboard,
        onSecondary = onSecondary,
        onPrimary = onPrimary,
      )
  }
}
