/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.passwordstore.ui.dialogs

import android.app.Activity
import android.view.View
import androidx.annotation.StringRes
import app.passwordstore.R
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
class ProgressOverlay private constructor(private val bar: Bar, private val entry: Bar.Entry) {

  /** Says something else while the same piece of work moves on to its next part. */
  fun setMessage(message: CharSequence) {
    entry.message = message
    bar.showLatest()
  }

  /**
   * Ends this wait. The bar stays up while any other wait shown in it is still going, and goes back
   * to saying what the latest of those is; it comes down only with the last of them.
   */
  fun dismiss() {
    if (!bar.entries.remove(entry)) return
    if (bar.entries.isEmpty()) removeFromTop(bar.binding.root) else bar.showLatest()
  }

  /**
   * The one bar at the top of an activity, and every wait currently said in it. Work that overlaps
   * — a Git command inside an operation, an export started while a sync runs — shares it rather
   * than stacking bars, and no piece of it takes the bar down while another is still running.
   */
  private class Bar(val binding: ViewProgressOverlayBinding) {
    class Entry(var message: CharSequence)

    val entries = mutableListOf<Entry>()

    fun showLatest() {
      entries.lastOrNull()?.let { binding.progressMessage.text = it.message }
    }
  }

  companion object {

    /** Says [message] at the top of [activity], in the bar already there if there is one. */
    fun show(activity: Activity, message: CharSequence): ProgressOverlay {
      val bar =
        (topBarParent(activity).findViewWithTag<View>(TAG)?.getTag(R.id.progress_overlay_bar)
          as? Bar)
          ?: Bar(
              ViewProgressOverlayBinding.inflate(
                activity.layoutInflater,
                topBarParent(activity),
                false,
              )
            )
            .also { bar ->
              bar.binding.root.tag = TAG
              bar.binding.root.setTag(R.id.progress_overlay_bar, bar)
              placeAtTopOf(activity, bar.binding.root)
            }
      val entry = Bar.Entry(message)
      bar.entries += entry
      bar.showLatest()
      return ProgressOverlay(bar, entry)
    }

    fun show(activity: Activity, @StringRes message: Int): ProgressOverlay =
      show(activity, activity.getString(message))

    private const val TAG = "progress_overlay"
  }
}
