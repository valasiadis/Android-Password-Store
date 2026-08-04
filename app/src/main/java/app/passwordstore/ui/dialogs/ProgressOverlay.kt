/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.passwordstore.ui.dialogs

import android.app.Activity
import android.view.ViewGroup
import androidx.annotation.StringRes
import app.passwordstore.databinding.ViewProgressOverlayBinding

/**
 * Says that something is happening, for the work that takes long enough to look like nothing is.
 *
 * Cloning a store, talking to a server, generating a key: until now these showed at most a
 * snackbar, which is easy to miss and leaves the screen looking idle and tappable. This covers the
 * screen instead — the work cannot be cancelled halfway, so the taps it swallows would have gone
 * nowhere.
 *
 * It attaches to the activity's content view rather than being a dialog, so it survives whatever
 * dialogs the work itself raises (a passphrase prompt, a smartcard prompt) appearing over it.
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
      val content = activity.findViewById<ViewGroup>(android.R.id.content)
      val binding = ViewProgressOverlayBinding.inflate(activity.layoutInflater, content, false)
      binding.progressMessage.text = message
      content.addView(binding.root)
      return ProgressOverlay(binding)
    }

    fun show(activity: Activity, @StringRes message: Int): ProgressOverlay =
      show(activity, activity.getString(message))
  }
}
