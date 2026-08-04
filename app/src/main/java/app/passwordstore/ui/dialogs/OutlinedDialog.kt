/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.passwordstore.ui.dialogs

import android.content.Context
import android.graphics.drawable.InsetDrawable
import androidx.appcompat.content.res.AppCompatResources
import app.passwordstore.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Gives a dialog the hairline outline the app's other containers carry.
 *
 * It matters most for the prompts raised over screens that are blanked out for the camera's sake,
 * where a dialog with no edge of its own is a shape floating in the dark. Set on the dialog rather
 * than in the dialog theme, because a background set there is inherited by the dialog's own panels
 * and the edge ends up drawn around each of them.
 */
fun MaterialAlertDialogBuilder.outlined(context: Context): MaterialAlertDialogBuilder = apply {
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
