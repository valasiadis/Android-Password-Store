/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.passwordstore.util.crypto

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What goes on the wire, and where a card can and cannot send it.
 *
 * A status word is not always an answer: `61 xx` says the rest is waiting to be fetched and `6C xx`
 * says to ask again with a different Le, and both mean sending something more. Following them is
 * what makes a 4096-bit signature readable through a 256-byte window; following them without limit
 * is what lets a card that says `61 ff` for ever fill the heap. And a command only has an Le to
 * correct if it was built with one — rewriting the last byte of a command that has none sends
 * something else entirely.
 */
class OpenPgpCardCommandTest {

  /** Answers whatever it is given with whatever the test says, and remembers what it was given. */
  private class RecordingTransport(
    override val maxTransceiveLength: Int = 261,
    private val answer: (ByteArray) -> ByteArray,
  ) : CardTransport {
    val commands = mutableListOf<ByteArray>()

    override val connection = CardConnection.USB
    override val isConnected = true

    override fun transceive(command: ByteArray, timeoutMs: Int): ByteArray {
      commands += command.copyOf()
      return answer(command)
    }

    override fun close() = Unit
  }

  private val ok = byteArrayOf(0x90.toByte(), 0x00)

  /** The least a card can say about itself that is still an answer: an empty 6E. */
  private val minimalApplicationData = byteArrayOf(0x6E, 0x00)

  @Test
  fun `a card that never finishes sending is not followed for ever`() {
    // One byte of data and "there is more", over and over. Followed without limit this recurses
    // until the stack goes, having appended a byte at a time on the way down.
    val transport = RecordingTransport { byteArrayOf(0x01, 0x61, 0x10) }
    assertFailsWith<IOException> { OpenPgpCard(transport).readCardInfo() }
    // Bounded, and generously: the longest honest chain is an RSA-4096 answer read 256 bytes at a
    // time, which is two.
    assertTrue(transport.commands.size <= 20, "followed the card ${transport.commands.size} times")
  }

  @Test
  fun `a wrong-length answer to a command with no Le is not answered by rewriting it`() {
    // The status check for remaining PIN attempts is a Case-1 VERIFY: four bytes, ending in the
    // password reference. Treating that last byte as an Le and replacing it would send a VERIFY
    // against whatever the card named — spending an attempt on another password entirely.
    val transport = RecordingTransport { byteArrayOf(0x6C, 0x10) }
    assertNull(OpenPgpCard(transport).readUserPinRetries())
    assertEquals(1, transport.commands.size)
    assertContentEquals(byteArrayOf(0x00, 0x20, 0x00, 0x82.toByte()), transport.commands.single())
  }

  @Test
  fun `a wrong-length answer to a command that has an Le is answered by correcting it`() {
    var asked = 0
    val transport = RecordingTransport {
      if (asked++ == 0) byteArrayOf(0x6C, 0x05) else minimalApplicationData + ok
    }
    OpenPgpCard(transport).readCardInfo()
    // GET DATA for the application-related data is Case 2 — five bytes, all of the last one Le —
    // so the card's correction is taken and the same command goes again with the length it asked
    // for.
    assertContentEquals(byteArrayOf(0x00, 0xCA.toByte(), 0x00, 0x6E, 0x00), transport.commands[0])
    assertContentEquals(byteArrayOf(0x00, 0xCA.toByte(), 0x00, 0x6E, 0x05), transport.commands[1])
  }
}
