/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.util.crypto

/**
 * Somewhere a card can turn up, kept open for the whole of one operation.
 *
 * A single card operation can take several presentations of the card — a wrong PIN, a card lifted
 * too early — and the reader outlives all of them: it is opened once when the operation starts,
 * asked for a card as many times as the operation needs, and closed once at the end. Over NFC that
 * span is exactly the span of reader mode, which is what keeps the platform from dispatching the
 * card's NDEF URL between two taps.
 */
interface CardReader : AutoCloseable {

  /** Which ways of reaching a card this reader is currently watching. Never empty. */
  val connections: Set<CardConnection>

  /**
   * Suspends until a card turns up here and its OpenPGP applet has been selected, transparently
   * skipping past anything that turns out not to be one. [onCardDetected] is invoked as soon as a
   * card has connected — before the applet selection it is waiting on — so the user can be told to
   * keep it there while the exchange runs, and is told which wire it arrived on.
   */
  suspend fun awaitCard(onCardDetected: (CardConnection) -> Unit): OpenPgpCard
}
