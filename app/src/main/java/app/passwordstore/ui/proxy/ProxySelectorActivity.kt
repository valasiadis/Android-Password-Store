/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.proxy

import android.content.SharedPreferences
import android.net.InetAddresses
import android.os.Build
import android.os.Bundle
import android.util.Patterns
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.widget.doOnTextChanged
import app.passwordstore.R
import app.passwordstore.databinding.ActivityProxySelectorBinding
import app.passwordstore.injection.prefs.SettingsPreferences
import app.passwordstore.util.extensions.enableEdgeToEdgeView
import app.passwordstore.util.extensions.getString
import app.passwordstore.util.extensions.viewBinding
import app.passwordstore.util.extensions.wipe
import app.passwordstore.util.proxy.ProxyUtils
import app.passwordstore.util.settings.GitSettings
import app.passwordstore.util.settings.PreferenceKeys
import dagger.hilt.android.AndroidEntryPoint
import java.nio.CharBuffer
import javax.inject.Inject

@AndroidEntryPoint
class ProxySelectorActivity : AppCompatActivity() {

  @Inject lateinit var gitSettings: GitSettings
  @SettingsPreferences @Inject lateinit var proxyPrefs: SharedPreferences
  @Inject lateinit var proxyUtils: ProxyUtils
  private val binding by viewBinding(ActivityProxySelectorBinding::inflate)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdgeView(binding.root)
    setContentView(binding.root)
    supportActionBar?.setDisplayHomeAsUpEnabled(true)
    with(binding) {
      proxyHost.setText(proxyPrefs.getString(PreferenceKeys.PROXY_HOST))
      proxyUser.setText(proxyPrefs.getString(PreferenceKeys.PROXY_USERNAME))
      proxyPrefs
        .getInt(PreferenceKeys.PROXY_PORT, -1)
        .takeIf { it != -1 }
        ?.let { proxyPort.setText("$it") }
      gitSettings.proxyPassword?.let {
        val charBuf = CharBuffer.wrap(it)
        proxyPassword.setText(charBuf)
        charBuf.array().wipe()
      }
      // Checked as it is typed, so a mistake is reported where it is made rather than on the way
      // out. Storing waits until host and port are both usable: half a proxy is worse than none,
      // and a port is briefly unusable on the way to being typed.
      proxyHost.doOnTextChanged { _, _, _, _ -> saveIfValid() }
      proxyPort.doOnTextChanged { _, _, _, _ -> saveIfValid() }
      proxyUser.doOnTextChanged { _, _, _, _ -> saveIfValid() }
      // The password waits until the field is done with, rather than storing every prefix of it
      // on the way to the whole.
      proxyPassword.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) saveIfValid() }
    }
  }

  override fun onPause() {
    saveIfValid()
    super.onPause()
  }

  private fun saveIfValid() {
    if (validateHost() and validatePort()) saveSettings()
  }

  /** Reports an unusable host under the field, and returns whether it can be stored. */
  private fun validateHost(): Boolean {
    val host = binding.proxyHost.text.toString()
    val isValid = host.isEmpty() || isNumericAddress(host) || host.matches(WEB_ADDRESS_REGEX)
    binding.proxyHostInputLayout.error =
      if (isValid) null else getString(R.string.invalid_proxy_url)
    return isValid
  }

  private fun validatePort(): Boolean {
    val port = binding.proxyPort.text.toString()
    val isValid = port.isEmpty() || port.toIntOrNull() in 1..MAX_PORT
    binding.proxyPortInputLayout.error =
      if (isValid) null else getString(R.string.invalid_proxy_port)
    return isValid
  }

  override fun onOptionsItemSelected(item: MenuItem): Boolean {
    when (item.itemId) {
      android.R.id.home -> {
        onBackPressedDispatcher.onBackPressed()
      }
      else -> return super.onOptionsItemSelected(item)
    }
    return true
  }

  private fun isNumericAddress(text: CharSequence): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      InetAddresses.isNumericAddress(text as String)
    } else {
      @Suppress("DEPRECATION") Patterns.IP_ADDRESS.matcher(text).matches()
    }
  }

  private fun saveSettings() {
    proxyPrefs.edit {
      binding.proxyHost.text
        ?.toString()
        ?.takeIf { it.isNotEmpty() }
        .let { gitSettings.proxyHost = it }
      binding.proxyUser.text
        ?.toString()
        ?.takeIf { it.isNotEmpty() }
        .let { gitSettings.proxyUsername = it }
      binding.proxyPort.text
        ?.toString()
        ?.takeIf { it.isNotEmpty() }
        ?.let { gitSettings.proxyPort = it.toInt() }
      (binding.proxyPassword.text?.let { CharArray(it.length) { i -> it[i] } } ?: charArrayOf())
        .let { gitSettings.proxyPassword = it }
    }
    proxyUtils.setDefaultProxy()
  }

  private companion object {
    private const val MAX_PORT = 65535
    private val WEB_ADDRESS_REGEX = Patterns.WEB_URL.toRegex()
  }
}
