/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.passwordstore.ui.dialogs

import android.content.Context
import android.graphics.drawable.InsetDrawable
import androidx.annotation.StyleRes
import androidx.appcompat.content.res.AppCompatResources
import app.passwordstore.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * What every dialog in the app is built with, so that they all look alike.
 *
 * It gives a dialog the hairline outline the app's other containers carry. That matters most for
 * the prompts raised over screens that are blanked out for the camera's sake, where a dialog with
 * no edge of its own is a shape floating in the dark — but a question looks the same wherever it is
 * asked, so every dialog has it. Set on the dialog rather than in the dialog theme, because a
 * background set there is inherited by the dialog's own panels and the edge ends up drawn around
 * each of them. Everything else a dialog's look depends on comes from the theme.
 */
open class AppDialogBuilder(context: Context, @StyleRes themeOverlay: Int = 0) :
  MaterialAlertDialogBuilder(context, themeOverlay) {

  init {
    val inset = context.resources.getDimensionPixelSize(R.dimen.dialog_inset)
    background =
      InsetDrawable(
        AppCompatResources.getDrawable(context, R.drawable.dialog_outlined_background),
        inset,
        0,
        inset,
        0,
      )
  }
}
