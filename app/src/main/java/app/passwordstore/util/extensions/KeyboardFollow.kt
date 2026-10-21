/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.util.extensions

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Reports how far the keyboard reaches into this screen, frame by frame as it moves.
 *
 * Letting the window resize puts whatever sits along the bottom at its new height in one step, a
 * whole animation before the keyboard arrives underneath it, and nothing can be drawn where it used
 * to be once the window has shrunk. So the window is left the size of the screen and [onMove] is
 * handed the overlap to hold things clear of — read off the keyboard's own animation while it runs,
 * and off the settled insets otherwise, so the two travel together in both directions.
 *
 * [onShown] is told whether the keyboard is on its way in or out, once per transition and at the
 * start of it, which is when anything that comes and goes with the keyboard should be moving.
 *
 * Only from Android 11, which is where a keyboard reports its animation. Before that the window
 * resize is all there is, and the bottom of the screen keeps arriving early.
 */
fun Activity.followsKeyboard(
  root: View,
  onShown: (showing: Boolean) -> Unit = {},
  onMove: (overlap: Int) -> Unit,
) {
  if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
  window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
  // Set while the keyboard is between its two resting places, when the insets handed to the
  // listener are the end of the animation rather than the frame being drawn.
  var moving = false
  ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
    if (!moving) onMove(insets.keyboardOverlap)
    onShown(insets.isVisible(WindowInsetsCompat.Type.ime()))
    insets
  }
  // The keyboard can come and go for another window while this one waits behind it — a dialog's
  // field, closed with the keyboard still up as the next dialog opens. Then this window may be told
  // an animation is starting and never that it ended, and goes on ignoring the insets that say
  // where the keyboard really is, holding everything up for a keyboard that is no longer there.
  // Coming back to the front is when it can trust them again: whatever is running from then on
  // reports its own frames, and what is settled is read off the insets as they stand.
  val onFocus = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
    if (!hasFocus) return@OnWindowFocusChangeListener
    moving = false
    val insets = ViewCompat.getRootWindowInsets(root) ?: return@OnWindowFocusChangeListener
    onMove(insets.keyboardOverlap)
    onShown(insets.isVisible(WindowInsetsCompat.Type.ime()))
  }
  // Held only while the view is on screen: the window outlives a fragment's view, and a listener
  // left with it would go on moving views that are gone.
  root.addOnAttachStateChangeListener(
    object : View.OnAttachStateChangeListener {
      override fun onViewAttachedToWindow(view: View) {
        view.viewTreeObserver.addOnWindowFocusChangeListener(onFocus)
      }

      override fun onViewDetachedFromWindow(view: View) {
        view.viewTreeObserver.removeOnWindowFocusChangeListener(onFocus)
      }
    }
  )
  if (root.isAttachedToWindow) root.viewTreeObserver.addOnWindowFocusChangeListener(onFocus)
  ViewCompat.setWindowInsetsAnimationCallback(
    root,
    object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {

      override fun onPrepare(animation: WindowInsetsAnimationCompat) {
        if (animation.isKeyboard) moving = true
      }

      override fun onProgress(
        insets: WindowInsetsCompat,
        runningAnimations: List<WindowInsetsAnimationCompat>,
      ): WindowInsetsCompat {
        if (runningAnimations.any { it.isKeyboard }) onMove(insets.keyboardOverlap)
        return insets
      }

      override fun onEnd(animation: WindowInsetsAnimationCompat) {
        if (animation.isKeyboard) moving = false
      }
    },
  )
}

/** How far the keyboard reaches into the screen, past the room the navigation bar already had. */
private val WindowInsetsCompat.keyboardOverlap
  get() =
    (getInsets(WindowInsetsCompat.Type.ime()).bottom -
        getInsets(WindowInsetsCompat.Type.navigationBars()).bottom)
      .coerceAtLeast(0)

private val WindowInsetsAnimationCompat.isKeyboard
  get() = typeMask and WindowInsetsCompat.Type.ime() != 0
