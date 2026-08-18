/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.util.crypto

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select

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

/**
 * Every way a card could arrive, watched at once, so the user picks by picking up their card
 * rather than by answering a question about it first.
 *
 * Whichever [readers] produces a card first wins and the others are told to stop. A reader that had
 * one in hand by then — a card tapped at the very moment another was plugged in — has nobody to
 * give it to, and closes it rather than leaving it open.
 */
class CompositeCardReader(private val readers: List<CardReader>) : CardReader {

  override val connections = readers.flatMap { it.connections }.toSet()

  override suspend fun awaitCard(onCardDetected: (CardConnection) -> Unit): OpenPgpCard =
    coroutineScope {
      val handedOver = AtomicBoolean(false)
      val attempts =
        readers.map { reader ->
          async {
            val card = reader.awaitCard(onCardDetected)
            if (handedOver.compareAndSet(false, true)) {
              card
            } else {
              runCatching { card.close() }
              awaitCancellation()
            }
          }
        }
      try {
        select { attempts.forEach { attempt -> attempt.onAwait { it } } }
      } finally {
        attempts.forEach { it.cancel() }
      }
    }

  override fun close() {
    readers.forEach { runCatching { it.close() } }
  }
}
