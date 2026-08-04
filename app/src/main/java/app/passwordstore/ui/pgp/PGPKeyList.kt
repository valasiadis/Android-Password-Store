/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.pgp

import android.annotation.SuppressLint
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import app.passwordstore.ui.compose.theme.APSTheme
import app.passwordstore.ui.compose.theme.SpacingLarge
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
  if (identifiers.isEmpty()) {
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
    LazyColumn(
      modifier = modifier,
      // Clearance for the floating actions, so the last key can scroll out from under them.
      contentPadding =
        PaddingValues(
          top = dimensionResource(R.dimen.spacing_xsmall),
          bottom = dimensionResource(R.dimen.list_scroll_bottom_padding),
        ),
    ) {
      if (offerInherit) {
        item {
          InheritItem(
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
      items(identifiers) { identifier ->
        KeyItem(
          identifier = identifier,
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

/** The choice of having no key of one's own, drawn like the keys it sits above. */
@Composable
private fun InheritItem(isSelected: Boolean, onSelected: () -> Unit) {
  val rowShape = RoundedCornerShape(dimensionResource(R.dimen.corner_radius_medium))
  Row(
    modifier =
      Modifier.fillMaxWidth()
        .padding(
          horizontal = dimensionResource(R.dimen.spacing_small),
          vertical = dimensionResource(R.dimen.spacing_xsmall),
        )
        .clip(rowShape)
        .background(
          if (isSelected) MaterialTheme.colorScheme.secondaryContainer
          else MaterialTheme.colorScheme.surfaceVariant
        )
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, rowShape)
        .clickable { onSelected() }
        .heightIn(min = 48.dp)
        .padding(horizontal = dimensionResource(R.dimen.activity_horizontal_margin)),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (isSelected) {
      Icon(
        painter = painterResource(id = R.drawable.ic_done_24dp),
        contentDescription = stringResource(R.string.pgp_key_selected_indicator),
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(24.dp),
      )
    } else {
      Icon(
        painter = painterResource(id = R.drawable.ic_call_merge_24px),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(24.dp),
      )
    }
    Spacer(modifier = Modifier.width(SpacingLarge))
    Text(
      text = stringResource(R.string.folder_encryption_key_inherited),
      modifier = Modifier.weight(1f),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      fontSize = 18.sp,
      overflow = TextOverflow.Ellipsis,
      maxLines = 1,
    )
  }
}

@SuppressLint("ComposeParameterOrder")
@Composable
private fun KeyItem(
  identifier: Pair<KeyId?, UserId?>,
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
  var isDeleting by remember { mutableStateOf(false) }
  var keyId = identifier.first ?: throw NullPointerException()
  // Keys that cannot serve the current purpose (e.g. a public-only key when selecting an SSH
  // authentication key) are shown greyed out and are not selectable.
  val enabled = isKeyEnabled(keyId)
  DeleteConfirmationDialog(
    isDeleting = isDeleting,
    isSecretKey = isSecretKey(keyId),
    onDismiss = { isDeleting = false },
    onConfirm = {
      onDeleteItemClick(keyId)
      isDeleting = false
    },
  )
  val rowShape = RoundedCornerShape(dimensionResource(R.dimen.corner_radius_medium))
  val outlineColor = MaterialTheme.colorScheme.outlineVariant
  Row(
    // Laid out like a password row, from the same dimensions, so the two lists cannot drift
    // apart: a filled, rounded container separated from its neighbours by margins on the row
    // itself, tinted while selected.
    modifier =
      modifier
        .fillMaxWidth()
        .padding(
          horizontal = dimensionResource(R.dimen.spacing_small),
          vertical = dimensionResource(R.dimen.spacing_xsmall),
        )
        .conditional(!enabled) { alpha(DISABLED_ALPHA) }
        .clip(rowShape)
        .background(
          if (isSelected) MaterialTheme.colorScheme.secondaryContainer
          else MaterialTheme.colorScheme.surfaceVariant
        )
        .border(1.dp, outlineColor, rowShape)
        .conditional(enabled && onSelectedChange != null) {
          toggleable(value = isSelected, onValueChange = { onSelectedChange?.invoke(it) })
        }
        // Outside selection mode, tapping a key opens its info dialog.
        .conditional(onSelectedChange == null) { clickable { onKeyInfoClick(keyId) } }
        // Tighter than a password row: a key is a single line of text with no counts or
        // affordances stacked beside it, so it does not need that row's height. The height comes
        // from this minimum alone, with no vertical padding, so that the key manager's overflow
        // button — a full 48dp touch target — does not make its rows taller than the selection
        // screen's.
        .heightIn(min = 48.dp)
        .padding(horizontal = dimensionResource(R.dimen.activity_horizontal_margin)),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    // As in the password list, the leading icon doubles as the selection indicator: while the
    // row is selected it shows a check instead of the key's type.
    if (isSelected) {
      Icon(
        painter = painterResource(id = R.drawable.ic_done_24dp),
        contentDescription = stringResource(R.string.pgp_key_selected_indicator),
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(24.dp),
      )
      Spacer(modifier = Modifier.width(SpacingLarge))
    } else if (isStubKey(keyId)) {
      Icon(
        painter = painterResource(id = R.drawable.ic_hardware_key_24dp),
        contentDescription = stringResource(R.string.pgp_key_hardware_indicator),
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(24.dp),
      )
      Spacer(modifier = Modifier.width(SpacingLarge))
    } else if (isSecretKey(keyId)) {
      Icon(
        painter = painterResource(id = R.drawable.ic_software_key_24dp),
        contentDescription = stringResource(R.string.pgp_key_software_indicator),
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(24.dp),
      )
      Spacer(modifier = Modifier.width(SpacingLarge))
    } else if (onSelectedChange != null) {
      // Keeps the label aligned with the rows that do carry an icon, so a selection check
      // appearing here does not shift the text.
      Spacer(modifier = Modifier.width(24.dp + SpacingLarge))
    }
    Text(
      text = identifier.second.toString(),
      modifier = Modifier.weight(1f),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      fontSize = 18.sp,
      overflow = TextOverflow.Ellipsis,
      maxLines = 1,
    )
    if (onSelectedChange == null) {
      Box() {
        var isMenuExpanded by remember { mutableStateOf(false) }

        IconButton(onClick = { isMenuExpanded = true }) {
          Icon(
            painter = painterResource(id = R.drawable.ic_more_vert_24dp),
            contentDescription = "PGP key actions",
          )
        }

        DropdownMenu(
          expanded = isMenuExpanded,
          onDismissRequest = { isMenuExpanded = false },
          shape = RoundedCornerShape(dimensionResource(R.dimen.corner_radius_medium)),
          containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
          if (isSecretKey(keyId)) {
            DropdownMenuItem(
              text = { Text(stringResource(id = R.string.pref_pgp_key_manager_change_passphrase)) },
              onClick = {
                isMenuExpanded = false
                onChangePassphraseClick(keyId)
              },
            )
            DropdownMenuItem(
              text = { Text(stringResource(id = R.string.pref_pgp_key_manager_export)) },
              onClick = {
                isMenuExpanded = false
                onExportItemClick(keyId)
              },
            )
            Spacer(modifier = Modifier)
          }
          DropdownMenuItem(
            text = { Text(stringResource(id = R.string.pref_pgp_key_manager_export_public)) },
            onClick = {
              isMenuExpanded = false
              onExportPublicClick(keyId)
            },
          )
          HorizontalDivider(modifier = Modifier.padding(top = SpacingLarge))
          DropdownMenuItem(
            text = {
              Text(stringResource(id = R.string.delete))
              /* Icon(
                painter = painterResource(R.drawable.ic_delete_24dp),
                stringResource(id = R.string.delete),
              ) */
            },
            onClick = {
              isMenuExpanded = false
              isDeleting = true
            },
          )
        }
      }
    }
  }
}

@Suppress("NOTHING_TO_INLINE")
@Composable
private inline fun DeleteConfirmationDialog(
  isDeleting: Boolean,
  isSecretKey: Boolean,
  noinline onDismiss: () -> Unit,
  noinline onConfirm: () -> Unit,
) {
  if (isDeleting) {
    AlertDialog(
      onDismissRequest = onDismiss,
      icon = {
        Icon(
          painter = painterResource(id = R.drawable.ic_warning_red_24dp),
          contentDescription = null,
          tint = MaterialTheme.colorScheme.error,
        )
      },
      title = {
        Text(
          text =
            if (isSecretKey)
              stringResource(R.string.pgp_key_manager_delete_secret_key_confirmation_dialog_title)
            else stringResource(R.string.pgp_key_manager_delete_key_confirmation_dialog_title)
        )
      },
      text = {
        if (isSecretKey)
          Text(text = stringResource(R.string.pgp_key_manager_delete_confirmation_dialog_message))
      },
      confirmButton = {
        TextButton(onClick = onConfirm) {
          Text(
            text = stringResource(R.string.delete),
            color = MaterialTheme.colorScheme.error,
          )
        }
      },
      dismissButton = {
        TextButton(onClick = onDismiss) {
          Text(
            text = stringResource(R.string.dialog_do_not_delete),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      },
    )
  }
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
