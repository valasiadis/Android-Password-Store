/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.passwordstore.util.crypto

import kotlin.test.Test
import kotlin.test.assertNull

/**
 * A card is not obliged to make sense.
 *
 * Everything a card says arrives as BER-TLV and is walked byte by byte, and the thing doing the
 * walking is reached before any PIN is verified — [OpenPgpCard.parseKdf] runs on the KDF-DO of
 * whatever was presented, which over NFC is whatever was held against the phone. So the answers
 * below are the shapes a broken or hostile card can put on the wire: tags that never end, lengths
 * claiming more than arrived, a length so large it wraps an Int. None of them is a secret at risk;
 * all of them used to be an exception thrown out of a byte-array index.
 *
 * The answer to every one of them is the same and is the whole point: null, meaning nothing was
 * found, so the PIN travels as the user typed it rather than as a hash derived from nonsense.
 */
class OpenPgpCardMalformedAnswerTest {

  @Test
  fun `a tag that runs off the end is not read past it`() {
    // 0x1f opens a multi-byte tag; every continuation byte says another follows, and then the
    // answer simply stops.
    assertNull(OpenPgpCard.parseKdf(byteArrayOf(0x1f, 0x81.toByte(), 0x81.toByte())))
    assertNull(OpenPgpCard.parseKdf(byteArrayOf(0x1f)))
  }

  @Test
  fun `a tag with no length after it is not read past either`() {
    assertNull(OpenPgpCard.parseKdf(byteArrayOf(0x81.toByte())))
  }

  @Test
  fun `a length claiming more than arrived finds nothing`() {
    // Tag 81, "the next 200 bytes are the algorithm", and one byte of them.
    assertNull(OpenPgpCard.parseKdf(byteArrayOf(0x81.toByte(), 0xC8.toByte(), 0x03)))
  }

  @Test
  fun `a long-form length with its own bytes missing finds nothing`() {
    // 0x84: "the length is in the next four bytes", of which two turned up.
    assertNull(OpenPgpCard.parseKdf(byteArrayOf(0x81.toByte(), 0x84.toByte(), 0x00, 0x01)))
  }

  @Test
  fun `a length that would wrap an Int finds nothing`() {
    // Four length bytes with the top bit set: as an Int that is negative, and a negative length
    // used to sail straight past a bounds check written as an addition.
    assertNull(
      OpenPgpCard.parseKdf(
        byteArrayOf(
          0x81.toByte(),
          0x84.toByte(),
          0xFF.toByte(),
          0xFF.toByte(),
          0xFF.toByte(),
          0xFF.toByte(),
          0x03,
        )
      )
    )
  }

  @Test
  fun `a length needing more bytes than an Int holds finds nothing`() {
    // 0x88: eight bytes of length. Nothing a card has to say is that long.
    assertNull(
      OpenPgpCard.parseKdf(byteArrayOf(0x81.toByte(), 0x88.toByte()) + ByteArray(8) { 0x00 })
    )
  }

  @Test
  fun `the indefinite form has no place in a card's answer`() {
    // 0x80: "length to be announced later", which BER allows and a card does not use.
    assertNull(OpenPgpCard.parseKdf(byteArrayOf(0x81.toByte(), 0x80.toByte(), 0x03)))
  }

  @Test
  fun `a nested constructed DO that lies about its contents finds nothing`() {
    // Tag 6E is recursed into; inside it, a tag whose length runs past the end of the nesting.
    assertNull(OpenPgpCard.parseKdf(byteArrayOf(0x6E, 0x03, 0x81.toByte(), 0x7F, 0x03)))
  }

  @Test
  fun `a well-formed answer is still read`() {
    // The guards above turn nothing away that a working card sends: the same DO the KDF test uses,
    // arriving after a truncated neighbour would have stopped the walk, is still found on its own.
    val kdf =
      OpenPgpCard.parseKdf(
        byteArrayOf(0x81.toByte(), 0x01, 0x03) +
          byteArrayOf(0x82.toByte(), 0x01, 0x08) +
          byteArrayOf(0x83.toByte(), 0x04, 0x00, 0x01, 0x86.toByte(), 0xA0.toByte()) +
          byteArrayOf(0x84.toByte(), 0x02, 0x0A, 0x0B)
      )
    requireNotNull(kdf)
  }
}
