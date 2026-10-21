/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.util.extensions

import android.app.Activity
import android.os.Build
import android.os.SystemClock
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
  // When the keyboard's animation last told this window anything, to tell one still running from
  // one that has gone quiet without ever saying it ended.
  var lastHeardAt = 0L
  ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
    if (!moving) onMove(insets.keyboardOverlap)
    onShown(insets.isVisible(WindowInsetsCompat.Type.ime()))
    insets
  }
  // The keyboard can come and go for another window while this one waits behind it — a dialog's
  // field, closed with the keyboard still up as the next dialog opens. Then this window may be told
  // an animation is starting and never that it ended, and goes on ignoring the insets that say
  // where the keyboard really is, holding everything up for a keyboard that is no longer there.
  // Coming back to the front is when to look: a moment later, so that an animation of this
  // window's own that is really running has had the time to report a frame, and is left to it.
  // One that has said nothing for that long is not running, and what is settled is read off the
  // insets as they stand.
  val resync = Runnable {
    if (moving && SystemClock.uptimeMillis() - lastHeardAt < STALLED_ANIMATION_MS) {
      return@Runnable
    }
    moving = false
    val insets = ViewCompat.getRootWindowInsets(root) ?: return@Runnable
    onMove(insets.keyboardOverlap)
    onShown(insets.isVisible(WindowInsetsCompat.Type.ime()))
  }
  val onFocus = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
    root.removeCallbacks(resync)
    if (hasFocus) root.postDelayed(resync, STALLED_ANIMATION_MS)
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
        view.removeCallbacks(resync)
      }
    }
  )
  if (root.isAttachedToWindow) root.viewTreeObserver.addOnWindowFocusChangeListener(onFocus)
  ViewCompat.setWindowInsetsAnimationCallback(
    root,
    object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {

      override fun onPrepare(animation: WindowInsetsAnimationCompat) {
        if (animation.isKeyboard) {
          moving = true
          lastHeardAt = SystemClock.uptimeMillis()
        }
      }

      override fun onProgress(
        insets: WindowInsetsCompat,
        runningAnimations: List<WindowInsetsAnimationCompat>,
      ): WindowInsetsCompat {
        if (runningAnimations.any { it.isKeyboard }) {
          lastHeardAt = SystemClock.uptimeMillis()
          onMove(insets.keyboardOverlap)
        }
        return insets
      }

      override fun onEnd(animation: WindowInsetsAnimationCompat) {
        if (animation.isKeyboard) moving = false
      }
    },
  )
}

/**
 * How long a keyboard animation may go without a frame before it is taken to have stopped. A
 * running one reports every frame, a few tens of milliseconds apart at the very slowest.
 */
private const val STALLED_ANIMATION_MS = 200L

/** How far the keyboard reaches into the screen, past the room the navigation bar already had. */
private val WindowInsetsCompat.keyboardOverlap
  get() =
    (getInsets(WindowInsetsCompat.Type.ime()).bottom -
        getInsets(WindowInsetsCompat.Type.navigationBars()).bottom)
      .coerceAtLeast(0)

private val WindowInsetsAnimationCompat.isKeyboard
  get() = typeMask and WindowInsetsCompat.Type.ime() != 0
