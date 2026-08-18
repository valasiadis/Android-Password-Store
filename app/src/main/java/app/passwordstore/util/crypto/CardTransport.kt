/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.util.crypto

import android.nfc.tech.IsoDep

/**
 * The whole of what an OpenPGP card needs from whatever carries its commands: send one APDU, get
 * the answer back.
 *
 * [OpenPgpCard] speaks the card's language — which command means "verify this PIN", how a long
 * answer is fetched in pieces, what a status word says — and none of that changes with the wire the
 * card is on. This is the wire, and the only place that knows which one it is.
 */
interface CardTransport : AutoCloseable {

  /**
   * Sends one complete command APDU and returns the card's raw answer, status word included.
   *
   * [timeoutMs] bounds this exchange alone. It is per-call rather than a property of the transport
   * because the two kinds of exchange want wildly different limits: an RSA-4096 decipher may keep
   * the card busy for seconds, while a check for whether the card is even still there should give
   * up almost at once.
   */
  fun transceive(command: ByteArray, timeoutMs: Int = DEFAULT_TIMEOUT_MS): ByteArray

  /** The largest command APDU that fits in a single exchange, which is what bounds chaining. */
  val maxTransceiveLength: Int

  companion object {
    /** Long enough for the slowest thing a card is asked to do here: an RSA-4096 private-key op. */
    const val DEFAULT_TIMEOUT_MS = 30_000
  }
}

/** Carries APDUs over NFC, to a card held against the phone. */
class IsoDepTransport(private val isoDep: IsoDep) : CardTransport {

  override val maxTransceiveLength: Int
    get() = isoDep.maxTransceiveLength

  override fun transceive(command: ByteArray, timeoutMs: Int): ByteArray {
    isoDep.timeout = timeoutMs
    return isoDep.transceive(command)
  }

  override fun close() {
    isoDep.close()
  }
}
