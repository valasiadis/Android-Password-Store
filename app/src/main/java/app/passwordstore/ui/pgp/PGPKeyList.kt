/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.pgp

import android.annotation.SuppressLint
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.passwordstore.R
import app.passwordstore.crypto.PGPIdentifier
import app.passwordstore.crypto.PGPIdentifier.KeyId
import app.passwordstore.crypto.PGPIdentifier.UserId
import app.passwordstore.crypto.displayName
import app.passwordstore.ui.compose.theme.APSTheme
import app.passwordstore.util.extensions.conditional
import app.passwordstore.util.git.sshj.SshKey
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/** Opacity of anything on the key screens that is present but currently unavailable. */
internal const val DISABLED_ALPHA = 0.38f

@Composable
fun KeyList(
  identifiers: ImmutableList<Pair<KeyId?, UserId?>>,
  isSecretKey: (identifier: PGPIdentifier) -> Boolean,
  onChangePassphraseClick: (identifier: PGPIdentifier) -> Unit,
  onDeleteItemClick: (identifier: PGPIdentifier) -> Unit,
  onExportItemClick: (identifier: PGPIdentifier) -> Unit,
  onExportPublicClick: (identifier: PGPIdentifier) -> Unit,
  modifier: Modifier = Modifier,
  isStubKey: (identifier: PGPIdentifier) -> Boolean = { false },
  onKeyInfoClick: (identifier: PGPIdentifier) -> Unit = {},
  onKeySelected: ((identifier: PGPIdentifier, isSelected: Boolean) -> Unit)? = null,
  singleSelection: Boolean = false,
  isKeyEnabled: (identifier: PGPIdentifier) -> Boolean = { true },
  initiallySelectedKeys: ImmutableList<KeyId> = persistentListOf(),
  /**
   * Keys the caller is set to that this device does not hold — a store cloned from elsewhere, or a
   * key since deleted. Shown so the current answer is visible rather than silently absent, marked
   * as absent, and selectable like any other: a store is usually shared, and a key that is no help
   * here may be exactly the key the machine at the other end opens its entries with. Dropping one
   * is the user's decision to stop encrypting to whoever holds it, not this screen's.
   */
  missingKeys: ImmutableList<KeyId> = persistentListOf(),
  /**
   * Whether "the folder above decides" is one of the choices, and what it currently is. A folder
   * with no key of its own uses its parent's, which is a real answer to "which key?" and belongs in
   * the same list as the keys — it is how a folder goes back to following its parent.
   */
  offerInherit: Boolean = false,
  inheritInitially: Boolean = false,
  onInheritChanged: (Boolean) -> Unit = {},
) {
  // The one place that knows what is selected. Rows deliberately keep no selection state of
  // their own: when each row remembered its own checked flag, a row selected earlier stayed
  // checked after another row was picked, so tapping it again toggled that stale flag off
  // instead of selecting it and the screen ended up with nothing selected at all.
  var inheritSelected by remember { mutableStateOf(inheritInitially) }
  val selectedKeys = remember {
    mutableStateListOf<KeyId>().apply {
      // Whatever is already in use starts out selected, so the screen opens showing the state
      // it is about to change rather than an empty one.
      addAll(initiallySelectedKeys)
      if (singleSelection && SshKey.pgpLongKeyId != 0L && isEmpty()) add(KeyId(SshKey.pgpLongKeyId))
    }
  }
  if (identifiers.isEmpty() && missingKeys.isEmpty()) {
    Column(
      modifier = modifier.fillMaxSize(),
      verticalArrangement = Arrangement.Center,
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Image(
        painter = painterResource(id = R.drawable.ic_launcher_foreground),
        contentDescription = "Password Store logo",
      )
      Text(stringResource(R.string.pgp_key_manager_no_keys_guidance))
    }
  } else {
    // The rows read as one block, as the password list's and the settings' do: rounded fully where
    // it starts and ends, slightly where rows meet. What each row is decides nothing about that,
    // only where it falls, so the place is counted across all three kinds of row.
    val inheritCount = if (offerInherit) 1 else 0
    val rowCount = inheritCount + missingKeys.size + identifiers.size
    LazyColumn(
      modifier = modifier,
      // The password list's margins, and clearance below for the floating actions, so the last
      // key can scroll out from under them.
      contentPadding =
        PaddingValues(
          start = dimensionResource(R.dimen.password_list_margin),
          end = dimensionResource(R.dimen.password_list_margin),
          top = dimensionResource(R.dimen.password_list_margin_top),
          bottom = dimensionResource(R.dimen.list_scroll_bottom_padding),
        ),
      verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.list_item_gap)),
    ) {
      if (offerInherit) {
        item {
          InheritItem(
            shape = rowShape(0, rowCount),
            isSelected = inheritSelected,
            onSelected = {
              // The two answers exclude each other: a folder either has keys of its own or has
              // whatever its parent has.
              inheritSelected = true
              selectedKeys.forEach { onKeySelected?.invoke(it, false) }
              selectedKeys.clear()
              onInheritChanged(true)
            },
          )
        }
      }
      itemsIndexed(missingKeys) { index, keyId ->
        MissingKeyItem(
          keyId = keyId,
          shape = rowShape(inheritCount + index, rowCount),
          isSelected = onKeySelected != null && keyId in selectedKeys,
          onSelectedChange =
            if (onKeySelected != null) {
              { isSelected ->
                if (singleSelection) selectedKeys.clear()
                if (isSelected) selectedKeys.add(keyId) else selectedKeys.remove(keyId)
                if (isSelected && inheritSelected) {
                  inheritSelected = false
                  onInheritChanged(false)
                }
                onKeySelected(keyId, isSelected)
              }
            } else null,
        )
      }
      itemsIndexed(identifiers) { index, identifier ->
        KeyItem(
          identifier = identifier,
          shape = rowShape(inheritCount + missingKeys.size + index, rowCount),
          isSecretKey = isSecretKey,
          isStubKey = isStubKey,
          onKeyInfoClick = onKeyInfoClick,
          onChangePassphraseClick = onChangePassphraseClick,
          onDeleteItemClick = onDeleteItemClick,
          onExportItemClick = onExportItemClick,
          onExportPublicClick = onExportPublicClick,
          isKeyEnabled = isKeyEnabled,
          isSelected = onKeySelected != null && identifier.first in selectedKeys,
          // Null outside selection mode, which is what tells a row it is not selectable.
          onSelectedChange =
            if (onKeySelected != null) {
              { isSelected ->
                identifier.first?.let { keyId ->
                  // A single selection replaces whatever was selected before, so picking a
                  // second key cannot leave two rows looking selected.
                  if (singleSelection) selectedKeys.clear()
                  if (isSelected) selectedKeys.add(keyId) else selectedKeys.remove(keyId)
                  if (isSelected && inheritSelected) {
                    inheritSelected = false
                    onInheritChanged(false)
                  }
                  onKeySelected(keyId, isSelected)
                }
              }
            } else null,
        )
      }
    }
  }
}

/**
 * Where a row falls in the list's block: the first rounded fully at its top, the last at its
 * bottom, every edge where two rows meet only slightly — the corners the password list and the
 * settings give theirs.
 */
@Composable
internal fun rowShape(index: Int, count: Int): Shape {
  val outer = dimensionResource(R.dimen.list_outer_corner_radius)
  val inner = dimensionResource(R.dimen.list_inner_corner_radius)
  val top = if (index == 0) outer else inner
  val bottom = if (index == count - 1) outer else inner
  return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

/**
 * A row of this list, measured as a row of the password list is, from the same dimensions, so the
 * two cannot drift apart: as tall, filled with the same colour, its icon standing in a circle of
 * the theme's primary colour, and its name in the colour and size of an entry's.
 *
 * The icon doubles as the selection indicator, as it does there: while the row is selected it shows
 * a check instead. A row that cannot serve here is [dimmed] — its content rather than its
 * container, so it still reads as part of the block. [trailing] is a button at the row's end rather
 * than its content, which is why it is not named so.
 */
@SuppressLint("ComposableLambdaParameterNaming")
@Composable
private fun ListRow(
  icon: Painter,
  title: String,
  shape: Shape,
  modifier: Modifier = Modifier,
  /** How the row is touched: applied inside its shape, so the ripple is drawn in it. */
  interaction: Modifier = Modifier,
  iconDescription: String? = null,
  summary: String? = null,
  isSelected: Boolean = false,
  dimmed: Boolean = false,
  trailing: @Composable (() -> Unit)? = null,
) {
  val circle = dimensionResource(R.dimen.list_icon_circle)
  val circlePadding = dimensionResource(R.dimen.list_icon_circle_padding)
  val contentAlpha = if (dimmed) DISABLED_ALPHA else 1f
  Row(
    modifier =
      modifier
        .fillMaxWidth()
        .heightIn(min = dimensionResource(R.dimen.password_row_height))
        .clip(shape)
        .background(
          if (isSelected) MaterialTheme.colorScheme.secondaryContainer
          else colorResource(R.color.list_row_fill)
        )
        // Inside the clip, so the touch is drawn in the row's own shape.
        .then(interaction)
        .padding(
          start = dimensionResource(R.dimen.spacing_large),
          // A button at the end brings its own room around its glyph.
          end =
            if (trailing != null) dimensionResource(R.dimen.spacing_xsmall)
            else dimensionResource(R.dimen.spacing_large),
        ),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      modifier =
        Modifier.alpha(contentAlpha)
          .size(circle)
          .clip(CircleShape)
          .background(MaterialTheme.colorScheme.primary),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        painter = if (isSelected) painterResource(R.drawable.ic_done_24dp) else icon,
        contentDescription =
          if (isSelected) stringResource(R.string.pgp_key_selected_indicator) else iconDescription,
        tint = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier.size(circle - circlePadding * 2),
      )
    }
    Spacer(modifier = Modifier.width(dimensionResource(R.dimen.list_icon_text_gap)))
    Column(
      modifier =
        Modifier.weight(1f)
          .alpha(contentAlpha)
          .padding(vertical = dimensionResource(R.dimen.spacing_medium))
    ) {
      Text(
        text = title,
        color = MaterialTheme.colorScheme.primary,
        fontSize = 18.sp,
        overflow = TextOverflow.Ellipsis,
        maxLines = 1,
      )
      if (summary != null) {
        Text(
          text = summary,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          fontSize = 13.sp,
          overflow = TextOverflow.Ellipsis,
          maxLines = 1,
        )
      }
    }
    trailing?.invoke()
  }
}

/**
 * A key the folder is set to that is not held on this device: chosen like any other, and said to be
 * absent.
 *
 * Absent here does not mean useless. A store is usually shared, and the entries in it are usually
 * encrypted to everyone who reads them — so a key this device cannot open anything with is
 * routinely the one the machine at the other end depends on. Dimmed to say it will be no help here,
 * and left selectable so that keeping it stays the user's call.
 */
@Composable
private fun MissingKeyItem(
  keyId: KeyId,
  shape: Shape,
  isSelected: Boolean,
  onSelectedChange: ((Boolean) -> Unit)?,
) {
  ListRow(
    icon = painterResource(R.drawable.ic_key_24dp),
    title = keyId.displayName,
    summary = stringResource(R.string.pgp_key_missing_label),
    shape = shape,
    isSelected = isSelected,
    // Dimmed only while it is not chosen: once it is, it is as much the answer as any other key.
    dimmed = !isSelected,
    interaction =
      Modifier.conditional(onSelectedChange != null) {
        toggleable(value = isSelected, onValueChange = { onSelectedChange?.invoke(it) })
      },
  )
}

/** The choice of having no key of one's own, drawn like the keys it sits above. */
@Composable
private fun InheritItem(shape: Shape, isSelected: Boolean, onSelected: () -> Unit) {
  ListRow(
    icon = painterResource(R.drawable.ic_call_merge_24px),
    title = stringResource(R.string.folder_encryption_key_inherited),
    shape = shape,
    isSelected = isSelected,
    interaction = Modifier.clickable { onSelected() },
  )
}

@SuppressLint("ComposeParameterOrder")
@Composable
private fun KeyItem(
  identifier: Pair<KeyId?, UserId?>,
  shape: Shape,
  isSecretKey: (identifier: PGPIdentifier) -> Boolean,
  isStubKey: (identifier: PGPIdentifier) -> Boolean,
  onKeyInfoClick: (identifier: PGPIdentifier) -> Unit,
  onChangePassphraseClick: (identifier: PGPIdentifier) -> Unit,
  onDeleteItemClick: (identifier: PGPIdentifier) -> Unit,
  onExportItemClick: (identifier: PGPIdentifier) -> Unit,
  onExportPublicClick: (identifier: PGPIdentifier) -> Unit,
  modifier: Modifier = Modifier,
  isSelected: Boolean = false,
  /** Null when the list is not a selection screen, which makes the row unselectable. */
  onSelectedChange: ((Boolean) -> Unit)? = null,
  isKeyEnabled: (identifier: PGPIdentifier) -> Boolean = { true },
) {
  val keyId = identifier.first ?: throw NullPointerException()
  // Keys that cannot serve the current purpose (e.g. a public-only key when selecting an SSH
  // authentication key) are shown greyed out and are not selectable.
  val enabled = isKeyEnabled(keyId)
  // What kind of key it is, in the icon and in words beneath its name, as the key's own info
  // dialog names it.
  val (icon, iconDescription) =
    when {
      isStubKey(keyId) ->
        R.drawable.ic_hardware_key_24dp to stringResource(R.string.pgp_key_hardware_indicator)
      isSecretKey(keyId) ->
        R.drawable.ic_software_key_24dp to stringResource(R.string.pgp_key_software_indicator)
      else -> R.drawable.ic_key_24dp to null
    }
  val kind =
    when {
      isStubKey(keyId) -> stringResource(R.string.pgp_key_info_type_hardware)
      isSecretKey(keyId) -> stringResource(R.string.pgp_key_info_type_secret)
      else -> stringResource(R.string.pgp_key_info_type_public)
    }
  ListRow(
    icon = painterResource(icon),
    iconDescription = iconDescription,
    title = identifier.second.toString(),
    summary = kind,
    shape = shape,
    modifier = modifier,
    isSelected = isSelected,
    dimmed = !enabled,
    interaction =
      Modifier.conditional(enabled && onSelectedChange != null) {
          toggleable(value = isSelected, onValueChange = { onSelectedChange?.invoke(it) })
        }
        // Outside selection mode, tapping a key opens its info dialog.
        .conditional(onSelectedChange == null) { clickable { onKeyInfoClick(keyId) } },
    trailing =
      if (onSelectedChange == null) {
        {
          Box {
            var isMenuExpanded by remember { mutableStateOf(false) }

            IconButton(onClick = { isMenuExpanded = true }) {
              Icon(
                painter = painterResource(id = R.drawable.ic_more_vert_24dp),
                contentDescription = "PGP key actions",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }

            DropdownMenu(
              expanded = isMenuExpanded,
              onDismissRequest = { isMenuExpanded = false },
              shape = RoundedCornerShape(dimensionResource(R.dimen.corner_radius_medium)),
              // The surface and edge of the app's other popup menus (popup_menu_background).
              containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
              border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
              if (isSecretKey(keyId)) {
                DropdownMenuItem(
                  text = {
                    MenuText(stringResource(id = R.string.pref_pgp_key_manager_change_passphrase))
                  },
                  onClick = {
                    isMenuExpanded = false
                    onChangePassphraseClick(keyId)
                  },
                )
                DropdownMenuItem(
                  text = { MenuText(stringResource(id = R.string.pref_pgp_key_manager_export)) },
                  onClick = {
                    isMenuExpanded = false
                    onExportItemClick(keyId)
                  },
                )
              }
              DropdownMenuItem(
                text = {
                  MenuText(stringResource(id = R.string.pref_pgp_key_manager_export_public))
                },
                onClick = {
                  isMenuExpanded = false
                  onExportPublicClick(keyId)
                },
              )
              DropdownMenuItem(
                text = { MenuText(stringResource(id = R.string.delete)) },
                onClick = {
                  isMenuExpanded = false
                  // Asked first by the screen, as every other deletion in the app is.
                  onDeleteItemClick(keyId)
                },
              )
            }
          }
        }
      } else null,
  )
}

/**
 * A menu item's words, in the style the app's other popup menus give theirs: Compose would set them
 * smaller, as a label, where the menus drawn by the platform set them as body text.
 */
@Composable
private fun MenuText(text: String) {
  Text(text = text, style = MaterialTheme.typography.bodyLarge)
}

@Preview
@Composable
private fun KeyListPreview() {
  APSTheme {
    Box(modifier = Modifier.background(MaterialTheme.colorScheme.background)) {
      KeyList(
        identifiers = persistentListOf(),
        isSecretKey = { _ -> true },
        onChangePassphraseClick = {},
        onDeleteItemClick = {},
        onExportItemClick = {},
        onExportPublicClick = {},
      )
    }
  }
}

@Preview
@Composable
private fun EmptyKeyListPreview() {
  APSTheme {
    Box(modifier = Modifier.background(MaterialTheme.colorScheme.background)) {
      KeyList(
        identifiers = persistentListOf(),
        isSecretKey = { _ -> true },
        onChangePassphraseClick = {},
        onDeleteItemClick = {},
        onExportItemClick = {},
        onExportPublicClick = {},
      )
    }
  }
}
