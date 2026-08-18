/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.passwordstore.ui.folderselect

import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import app.passwordstore.R
import app.passwordstore.data.password.PasswordItem
import app.passwordstore.data.repo.PasswordRepository
import app.passwordstore.databinding.PasswordRecyclerViewBinding
import app.passwordstore.ui.adapters.PasswordItemRecyclerAdapter
import app.passwordstore.ui.adapters.PasswordRowDecoration
import app.passwordstore.ui.passwords.PasswordStore
import app.passwordstore.util.coroutines.DispatcherProvider
import app.passwordstore.util.extensions.viewBinding
import app.passwordstore.util.extensions.windowInsetsLambda
import app.passwordstore.util.viewmodel.ListMode
import app.passwordstore.util.viewmodel.SearchableRepositoryViewModel
import com.github.michaelbull.result.onErr
import com.github.michaelbull.result.runCatching
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.launch
import me.zhanghai.android.fastscroll.FastScrollerBuilder

@AndroidEntryPoint
class SelectFolderFragment : Fragment(R.layout.password_recycler_view) {

  @Inject lateinit var dispatcherProvider: DispatcherProvider
  private val binding by viewBinding(PasswordRecyclerViewBinding::bind)
  private lateinit var recyclerAdapter: PasswordItemRecyclerAdapter
  private lateinit var listener: OnFragmentInteractionListener

  private val model: SearchableRepositoryViewModel by activityViewModels()

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)

    ViewCompat.setOnApplyWindowInsetsListener(view, windowInsetsLambda)

    // This screen borrows the password list's layout, which carries a row of its own along the
    // bottom: a search field, and the creation button that moved to this activity's own bar
    // opposite the confirm action. Both were drawn over that bar, and neither belongs here —
    // nothing is searched on this screen, a folder is picked by walking into it.
    binding.bottomBar.isVisible = false

    recyclerAdapter =
      PasswordItemRecyclerAdapter(lifecycleScope, dispatcherProvider).onItemClicked { _, item ->
        listener.onFragmentInteraction(item)
      }
    binding.passRecycler.apply {
      layoutManager = LinearLayoutManager(requireContext())
      itemAnimator = null
      adapter = recyclerAdapter
    }
    PasswordRowDecoration(requireContext()).attachTo(binding.passRecycler)

    FastScrollerBuilder(binding.passRecycler).build()
    registerForContextMenu(binding.passRecycler)

    model.navigateTo(
      PasswordRepository.getRepositoryDirectory(),
      listMode = ListMode.DirectoriesOnly,
      pushPreviousLocation = false,
    )
    arguments?.getString(PasswordStore.REQUEST_ARG_PATH)?.let { relPath ->
      // Empty components are dropped rather than resolved: the repository root arrives here as
      // the empty string, which resolves back to the directory we are already in and pushes a
      // duplicate onto the navigation stack, so the first back press appears to do nothing.
      relPath.split('/').filter(String::isNotEmpty).forEach { dir ->
        val target = File(currentDir, dir)
        if (target.isDirectory) {
          model.navigateTo(
            target,
            pushPreviousLocation = true,
            listMode = ListMode.DirectoriesOnly,
          )
        }
      }
    }

    binding.emptyMessage.setText(R.string.folder_list_empty)

    lifecycleScope.launch {
      model.searchResult.flowWithLifecycle(lifecycle).collect { result ->
        recyclerAdapter.submitList(result.passwordItems)
        // A folder with no subfolders would otherwise render as an unexplained blank list.
        binding.emptyMessage.isVisible = result.passwordItems.isEmpty()
      }
    }
  }

  fun navigateTo(file: File) {
    model.navigateTo(
      file,
      listMode = ListMode.DirectoriesOnly,
      recyclerViewState =
        binding.passRecycler.layoutManager?.onSaveInstanceState() ?: throw NullPointerException(),
    )
  }

  override fun onAttach(context: Context) {
    super.onAttach(context)
    runCatching {
      listener =
        object : OnFragmentInteractionListener {
          override fun onFragmentInteraction(item: PasswordItem) {
            if (item.type == PasswordItem.TYPE_CATEGORY) {
              model.navigateTo(item.file, listMode = ListMode.DirectoriesOnly)
            }
          }
        }
    }
      .onErr { throw ClassCastException("$context must implement OnFragmentInteractionListener") }
  }

  /** Returns true if the back press was handled by the [Fragment]. */
  fun onBackPressedInActivity(): Boolean {
    if (!model.canNavigateBack) return false
    model.navigateBack(listMode = ListMode.DirectoriesOnly)
    return true
  }

  val currentDir: File
    get() = model.currentDir.value

  interface OnFragmentInteractionListener {

    fun onFragmentInteraction(item: PasswordItem)
  }
}
