/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.util.crypto

import android.app.Activity
import android.content.Context
import androidx.annotation.DrawableRes
import app.passwordstore.R
import com.github.michaelbull.result.runCatching
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.supervisorScope
import logcat.asLog
import logcat.logcat

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
 *
 * A reader that gives up does not take the others with it. Each way to a card can fail for reasons
 * entirely its own — a plugged-in reader this app cannot speak to, a card that refuses the applet —
 * and none of them says anything about the card the user is about to hold against the phone. So a
 * failure drops that reader out of the watch and the rest carry on; only when every one of them has
 * given up is there nothing left to wait for, and then the last failure is the one reported.
 */
class CompositeCardReader(private val readers: List<CardReader>) : CardReader {

  override val connections = readers.flatMap { it.connections }.toSet()

  override suspend fun awaitCard(onCardDetected: (CardConnection) -> Unit): OpenPgpCard =
    // Supervised, so that one attempt failing is not a reason to cancel its siblings — which is
    // exactly what a plain coroutineScope would do.
    supervisorScope {
      val handedOver = AtomicBoolean(false)
      val watching =
        readers
          .map { reader ->
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
          .toMutableList()
      try {
        var lastFailure: Throwable? = null
        while (watching.isNotEmpty()) {
          val finished = select { watching.forEach { attempt -> attempt.onJoin { attempt } } }
          watching.remove(finished)
          try {
            return@supervisorScope finished.await()
          } catch (e: CancellationException) {
            throw e
          } catch (e: Throwable) {
            logcat { "One way to a card gave up; still watching ${watching.size}: ${e.asLog()}" }
            lastFailure = e
          }
        }
        throw lastFailure ?: IOException("There is no way to reach a card from this phone")
      } finally {
        watching.forEach { it.cancel() }
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
 * The mark shown while a card is being waited for, which is a picture of where to put it.
 *
 * With both watched at once it is the contactless mark: presenting a card is what the great
 * majority will be doing, and the words below it say the other way is open too.
 */
@DrawableRes
fun cardMark(connections: Set<CardConnection>): Int =
  if (connections.singleOrNull() == CardConnection.USB) R.drawable.ic_usb_24dp
  else R.drawable.ic_contactless_24dp

/** The mark for the card that has actually answered. */
@DrawableRes
fun cardMark(connection: CardConnection): Int =
  when (connection) {
    CardConnection.NFC -> R.drawable.ic_contactless_24dp
    CardConnection.USB -> R.drawable.ic_usb_24dp
  }

/**
 * Whether the mark for a wire is a circle, and so can have the ring stand where its own outer
 * circle was. The contactless mark is; the USB mark is a plug on a stalk, which has to be drawn
 * inside the ring rather than cropped by it.
 */
fun cardMarkIsCropped(connection: CardConnection): Boolean = connection == CardConnection.NFC

fun cardMarkIsCropped(connections: Set<CardConnection>): Boolean =
  connections.singleOrNull() != CardConnection.USB

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
