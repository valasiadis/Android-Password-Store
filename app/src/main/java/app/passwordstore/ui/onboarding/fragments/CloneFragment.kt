/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.onboarding.fragments

import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import app.passwordstore.R
import app.passwordstore.databinding.FragmentCloneBinding
import app.passwordstore.util.extensions.performTransactionWithBackStack
import app.passwordstore.util.extensions.sharedPrefs
import app.passwordstore.util.extensions.viewBinding
import app.passwordstore.util.extensions.windowInsetsLambda
import app.passwordstore.util.settings.PreferenceKeys

class CloneFragment : Fragment(R.layout.fragment_clone) {

  private val binding by viewBinding(FragmentCloneBinding::bind)

  /**
   * Asks who is committing before anything else is decided, since every route from here ends in a
   * commit. Once answered it stays answered, and the question is not asked again.
   */
  private fun withIdentity(proceed: () -> Unit) {
    val settings = requireContext().applicationContext.sharedPrefs
    val hasIdentity =
      !settings.getString(PreferenceKeys.GIT_CONFIG_AUTHOR_NAME, "").isNullOrEmpty() &&
        !settings.getString(PreferenceKeys.GIT_CONFIG_AUTHOR_EMAIL, "").isNullOrEmpty()
    if (hasIdentity) {
      proceed()
      return
    }
    pendingAfterIdentity = proceed
    GitIdentityDialogFragment.newInstance().show(parentFragmentManager, "GIT_IDENTITY_DIALOG")
  }

  private var pendingAfterIdentity: (() -> Unit)? = null

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)
    ViewCompat.setOnApplyWindowInsetsListener(view, windowInsetsLambda)
    parentFragmentManager.setFragmentResultListener(
      GitIdentityDialogFragment.IDENTITY_RESULT_KEY,
      viewLifecycleOwner,
    ) { _, _ ->
      pendingAfterIdentity?.invoke()
      pendingAfterIdentity = null
    }

    binding.cloneRemote.setOnClickListener {
      withIdentity {
        parentFragmentManager.performTransactionWithBackStack(RepoVisibilityFragment.newInstance())
      }
    }

    binding.createLocal.setOnClickListener {
      withIdentity {
        parentFragmentManager.performTransactionWithBackStack(
          RepoVisibilityFragment.newInstance(shouldClone = false)
        )
      }
    }
  }

  companion object {

    fun newInstance(): CloneFragment = CloneFragment()
  }
}
