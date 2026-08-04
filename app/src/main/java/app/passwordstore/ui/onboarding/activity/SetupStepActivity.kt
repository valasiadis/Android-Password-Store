/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.onboarding.activity

import android.os.Bundle
import android.view.View
import androidx.annotation.DrawableRes
import androidx.annotation.LayoutRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import app.passwordstore.R
import app.passwordstore.databinding.ActivitySetupStepBinding
import app.passwordstore.util.extensions.enableEdgeToEdgeView
import app.passwordstore.util.extensions.viewBinding

/**
 * One question of setting a store up, asked on a screen of its own.
 *
 * These used to be dialogs stacked on the screen that started the store, which made a sequence of
 * decisions look like an interruption of one. As screens they can say where in the flow the user
 * is, be left with the back gesture and returned to, and put the way onwards where a setup flow
 * puts it — bottom right, unavailable until the step has an answer.
 *
 * Subclasses provide the content between the heading and that button, and what pressing it means.
 * What each step stores is stored by the same code the settings use, so the answer given here and
 * the answer changed later are the same answer.
 */
abstract class SetupStepActivity : AppCompatActivity() {

  private val binding by viewBinding(ActivitySetupStepBinding::inflate)

  /** The layout holding this step's own fields, shown under the heading. */
  @get:LayoutRes protected abstract val contentLayout: Int

  @get:StringRes protected abstract val stepTitle: Int

  @get:StringRes protected abstract val stepMessage: Int

  @get:DrawableRes protected abstract val stepIcon: Int

  /** What the button in the corner says, for steps that end in something other than "next". */
  @get:StringRes protected open val nextLabel: Int = R.string.setup_next

  /** Called once the step's own layout is in place, to bind it. */
  protected abstract fun onContentInflated(content: View, savedInstanceState: Bundle?)

  /** The step has an answer and the user asked to go on with it. */
  protected abstract fun onNext()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    supportActionBar?.hide()
    enableEdgeToEdgeView(binding.root)
    setContentView(binding.root)

    binding.header.show(
      icon = stepIcon,
      title = stepTitle,
      message = stepMessage,
      step = intent.getIntExtra(EXTRA_STEP, 0),
      stepCount = intent.getIntExtra(EXTRA_STEP_COUNT, 0),
    )
    binding.setupFooter.setupNext.setText(nextLabel)

    val content = layoutInflater.inflate(contentLayout, binding.setupContent, false)
    binding.setupContent.addView(content)
    onContentInflated(content, savedInstanceState)

    binding.setupFooter.setupBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
    binding.setupFooter.setupNext.setOnClickListener { onNext() }
  }

  /**
   * Whether the step can be left by its own button. Steps start with no answer, so it is closed
   * until they say otherwise.
   */
  protected fun setCanProceed(canProceed: Boolean) {
    binding.setupFooter.setupNext.isEnabled = canProceed
  }

  companion object {

    const val EXTRA_STEP = "SETUP_STEP"
    const val EXTRA_STEP_COUNT = "SETUP_STEP_COUNT"
  }
}
