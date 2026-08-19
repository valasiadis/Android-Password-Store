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
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity
import androidx.transition.AutoTransition
import androidx.transition.TransitionManager
import app.passwordstore.R
import app.passwordstore.databinding.ViewCardPromptBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * What the app puts on the screen while a card is in its hands.
 *
 * One prompt for the whole of an operation, told what to say as the operation moves through it:
 * present the card, hold it there, touch it, lift it off. The mark at the top waits with a slow
 * pulse and stands still once the card is being worked, where a bar underneath says that something
 * is happening — the stretch where the card must not be moved is exactly the stretch that otherwise
 * looks like nothing happening at all. It closes itself when the operation is done.
 *
 * Only what the card needs of the user is said here. Asking for the PIN is its own dialog, since
 * that is a question with an answer rather than a state to wait out.
 */
class CardPrompt private constructor(private val binding: ViewCardPromptBinding) {

  private var dialog: AlertDialog? = null
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
    val shown = dialog
    if (shown == null || !shown.isShowing) return
    TransitionManager.beginDelayedTransition(binding.root, AutoTransition().setDuration(CHANGE_MS))
    binding.cardMark.setImageResource(state.mark)
    binding.cardTitle.text = state.title
    binding.cardMessage.text = state.message
    binding.cardMessage.isVisible = state.message.isNotEmpty()
    // Kept in the layout rather than removed from it, so the prompt does not change height when the
    // bar comes and goes.
    binding.cardProgress.isInvisible = !state.working
    binding.cardCancel.isVisible = state.cancellable
    shown.setCancelable(state.cancellable)
    if (state.waiting) startPulse() else stopPulse()
  }

  /**
   * Marks the operation done and takes the prompt away.
   *
   * The tick stays up for a moment before it goes: an operation that finishes as fast as a card can
   * answer would otherwise be a prompt that flashed and vanished, leaving the user unsure whether
   * their card had been read at all.
   */
  fun dismissWithSuccess(onDone: () -> Unit = {}) {
    val shown = dialog
    if (shown == null || !shown.isShowing) {
      onDone()
      return
    }
    stopPulse()
    TransitionManager.beginDelayedTransition(binding.root, AutoTransition().setDuration(CHANGE_MS))
    binding.cardMark.setImageResource(R.drawable.ic_done_24dp)
    binding.cardProgress.isInvisible = true
    binding.cardCancel.isVisible = false
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
    dialog?.let { if (it.isShowing) it.dismiss() }
    dialog = null
  }

  val isShowing: Boolean
    get() = dialog?.isShowing == true

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
    private const val PULSE_SCALE = 1.12f
    private const val PULSE_MS = 850L
    private const val CHANGE_MS = 180L
    private const val SUCCESS_MS = 550L

    /**
     * Puts the prompt on the screen saying [state]. [onCancel] runs when the user takes it down, by
     * its own button or by the ways the system offers.
     */
    fun show(activity: FragmentActivity, state: State, onCancel: () -> Unit): CardPrompt {
      val binding = ViewCardPromptBinding.inflate(activity.layoutInflater)
      val prompt = CardPrompt(binding)
      val dialog =
        MaterialAlertDialogBuilder(activity)
          .outlined(activity)
          .setView(binding.root)
          .setOnCancelListener { onCancel() }
          .create()
      dialog.window?.setWindowAnimations(R.style.CardPromptAnimation)
      dialog.show()
      prompt.dialog = dialog
      binding.cardCancel.setOnClickListener {
        prompt.dismiss()
        onCancel()
      }
      prompt.show(state)
      return prompt
    }
  }
}
