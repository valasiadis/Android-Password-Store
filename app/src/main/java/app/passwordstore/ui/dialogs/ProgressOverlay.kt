/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.passwordstore.ui.dialogs

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.appcompat.R as AppCompatR
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.marginTop
import androidx.core.view.updateLayoutParams
import app.passwordstore.databinding.ViewProgressOverlayBinding

/**
 * Says that something is happening, for the work that takes long enough to look like nothing is.
 *
 * Cloning a store, talking to a server, generating a key: these used to say so in a snackbar at the
 * foot of the screen, where a message is easy to miss and easily confused with the ones reporting
 * that something is done. Waiting is now said at the top, as a spinner and a line beside it, and
 * stays there for as long as the waiting lasts.
 *
 * It attaches to the activity's content view rather than being a dialog, so the screen underneath
 * stays usable and whatever dialogs the work itself raises — a passphrase prompt, a smartcard
 * prompt — appear over it rather than fighting it.
 */
class ProgressOverlay private constructor(private val binding: ViewProgressOverlayBinding) {

  /** Says something else while the same piece of work moves on to its next part. */
  fun setMessage(message: CharSequence) {
    binding.progressMessage.text = message
  }

  fun dismiss() {
    (binding.root.parent as? ViewGroup)?.removeView(binding.root)
  }

  companion object {

    fun show(activity: Activity, message: CharSequence): ProgressOverlay {
      // The window's own root rather than its content: an action bar is drawn over the top of the
      // content on some screens, and would swallow a bar that sits there.
      val content = activity.window.decorView as ViewGroup
      val binding = ViewProgressOverlayBinding.inflate(activity.layoutInflater, content, false)
      binding.progressMessage.text = message
      // Below the status bar and whatever the screen puts at its top edge, so it reads as being
      // about the screen rather than as part of it.
      val margin = binding.root.marginTop
      ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, windowInsets ->
        val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
        view.updateLayoutParams<ViewGroup.MarginLayoutParams> { topMargin = insets.top + margin }
        windowInsets
      }
      content.addView(binding.root)
      // Under whatever the screen already has up there — its title bar, or failing that the
      // status bar. Added long after the window handed its insets out, so they are read off it
      // rather than waited for; otherwise the first thing the bar does is sit on the clock.
      val actionBar =
        content.findViewById<View>(AppCompatR.id.action_bar_container)?.takeIf {
          it.isVisible && it.height > 0
        }
      val top =
        actionBar?.bottom
          ?: ViewCompat.getRootWindowInsets(content)
            ?.getInsets(WindowInsetsCompat.Type.systemBars())
            ?.top
          ?: 0
      binding.root.updateLayoutParams<ViewGroup.MarginLayoutParams> { topMargin = top + margin }
      return ProgressOverlay(binding)
    }

    fun show(activity: Activity, @StringRes message: Int): ProgressOverlay =
      show(activity, activity.getString(message))
  }
}
