/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.passwordstore.ui.dialogs

import android.app.Activity
import androidx.annotation.StringRes
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
    removeFromTop(binding.root)
  }

  companion object {

    fun show(activity: Activity, message: CharSequence): ProgressOverlay {
      val binding =
        ViewProgressOverlayBinding.inflate(activity.layoutInflater, topBarParent(activity), false)
      binding.progressMessage.text = message
      placeAtTopOf(activity, binding.root)
      return ProgressOverlay(binding)
    }

    fun show(activity: Activity, @StringRes message: Int): ProgressOverlay =
      show(activity, activity.getString(message))
  }
}
