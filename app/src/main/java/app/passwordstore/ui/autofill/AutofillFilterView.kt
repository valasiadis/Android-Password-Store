/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.passwordstore.ui.autofill

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.autofill.AutofillManager
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.text.bold
import androidx.core.text.buildSpannedString
import androidx.core.text.underline
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import app.passwordstore.R
import app.passwordstore.data.password.PasswordItem
import app.passwordstore.databinding.ActivityOreoAutofillFilterBinding
import app.passwordstore.injection.prefs.SettingsPreferences
import app.passwordstore.ui.adapters.PasswordRowDecoration
import app.passwordstore.ui.dialogs.AppDialogBuilder
import app.passwordstore.util.autofill.AutofillMatcher
import app.passwordstore.util.autofill.AutofillPreferences
import app.passwordstore.util.coroutines.DispatcherProvider
import app.passwordstore.util.extensions.enableEdgeToEdgeView
import app.passwordstore.util.extensions.viewBinding
import app.passwordstore.util.settings.DirectoryStructure
import app.passwordstore.util.settings.PreferenceKeys
import app.passwordstore.util.viewmodel.FilterMode
import app.passwordstore.util.viewmodel.ListMode
import app.passwordstore.util.viewmodel.SearchMode
import app.passwordstore.util.viewmodel.SearchableRepositoryAdapter
import app.passwordstore.util.viewmodel.SearchableRepositoryViewModel
import com.github.androidpasswordstore.autofillparser.FormOrigin
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import logcat.LogPriority.ERROR
import logcat.logcat

@AndroidEntryPoint
class AutofillFilterView : AppCompatActivity() {

  @Inject lateinit var dispatcherProvider: DispatcherProvider

  @SettingsPreferences @Inject lateinit var settings: SharedPreferences

  companion object {

    private const val HEIGHT_PERCENTAGE = .9
    private const val WIDTH_PERCENTAGE = .95

    private const val EXTRA_FORM_ORIGIN_WEB =
      "app.passwordstore.autofill.oreo.ui.EXTRA_FORM_ORIGIN_WEB"
    private const val EXTRA_FORM_ORIGIN_APP =
      "app.passwordstore.autofill.oreo.ui.EXTRA_FORM_ORIGIN_APP"
    private var matchAndDecryptFileRequestCode = 900000

    fun makeMatchAndDecryptFileIntentSender(
      context: Context,
      formOrigin: FormOrigin,
    ): IntentSender {
      val intent =
        Intent(context, AutofillFilterView::class.java).apply {
          when (formOrigin) {
            is FormOrigin.Web -> putExtra(EXTRA_FORM_ORIGIN_WEB, formOrigin.identifier)
            is FormOrigin.App -> putExtra(EXTRA_FORM_ORIGIN_APP, formOrigin.identifier)
          }
        }
      return PendingIntent.getActivity(
          context,
          matchAndDecryptFileRequestCode++,
          intent,
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE
          } else {
            PendingIntent.FLAG_CANCEL_CURRENT
          },
        )
        .intentSender
    }
  }

  private lateinit var formOrigin: FormOrigin
  private lateinit var directoryStructure: DirectoryStructure
  private val binding by viewBinding(ActivityOreoAutofillFilterBinding::inflate)

  private val model: SearchableRepositoryViewModel by viewModels()
  private var showHiddenSetting: Boolean = false

  private val decryptAction =
    registerForActivityResult(StartActivityForResult()) { result ->
      if (result.resultCode == RESULT_OK) {
        setResult(RESULT_OK, result.data)
      }
      finish()
    }

  private fun resize() {
    val params = window.attributes
    params.height = (HEIGHT_PERCENTAGE * resources.displayMetrics.heightPixels).toInt()
    params.width = (WIDTH_PERCENTAGE * resources.displayMetrics.widthPixels).toInt()
    window.attributes = params
  }

  override fun onConfigurationChanged(newConfig: Configuration) {
    super.onConfigurationChanged(newConfig)

    resize()
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdgeView(binding.root)
    setContentView(binding.root)
    setFinishOnTouchOutside(true)

    resize()

    if (intent?.hasExtra(AutofillManager.EXTRA_CLIENT_STATE) != true) {
      logcat(ERROR) { "AutofillFilterActivity started without EXTRA_CLIENT_STATE" }
      finish()
      return
    }

    formOrigin =
      when {
        intent?.hasExtra(EXTRA_FORM_ORIGIN_WEB) == true -> {
          FormOrigin.Web(
            intent?.getStringExtra(EXTRA_FORM_ORIGIN_WEB) ?: throw NullPointerException()
          )
        }
        intent?.hasExtra(EXTRA_FORM_ORIGIN_APP) == true -> {
          FormOrigin.App(
            intent?.getStringExtra(EXTRA_FORM_ORIGIN_APP) ?: throw NullPointerException()
          )
        }
        else -> {
          logcat(ERROR) {
            "AutofillFilterActivity started without EXTRA_FORM_ORIGIN_WEB or EXTRA_FORM_ORIGIN_APP"
          }
          finish()
          return
        }
      }

    directoryStructure = AutofillPreferences.directoryStructure(this)
    supportActionBar?.hide()

    binding.filterMenuButton.setOnClickListener {
      var items =
        arrayOf(
          getString(
            R.string.oreo_autofill_match_with,
            formOrigin.getPrettyIdentifier(applicationContext, untrusted = false),
          ),
          getString(R.string.oreo_autofill_matches_clear_existing),
        )

      var checked =
        booleanArrayOf(
          AutofillPreferences.addQuickSelectButton(this@AutofillFilterView),
          AutofillPreferences.removeQuickSelectButtons(this@AutofillFilterView),
        )

      if (formOrigin is FormOrigin.Web) {
        items = arrayOf(getString(R.string.oreo_autofill_strict_domain_search)) + items
        checked =
          booleanArrayOf(AutofillPreferences.strictDomainSearch(this@AutofillFilterView)) + checked
      }

      AppDialogBuilder(this)
        .setTitle(R.string.oreo_autofill_search_options)
        .setMultiChoiceItems(items, checked) { _, which, isChecked ->
          if (formOrigin is FormOrigin.Web) {
            when (which) {
              0 -> {
                AutofillPreferences.strictDomainSearch(this@AutofillFilterView, isChecked)
                updateSearch()
              }
              1 -> AutofillPreferences.addQuickSelectButton(this@AutofillFilterView, isChecked)
              2 -> AutofillPreferences.removeQuickSelectButtons(this@AutofillFilterView, isChecked)
            }
          } else {
            when (which) {
              0 -> AutofillPreferences.addQuickSelectButton(this@AutofillFilterView, isChecked)
              1 -> AutofillPreferences.removeQuickSelectButtons(this@AutofillFilterView, isChecked)
            }
          }
        }
        .show()
    }

    // temporarily suppress display of hidden files
    showHiddenSetting = settings.getBoolean(PreferenceKeys.SHOW_HIDDEN_CONTENTS, false)
    settings.edit { putBoolean(PreferenceKeys.SHOW_HIDDEN_CONTENTS, false) }

    bindUI()
    updateSearch()
    setResult(RESULT_CANCELED)
  }

  override fun onStop() {
    super.onStop()
    settings.edit { putBoolean(PreferenceKeys.SHOW_HIDDEN_CONTENTS, showHiddenSetting) }
  }

  private fun bindUI() {
    // Once, not with every search: results stand as one block of rows, as the password list's do.
    PasswordRowDecoration(this).attachTo(binding.rvPassword)
    with(binding) {
      search.apply {
        val initialSearch = formOrigin.getPrettyIdentifier(applicationContext, untrusted = false)
        setText(initialSearch, TextView.BufferType.EDITABLE)
        searchClear.isVisible = initialSearch.isNotEmpty()
        addTextChangedListener {
          searchClear.isVisible = !it.isNullOrEmpty()
          updateSearch()
        }
      }
      searchClear.setOnClickListener { search.text?.clear() }
      // The line above it says what this is for; this says where, in the size a heading has.
      origin.text = formOrigin.getPrettyIdentifier(applicationContext, untrusted = true)
      lifecycleScope.launch { handleSearchResults() }
    }
  }

  private suspend fun handleSearchResults() {
    model.searchResult.collect { result ->
      val list = result.passwordItems
      (binding.rvPassword.adapter as SearchableRepositoryAdapter).submitList(list) {
        binding.rvPassword.scrollToPosition(0)
      }
      // Switch RecyclerView out for a "no results" message if the new list is empty and
      // the message is not yet shown (and vice versa).
      if (
        (list.isEmpty() && binding.rvPasswordSwitcher.nextView.id == binding.rvPasswordEmpty.id) ||
          (list.isNotEmpty() && binding.rvPasswordSwitcher.nextView.id == binding.rvPassword.id)
      ) {
        binding.rvPasswordSwitcher.showNext()
      }
    }
  }

  private fun buildResultList() {
    binding.rvPassword.apply {
      adapter =
        SearchableRepositoryAdapter(
            R.layout.oreo_autofill_filter_row,
            ::PasswordViewHolder,
            lifecycleScope,
            dispatcherProvider,
          ) { item, _ ->
            val file = item.file.relativeTo(item.rootDir)

            val hexIdRegex = """[A-Fa-f0-9]{8}([A-Fa-f0-9]+)$""".toRegex()

            val trailingCount =
              hexIdRegex.find(file.nameWithoutExtension)?.groupValues?.getOrNull(1)?.length ?: 0

            val fileName =
              file.nameWithoutExtension.let {
                if (trailingCount > 0) it.dropLast(trailingCount) + "…" else it
              }

            // As the password list shows an entry: the folder it is in, small, above its name. What
            // was searched for is picked out in both, so it is plain why the entry matched. Where
            // the store keeps one folder per account (example.org/jon@doe.org/password.gpg), the
            // account folder is the name, or every row would be called "password".
            val searchString = binding.search.text.toString()
            val accountDir =
              file.parentFile?.takeIf { directoryStructure == DirectoryStructure.DirectoryBased }
            title.text = highlight(accountDir?.name ?: fileName, searchString)
            val parent = (accountDir ?: file).parentFile?.path
            subtitle.isVisible = parent != null
            subtitle.text = parent?.let { highlight(it, searchString) }
          }
          .onItemClicked { _, item -> decryptAndFill(item) }
      layoutManager = LinearLayoutManager(context)
    }
  }

  /** [text] with each place [query] occurs in it in bold and underlined. */
  private fun highlight(text: String, query: String): CharSequence {
    if (query.isEmpty()) return text
    return buildSpannedString {
      var from = 0
      Regex(Regex.escape(query), RegexOption.IGNORE_CASE).findAll(text).forEach { match ->
        append(text, from, match.range.first)
        bold { underline { append(match.value) } }
        from = match.range.last + 1
      }
      append(text, from, text.length)
    }
  }

  private fun updateSearch() {
    model.search(
      binding.search.text.toString(),
      filterMode =
        if (
          formOrigin is FormOrigin.Web &&
            AutofillPreferences.strictDomainSearch(this@AutofillFilterView)
        )
          FilterMode.StrictDomain
        else if (settings.getString(PreferenceKeys.SEARCH_FILTER_MODE, "exact") == "fuzzy")
          FilterMode.Fuzzy
        else FilterMode.Exact,
      searchMode = SearchMode.RecursivelyInSubdirectories,
      listMode = ListMode.FilesOnly,
    )
    buildResultList()
  }

  private fun decryptAndFill(item: PasswordItem) {
    if (AutofillPreferences.removeQuickSelectButtons(this@AutofillFilterView))
      AutofillMatcher.clearMatchesFor(applicationContext, formOrigin)
    decryptAction.launch(
      AutofillDecryptActivity.makeDecryptFileIntent(
        item.file,
        formOrigin,
        intent?.extras ?: throw NullPointerException(),
        this,
      )
    )
  }
}
