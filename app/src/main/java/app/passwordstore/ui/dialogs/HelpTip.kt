/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.passwordstore.ui.dialogs

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import androidx.annotation.StringRes
import app.passwordstore.R
import app.passwordstore.databinding.ViewHelpTipBinding

/**
 * Puts an explanation behind a question mark instead of under the thing it explains.
 *
 * A paragraph beneath every setting reads as part of the form and pushes the next question off the
 * screen, while the explanation is only wanted once. The platform's own tooltip would do, but it is
 * drawn in the platform's colours and shape rather than the app's — so this is a small container
 * like the app's others, shown under the mark that was tapped and dismissed by tapping anywhere.
 */
fun View.showsTip(@StringRes tip: Int) {
  val text = context.getString(tip)
  contentDescription = text
  setOnClickListener { anchor ->
    val binding = ViewHelpTipBinding.inflate(LayoutInflater.from(anchor.context))
    binding.root.text = text
    // Never wider than the screen it is explaining, and never so narrow that a sentence becomes a
    // column of single words.
    val screenWidth = anchor.resources.displayMetrics.widthPixels
    val margin = anchor.resources.getDimensionPixelSize(R.dimen.spacing_large)
    binding.root.maxWidth = ((screenWidth - 2 * margin) * TIP_WIDTH_FRACTION).toInt()
    binding.root.measure(
      View.MeasureSpec.makeMeasureSpec(screenWidth, View.MeasureSpec.AT_MOST),
      View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
    )
    // Centred under the mark that was tapped, and pulled back where that would take it off the
    // screen: a tip half past the edge explains half of what it was asked. A mark near one side of
    // the screen therefore keeps its tip inside it rather than centred on the mark.
    val anchorLeft = IntArray(2).also(anchor::getLocationOnScreen)[0]
    val tipWidth = binding.root.measuredWidth
    val centred = (anchor.width - tipWidth) / 2
    val offset =
      centred.coerceIn(
        // Never past either margin, and never so far back that a tip wider than the room left
        // between the margins is pushed off the side it was pulled towards.
        minimumValue = margin - anchorLeft,
        maximumValue =
          (screenWidth - margin - tipWidth - anchorLeft).coerceAtLeast(margin - anchorLeft),
      )
    PopupWindow(
        binding.root,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
      )
      .apply {
        isOutsideTouchable = true
        isFocusable = true
        showAsDropDown(anchor, offset, 0)
      }
  }
}

/** How much of the screen's width a tip may take before it wraps. */
private const val TIP_WIDTH_FRACTION = 0.8f
