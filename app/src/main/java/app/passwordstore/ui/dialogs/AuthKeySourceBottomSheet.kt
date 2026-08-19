/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.passwordstore.ui.dialogs

import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.setFragmentResult
import app.passwordstore.R
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * Asks where an authentication key should come from, in the sheet the key manager already uses to
 * ask where a PGP key comes from. The answer comes back as a fragment result rather than through a
 * listener handed in, so it survives the screen being rebuilt under the sheet.
 */
class AuthKeySourceBottomSheet : BottomSheetDialogFragment() {

  private var behavior: BottomSheetBehavior<FrameLayout>? = null
  private val bottomSheetCallback =
    object : BottomSheetBehavior.BottomSheetCallback() {
      override fun onSlide(bottomSheet: View, slideOffset: Float) {}

      override fun onStateChanged(bottomSheet: View, newState: Int) {
        if (newState == BottomSheetBehavior.STATE_COLLAPSED) {
          dismiss()
        }
      }
    }

  override fun onCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?,
  ): View? {
    if (savedInstanceState != null) dismiss()
    return inflater.inflate(R.layout.auth_key_source_sheet, container, false)
  }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)
    if (Build.VERSION.SDK_INT > Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
      ViewCompat.setOnApplyWindowInsetsListener(view) { v, windowInsets ->
        val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
        v.updateLayoutParams<ViewGroup.MarginLayoutParams> { bottomMargin = insets.bottom }
        windowInsets
      }

    view.viewTreeObserver.addOnGlobalLayoutListener(
      object : ViewTreeObserver.OnGlobalLayoutListener {
        override fun onGlobalLayout() {
          view.viewTreeObserver.removeOnGlobalLayoutListener(this)
          val dialog = dialog as BottomSheetDialog? ?: return
          behavior = dialog.behavior
          behavior?.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            peekHeight = 0
            addBottomSheetCallback(bottomSheetCallback)
          }
          dialog.answers(R.id.auth_key_generate, SOURCE_GENERATE)
          dialog.answers(R.id.auth_key_import, SOURCE_IMPORT)
          dialog.answers(R.id.auth_key_pgp, SOURCE_PGP)
        }
      }
    )
  }

  private fun BottomSheetDialog.answers(viewId: Int, source: String) {
    findViewById<View>(viewId)?.setOnClickListener {
      setFragmentResult(
        AUTH_KEY_SOURCE_REQUEST_KEY,
        Bundle().also { it.putString(SOURCE_KEY, source) },
      )
      dismiss()
    }
  }

  override fun dismiss() {
    super.dismiss()
    behavior?.removeBottomSheetCallback(bottomSheetCallback)
  }

  companion object {
    const val AUTH_KEY_SOURCE_REQUEST_KEY = "auth_key_source_request_key"
    const val SOURCE_KEY = "auth_key_source"
    const val SOURCE_GENERATE = "generate"
    const val SOURCE_IMPORT = "import"
    const val SOURCE_PGP = "pgp"
  }
}
