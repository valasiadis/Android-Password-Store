/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.passwordstore.ui.dialogs

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.view.View
import android.view.ViewOutlineProvider
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.R as AppCompatR
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.FragmentActivity
import androidx.transition.AutoTransition
import androidx.transition.TransitionManager
import app.passwordstore.R
import app.passwordstore.databinding.ViewCardPromptBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.color.MaterialColors

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

  /** One thing the prompt can be saying. */
  class State(
    @DrawableRes val mark: Int,
    val title: String,
    val message: String,
    /** Whether the card is being worked, and so must be left where it is. */
    val working: Boolean,
    /**
     * Whether the mark is a card, and so carries the ring. The touch and the tick are neither round
     * nor waiting on the card, and wear no ring at all.
     */
    val framed: Boolean = true,
    /**
     * Whether the mark is drawn oversized so that the ring stands where its own outer circle was.
     * True of the contactless mark, which is a circle; false of a mark that has to fit inside the
     * ring rather than be cropped by it.
     */
    val cropped: Boolean = true,
    val cancellable: Boolean = true,
  )

  /** Says [state], animating the change from whatever was being said before. */
  fun show(state: State) {
    val shown = sheet
    if (shown == null || !shown.isShowing) return
    TransitionManager.beginDelayedTransition(binding.root, AutoTransition().setDuration(CHANGE_MS))
    binding.cardTitle.text = state.title
    // Kept in the layout even when it says nothing, so that the sheet is the same height
    // throughout and one state turns into the next rather than the whole thing resizing under it.
    binding.cardMessage.text = state.message
    binding.cardMarkFramed.isVisible = state.framed
    binding.cardMarkPlain.isVisible = !state.framed
    if (state.framed) {
      binding.cardMark.setImageResource(state.mark)
      val size =
        binding.root.resources.getDimensionPixelSize(
          if (state.cropped) R.dimen.card_prompt_mark_size else R.dimen.card_prompt_mark_fit
        )
      binding.cardMark.updateLayoutParams {
        width = size
        height = size
      }
      binding.cardRingBusy.isVisible = state.working
      binding.cardRingIdle.isVisible = !state.working
    } else {
      binding.cardMarkPlain.setImageResource(state.mark)
    }
    binding.cardClose.isVisible = state.cancellable
    shown.setCancelable(state.cancellable)
    shown.setCanceledOnTouchOutside(state.cancellable)
  }

  fun dismiss() {
    sheet?.let { if (it.isShowing) it.dismiss() }
    sheet = null
  }

  val isShowing: Boolean
    get() = sheet?.isShowing == true

  companion object {
    private const val CHANGE_MS = 180L

    /** How long the tick stays up: long enough to be read before whatever comes next. */
    const val SUCCESS_MS = 1_200L

    private const val TRACK_ALPHA = 0x40

    /**
     * The operation is done. Said by the sheet already up, which then goes or says the next thing.
     */
    fun done(context: Context, @StringRes title: Int) =
      State(
        mark = R.drawable.ic_done_24dp,
        title = context.getString(title),
        message = "",
        working = false,
        framed = false,
        cancellable = false,
      )

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
      // The unlit part of the ring: the same colour kept faint, and the same width, so the circle
      // is whole in both states — a complete ring while the card is awaited, and a track with the
      // bright part travelling round it while the card is worked. Given to both indicators, since
      // the two of them stand in for one ring.
      val track =
        ColorUtils.setAlphaComponent(
          MaterialColors.getColor(
            binding.cardRingBusy,
            AppCompatR.attr.colorPrimary,
            Color.TRANSPARENT,
          ),
          TRACK_ALPHA,
        )
      binding.cardRingBusy.trackColor = track
      binding.cardRingIdle.trackColor = track
      // The middle of the mark, cut out of it into the ring.
      binding.cardMarkClip.clipToOutline = true
      binding.cardMarkClip.outlineProvider =
        object : ViewOutlineProvider() {
          override fun getOutline(view: View, outline: Outline) {
            outline.setOval(0, 0, view.width, view.height)
          }
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
