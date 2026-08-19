/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.passwordstore.ui.dialogs

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.annotation.DrawableRes
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity
import androidx.transition.AutoTransition
import androidx.transition.TransitionManager
import app.passwordstore.R
import app.passwordstore.databinding.ViewCardPromptBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * What the app puts on the screen while a card is in its hands.
 *
 * A sheet up from the bottom, as the Yubico Authenticator does it and for the same reason: what is
 * being asked concerns the back of the phone, not the screen, and a sheet leaves the screen where
 * it was rather than covering it with a box in the middle. One sheet for the whole of an operation,
 * told what to say as the operation moves through it — present the card, hold it there, touch it,
 * lift it off — with the waiting drawn as a ring around the mark: a closed circle while the card
 * has yet to arrive, turning while it is being worked. That is the stretch that takes time and the
 * stretch where the card must not be moved, and it used to look exactly like the stretch where
 * nothing was happening at all.
 *
 * Only what the card needs of the user is said here. Asking for the PIN is its own dialog, since
 * that is a question with an answer rather than a state to wait out.
 */
class CardPrompt private constructor(private val binding: ViewCardPromptBinding) {

  private var sheet: BottomSheetDialog? = null
  private var pulse: ObjectAnimator? = null

  /** One thing the prompt can be saying. */
  class State(
    @DrawableRes val mark: Int,
    val title: String,
    val message: String,
    /** Whether the mark waits with a pulse, which is what tells presenting apart from working. */
    val waiting: Boolean,
    /** Whether the card is being worked, and so must be left where it is. */
    val working: Boolean,
    val cancellable: Boolean = true,
  )

  /** Says [state], animating the change from whatever was being said before. */
  fun show(state: State) {
    val shown = sheet
    if (shown == null || !shown.isShowing) return
    TransitionManager.beginDelayedTransition(binding.root, AutoTransition().setDuration(CHANGE_MS))
    binding.cardMark.setImageResource(state.mark)
    binding.cardTitle.text = state.title
    binding.cardMessage.text = state.message
    binding.cardMessage.isVisible = state.message.isNotEmpty()
    binding.cardRingBusy.isVisible = state.working
    binding.cardRingIdle.isVisible = !state.working
    binding.cardClose.isVisible = state.cancellable
    shown.setCancelable(state.cancellable)
    shown.setCanceledOnTouchOutside(state.cancellable)
    if (state.waiting) startPulse() else stopPulse()
  }

  /**
   * Marks the operation done and takes the sheet away.
   *
   * The tick stays up for a moment before it goes: an operation that finishes as fast as a card can
   * answer would otherwise be a sheet that flashed and vanished, leaving the user unsure whether
   * their card had been read at all.
   */
  fun dismissWithSuccess(onDone: () -> Unit = {}) {
    val shown = sheet
    if (shown == null || !shown.isShowing) {
      onDone()
      return
    }
    stopPulse()
    TransitionManager.beginDelayedTransition(binding.root, AutoTransition().setDuration(CHANGE_MS))
    binding.cardMark.setImageResource(R.drawable.ic_done_24dp)
    binding.cardRingBusy.isVisible = false
    binding.cardRingIdle.isVisible = false
    binding.cardClose.isVisible = false
    binding.root.postDelayed(
      {
        dismiss()
        onDone()
      },
      SUCCESS_MS,
    )
  }

  fun dismiss() {
    stopPulse()
    sheet?.let { if (it.isShowing) it.dismiss() }
    sheet = null
  }

  val isShowing: Boolean
    get() = sheet?.isShowing == true

  private fun startPulse() {
    if (pulse?.isRunning == true) return
    pulse =
      ObjectAnimator.ofPropertyValuesHolder(
          binding.cardMark,
          PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, PULSE_SCALE),
          PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, PULSE_SCALE),
        )
        .apply {
          duration = PULSE_MS
          repeatMode = ValueAnimator.REVERSE
          repeatCount = ValueAnimator.INFINITE
          interpolator = AccelerateDecelerateInterpolator()
          start()
        }
  }

  private fun stopPulse() {
    pulse?.cancel()
    pulse = null
    binding.cardMark.scaleX = 1f
    binding.cardMark.scaleY = 1f
  }

  companion object {
    // Small enough that the mark stays inside its ring while it breathes.
    private const val PULSE_SCALE = 1.08f
    private const val PULSE_MS = 850L
    private const val CHANGE_MS = 180L
    private const val SUCCESS_MS = 550L

    /**
     * Puts the sheet on the screen saying [state]. [onCancel] runs when the user takes it down, by
     * its close button or by the ways a sheet offers of its own.
     */
    fun show(activity: FragmentActivity, state: State, onCancel: () -> Unit): CardPrompt {
      val binding = ViewCardPromptBinding.inflate(activity.layoutInflater)
      val prompt = CardPrompt(binding)
      val sheet =
        BottomSheetDialog(activity).apply {
          setContentView(binding.root)
          setOnCancelListener { onCancel() }
          // Nothing here is worth reading half of, so it arrives at its full height and leaves
          // rather than resting half-open.
          behavior.state = BottomSheetBehavior.STATE_EXPANDED
          behavior.skipCollapsed = true
        }
      sheet.show()
      prompt.sheet = sheet
      binding.cardClose.setOnClickListener {
        prompt.dismiss()
        onCancel()
      }
      prompt.show(state)
      return prompt
    }
  }
}
