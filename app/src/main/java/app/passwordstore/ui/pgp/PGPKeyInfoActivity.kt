/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.pgp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.getSystemService
import app.passwordstore.R
import app.passwordstore.crypto.KeyUtils
import app.passwordstore.crypto.PGPIdentifier
import app.passwordstore.crypto.PGPKeyManager
import app.passwordstore.data.crypto.CryptoRepository
import app.passwordstore.ui.APSAppBar
import app.passwordstore.ui.compose.theme.APSTheme
import app.passwordstore.ui.dialogs.Notice
import com.github.michaelbull.result.get
import com.google.android.material.color.MaterialColors
import dagger.hilt.android.AndroidEntryPoint
import java.text.DateFormat
import javax.inject.Inject

/**
 * Everything there is to know about one key, laid out as the system settings lay out a phone's
 * details: who it is, then grouped facts, each a row with its name above its value, any of which is
 * copied by tapping it.
 *
 * It used to be a dialog of "Label: value" lines that could be read but not copied, and that said
 * nothing about the key's parts — which of them signs, which decrypts, and whether its secret half
 * is on this device, on a card, or nowhere here.
 */
@AndroidEntryPoint
class PGPKeyInfoActivity : AppCompatActivity() {

  @Inject lateinit var pgpKeyManager: PGPKeyManager
  @Inject lateinit var cryptoRepository: CryptoRepository

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val identifier = intent.getStringExtra(EXTRA_IDENTIFIER)?.let(PGPIdentifier::fromString)
    val cert =
      identifier
        ?.let { pgpKeyManager.getKeyById(it).get() }
        ?.let(KeyUtils::tryParseCertificateOrKey)
    if (identifier == null || cert == null) {
      finish()
      return
    }
    val details = KeyDetails.of(cert, cryptoRepository.cardFingerprints(identifier))
    val kind =
      when {
        cryptoRepository.isSmartcardBacked(identifier) ||
          cryptoRepository.hasOnlyStubDecKey(identifier) -> KeyKind.Hardware
        cryptoRepository.isSecretKey(identifier) -> KeyKind.Software
        else -> KeyKind.Public
      }
    setContent {
      APSTheme {
        // The window's own background, as on the key list: the grey every list in the app stands
        // on.
        val screenColor = Color(MaterialColors.getColor(this, android.R.attr.colorBackground, 0))
        Scaffold(
          containerColor = screenColor,
          topBar = {
            APSAppBar(
              title = stringResource(R.string.pgp_key_info_title),
              navigationIcon = painterResource(R.drawable.ic_arrow_back_24dp),
              onNavigationIconClick = ::finish,
              backgroundColor = screenColor,
            )
          },
        ) { padding ->
          KeyInfo(
            details = details,
            kind = kind,
            onCopy = ::copy,
            modifier = Modifier.padding(padding).fillMaxSize(),
          )
        }
      }
    }
  }

  /** Puts [value] on the clipboard. None of it is secret: it is what a key shows the world. */
  private fun copy(label: String, value: String) {
    getSystemService<ClipboardManager>()?.setPrimaryClip(ClipData.newPlainText(label, value))
    // Android 13 and later say so themselves.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
      Notice.show(this, R.string.clipboard_copied_text)
    }
  }

  companion object {

    private const val EXTRA_IDENTIFIER = "IDENTIFIER"

    fun newIntent(context: Context, identifier: PGPIdentifier): Intent =
      Intent(context, PGPKeyInfoActivity::class.java)
        .putExtra(EXTRA_IDENTIFIER, identifier.toString())
  }
}

/** Where the key's secrets are, as a whole, in the words and the icon the key list uses. */
internal enum class KeyKind(val icon: Int, val label: Int) {
  Hardware(R.drawable.ic_hardware_key_24dp, R.string.pgp_key_info_type_hardware),
  Software(R.drawable.ic_software_key_24dp, R.string.pgp_key_info_type_secret),
  Public(R.drawable.ic_key_24dp, R.string.pgp_key_info_type_public),
}

/** One line of a group: what it is, what it says, and what tapping it copies. */
private data class Fact(val label: String, val value: String, val copied: String = value)

@Composable
private fun KeyInfo(
  details: KeyDetails,
  kind: KeyKind,
  onCopy: (label: String, value: String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val dateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM)
  val never = stringResource(R.string.pgp_key_info_never)
  val revoked = stringResource(R.string.pgp_key_info_revoked)
  val noUsage = stringResource(R.string.pgp_key_info_no_usage)
  val usageStrings =
    details.parts.associateWith { part ->
      part.usages.map { stringResource(it.label) }.ifEmpty { listOf(noUsage) }.joinToString(", ")
    }
  val expiry = { part: KeyPart ->
    when {
      part.isRevoked -> revoked
      part.expires != null -> dateFormat.format(part.expires)
      else -> never
    }
  }

  val keyFacts =
    listOf(
      Fact(stringResource(R.string.pgp_key_info_type_label), stringResource(kind.label)),
      Fact(stringResource(R.string.pgp_key_info_fingerprint_label), details.primary.fingerprint),
      Fact(stringResource(R.string.pgp_key_info_key_id_label), details.primary.keyId),
      Fact(stringResource(R.string.pgp_key_info_algorithm_label), details.primary.algorithm),
      Fact(
        stringResource(R.string.pgp_key_info_usage_label),
        usageStrings.getValue(details.primary),
      ),
      Fact(
        stringResource(R.string.pgp_key_info_created_label),
        dateFormat.format(details.primary.created),
      ),
      Fact(stringResource(R.string.pgp_key_info_expires_label), expiry(details.primary)),
    )
  val userIdLabel = stringResource(R.string.pgp_key_info_user_id_label)
  val userIdFacts = details.userIds.map { Fact(userIdLabel, it) }
  // A subkey is named by what it does, which is how it is told apart from its siblings, and
  // described by everything else on one line apiece. Tapping it copies its fingerprint: the one
  // thing about it that is worth pasting.
  val subkeyFacts =
    details.subkeys.map { part ->
      Fact(
        label = "${usageStrings.getValue(part)} · ${part.algorithm}",
        value =
          listOf(
              part.keyId,
              stringResource(part.location.label),
              "${dateFormat.format(part.created)} – ${expiry(part)}",
            )
            .joinToString("\n"),
        copied = part.fingerprint,
      )
    }

  LazyColumn(
    modifier = modifier,
    contentPadding =
      PaddingValues(
        start = dimensionResource(R.dimen.password_list_margin),
        end = dimensionResource(R.dimen.password_list_margin),
        bottom = dimensionResource(R.dimen.spacing_xlarge),
      ),
    verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.list_item_gap)),
  ) {
    item { KeyHeader(userId = details.userIds.firstOrNull(), kind = kind) }
    factGroup(R.string.pgp_key_info_section_key, keyFacts, onCopy)
    if (userIdFacts.isNotEmpty()) {
      factGroup(R.string.pgp_key_info_section_user_ids, userIdFacts, onCopy)
    }
    if (subkeyFacts.isNotEmpty()) {
      factGroup(R.string.pgp_key_info_section_subkeys, subkeyFacts, onCopy)
    }
  }
}

/** The key as the top of the system settings' About page shows the phone: its mark and its name. */
@Composable
private fun KeyHeader(userId: String?, kind: KeyKind) {
  val name = userId?.substringBefore(" <")?.takeIf { it.isNotBlank() }
  val email = userId?.substringAfter("<", "")?.substringBefore(">")?.takeIf { it.isNotBlank() }
  Column(
    modifier =
      Modifier.fillMaxWidth()
        .padding(
          top = dimensionResource(R.dimen.spacing_large),
          bottom = dimensionResource(R.dimen.spacing_small),
        ),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Box(
      modifier =
        Modifier.size(HeaderIconSize)
          .clip(CircleShape)
          .background(MaterialTheme.colorScheme.primary),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        painter = painterResource(kind.icon),
        contentDescription = stringResource(kind.label),
        tint = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier.size(HeaderGlyphSize),
      )
    }
    Text(
      text = name ?: userId ?: stringResource(R.string.pgp_key_info_title),
      modifier = Modifier.padding(top = dimensionResource(R.dimen.spacing_medium)),
      style = MaterialTheme.typography.headlineSmall,
      color = MaterialTheme.colorScheme.onSurface,
      textAlign = TextAlign.Center,
    )
    if (email != null) {
      Text(
        text = email,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
      )
    }
  }
}

/** A group's heading, and its facts as one rounded block, in the manner of the system settings. */
private fun LazyListScope.factGroup(
  heading: Int,
  facts: List<Fact>,
  onCopy: (label: String, value: String) -> Unit,
) {
  item(key = "heading-$heading") {
    Text(
      text = stringResource(heading),
      modifier =
        Modifier.padding(
          start = dimensionResource(R.dimen.spacing_large),
          top = dimensionResource(R.dimen.spacing_xlarge),
          bottom = dimensionResource(R.dimen.spacing_small),
        ),
      style = MaterialTheme.typography.titleSmall,
      fontWeight = FontWeight.Bold,
      color = MaterialTheme.colorScheme.onSurface,
    )
  }
  facts.forEachIndexed { index, fact ->
    item(key = "fact-$heading-$index") {
      FactRow(
        fact = fact,
        index = index,
        count = facts.size,
        onClick = { onCopy(fact.label, fact.copied) },
      )
    }
  }
}

/**
 * A fact as a row of the system settings' About page: its name, and its value beneath in the
 * quieter colour, wrapped rather than cut short — a fingerprint is no use with its end missing.
 */
@Composable
private fun FactRow(fact: Fact, index: Int, count: Int, onClick: () -> Unit) {
  val shape = rowShape(index, count)
  Column(
    modifier =
      Modifier.fillMaxWidth()
        .heightIn(min = dimensionResource(R.dimen.password_row_height))
        .clip(shape)
        .background(colorResource(R.color.list_row_fill))
        .clickable(onClick = onClick)
        .padding(
          horizontal = dimensionResource(R.dimen.spacing_large),
          vertical = dimensionResource(R.dimen.spacing_medium),
        ),
    verticalArrangement = Arrangement.Center,
  ) {
    Text(text = fact.label, color = MaterialTheme.colorScheme.onSurface, fontSize = 18.sp)
    Text(
      text = fact.value,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.bodyMedium,
    )
  }
}

private val HeaderIconSize = 88.dp
private val HeaderGlyphSize = 44.dp
