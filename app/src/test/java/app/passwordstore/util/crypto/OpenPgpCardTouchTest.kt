/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.passwordstore.util.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a card says about wanting to be touched, which is a User Interaction Flag per operation in
 * the application-related data (OpenPGP Card spec 3.4, sec. 4.4.3.9): a byte that is 0x00 when no
 * touch is wanted, 0x01 when one is, and 0x02 when that can no longer be switched off.
 */
class OpenPgpCardTouchTest {

  /** Answers every command with the same canned application-related data. */
  private class CannedTransport(private val applicationData: ByteArray) : CardTransport {
    var exchanges = 0

    override val connection = CardConnection.USB
    override val maxTransceiveLength = 261

    override fun transceive(command: ByteArray, timeoutMs: Int): ByteArray {
      exchanges++
      return applicationData + byteArrayOf(0x90.toByte(), 0x00)
    }

    override fun close() = Unit
  }

  private fun applicationData(vararg uif: Pair<Int, Int>): ByteArray {
    val flags =
      uif.fold(byteArrayOf()) { acc, (tag, state) ->
        // Each UIF is two bytes: the flag itself, then which button the card has.
        acc + byteArrayOf(tag.toByte(), 0x02, state.toByte(), 0x20)
      }
    val discretionary = byteArrayOf(0x73, flags.size.toByte()) + flags
    return byteArrayOf(0x6E, discretionary.size.toByte()) + discretionary
  }

  @Test
  fun `reads the flag belonging to each operation`() {
    val card =
      OpenPgpCard(CannedTransport(applicationData(0xD6 to 0x01, 0xD7 to 0x00, 0xD8 to 0x02)))
    assertTrue(card.requiresTouch(CardOperation.SIGN))
    assertFalse(card.requiresTouch(CardOperation.DECRYPT))
    // Permanently on is still on.
    assertTrue(card.requiresTouch(CardOperation.AUTHENTICATE))
  }

  @Test
  fun `a card with no flags at all is one that never asks`() {
    val card = OpenPgpCard(CannedTransport(applicationData()))
    assertFalse(card.requiresTouch(CardOperation.SIGN))
    assertFalse(card.requiresTouch(CardOperation.DECRYPT))
    assertFalse(card.requiresTouch(CardOperation.AUTHENTICATE))
  }

  @Test
  fun `asks the card what it is only once`() {
    val transport = CannedTransport(applicationData(0xD6 to 0x01))
    val card = OpenPgpCard(transport)
    card.requiresTouch(CardOperation.SIGN)
    card.requiresTouch(CardOperation.DECRYPT)
    card.readCardInfo()
    // The card cannot change under us, and every attempt asks: one exchange for the application
    // data, and the one readCardInfo makes for the card's URL.
    assertEquals(2, transport.exchanges)
  }
}
