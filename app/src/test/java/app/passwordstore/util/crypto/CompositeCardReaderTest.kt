/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.passwordstore.util.crypto

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest

/**
 * Both ways to a card are watched at once, and the user picks by picking up their card. What that
 * has to survive is one of the two failing on its own account — a plugged-in reader this app cannot
 * speak to, a card that refuses the applet — which says nothing whatever about the card the user is
 * at that moment holding against the phone. A failure that took the other watch down with it left
 * the tap unheard, and, since the caller reads a failed wait as "try again", asked for the card
 * again, failed again, and went round.
 */
class CompositeCardReaderTest {

  private class FakeTransport : CardTransport {
    var closed = false

    override val connection = CardConnection.NFC
    override val maxTransceiveLength = 261
    override val isConnected = true

    override fun transceive(command: ByteArray, timeoutMs: Int) = byteArrayOf(0x90.toByte(), 0x00)

    override fun close() {
      closed = true
    }
  }

  private class FakeReader(
    connection: CardConnection,
    private val produce: suspend () -> OpenPgpCard,
  ) : CardReader {
    var closed = false

    override val connections = setOf(connection)

    override suspend fun awaitCard(onCardDetected: (CardConnection) -> Unit): OpenPgpCard =
      produce()

    override fun close() {
      closed = true
    }
  }

  @Test
  fun `a way that gives up does not take the other with it`() = runTest {
    val transport = FakeTransport()
    val card = OpenPgpCard(transport)
    val plugged =
      FakeReader(CardConnection.USB) {
        throw UnsupportedCardReaderException("This reader exchanges T=1 blocks")
      }
    val held =
      FakeReader(CardConnection.NFC) {
        // The user reaches for their card a moment after the reader they plugged in gave up.
        delay(50)
        card
      }
    val reader = CompositeCardReader(listOf(plugged, held))
    assertSame(card, reader.awaitCard {})
    assertFalse(transport.closed, "handed over a card it had already closed")
  }

  @Test
  fun `when every way has given up, the failure is reported`() = runTest {
    val reader =
      CompositeCardReader(
        listOf(
          FakeReader(CardConnection.USB) { throw UnsupportedCardReaderException("no good") },
          FakeReader(CardConnection.NFC) { throw IOException("the tag went away") },
        )
      )
    assertFailsWith<IOException> { reader.awaitCard {} }
  }

  @Test
  fun `a card that arrives too late to be wanted is closed rather than left open`() = runTest {
    val wanted = FakeTransport()
    val tooLate = FakeTransport()
    // Both have a card in hand before either is asked for one, which is the tap that lands at the
    // moment another key is plugged in.
    val reader =
      CompositeCardReader(
        listOf(
          FakeReader(CardConnection.USB) { OpenPgpCard(wanted) },
          FakeReader(CardConnection.NFC) { OpenPgpCard(tooLate) },
        )
      )
    reader.awaitCard {}
    assertFalse(wanted.closed, "closed the card it handed over")
    // The loser's card has nobody to give it to. Left open it would keep the card powered with
    // nothing on its way to close it.
    assertTrue(tooLate.closed, "left a card open that nobody was ever given")
  }

  @Test
  fun `closing the composite closes every way it was watching`() = runTest {
    val plugged = FakeReader(CardConnection.USB) { awaitCancellation() }
    val held = FakeReader(CardConnection.NFC) { awaitCancellation() }
    CompositeCardReader(listOf(plugged, held)).close()
    assertTrue(plugged.closed)
    assertTrue(held.closed)
  }

  @Test
  fun `it says every way it is watching`() {
    val reader =
      CompositeCardReader(
        listOf(
          FakeReader(CardConnection.USB) { error("not asked") },
          FakeReader(CardConnection.NFC) { error("not asked") },
        )
      )
    assertEquals(setOf(CardConnection.USB, CardConnection.NFC), reader.connections)
  }
}
