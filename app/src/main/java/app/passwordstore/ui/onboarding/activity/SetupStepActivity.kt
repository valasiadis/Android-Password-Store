/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.onboarding.activity

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.annotation.DrawableRes
import androidx.annotation.LayoutRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.core.os.BundleCompat
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
 *
 * A step carries the step after it, and starts that one rather than finishing itself, so the flow
 * is a stack: back goes to the question before, with its answer still on the screen, instead of
 * abandoning setup altogether. Once the last step is answered the whole stack closes at once, and
 * whoever started the flow is handed what the first step decided.
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

  /**
   * The step has an answer and the user asked to go on with it. Implementations end by calling
   * [proceed] with whatever they decided.
   */
  protected abstract fun onNext()

  /** What this step decided, held while the steps after it are answered. */
  private var stepResult: Intent? = null

  private val nextStepAction =
    registerForActivityResult(StartActivityForResult()) { result ->
      // The rest of the flow is done, so this step is too; anything else means the user came back
      // here, and here is where they stay.
      if (result.resultCode == RESULT_OK) finishStep()
    }

  /**
   * Moves on: to the step after this one if there is one, or out of the flow with [answer], which
   * whoever started it receives. The answer travels forward as well, since later steps may have
   * something to say about it — the key chosen here names the person the next step suggests.
   */
  protected fun proceed(answer: Intent? = null) {
    stepResult = answer
    val next = IntentCompat.getParcelableExtra(intent, EXTRA_NEXT_STEP, Intent::class.java)
    if (next == null) {
      finishStep()
      return
    }
    answer?.extras?.let(next::putExtras)
    nextStepAction.launch(next)
  }

  private fun finishStep() {
    setResult(RESULT_OK, stepResult)
    finish()
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    stepResult = savedInstanceState?.let {
      BundleCompat.getParcelable(it, STATE_RESULT, Intent::class.java)
    }
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

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    outState.putParcelable(STATE_RESULT, stepResult)
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

    /** The step to start once this one is answered, if this one is not the last. */
    const val EXTRA_NEXT_STEP = "SETUP_NEXT_STEP"

    /** The keys the store is being set up for, passed along the flow and back out of it. */
    const val EXTRA_KEY_IDS = "SETUP_KEY_IDS"

    private const val STATE_RESULT = "SETUP_STEP_RESULT"
  }
}
