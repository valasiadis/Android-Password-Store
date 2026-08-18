/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.util.crypto

import android.app.Activity
import android.content.Context
import app.passwordstore.R
import com.github.michaelbull.result.runCatching
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
 * Every way a card could arrive, watched at once, so the user picks by picking up their card rather
 * than by answering a question about it first.
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
      val attempts = readers.map { reader ->
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

/**
 * Opens every way a card could reach this phone, for the length of one operation. Returns `null`
 * when there is no way at all: NFC switched off or absent on a phone that cannot host USB either.
 *
 * Both are watched at once rather than one being chosen, because which one the user will reach for
 * is not something this app can know — and asking them to say so in a setting, before they have
 * picked up either, is asking the wrong question. Must be called from the main thread, since
 * enabling NFC reader mode is tied to the activity.
 */
fun openCardReaders(activity: Activity): CardReader? {
  val readers = listOfNotNull(NfcCardReader.create(activity), UsbCardReader.create(activity))
  return when (readers.size) {
    0 -> null
    1 -> readers.single()
    else -> CompositeCardReader(readers)
  }
}

/**
 * What to ask the user to do, given everywhere a card could turn up. Never says "present" to
 * someone whose phone is only watching a socket, or "plug in" to one only watching the air.
 */
fun cardPresentMessage(context: Context, connections: Set<CardConnection>): String =
  context.getString(
    when {
      connections.size > 1 -> R.string.openpgp_card_present_any
      connections.single() == CardConnection.USB -> R.string.openpgp_card_present_usb
      else -> R.string.openpgp_card_present
    }
  )

/** What to tell the user to do with the card that has answered, while it is being worked. */
fun cardHoldMessage(context: Context, connection: CardConnection): String =
  context.getString(
    when (connection) {
      CardConnection.NFC -> R.string.openpgp_card_hold
      CardConnection.USB -> R.string.openpgp_card_hold_usb
    }
  )

/**
 * What to tell the user while the card holds its answer back waiting to be touched. Said in terms
 * of where the card is, since a card on the back of the phone has to be touched without being moved
 * off it, and one in the socket only has to be touched.
 */
fun cardTouchMessage(context: Context, connection: CardConnection): String =
  context.getString(
    when (connection) {
      CardConnection.NFC -> R.string.openpgp_card_touch_nfc
      CardConnection.USB -> R.string.openpgp_card_touch_usb
    }
  )

/**
 * How to have another go after an exchange failed on the way. Asked of the card that failed, since
 * that is the one the user has in their hand; when the failure came before any card answered there
 * is nothing to say about where it is.
 */
fun cardRetryMessage(context: Context, connection: CardConnection?): String =
  context.getString(
    when (connection) {
      CardConnection.NFC -> R.string.openpgp_card_comm_failed
      CardConnection.USB -> R.string.openpgp_card_comm_failed_usb
      null -> R.string.openpgp_card_comm_failed_any
    }
  )
