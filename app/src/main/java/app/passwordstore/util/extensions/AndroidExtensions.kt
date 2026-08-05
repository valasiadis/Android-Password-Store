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
import app.passwordstore.Application
import app.passwordstore.BuildConfig
import app.passwordstore.R
import app.passwordstore.data.repo.PasswordRepository
import app.passwordstore.ui.dialogs.ErrorDialog
import app.passwordstore.util.coroutines.DispatcherProvider
import app.passwordstore.util.crypto.OpenPgpCardPrompt
import app.passwordstore.util.git.ErrorMessages
import app.passwordstore.util.git.PendingCommit
import app.passwordstore.util.git.operation.GitOperation
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.onErr
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.withContext
import logcat.LogPriority.ERROR
import logcat.asLog
import logcat.logcat
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.errors.CanceledException
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.TreeWalk

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
 * passes the commit message along, so the entry is on screen while this runs behind it.
 *
 * A commit that does not go through — a cancelled signing prompt, a locked index — takes the save
 * with it: the entry is put back the way the last commit had it, so what is on disk and what is in
 * the history never disagree. Only the files the save touched are restored, and a store with other
 * uncommitted work keeps it.
 */
suspend fun FragmentActivity.commitSavedChange(
  onRolledBack: () -> Unit = {}
): Result<Unit, Throwable> {
  val saved = PendingCommit.take() ?: return Ok(Unit)
  return commitChange(saved.message).onErr { error ->
    val restored = restoreFromHead(saved.touchedPaths)
    // Cancelling the signing prompt is an answer, not a fault, and a card that refused already
    // said so in its own dialog — but a save that could not be undone is worth saying either way.
    // Whatever the screen does about the entry waits until the failure has been read.
    when {
      !restored ->
        ErrorDialog.show(this, getString(R.string.git_index_locked_error), onDismiss = onRolledBack)
      !OpenPgpCardPrompt.isHandled(error) &&
        generateSequence(error) { it.cause }.none { it is CanceledException } ->
        ErrorDialog.show(this, ErrorMessages[error], onDismiss = onRolledBack)
      else -> onRolledBack()
    }
  }
}

/** The app's own dispatchers, for the few helpers here that are not part of an injected class. */
private fun dispatchers(): DispatcherProvider =
  EntryPointAccessors.fromApplication(
      Application.instance.applicationContext,
      DispatchersEntryPoint::class.java,
    )
    .dispatcherProvider()

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface DispatchersEntryPoint {
  fun dispatcherProvider(): DispatcherProvider
}

/**
 * Puts [paths] back the way the last commit has them: a file the commit knows is restored from it,
 * and one it has never seen — a new entry — is removed. Returns whether that succeeded, since a
 * store left holding a change git refused is worth complaining about.
 */
private suspend fun restoreFromHead(paths: List<String>): Boolean {
  if (paths.isEmpty()) return true
  val repository = PasswordRepository.repository ?: return true
  val workTree = repository.workTree ?: return true
  return withContext(dispatchers().io()) {
    try {
      val head = repository.resolve("${Constants.HEAD}^{tree}")
      val git = Git(repository)
      paths.forEach { path ->
        val relative =
          File(path).relativeToOrNull(workTree)?.invariantSeparatorsPath ?: return@forEach
        val known =
          head != null &&
            RevWalk(repository).use { walk ->
              TreeWalk.forPath(repository, relative, walk.parseTree(head)) != null
            }
        if (known) {
          git.checkout().setStartPoint(Constants.HEAD).addPath(relative).call()
        } else {
          // Never committed, so there is nothing to restore it from — and the failed commit may
          // have staged it on the way, which is taken back with it.
          git.rm().addFilepattern(relative).setCached(true).call()
          File(path).delete()
        }
      }
      true
    } catch (error: Throwable) {
      logcat(ERROR) { "Could not put the entry back\n${error.asLog()}" }
      false
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
