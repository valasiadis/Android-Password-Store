/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.passwordstore.ui.pgp

import app.passwordstore.crypto.PGPIdentifier
import app.passwordstore.data.crypto.CryptoRepository

/**
 * Names the keys behind [keyIds] the way their owners would recognise them.
 *
 * A key is stored as the number that identifies it, which is what a field showing "the key this
 * folder uses" ended up displaying — a string that says nothing to the person who chose it. The
 * number is still what is shown for a key this store does not hold, since there is nothing else to
 * say about it.
 */
fun CryptoRepository.keyNames(keyIds: String?): List<String> =
  keyIds?.split("\n")?.filter(String::isNotBlank).orEmpty().map { id ->
    PGPIdentifier.fromString(id)?.let { identifier ->
      getUserIdFromKeyId(identifier)?.takeIf { it != "null" }
    } ?: id
  }
