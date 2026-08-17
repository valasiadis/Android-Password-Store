/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.settings

import androidx.lifecycle.lifecycleScope
import app.passwordstore.R
import app.passwordstore.data.repo.PasswordRepository
import app.passwordstore.ui.dialogs.ErrorDialog
import app.passwordstore.ui.dialogs.Notice
import app.passwordstore.ui.git.base.BaseGitActivity.GitOp
import app.passwordstore.ui.git.log.GitLogActivity
import app.passwordstore.util.extensions.asLog
import app.passwordstore.util.extensions.launchActivity
import app.passwordstore.util.settings.PreferenceKeys
import com.github.michaelbull.result.fold
import com.github.michaelbull.result.getOrElse
import com.github.michaelbull.result.onErr
import com.github.michaelbull.result.runCatching
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import de.Maxr1998.modernpreferences.Preference
import de.Maxr1998.modernpreferences.PreferenceScreen
import de.Maxr1998.modernpreferences.helpers.onClick
import de.Maxr1998.modernpreferences.helpers.pref
import kotlinx.coroutines.launch
import logcat.LogPriority.ERROR
import logcat.logcat
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.RepositoryState

/**
 * The one-off things done to a repository, as against the settings that describe one.
 *
 * These used to sit under the local git config, on a screen whose other half asks who the commits
 * belong to — two unrelated questions sharing a scroll. Pulling and pushing join them here: they
 * were in the store's own menu, where the everyday action is synchronising and these two are the
 * halves of it, wanted only when something has gone wrong enough to need them apart.
 */
class GitToolsSettings(private val activity: SettingsActivity) : SettingsProvider {

  /** Whether there is a repository at all, which every tool here needs. */
  private val hasRepository
    get() = PasswordRepository.isGitRepo()

  /** Whether the repository knows where it came from, which the remote-facing tools need. */
  private val hasRemote
    get() = hasRepository && activity.gitSettings.url != null

  /**
   * Every preference on this screen whose state is a fact about the repository rather than a
   * setting, so that an operation which changes the repository can have them all read it again.
   *
   * Built once when the screen is, but what they say — which branch HEAD is on, whether a rebase
   * is under way, whether a lock is lying about — is answered by the repository at that moment.
   * Running any of them from here is exactly what changes those answers.
   */
  private val repositoryState = mutableListOf<Pair<Preference, () -> Unit>>()

  private fun Preference.reads(refresh: Preference.() -> Unit) {
    refresh()
    repositoryState += this to { refresh(); requestRebind() }
  }

  /** Asks the repository again and redraws whatever it told the screen last time. */
  private fun refreshRepositoryState() = repositoryState.forEach { (_, refresh) -> refresh() }

  override fun provideSettings(builder: PreferenceScreen.Builder) {
    repositoryState.clear()
    builder.apply {
      pref(PreferenceKeys.GIT_HEAD_STATUS) {
        titleRes = R.string.git_head_status_title
        enabled = false
        reads { summary = headStatusMsg() }
      }
      // Kept beside its two halves rather than dropped with the rest of the store's old menu: the
      // button on the list only appears once there is something to push, and the pull-to-refresh
      // gesture is not somewhere a reader would think to look for it.
      pref(PreferenceKeys.GIT_SYNC) {
        titleRes = R.string.git_sync
        summaryRes = R.string.git_sync_summary
        reads { enabled = hasRemote }
        onClick {
          runOperation(GitOp.SYNC)
          true
        }
      }
      pref(PreferenceKeys.GIT_PULL) {
        titleRes = R.string.git_pull
        summaryRes = R.string.git_pull_summary
        reads { enabled = hasRemote }
        onClick {
          runOperation(GitOp.PULL)
          true
        }
      }
      pref(PreferenceKeys.GIT_PUSH) {
        titleRes = R.string.git_push
        summaryRes = R.string.git_push_summary
        reads { enabled = hasRemote }
        onClick {
          runOperation(GitOp.PUSH)
          true
        }
      }
      pref(PreferenceKeys.GIT_LOG) {
        titleRes = R.string.git_log
        reads { enabled = hasRepository }
        onClick {
          runCatching { activity.launchActivity(GitLogActivity::class.java) }
            .onErr { ex -> logcat(ERROR) { ex.asLog("Failed to start GitLogActivity") } }
          true
        }
      }
      pref(PreferenceKeys.GIT_ABORT_REBASE) {
        titleRes = R.string.abort_rebase
        reads { enabled = isMidRebaseOrMerge() }
        onClick {
          abortRebase()
          true
        }
      }
      pref(PreferenceKeys.GIT_RESET_TO_REMOTE) {
        titleRes = R.string.reset_to_remote
        reads { enabled = hasRemote }
        onClick {
          resetToRemote()
          true
        }
      }
      // Compacting a store is done to the store, and a store that has never been anywhere is
      // exactly the one whose history nothing else is ever going to tidy up.
      pref(PreferenceKeys.GIT_GC) {
        titleRes = R.string.git_run_gc_job
        reads { enabled = hasRepository }
        onClick {
          runOperation(GitOp.GC)
          true
        }
      }
      pref(PreferenceKeys.GIT_REMOVE_LOCK) {
        titleRes = R.string.git_remove_lock_file
        reads { enabled = hasStaleLock() }
        onClick { removeLockFile() }
      }
    }
  }

  /** Runs [operation] and reports whatever went wrong, leaving this screen where it is. */
  private fun runOperation(operation: GitOp) {
    activity.lifecycleScope.launch {
      activity
        .launchGitOperation(operation)
        .fold(success = {}, failure = { err -> activity.promptOnErrorHandler(err) })
      // Either way: a pull that moved HEAD, a reset that ended a rebase, an operation that died
      // and left a lock behind. The screen is still up, and it was describing the repository as it
      // stood before any of that.
      refreshRepositoryState()
    }
  }

  private fun isMidRebaseOrMerge(): Boolean {
    val repo = PasswordRepository.repository ?: return false
    return repo.repositoryState.isRebasing || repo.repositoryState == RepositoryState.MERGING
  }

  private fun hasStaleLock(): Boolean =
    PasswordRepository.repository?.directory?.resolve(GIT_INDEX_LOCK)?.isFile == true

  private fun removeLockFile(): Boolean {
    val lockFile = PasswordRepository.repository?.directory?.resolve(GIT_INDEX_LOCK)
    val messageRes =
      when {
        lockFile == null || !lockFile.isFile -> R.string.git_remove_lock_file_missing
        lockFile.delete() -> R.string.git_remove_lock_file_success
        else -> R.string.git_remove_lock_file_failed
      }
    Notice.show(activity, messageRes)
    refreshRepositoryState()
    return true
  }

  /**
   * Asks which branch to reset onto, from the ones the remote is known to have.
   *
   * The branch used to be typed in, which asks the user to remember what a `git branch -r` would
   * have told them — and answers a typo by creating a branch that tracks nothing.
   */
  private fun resetToRemote() {
    val branches = remoteBranches()
    if (branches.isEmpty()) {
      ErrorDialog.show(activity, R.string.git_utils_no_remote_branches)
      return
    }
    val current = PasswordRepository.getCurrentBranch()
    var chosen = branches.indexOf(current).coerceAtLeast(0)
    MaterialAlertDialogBuilder(activity)
      .setTitle(R.string.git_utils_reset_remote_branch_title)
      .setSingleChoiceItems(branches.toTypedArray(), chosen) { _, which -> chosen = which }
      .setNegativeButton(R.string.dialog_cancel, null)
      .setPositiveButton(R.string.dialog_ok) { _, _ ->
        activity.remoteBranch = branches[chosen]
        runOperation(GitOp.RESET)
      }
      .show()
  }

  private fun abortRebase() {
    activity.lifecycleScope.launch {
      activity
        .launchGitOperation(GitOp.BREAK_OUT_OF_DETACHED)
        .fold(
          success = {
            val branch = PasswordRepository.getCurrentBranch()
            MaterialAlertDialogBuilder(activity)
              .setTitle(R.string.git_abort_and_push_title)
              .setMessage(
                activity.getString(
                  R.string.git_break_out_of_detached_success,
                  branch,
                  "conflicting-$branch-...",
                )
              )
              .setPositiveButton(R.string.dialog_ok, null)
              .show()
          },
          failure = { err -> activity.promptOnErrorHandler(err) },
        )
      refreshRepositoryState()
    }
  }

  /** The branches the remote had when it was last spoken to, under their bare names. */
  private fun remoteBranches(): List<String> =
    PasswordRepository.repository
      ?.refDatabase
      ?.getRefsByPrefix("${Constants.R_REMOTES}origin/")
      ?.map { it.name.removePrefix("${Constants.R_REMOTES}origin/") }
      ?.filter { it != Constants.HEAD }
      ?.distinct()
      ?.sorted()
      .orEmpty()

  /**
   * Returns a user-friendly message about the current state of HEAD.
   *
   * The state is recognized to be either pointing to a branch or detached.
   */
  private fun headStatusMsg(): String {
    val repo = PasswordRepository.repository ?: return activity.getString(R.string.git_head_missing)
    return runCatching {
      val headRef = repo.findRef(Constants.HEAD)
      if (headRef.isSymbolic) {
        val shortBranchName = Repository.shortenRefName(headRef.target.name)
        activity.getString(R.string.git_head_on_branch, shortBranchName)
      } else {
        activity.getString(R.string.git_head_detached, headRef.objectId.abbreviate(8).name())
      }
    }
      .getOrElse { ex ->
        logcat(ERROR) { "Error getting HEAD reference\n${ex}" }
        activity.getString(R.string.git_head_missing)
      }
  }

  private companion object {
    const val GIT_INDEX_LOCK = "index.lock"
  }
}
