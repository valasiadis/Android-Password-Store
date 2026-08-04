/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.util.extensions

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PackageManager.ApplicationInfoFlags
import android.content.pm.PackageManager.PackageInfoFlags
import android.os.Build
import android.util.TypedValue
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.autofill.AutofillManager
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.fragment.app.FragmentActivity
import app.passwordstore.BuildConfig
import app.passwordstore.data.repo.PasswordRepository
import app.passwordstore.ui.crypto.PasswordCreationActivity
import app.passwordstore.ui.dialogs.ErrorDialog
import app.passwordstore.util.crypto.OpenPgpCardPrompt
import app.passwordstore.util.git.ErrorMessages
import app.passwordstore.util.git.operation.GitOperation
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.onErr
import kotlin.math.abs
import kotlin.math.max
import logcat.logcat
import org.eclipse.jgit.api.errors.CanceledException

/** Get an instance of [AutofillManager]. Only available on Android Oreo and above */
val Context.autofillManager: AutofillManager?
  get() = getSystemService()

/**
 * Shows [text] on one line, draggable sideways where it does not fit.
 *
 * The value fields that name chosen keys can hold several of them, and long names besides. Wrapping
 * grows the field and shuffles its contents about; cutting the text hides the end of it for good.
 * Kept inline, all of it stays reachable and the field keeps its shape.
 *
 * The dragging is done here rather than left to a movement method because these fields open a
 * picker when tapped, and a view that is clickable treats a drag ending inside it as a tap. A drag
 * therefore scrolls and does not open anything; a tap opens and does not scroll.
 */
@SuppressLint("ClickableViewAccessibility")
fun TextView.setInlineText(text: CharSequence) {
  setHorizontallyScrolling(true)
  setText(text)
  scrollTo(0, 0)
  val slop = ViewConfiguration.get(context).scaledTouchSlop
  var lastX = 0f
  var dragged = false
  setOnTouchListener { _, event ->
    when (event.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        lastX = event.x
        dragged = false
      }
      MotionEvent.ACTION_MOVE -> {
        val moved = lastX - event.x
        if (dragged || abs(moved) > slop) {
          dragged = true
          lastX = event.x
          val room =
            (layout?.getLineWidth(0) ?: 0f) - (width - compoundPaddingStart - compoundPaddingEnd)
          scrollX = (scrollX + moved.toInt()).coerceIn(0, max(0, room.toInt()))
        }
      }
      MotionEvent.ACTION_UP -> if (!dragged) performClick()
    }
    true
  }
}

/**
 * Hides the soft keyboard and clears the focused view, so a focused text field cannot resurface the
 * keyboard over a dialog or the status bar once overlaying dialogs close. Must be called on the
 * main thread.
 */
fun FragmentActivity.hideKeyboard() {
  val imm = getSystemService<InputMethodManager>() ?: return
  val focus = currentFocus
  imm.hideSoftInputFromWindow((focus ?: window.decorView).windowToken, 0)
  focus?.clearFocus()
}

/** Get an instance of [ClipboardManager] */
val Context.clipboard
  get() = getSystemService<ClipboardManager>()

/** Get an instance of [KeyguardManager] */
val Context.keyguardManager: KeyguardManager
  get() = getSystemService() ?: throw NullPointerException()

/** Get the default [SharedPreferences] instance */
val Context.sharedPrefs: SharedPreferences
  get() = getSharedPreferences("${BuildConfig.APPLICATION_ID}_preferences", MODE_PRIVATE)

/** Get the persistent passphrases [SharedPreferences] instance */
val Context.persistentPassphrases: SharedPreferences
  get() = getSharedPreferences("${BuildConfig.APPLICATION_ID}_passphrases", MODE_PRIVATE)

/** Get the persistent Git server secrets [SharedPreferences] instance */
val Context.gitSecrets: SharedPreferences
  get() = getSharedPreferences("${BuildConfig.APPLICATION_ID}_git_secrets", MODE_PRIVATE)

/** Get the persistent pass file timestamps */
val Context.passwordHistory: SharedPreferences
  get() = getSharedPreferences("recent_password_history", MODE_PRIVATE)

/** Get the persistent pass file timestamps */
val Context.credentialUsernames: SharedPreferences
  get() = getSharedPreferences("credential_usernames", MODE_PRIVATE)

/** Resolve [attr] from the [Context]'s theme */
fun Context.resolveAttribute(attr: Int): Int {
  val typedValue = TypedValue()
  this.theme.resolveAttribute(attr, typedValue, true)
  return typedValue.data
}

/**
 * Commit changes to the store from a [FragmentActivity] using a custom implementation of
 * [GitOperation]
 */
suspend fun FragmentActivity.commitChange(message: String): Result<Unit, Throwable> {
  if (!PasswordRepository.isInitialized) {
    return Ok(Unit)
  }
  return object : GitOperation(this@commitChange) {
      override val commands =
        arrayOf(
          // Stage all files
          git.add().addFilepattern("."),
          // Populate the changed files count
          git.status(),
          // Commit everything! If anything changed, that is.
          git.commit().setAll(true).setMessage(message),
        )

      override fun preExecute(): Boolean {
        logcat { "Committing with message: '$message'" }
        return true
      }
    }
    .execute()
}

/**
 * Commits what a saved entry changed, on the screen the editor handed back to.
 *
 * The editor used to wait for git before closing, which left it sitting over the entry it had just
 * written for the length of a commit — signing prompt included. It now returns straight away and
 * passes the commit message along, so the entry is on screen while this runs behind it. A commit
 * that fails says so and leaves the file where it is: it is saved either way, and the next commit
 * picks it up.
 */
suspend fun FragmentActivity.commitSavedChange(data: Intent?): Result<Unit, Throwable> {
  val message =
    data?.getStringExtra(PasswordCreationActivity.RETURN_EXTRA_COMMIT_MESSAGE) ?: return Ok(Unit)
  data.removeExtra(PasswordCreationActivity.RETURN_EXTRA_COMMIT_MESSAGE)
  return commitChange(message).onErr { error ->
    // Cancelling the signing prompt is an answer, not a fault, and a card that refused already
    // said so in its own dialog.
    if (
      !OpenPgpCardPrompt.isHandled(error) &&
        generateSequence(error) { it.cause }.none { it is CanceledException }
    ) {
      ErrorDialog.show(this, ErrorMessages[error])
    }
  }
}

/** Check if [permission] has been granted to the app. */
fun FragmentActivity.isPermissionGranted(permission: String): Boolean {
  return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}

/** Launch an activity denoted by [clazz]. */
fun <T : ComponentActivity> ComponentActivity.launchActivity(clazz: Class<T>) {
  startActivity(Intent(this, clazz).setAction(Intent.ACTION_VIEW))
}

/** Launch an activity denoted by [intent]. */
fun ComponentActivity.launchActivity(intent: Intent) {
  startActivity(intent.setAction(Intent.ACTION_VIEW))
}

/** Simplifies the common `getString(key, null) ?: defaultValue` case slightly */
fun SharedPreferences.getString(key: String): String? = getString(key, null)

fun PackageManager.getPackageInfoCompat(packageName: String, flags: Int): PackageInfo {
  return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    getPackageInfo(packageName, PackageInfoFlags.of(flags.toLong()))
  } else {
    getPackageInfo(packageName, flags)
  }
}

fun PackageManager.getApplicationInfoCompat(packageName: String, flags: Int): ApplicationInfo {
  return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    getApplicationInfo(packageName, ApplicationInfoFlags.of(flags.toLong()))
  } else {
    getApplicationInfo(packageName, flags)
  }
}

/**
 * Allows conditionally applying the given [modifier] if [isEnabled] is `true`.
 *
 * The block builds on an empty [Modifier], not on the chain it is appended to: calling it on the
 * receiver would append a second copy of everything before it, so a conditional background would
 * bring a duplicate of the padding, clip and shape along with it and draw a nested box.
 */
fun Modifier.conditional(isEnabled: Boolean, modifier: Modifier.() -> Modifier): Modifier {
  return if (isEnabled) {
    then(Modifier.modifier())
  } else {
    this
  }
}
