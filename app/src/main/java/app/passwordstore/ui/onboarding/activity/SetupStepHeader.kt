/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.onboarding.activity

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import app.passwordstore.R
import app.passwordstore.databinding.SetupStepHeaderBinding

/**
 * Introduces a setup step: what it is asking, why, and where in the flow it is being asked.
 *
 * The place in the flow is optional because the same screens are also reached from the settings,
 * where there is no flow to be a step of.
 */
fun SetupStepHeaderBinding.show(
  @DrawableRes icon: Int,
  @StringRes title: Int,
  @StringRes message: Int,
  step: Int = 0,
  stepCount: Int = 0,
) {
  setupIcon.setImageResource(icon)
  setupTitle.setText(title)
  setupMessage.setText(message)
  setupStepCounter.isVisible = step > 0 && stepCount > 0
  if (setupStepCounter.isVisible) {
    setupStepCounter.text = root.context.getString(R.string.setup_step_counter, step, stepCount)
  }
}
