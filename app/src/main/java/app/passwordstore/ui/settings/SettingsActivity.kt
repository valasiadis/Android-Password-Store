/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.settings

import android.content.Context
import android.os.Bundle
import android.view.MenuItem
import androidx.activity.OnBackPressedCallback
import androidx.core.os.BundleCompat
import app.passwordstore.R
import app.passwordstore.databinding.ActivityPreferenceRecyclerviewBinding
import app.passwordstore.ui.git.base.BaseGitActivity
import app.passwordstore.util.extensions.enableEdgeToEdgeView
import app.passwordstore.util.extensions.viewBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import de.Maxr1998.modernpreferences.Preference
import de.Maxr1998.modernpreferences.PreferencesAdapter
import de.Maxr1998.modernpreferences.helpers.screen
import de.Maxr1998.modernpreferences.helpers.subScreen

/**
 * Built on [BaseGitActivity] so the repository's own tools can be offered here as settings rather
 * than as a screen of their own: the one-off git operations need somewhere to run, and this is
 * already where everything else about the repository is decided.
 */
@AndroidEntryPoint
class SettingsActivity : BaseGitActivity() {

  private val miscSettings = MiscSettings(this)
  private val autofillSettings = AutofillSettings(this)
  private val passwordSettings = PasswordSettings(this)
  val repositorySettings = RepositorySettings(this)
  private val generalSettings = GeneralSettings(this)
  private val pgpSettings = PGPSettings(this)

  private val binding by viewBinding(ActivityPreferenceRecyclerviewBinding::inflate)
  private val preferencesAdapter: PreferencesAdapter
    get() = binding.preferenceRecyclerView.adapter as PreferencesAdapter

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdgeView(binding.root)
    setContentView(binding.root)
    Preference.Config.dialogBuilderFactory = { context -> CancellableDialogBuilder(context) }
    // Ordered by how close each group sits to the passwords themselves: how the app looks and
    // behaves, then the entries, then the keys that encrypt them, then the repository they live
    // in, then the ways other apps reach them, and finally the things that fit nowhere else.
    val screen =
      screen(this) {
        subScreen {
          collapseIcon = true
          titleRes = R.string.pref_category_general_title
          iconRes = R.drawable.app_settings_alt_24px
          generalSettings.provideSettings(this)
        }
        subScreen {
          collapseIcon = true
          titleRes = R.string.pref_category_passwords_title
          iconRes = R.drawable.ic_password_24px
          passwordSettings.provideSettings(this)
        }
        subScreen {
          collapseIcon = true
          titleRes = R.string.pref_category_pgp_title
          iconRes = R.drawable.ic_lock_open_24px
          pgpSettings.provideSettings(this)
        }
        subScreen {
          collapseIcon = true
          titleRes = R.string.pref_category_repository_title
          iconRes = R.drawable.ic_call_merge_24px
          repositorySettings.provideSettings(this)
        }
        subScreen {
          collapseIcon = true
          titleRes = R.string.pref_category_autofill_title
          iconRes = R.drawable.ic_wysiwyg_24px
          autofillSettings.provideSettings(this)
        }
        subScreen {
          collapseIcon = true
          titleRes = R.string.pref_category_misc_title
          iconRes = R.drawable.ic_miscellaneous_services_24px
          miscSettings.provideSettings(this)
        }
      }
    val backPressedCallback =
      object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
          preferencesAdapter.goBack()
        }
      }
    onBackPressedDispatcher.addCallback(backPressedCallback)
    val adapter = PreferencesAdapter(screen)
    adapter.onScreenChangeListener =
      PreferencesAdapter.OnScreenChangeListener { subScreen, entering ->
        backPressedCallback.isEnabled = entering
        supportActionBar?.title =
          if (!entering) {
            getString(R.string.action_settings)
          } else {
            getString(subScreen.titleRes)
          }
      }
    if (savedInstanceState != null) {
      BundleCompat.getParcelable(
          savedInstanceState,
          "adapter",
          PreferencesAdapter.SavedState::class.java,
        )
        ?.let(adapter::loadSavedState)
    }
    binding.preferenceRecyclerView.adapter = adapter
    PreferenceGroupDecoration(this).attachTo(binding.preferenceRecyclerView)
    // Opening a screen replaces the whole list at once, so every row is rebound in one pass. A
    // pool of the default five per type sends the rest back through inflation each time; holding
    // a screenful means they are reused instead.
    binding.preferenceRecyclerView.recycledViewPool.setMaxRecycledViews(
      DEFAULT_PREFERENCE_VIEW_TYPE,
      PREFERENCE_VIEW_POOL_SIZE,
    )
    binding.preferenceRecyclerView.setItemViewCacheSize(PREFERENCE_VIEW_POOL_SIZE)
  }

  /**
   * The preferences library builds its dialogs through this factory and then insists they cannot be
   * cancelled, which leaves the back gesture doing nothing while one is open. Ignoring that one
   * call is the only way to reach the dialogs it creates.
   */
  private class CancellableDialogBuilder(context: Context) : MaterialAlertDialogBuilder(context) {
    override fun setCancelable(cancelable: Boolean): MaterialAlertDialogBuilder =
      super.setCancelable(true)
  }

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    outState.putParcelable("adapter", preferencesAdapter.getSavedState())
  }

  override fun onOptionsItemSelected(item: MenuItem): Boolean {
    return when (item.itemId) {
      android.R.id.home ->
        if (!preferencesAdapter.goBack()) {
          super.onOptionsItemSelected(item)
        } else {
          true
        }
      else -> super.onOptionsItemSelected(item)
    }
  }

  private companion object {
    /** What the library reports for preferences that have no widget layout of their own. */
    const val DEFAULT_PREFERENCE_VIEW_TYPE = 0
    const val PREFERENCE_VIEW_POOL_SIZE = 20
  }
}
