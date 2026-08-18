/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.passwordstore.util.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The bytes on the wire to a plugged-in card, which are the one part of that path that can be
 * checked without the hardware in hand. Everything here is read straight off the USB CCID class
 * specification, rev 1.1: the ten-byte message header (sec. 4.1), the class descriptor's layout
 * (sec. 5.1) and the status byte a card sends while it is still busy (sec. 6.2.1).
 */
class CcidFramingTest {

  @Test
  fun `wraps an APDU in a CCID transfer message`() {
    val apdu = byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, 0x02, 0x11, 0x22)
    val message = ccidMessage(messageType = 0x6F, sequence = 0x2A, payload = apdu, parameter = 0)
    assertEquals(10 + apdu.size, message.size)
    assertEquals(0x6F, message[0].toInt() and 0xff)
    // dwLength is the payload length alone, little-endian over four bytes.
    assertContentEquals(byteArrayOf(0x07, 0x00, 0x00, 0x00), message.copyOfRange(1, 5))
    assertEquals(0, message[5].toInt()) // bSlot
    assertEquals(0x2A, message[6].toInt() and 0xff) // bSeq
    assertEquals(0, message[7].toInt()) // bBWI: the reader's own waiting time will do
    assertContentEquals(byteArrayOf(0x00, 0x00), message.copyOfRange(8, 10)) // wLevelParameter
    assertContentEquals(apdu, message.copyOfRange(10, message.size))
  }

  @Test
  fun `counts a long payload past a single byte`() {
    val message =
      ccidMessage(messageType = 0x6F, sequence = 1, payload = ByteArray(300), parameter = 0)
    assertContentEquals(byteArrayOf(0x2C, 0x01, 0x00, 0x00), message.copyOfRange(1, 5))
  }

  @Test
  fun `asks for a named voltage when powering the card up`() {
    val message =
      ccidMessage(messageType = 0x62, sequence = 3, payload = byteArrayOf(), parameter = 2)
    assertEquals(0x62, message[0].toInt() and 0xff)
    assertContentEquals(ByteArray(4), message.copyOfRange(1, 5))
    assertEquals(2, message[7].toInt()) // bPowerSelect: 3.0V
  }

  @Test
  fun `reads the card's answer out of a data block`() {
    val buffer = ByteArray(64)
    byteArrayOf(0x80.toByte(), 0x03, 0x00, 0x00, 0x00, 0x00, 0x11, 0x00, 0x00, 0x00)
      .copyInto(buffer)
    byteArrayOf(0x5A, 0x90.toByte(), 0x00).copyInto(buffer, 10)
    val response = parseCcidResponse(buffer, 13)
    assertEquals(0x11, response.sequence)
    assertContentEquals(byteArrayOf(0x5A, 0x90.toByte(), 0x00), response.data)
    assertFalse(response.failed)
    assertFalse(response.isTimeExtensionRequest)
  }

  @Test
  fun `tells a busy card apart from a failed command`() {
    fun responseWithStatus(status: Int) =
      parseCcidResponse(
        byteArrayOf(0x80.toByte(), 0, 0, 0, 0, 0, 1, status.toByte(), 0, 0),
        10,
      )
    // bmCommandStatus lives in the top two bits: 0 done, 1 failed, 2 still working.
    assertFalse(responseWithStatus(0x00).failed)
    assertTrue(responseWithStatus(0x40).failed)
    assertTrue(responseWithStatus(0x80).isTimeExtensionRequest)
    assertFalse(responseWithStatus(0x80).failed)
  }

  @Test
  fun `finds the reader interface and what it can do`() {
    val descriptors =
      interfaceDescriptor(number = 0, interfaceClass = 0x03) + // an HID interface, skipped
        ccidClassDescriptor(features = 0x00040840, maxMessageLength = 271) + // and its own, ignored
        interfaceDescriptor(number = 2, interfaceClass = 0x0B) +
        ccidClassDescriptor(features = 0x00020840, maxMessageLength = 271)
    val found = parseCcidInterfaces(descriptors)
    assertEquals(1, found.size)
    assertEquals(2, found[0].interfaceNumber)
    assertEquals(271, found[0].maxMessageLength)
    assertEquals(CcidExchangeLevel.SHORT_APDU, found[0].exchangeLevel)
  }

  @Test
  fun `names the level a reader exchanges at`() {
    fun levelOf(features: Int) =
      parseCcidInterfaces(
          interfaceDescriptor(number = 0, interfaceClass = 0x0B) +
            ccidClassDescriptor(features = features, maxMessageLength = 271)
        )
        .single()
        .exchangeLevel
    assertEquals(CcidExchangeLevel.EXTENDED_APDU, levelOf(0x00040000))
    assertEquals(CcidExchangeLevel.SHORT_APDU, levelOf(0x00020000))
    assertEquals(CcidExchangeLevel.TPDU, levelOf(0x00010000))
    assertEquals(CcidExchangeLevel.CHARACTER, levelOf(0x00000000))
    assertTrue(CcidExchangeLevel.SHORT_APDU.carriesWholeApdus)
    assertFalse(CcidExchangeLevel.TPDU.carriesWholeApdus)
  }

  @Test
  fun `stops rather than guessing at descriptors that do not add up`() {
    // A length of zero would step nowhere and loop for ever; a length past the end is a lie.
    assertTrue(parseCcidInterfaces(byteArrayOf(0x00, 0x04, 0x00)).isEmpty())
    assertTrue(parseCcidInterfaces(byteArrayOf(0x40, 0x04, 0x00)).isEmpty())
    assertTrue(parseCcidInterfaces(byteArrayOf()).isEmpty())
    // A class descriptor with no interface ahead of it belongs to nothing.
    assertTrue(parseCcidInterfaces(ccidClassDescriptor(0x00020000, 271)).isEmpty())
    // A truncated class descriptor says nothing about features or message length.
    assertTrue(
      parseCcidInterfaces(
          interfaceDescriptor(number = 0, interfaceClass = 0x0B) +
            byteArrayOf(0x0A, 0x21, 0, 0, 0, 0, 0, 0, 0, 0)
        )
        .isEmpty()
    )
  }

  @Test
  fun `reads a four-byte little-endian field`() {
    assertEquals(271, readLittleEndianInt(byteArrayOf(0x0F, 0x01, 0x00, 0x00), 0))
    assertEquals(0x00040840, readLittleEndianInt(byteArrayOf(0x40, 0x08, 0x04, 0x00), 0))
  }

  private fun interfaceDescriptor(number: Int, interfaceClass: Int): ByteArray =
    byteArrayOf(
      0x09,
      0x04,
      number.toByte(),
      0x00, // bAlternateSetting
      0x03, // bNumEndpoints
      interfaceClass.toByte(),
      0x00,
      0x00,
      0x00,
    )

  private fun ccidClassDescriptor(features: Int, maxMessageLength: Int): ByteArray {
    val descriptor = ByteArray(54)
    descriptor[0] = 54
    descriptor[1] = 0x21
    littleEndian(features).copyInto(descriptor, 40)
    littleEndian(maxMessageLength).copyInto(descriptor, 44)
    return descriptor
  }

  private fun littleEndian(value: Int): ByteArray =
    byteArrayOf(
      value.toByte(),
      (value shr 8).toByte(),
      (value shr 16).toByte(),
      (value shr 24).toByte(),
    )
}
