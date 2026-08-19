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
import com.github.michaelbull.result.getError
import com.github.michaelbull.result.onErr
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.File
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import logcat.LogPriority.ERROR
import logcat.asLog
import logcat.logcat
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.errors.CanceledException
import org.eclipse.jgit.errors.LockFailedException
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

/** Get the persistent unlock PINs [SharedPreferences] instance */
val Context.unlockPins: SharedPreferences
  get() = getSharedPreferences("${BuildConfig.APPLICATION_ID}_unlock_pins", MODE_PRIVATE)

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
 * Commits what a save changed, and puts that save back if the commit does not go through.
 *
 * A commit that fails — a cancelled signing prompt, a locked index — takes the save with it: the
 * entry is put back the way the last commit had it, so that what is on disk and what is in the
 * history never disagree. Only the files that save touched are restored, so a store with other
 * uncommitted work keeps it.
 *
 * The screen that called this decides what to do about the failure. An editor stays open on what
 * was typed, since nothing of it survived on disk; a screen that is only showing the entry has
 * nothing to keep and reloads instead.
 */
suspend fun FragmentActivity.commitSavedChange(
  onRolledBack: () -> Unit = {}
): Result<Unit, Throwable> {
  val saved = PendingCommit.take() ?: return Ok(Unit)
  var outcome = commitChange(saved.message)
  // A lock left behind by an operation that died is the one failure here with an obvious remedy,
  // and one the user would otherwise have to go and find under the repository's tools. Offered
  // once: refusing it is an answer, and the save is taken back like any other failed commit.
  if (outcome.getError()?.isGitLockError() == true && offerToClearStaleLock()) {
    outcome = commitChange(saved.message)
  }
  return outcome.onErr { error ->
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

/** The `index.lock` a git operation leaves behind when it does not finish. */
private const val GIT_INDEX_LOCK = "index.lock"

/**
 * Whether [this] is git refusing to work because the index is locked.
 *
 * Deliberately generous about what counts. A lock that has outlived the operation that took it is
 * something the user can only clear from in here, so the cost of reading one error too many as a
 * lock is an offer that turns out not to help — against the cost of reading one too few, which is a
 * store that cannot be written to and no way to say so.
 */
fun Throwable.isGitLockError(): Boolean =
  generateSequence(this) { it.cause }
    .any { cause ->
      cause is LockFailedException ||
        cause.message.orEmpty().contains(GIT_INDEX_LOCK, ignoreCase = true) ||
        cause.message.orEmpty().contains("Cannot lock", ignoreCase = true)
    }

/**
 * Asks whether to clear a stale lock, and returns whether it is gone.
 *
 * Never taken without asking: a lock file is how git says another operation is under way, and the
 * only one who can tell a lock that is stale from a lock that is doing its job is the person who
 * knows whether anything else is running.
 */
private suspend fun FragmentActivity.offerToClearStaleLock(): Boolean =
  suspendCancellableCoroutine { continuation ->
    if (isFinishing || isDestroyed) {
      continuation.resume(false)
      return@suspendCancellableCoroutine
    }
    val dialog =
      MaterialAlertDialogBuilder(this)
        .setIcon(R.drawable.ic_warning_red_24dp)
        .setTitle(R.string.git_index_locked_title)
        .setMessage(R.string.git_index_locked_error)
        .setCancelable(false)
        .setPositiveButton(R.string.git_index_locked_remove) { _, _ ->
          val lock = PasswordRepository.repository?.directory?.resolve(GIT_INDEX_LOCK)
          continuation.resume(lock != null && (!lock.isFile || lock.delete()))
        }
        .setNegativeButton(R.string.dialog_cancel) { _, _ -> continuation.resume(false) }
        .show()
    // The dialog cannot be dismissed by tapping away from it, so if the screen underneath goes
    // while it is up, it goes with it rather than staying behind as a leaked window.
    continuation.invokeOnCancellation {
      try {
        dialog.dismiss()
      } catch (e: IllegalArgumentException) {
        // Thrown when the window this dialog hung on has already gone, which is the very case
        // this runs in. Named rather than caught wholesale: a broad catch here would also swallow
        // the cancellation that brought us here.
        logcat(ERROR) { e.asLog() }
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
