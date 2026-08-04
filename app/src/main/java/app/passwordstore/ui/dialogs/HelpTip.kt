/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.passwordstore.ui.dialogs

import android.view.View
import androidx.annotation.StringRes
import androidx.appcompat.widget.TooltipCompat

/**
 * Puts an explanation behind a question mark instead of under the thing it explains.
 *
 * A paragraph beneath every setting reads as part of the form and pushes the next question off the
 * screen, while the explanation is only wanted once. Tapping shows the platform's own tooltip,
 * which is also what a long press already does — so the icon behaves the way the rest of the system
 * does, and the reader who does not need it loses nothing but a glance.
 */
fun View.showsTip(@StringRes tip: Int) {
  val text = context.getString(tip)
  contentDescription = text
  TooltipCompat.setTooltipText(this, text)
  setOnClickListener { it.performLongClick() }
}
