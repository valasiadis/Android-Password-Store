/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.passwordstore.ui.dialogs

import android.app.Activity
import app.passwordstore.databinding.OtpSourceSheetBinding
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * Asks where a one-time password's secret comes from — a code scanned with the camera, a code in a
 * picture, or the secret typed in — as a sheet of rows, like every other "where from" the app asks.
 * Shared by the screens that make passwords and passkeys, so the question looks the same in both.
 */
object OtpSourceSheet {

  fun show(activity: Activity, onScan: () -> Unit, onImage: () -> Unit, onManual: () -> Unit) {
    val sheet = BottomSheetDialog(activity)
    val choices = OtpSourceSheetBinding.inflate(activity.layoutInflater)
    fun choose(action: () -> Unit) {
      sheet.dismiss()
      action()
    }
    choices.otpSourceScan.setOnClickListener { choose(onScan) }
    choices.otpSourceImage.setOnClickListener { choose(onImage) }
    choices.otpSourceManual.setOnClickListener { choose(onManual) }
    sheet.setContentView(choices.root)
    sheet.show()
  }
}
