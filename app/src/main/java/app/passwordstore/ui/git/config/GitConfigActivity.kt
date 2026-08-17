/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.passwordstore.ui.git.config

import android.os.Bundle
import android.view.MenuItem
import app.passwordstore.databinding.ActivityGitConfigBinding
import app.passwordstore.ui.git.base.BaseGitActivity
import app.passwordstore.util.extensions.enableEdgeToEdgeView
import app.passwordstore.util.extensions.viewBinding

/**
 * Who the commits belong to, and nothing else.
 *
 * The screen used to carry the repository's tools below these fields — aborting a rebase, resetting
 * to the remote, collecting garbage. Those are things done to a store rather than facts about one,
 * and they now sit under the repository's settings, leaving one question to a screen.
 */
class GitConfigActivity : BaseGitActivity() {

  private val binding by viewBinding(ActivityGitConfigBinding::inflate)

  private lateinit var identity: GitIdentityFields

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdgeView(binding.root)
    setContentView(binding.root)
    supportActionBar?.setDisplayHomeAsUpEnabled(true)

    identity = GitIdentityFields(binding.identity, gitSettings)
    identity.focusFirstEmptyField()
  }

  override fun onPause() {
    identity.save()
    super.onPause()
  }

  override fun onOptionsItemSelected(item: MenuItem): Boolean {
    return when (item.itemId) {
      android.R.id.home -> {
        onBackPressedDispatcher.onBackPressed()
        true
      }
      else -> super.onOptionsItemSelected(item)
    }
  }
}
