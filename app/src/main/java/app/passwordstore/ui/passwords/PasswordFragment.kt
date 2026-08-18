/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.passwordstore.ui.passwords

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Parcelable
import android.transition.ChangeBounds
import android.transition.TransitionManager
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.appcompat.view.ActionMode
import androidx.core.content.edit
import androidx.core.content.getSystemService
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import app.passwordstore.R
import app.passwordstore.crypto.PGPIdentifier
import app.passwordstore.data.crypto.CryptoRepository
import app.passwordstore.data.password.PasswordItem
import app.passwordstore.data.repo.PasswordRepository
import app.passwordstore.databinding.PasswordRecyclerViewBinding
import app.passwordstore.injection.prefs.PasswordHistory
import app.passwordstore.injection.prefs.SettingsPreferences
import app.passwordstore.ui.adapters.PasswordItemRecyclerAdapter
import app.passwordstore.ui.adapters.PasswordRowDecoration
import app.passwordstore.ui.dialogs.BasicBottomSheet
import app.passwordstore.ui.dialogs.ItemCreationBottomSheet
import app.passwordstore.ui.git.base.BaseGitActivity
import app.passwordstore.ui.git.config.GitServerConfigActivity
import app.passwordstore.ui.util.OnOffItemAnimator
import app.passwordstore.util.coroutines.DispatcherProvider
import app.passwordstore.util.extensions.SCALE_APPEAR_DELAY_MS
import app.passwordstore.util.extensions.SCALE_MS
import app.passwordstore.util.extensions.base64
import app.passwordstore.util.extensions.followsKeyboard
import app.passwordstore.util.extensions.getString
import app.passwordstore.util.extensions.hideKeyboard
import app.passwordstore.util.extensions.scalesAway
import app.passwordstore.util.extensions.sharedPrefs
import app.passwordstore.util.extensions.substringBefore
import app.passwordstore.util.extensions.viewBinding
import app.passwordstore.util.settings.AuthMode
import app.passwordstore.util.settings.GitSettings
import app.passwordstore.util.settings.PasswordSortOrder
import app.passwordstore.util.settings.PreferenceKeys
import app.passwordstore.util.shortcuts.ShortcutHandler
import app.passwordstore.util.viewmodel.SearchableRepositoryViewModel
import com.github.michaelbull.result.fold
import com.github.michaelbull.result.onErr
import com.github.michaelbull.result.runCatching
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.launch
import me.zhanghai.android.fastscroll.FastScrollerBuilder

@AndroidEntryPoint
class PasswordFragment : Fragment(R.layout.password_recycler_view) {

  @Inject lateinit var repository: CryptoRepository
  @Inject lateinit var gitSettings: GitSettings
  @Inject lateinit var shortcutHandler: ShortcutHandler
  @Inject lateinit var dispatcherProvider: DispatcherProvider
  @Inject @SettingsPreferences lateinit var prefs: SharedPreferences
  @Inject @PasswordHistory lateinit var passwordHistory: SharedPreferences
  private lateinit var recyclerAdapter: PasswordItemRecyclerAdapter
  private lateinit var listener: OnFragmentInteractionListener
  private lateinit var settings: SharedPreferences

  private var recyclerViewStateToRestore: Parcelable? = null
  private var actionMode: ActionMode? = null
  private var scrollTarget: File? = null

  private val model: SearchableRepositoryViewModel by activityViewModels()
  private val binding by viewBinding(PasswordRecyclerViewBinding::bind)
  private val swipeResult =
    registerForActivityResult(StartActivityForResult()) {
      binding.swipeRefresher.isRefreshing = false
      requireStore().refreshPasswordList()
    }

  val currentDir: File
    get() = model.currentDir.value

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)
    settings = requireContext().sharedPrefs
    initializePasswordList()
    // The field is the list's; what a query means is the store screen's.
    binding.searchInput.doOnTextChanged { text, _, _, _ ->
      val query = text?.toString().orEmpty()
      binding.searchClear.isVisible = query.isNotEmpty()
      requireStore().searchFor(query)
    }
    binding.searchInput.setOnEditorActionListener { _, _, _ ->
      requireActivity().hideKeyboard()
      true
    }
    binding.searchClear.setOnClickListener { clearSearch() }
    binding.fabSync.setOnClickListener {
      if (!PasswordRepository.isInitialized) {
        MaterialAlertDialogBuilder(requireContext())
          .setCancelable(false)
          .setTitle(R.string.error)
          .setIcon(R.drawable.ic_crossmark_red_24dp)
          .setMessage(R.string.creation_dialog_text)
          .setPositiveButton(R.string.dialog_ok, null)
          .show()
      } else {
        requireStore().runGitOperation(BaseGitActivity.GitOp.SYNC)
      }
    }
    binding.fab.setOnClickListener {
      ItemCreationBottomSheet().show(childFragmentManager, "BOTTOM_SHEET")
    }
    followKeyboard()
    childFragmentManager.setFragmentResultListener(ITEM_CREATION_REQUEST_KEY, viewLifecycleOwner) {
      _,
      bundle ->
      when (bundle.getString(ACTION_KEY)) {
        ACTION_FOLDER -> requireStore().createFolder()
        ACTION_PASSWORD -> requireStore().createPassword()
      }
    }
  }

  private fun initializePasswordList() {
    val gitDir = File(PasswordRepository.getRepositoryDirectory(), ".git")
    val hasGitDir =
      gitDir.exists() && gitDir.isDirectory && (gitDir.listFiles()?.isNotEmpty() == true)
    binding.swipeRefresher.setOnRefreshListener {
      if (!hasGitDir) {
        requireStore().refreshPasswordList()
        binding.swipeRefresher.isRefreshing = false
      } else if (!PasswordRepository.isGitRepo()) {
        BasicBottomSheet.Builder(requireContext())
          .setMessageRes(R.string.clone_git_repo)
          .setPositiveButtonClickListener(getString(R.string.clone_button)) {
            swipeResult.launch(GitServerConfigActivity.createCloneIntent(requireContext()))
          }
          .build()
          .show(requireActivity().supportFragmentManager, "NOT_A_GIT_REPO")
        binding.swipeRefresher.isRefreshing = false
      } else {
        // The gesture's own spinner goes as soon as the operation starts: what is happening is
        // said at the top of the screen, in the same place as every other wait in the app, and two
        // indicators for one operation is one too many.
        binding.swipeRefresher.isRefreshing = false
        // When authentication is set to AuthMode.None then the only git operation we can
        // run is a pull, so automatically fallback to that.
        val operationId =
          when (gitSettings.authMode) {
            AuthMode.None -> BaseGitActivity.GitOp.PULL
            else -> BaseGitActivity.GitOp.SYNC
          }
        requireStore().apply {
          lifecycleScope.launch {
            launchGitOperation(operationId)
              .fold(success = { refreshPasswordList() }, failure = { promptOnErrorHandler(it) })
          }
        }
      }
    }

    recyclerAdapter =
      PasswordItemRecyclerAdapter(lifecycleScope, dispatcherProvider)
        .onItemClicked { _, item -> listener.onFragmentInteraction(item) }
        .onSelectionChanged { selection ->
          // In order to not interfere with drag selection, we disable the
          // SwipeRefreshLayout
          // once an item is selected.
          binding.swipeRefresher.isEnabled =
            selection.isEmpty && !prefs.getBoolean(PreferenceKeys.DISABLE_SYNC_ACTION, false)

          if (actionMode == null)
            actionMode =
              requireStore().startSupportActionMode(actionModeCallback) ?: return@onSelectionChanged

          if (!selection.isEmpty) {
            if (actionMode != null) {
              actionMode?.title =
                resources.getQuantityString(
                  R.plurals.delete_title,
                  selection.size(),
                  selection.size(),
                )
            } else {
              throw NullPointerException()
            }
            actionMode?.invalidate() ?: throw NullPointerException()
          } else {
            actionMode?.finish() ?: throw NullPointerException()
          }
        }
    val recyclerView = binding.passRecycler
    recyclerView.apply {
      // No divider decoration: rows carry their own filled container and are
      // separated by a thin gap, so a rule between them reads as clutter.
      layoutManager = LinearLayoutManager(requireContext())
      itemAnimator = OnOffItemAnimator()
      adapter = recyclerAdapter
    }
    PasswordRowDecoration(requireContext()).attachTo(recyclerView)

    FastScrollerBuilder(recyclerView).build()
    recyclerAdapter.makeSelectable(recyclerView)
    registerForContextMenu(recyclerView)

    val path =
      requireNotNull(requireArguments().getString(PasswordStore.REQUEST_ARG_PATH)) {
        "Cannot navigate if ${PasswordStore.REQUEST_ARG_PATH} is not provided"
      }
    model.navigateTo(File(path), pushPreviousLocation = false)
    lifecycleScope.launch {
      model.searchResult.flowWithLifecycle(lifecycle).collect { result ->
        // Only run animations when the new list is filtered, i.e., the user submitted a search,
        // and not on folder navigation since the latter leads to too many removal animations.
        (recyclerView.itemAnimator as OnOffItemAnimator).isEnabled = result.isFiltered
        recyclerAdapter.submitList(result.passwordItems) {
          when {
            result.isFiltered -> {
              // When the result is filtered, we always scroll to the top since that is
              // where the best fuzzy match appears.
              recyclerView.scrollToPosition(0)
            }
            scrollTarget != null -> {
              scrollTarget?.let {
                recyclerView.scrollToPosition(recyclerAdapter.getPositionForFile(it))
              }
              scrollTarget = null
            }
            else -> {
              // When the result is not filtered and there is a saved scroll position for
              // it, we try to restore it.
              recyclerViewStateToRestore?.let {
                recyclerView.layoutManager?.onRestoreInstanceState(it)
                  ?: throw NullPointerException()
              }
              recyclerViewStateToRestore = null
            }
          }
        }
      }
    }
    updateFabSync()
  }

  /**
   * Puts the cursor in the search field, and starts it off with [query] where typing on the list is
   * what opened it. Returns whether there was a field to focus at all.
   */
  fun focusSearch(query: String? = null): Boolean {
    view ?: return false
    val field = binding.searchInput
    query?.let { field.setText(it) }
    field.setSelection(field.text?.length ?: 0)
    field.requestFocus()
    requireContext().getSystemService<InputMethodManager>()?.showSoftInput(field, 0)
    return true
  }

  /** Empties the field, which puts the folder that was being looked at back on the screen. */
  fun clearSearch() {
    view ?: return
    binding.searchInput.text?.clear()
    binding.searchInput.clearFocus()
  }

  private var fabVisible = true

  private var keyboardShowing = false

  /** What the list leaves free below its last row before the keyboard asks for any more. */
  private var listBottomRoom = 0

  /** Moves the row along the bottom of the screen with the keyboard, in one piece. */
  private fun followKeyboard() {
    listBottomRoom = binding.passRecycler.paddingBottom
    requireActivity().followsKeyboard(
      binding.root,
      onShown = { showing ->
        if (showing != keyboardShowing) {
          keyboardShowing = showing
          updateFab()
        }
      },
    ) { overlap ->
      binding.bottomBar.translationY = -overlap.toFloat()
      binding.passRecycler.updatePadding(bottom = listBottomRoom + overlap)
    }
  }

  private val actionModeCallback =
    object : ActionMode.Callback {
      // Called when the action mode is created; startActionMode() was called
      override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        // Inflate a menu resource providing context menu items
        mode.menuInflater.inflate(R.menu.context_pass, menu)
        // hide the fab
        animateFab(false)
        return true
      }

      // Called each time the action mode is shown. Always called after onCreateActionMode,
      // but may be called multiple times if the mode is invalidated.
      override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
        val selectedItems = recyclerAdapter.getSelectedItems()
        val onlyOne = selectedItems.size == 1
        val single = selectedItems.singleOrNull()
        // Renaming asks for one new name, which several things at once cannot be given.
        menu.findItem(R.id.menu_edit_password).isVisible =
          onlyOne && single?.type == PasswordItem.TYPE_CATEGORY
        menu.findItem(R.id.menu_set_folder_key).isVisible =
          onlyOne &&
            single?.type == PasswordItem.TYPE_CATEGORY &&
            requireStore().canChooseKeyFor(single.file)
        // A shortcut points at one entry.
        menu.findItem(R.id.menu_pin_password).isVisible =
          onlyOne && single?.type == PasswordItem.TYPE_PASSWORD
        return true
      }

      // Called when the user selects a contextual menu item
      override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
        return when (item.itemId) {
          R.id.menu_delete_password -> {
            requireStore().deletePasswords(recyclerAdapter.getSelectedItems())
            // Action picked, so close the CAB
            mode.finish()
            true
          }
          R.id.menu_move_password -> {
            requireStore().movePasswords(recyclerAdapter.getSelectedItems())
            false
          }
          R.id.menu_edit_password -> {
            requireStore().renameCategory(recyclerAdapter.getSelectedItems())
            mode.finish()
            false
          }
          R.id.menu_set_folder_key -> {
            requireStore().showFolderEncryptionKey(recyclerAdapter.getSelectedItems()[0].file)
            mode.finish()
            true
          }
          R.id.menu_pin_password -> {
            val passwordItem = recyclerAdapter.getSelectedItems()[0]
            shortcutHandler.addPinnedShortcut(
              passwordItem,
              passwordItem.createAuthEnabledIntent(requireContext()),
            )
            false
          }
          else -> false
        }
      }

      // Called when the user exits the action mode
      override fun onDestroyActionMode(mode: ActionMode) {
        recyclerAdapter.requireSelectionTracker().clearSelection()
        actionMode = null
        // show the fab
        animateFab(true)
      }

      private fun animateFab(show: Boolean) {
        fabVisible = show
        // Nothing on this bar applies while entries are being picked out — searching would
        // only take the selection off the screen — so it goes with the buttons.
        animateSearchBar(show)
        updateFab()
      }
    }

  /**
   * Shows or hides the buttons on either side of the search field, and hands the field whatever
   * room that leaves.
   *
   * Both are gone while entries are being picked out, and while the keyboard is up: what is being
   * typed then is a search, and neither starting a new entry nor synchronising is what the screen
   * is for until that search is over.
   */
  private fun updateFab() {
    binding.fab.scalesAway(fabVisible && !keyboardShowing)
    updateFabSync()
  }

  fun updateFabSync() {
    // Called from the store screen, which can outlive this fragment's view.
    view ?: return
    val syncShowing = PasswordRepository.getAheadCount() > 0 && fabVisible && !keyboardShowing
    binding.fabSync.scalesAway(syncShowing)
    updateSearchBarWidth(syncShowing)
  }

  /** Takes the search field away with the buttons, and brings it back with them. */
  private fun animateSearchBar(show: Boolean) =
    with(binding.searchBar) {
      if (show == isVisible) return@with
      if (!show) requireActivity().hideKeyboard()
      scalesAway(show)
    }

  /**
   * Gives the search field the room the buttons beside it are not using.
   *
   * The sync button comes and goes with whether the store is ahead of its remote, and both buttons
   * step aside while the keyboard is up; a field that kept a gap for a button that is not there
   * looks off-centre for no reason. The change is animated so the field grows and shrinks with the
   * button rather than jumping the moment it appears.
   */
  private fun updateSearchBarWidth(syncShowing: Boolean) {
    fun room(forButton: Boolean) =
      resources.getDimensionPixelSize(
        if (forButton) R.dimen.search_bar_side_room else R.dimen.fab_compat_margin
      )
    val start = room(syncShowing)
    val end = room(fabVisible && !keyboardShowing)
    val bar = binding.searchBar
    val current = bar.layoutParams as ViewGroup.MarginLayoutParams
    if (current.marginStart == start && current.marginEnd == end) return
    // In step with the button it is making room for: the same length, and the same wait before
    // starting when that button is on its way in. Staged within the row, so that a row on its way
    // up or down carries this along rather than having it played out against the screen.
    TransitionManager.beginDelayedTransition(
      binding.bottomBar,
      ChangeBounds().apply {
        addTarget(bar)
        duration = SCALE_MS
        startDelay =
          if (start > current.marginStart || end > current.marginEnd) SCALE_APPEAR_DELAY_MS else 0
      },
    )
    // Both ends, every time: setting one of a pair of start/end margins is what makes the layout
    // resolve them, and the one left alone comes back as nothing rather than as what it was.
    bar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
      marginStart = start
      marginEnd = end
    }
  }

  override fun onResume() {
    super.onResume()
    binding.swipeRefresher.isEnabled = !prefs.getBoolean(PreferenceKeys.DISABLE_SYNC_ACTION, false)
  }

  override fun onAttach(context: Context) {
    super.onAttach(context)
    runCatching {
      listener =
        object : OnFragmentInteractionListener {
          override fun onFragmentInteraction(item: PasswordItem) {
            if (
              settings.getString(PreferenceKeys.SORT_ORDER) == PasswordSortOrder.RECENTLY_USED.name
            ) {
              // save the time when password was used
              passwordHistory.edit {
                putString(item.file.absolutePath.base64(), System.currentTimeMillis().toString())
              }
            }

            if (item.type == PasswordItem.TYPE_CATEGORY) {
              navigateTo(item.file)
            } else {
              if (requireArguments().getBoolean("matchWith", false)) {
                requireStore().matchPasswordWithApp(item)
              } else if (item.type == PasswordItem.TYPE_PASSWORD) {
                requireStore().decryptPassword(item)
              } else if (item.type == PasswordItem.TYPE_GPG_ID) {
                showGpgIds(item.file)
              }
            }
          }
        }
    }
      .onErr { throw ClassCastException("$context must implement OnFragmentInteractionListener") }
  }

  private fun showGpgIds(file: File) {
    val gpgIds =
      file
        .readLines()
        .map { // strip trailing comments and GPG subkey ID marker
          it.substringBefore(Regex("\\s*#|!"))
        }
        .filter { it.isNotBlank() && it != "gpg-id" }
        .map { line ->
          if (line.removePrefix("0x").matches("[a-fA-F0-9]{8}".toRegex())) {
            "${line.removePrefix("0x")} (${resources.getString(R.string.pgp_short_key_identifier)})"
          } else {
            val id = PGPIdentifier.fromString(line)
            if (id == null) "${line} (${resources.getString(R.string.pgp_invalid_key_identifier)})"
            else if (!repository.hasKey(id)) {
              val message = resources.getString(R.string.pgp_unknown_key_identifier)
              if (id is PGPIdentifier.KeyId) {
                "${id.toString()} (${message})"
              } else {
                if (id.toString().matches("[^@]*@[^@]*".toRegex()))
                  "<${id.toString()}> (${message})"
                else "\"${id.toString()}\" (${message})"
              }
            } else {
              val keyId = repository.getLongKeyIdFromKeyId(id).toString()
              repository.getEmailFromKeyId(id)?.let {
                if (it.matches("[^@]*@[^@]*".toRegex())) "$keyId <$it>" else "$keyId \"$it\""
              }
            }
          }
        }
    val title =
      if (gpgIds.size > 1) resources.getString(R.string.pgp_id_label_plural)
      else resources.getString(R.string.pgp_id_label)
    MaterialAlertDialogBuilder(requireContext())
      .setTitle(title.substringBefore(':'))
      .setMessage(gpgIds.joinToString("\n"))
      .setPositiveButton(R.string.dialog_ok) { dialog, _ -> dialog.dismiss() }
      .show()
  }

  private fun requireStore() = requireActivity() as PasswordStore

  /** Returns true if the back press was handled by the [Fragment]. */
  fun onBackPressedInActivity(): Boolean {
    if (!model.canNavigateBack) return false
    // The RecyclerView state is restored when the asynchronous update operation on the
    // adapter is completed.
    recyclerViewStateToRestore = model.navigateBack()
    if (!model.canNavigateBack) requireStore().supportActionBar?.setDisplayHomeAsUpEnabled(false)
    return true
  }

  fun dismissActionMode() {
    actionMode?.finish()
  }

  companion object {

    const val ITEM_CREATION_REQUEST_KEY = "creation_key"
    const val ACTION_KEY = "action"
    const val ACTION_FOLDER = "folder"
    const val ACTION_PASSWORD = "password"

    fun newInstance(args: Bundle): PasswordFragment {
      val fragment = PasswordFragment()
      fragment.arguments = args
      return fragment
    }
  }

  fun navigateTo(file: File) {
    requireStore().clearSearch()
    model.navigateTo(
      file,
      recyclerViewState =
        binding.passRecycler.layoutManager?.onSaveInstanceState() ?: throw NullPointerException(),
    )
    requireStore().supportActionBar?.setDisplayHomeAsUpEnabled(true)
  }

  fun scrollToOnNextRefresh(file: File) {
    scrollTarget = file
  }

  interface OnFragmentInteractionListener {

    fun onFragmentInteraction(item: PasswordItem)
  }
}
