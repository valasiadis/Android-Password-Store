/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.passwordstore.util.git

/**
 * What the editor wrote and has not yet recorded, waiting for the screen it handed back to.
 *
 * The editor returns as soon as the file is written and leaves the commit to whichever screen comes
 * next. That hand-off is kept here rather than in the intent that opens the entry: the screen that
 * shows an entry can be started by any app, and an intent-borne "commit this, and put these files
 * back if it fails" is an instruction from a stranger. Held in memory, it can only have come from
 * this app's own editor.
 *
 * One save is in flight at a time — the editor writes a file and leaves — so one is all this holds,
 * and taking it clears it.
 */
object PendingCommit {

  private var pending: Pending? = null

  /** Records what a save changed, for the next screen to commit. */
  fun record(message: String, touchedPaths: List<String>) {
    pending = Pending(message, touchedPaths)
  }

  /** Takes what is waiting, if anything is, leaving nothing behind. */
  fun take(): Pending? = pending.also { pending = null }

  data class Pending(val message: String, val touchedPaths: List<String>)
}
