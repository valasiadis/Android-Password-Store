/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.git.log

import android.os.Bundle
import android.view.MenuItem
import app.passwordstore.databinding.ActivityGitLogBinding
import app.passwordstore.ui.adapters.PasswordRowDecoration
import app.passwordstore.ui.git.base.BaseGitActivity
import app.passwordstore.util.extensions.enableEdgeToEdgeView
import app.passwordstore.util.extensions.viewBinding

/**
 * Displays the repository's git commits in git-log fashion.
 *
 * It provides basic information about each commit by way of a non-interactive RecyclerView.
 */
class GitLogActivity : BaseGitActivity() {

  private val binding by viewBinding(ActivityGitLogBinding::inflate)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdgeView(binding.root)
    setContentView(binding.root)
    supportActionBar?.setDisplayHomeAsUpEnabled(true)
    createRecyclerView()
  }

  override fun onOptionsItemSelected(item: MenuItem): Boolean {
    return when (item.itemId) {
      android.R.id.home -> {
        finish()
        true
      }
      else -> super.onOptionsItemSelected(item)
    }
  }

  private fun createRecyclerView() {
    binding.gitLogRecyclerView.apply {
      setHasFixedSize(true)
      // Commits as one block of rows, as the password list sets its entries, not ruled apart.
      PasswordRowDecoration(context).attachTo(this)
      adapter = GitLogAdapter()
    }
  }
}
