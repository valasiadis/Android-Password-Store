/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.util.crypto

/**
 * The ways a card can be reached from this phone.
 *
 * Only two things ever turn on this, and both are about the user rather than the protocol: what to
 * ask them to do with the card, and whether the card has to be seen to leave before the reader is
 * shut down. Every APDU below is the same either way.
 */
enum class CardConnection {
  /** Held against the back of the phone. */
  NFC,
  /** Plugged into it. */
  USB,
}

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

  /** Which wire this is, for the sake of what the user is told and asked to do. */
  val connection: CardConnection

  /**
   * Whether the wire still has a card on the end of it, answered without sending anything.
   *
   * Asked while an exchange is in flight, when actually addressing the card is not an option: the
   * point is to notice a card that has gone in the middle of an operation, which otherwise shows up
   * only when the exchange finally times out.
   */
  val isConnected: Boolean

  companion object {
    /** Long enough for the slowest thing a card is asked to do here: an RSA-4096 private-key op. */
    const val DEFAULT_TIMEOUT_MS = 30_000
  }
}
