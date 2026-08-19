/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.util.crypto

import com.github.michaelbull.result.get
import com.github.michaelbull.result.getOr
import com.github.michaelbull.result.runCatching
import java.io.IOException
import java.nio.CharBuffer
import java.security.MessageDigest

/**
 * An OpenPGP card, reached over whatever [CardTransport] it turned up on.
 *
 * Everything here is the card's own protocol as the OpenPGP Card specification defines it: which
 * APDU asks what, how a long answer is fetched in pieces, what a status word means. None of it
 * knows whether the card is held against the phone or plugged into it.
 */
class OpenPgpCard(
  private val transport: CardTransport,
  private val onClose: () -> Unit = {},
) : AutoCloseable {

  private var kdfParameters: KdfParameters? = null
  private var kdfRead = false
  private var applicationData: ByteArray? = null

  /**
   * Run just before the card is asked for something it will not do until it is touched.
   *
   * A card can be set up to require a finger on its contact for each private-key operation, and
   * then it simply does not answer until that happens — for as long as its own patience lasts. From
   * the outside that is indistinguishable from a slow card, so nothing could be said about it
   * beyond "working", and a user who did not know their key wanted touching waited for a timeout.
   */
  var onTouchRequired: (CardOperation) -> Unit = {}

  /** Which wire this card turned up on, which decides what the user is asked to do with it. */
  val connection: CardConnection
    get() = transport.connection

  /**
   * Whether the card is still on the end of the wire, asked of the wire rather than of the card.
   */
  val isConnected: Boolean
    get() = transport.isConnected

  /**
   * Opens the card's OpenPGP application, which is the first thing said to any card.
   *
   * Held to a short deadline of its own. It is a one-command exchange that any card answers at
   * once, and it happens in the moment right after the card has been found — when the user is most
   * likely to be still settling it into place, or to have lifted it again. Left on the ordinary
   * deadline, a card that goes at that moment leaves the prompt saying it has been found for the
   * best part of a minute, with nothing listening for the card being presented again.
   */
  fun selectOpenPgpApplet() {
    transceive(SELECT_OPENPGP, SELECT_TIMEOUT_MS)
  }

  fun verifyUserPin(pin: CharArray) {
    verifyPin(pin, reference = 0x82)
  }

  fun verifySignaturePin(pin: CharArray) {
    verifyPin(pin, reference = 0x81)
  }

  /** Remaining PW1 verification attempts as seen through the decryption (0x82) slot. */
  fun readUserPinRetries(): Int? = readPinRetries(reference = 0x82)

  /** Remaining PW1 verification attempts as seen through the signature (0x81) slot. */
  fun readSignaturePinRetries(): Int? = readPinRetries(reference = 0x81)

  /**
   * Asks the card how many verification attempts are left for the password [reference], *without*
   * consuming one. Per the OpenPGP Card spec a VERIFY with an empty data field (a Case-1 APDU) is a
   * pure status check: the card answers `63 Cx` (x tries left), `69 83` (blocked → 0), or `90 00`
   * (already verified this session). Returns null when the card doesn't report a usable count.
   */
  private fun readPinRetries(reference: Int): Int? =
    try {
      transceive(byteArrayOf(0x00, 0x20, 0x00, reference.toByte()))
      null // 90 00: already verified this session; no counter reported.
    } catch (e: OpenPgpCardStatusException) {
      e.retriesRemaining
    } catch (e: IOException) {
      null // A transport problem while probing shouldn't mask the original failure.
    }

  private fun verifyPin(pin: CharArray, reference: Int) {
    // Encode the PIN straight from the CharArray to a wipeable ByteArray. Going through
    // String.toByteArray() would leave the PIN in an immutable String that cannot be zeroed and
    // lingers on the heap until garbage collection.
    val rawPin = charArrayToUtf8Bytes(pin)
    // A card set up with a KDF has been told to expect the derived hash of the PIN and never the
    // PIN itself. Handing it the characters the user typed is simply a wrong PIN to it — and,
    // since the hash is a fixed 32 or 64 bytes, usually a wrong *length* as well, which the card
    // turns down without even spending one of the retries. That reads from the outside as a PIN
    // rejected over and over while the card's counter never moves.
    val pinBytes = kdfParameters()?.let { kdf -> deriveKdfPin(rawPin, kdf) } ?: rawPin
    try {
      // Caught here rather than left to wrap the length byte and put a command on the wire that
      // means something other than what was intended. A KDF card is never in this position: what
      // it is handed is a digest of a fixed size.
      if (pinBytes.size > MAX_PIN_BYTES) throw PinTooLongForCardException(MAX_PIN_BYTES)
      transceive(
        byteArrayOf(0x00, 0x20, 0x00, reference.toByte(), pinBytes.size.toByte()) + pinBytes
      )
    } catch (e: OpenPgpCardStatusException) {
      // A VERIFY carries only the PIN in its data field, so a "wrong data / wrong length" rejection
      // (67 xx / 6A 80) means the PIN didn't fit the card's PW length bounds — surface it as a
      // recoverable, re-promptable error rather than the raw status word.
      if (e.isDataFieldRejection) throw SmartcardPinFormatException(e.sw1, e.sw2)
      // Every other rejection carries its status word onwards unchanged, but typed so callers can
      // tell it came from a VERIFY. Only a VERIFY can turn a PIN down; the commands that follow one
      // answer with overlapping status words (69 82 above all) that mean something else entirely.
      throw SmartcardPinVerificationException(e.sw1, e.sw2)
    } finally {
      rawPin.fill(0)
      pinBytes.fill(0)
    }
  }

  /**
   * The card's KDF-DO (`00F9`) if it carries one that asks for a derived PIN, else null.
   *
   * Read once per card session and remembered, since it cannot change under us. A card that has no
   * such DO answers with a status word, which means the PIN goes to it as typed; a *transport*
   * failure is left to propagate instead, so the caller re-presents the card rather than sending a
   * raw PIN to a card that may well have wanted a derived one.
   */
  private fun kdfParameters(): KdfParameters? {
    if (!kdfRead) {
      val data =
        try {
          transceive(GET_KDF)
        } catch (e: OpenPgpCardStatusException) {
          null
        }
      kdfParameters = data?.takeIf { it.isNotEmpty() }?.let(::parseKdf)
      kdfRead = true
    }
    return kdfParameters
  }

  /**
   * Encodes [chars] as UTF-8 without ever materializing the secret in an immutable String. The
   * encoder's backing array is wiped before returning, so the only surviving copy is the
   * caller-owned result, which the caller can zero once it is done.
   */
  private fun charArrayToUtf8Bytes(chars: CharArray): ByteArray {
    val byteBuffer = Charsets.UTF_8.encode(CharBuffer.wrap(chars))
    val bytes = ByteArray(byteBuffer.remaining())
    byteBuffer.get(bytes)
    if (byteBuffer.hasArray()) byteBuffer.array().fill(0)
    return bytes
  }

  fun decipher(ciphertext: ByteArray): ByteArray {
    announceTouch(CardOperation.DECRYPT)
    val payload = byteArrayOf(0x00) + ciphertext
    return transceiveData(0x2A, 0x80, 0x86, payload, expectedLength = ciphertext.size)
  }

  fun computeDigitalSignature(digestInfo: ByteArray, expectedLength: Int): ByteArray {
    announceTouch(CardOperation.SIGN)
    return transceiveData(0x2A, 0x9E, 0x9A, digestInfo, expectedLength)
  }

  /**
   * Runs INTERNAL AUTHENTICATE (INS 0x88) with the card's Authentication key over [input] and
   * returns the raw signature. Unlike PSO:CDS (used for OpenPGP signatures), this uses the
   * Authentication key slot and requires PW1 verified in mode 0x82 (see [verifyUserPin]). Used for
   * SSH public-key authentication.
   */
  fun internalAuthenticate(input: ByteArray): ByteArray {
    announceTouch(CardOperation.AUTHENTICATE)
    // Le = 0 → request up to 256 bytes; longer responses (e.g. RSA) are pulled in via 61xx
    // chaining.
    return transceiveData(0x88, 0x00, 0x00, input, expectedLength = 0)
  }

  fun readCardInfo(): OpenPgpCardInfo {
    val fingerprints = findTlv(applicationData(), 0xC5)?.let(::parseFingerprints).orEmpty()
    val url = runCatching { transceive(GET_URL).toString(Charsets.UTF_8).trim() }.get()
    return OpenPgpCardInfo(fingerprints = fingerprints, url = url?.takeIf { it.isNotBlank() })
  }

  /**
   * Everything the card says about itself in one answer: its keys' fingerprints, and which of its
   * operations want a touch. Read once per session and remembered, since none of it can change
   * while the card is in the field, and every attempt asks for it.
   */
  private fun applicationData(): ByteArray =
    applicationData ?: transceive(GET_APPLICATION_RELATED_DATA).also { applicationData = it }

  /**
   * Whether the card will wait for a touch before it performs [operation].
   *
   * The card keeps one User Interaction Flag per operation (`D6`, `D7`, `D8`), each a byte that is
   * zero when no touch is wanted and non-zero when one is — either switchable or set for good. A
   * card that carries no such flag at all is one that never asks.
   */
  fun requiresTouch(operation: CardOperation): Boolean =
    runCatching { findTlv(applicationData(), operation.uifTag) }
      .get()
      ?.firstOrNull()
      ?.let { it.toInt() != 0 } == true

  private fun announceTouch(operation: CardOperation) {
    if (runCatching { requiresTouch(operation) }.getOr(false)) onTouchRequired(operation)
  }

  override fun close() {
    runCatching { transport.close() }
    onClose()
  }

  /**
   * Sends one command and reads the answer, following the card wherever the status word points.
   *
   * Two status words are not answers but instructions: `61 xx` means the rest is waiting to be
   * fetched, and `6C xx` means the same command with a different Le. Both are followed, and both
   * are bounded — a card is at liberty to answer `61 ff` for ever, and a chain nothing stops is a
   * chain that fills the heap and then overflows the stack. [remainingSteps] and [collected] are
   * what is left of each budget; a card that spends either has stopped making sense.
   */
  private fun transceive(
    command: ByteArray,
    timeoutMs: Int = transport.defaultTimeoutMs,
    remainingSteps: Int = MAX_CHAIN_STEPS,
    collected: Int = 0,
  ): ByteArray {
    val response = transport.transceive(command, timeoutMs)
    if (response.size < 2) throw IOException("Malformed card response")
    val sw1 = response[response.size - 2].toInt() and 0xff
    val sw2 = response[response.size - 1].toInt() and 0xff
    val data = response.copyOf(response.size - 2)
    if (sw1 == 0x90 && sw2 == 0x00) return data
    if (sw1 == 0x61) {
      val total = collected + data.size
      if (remainingSteps <= 0 || total > MAX_RESPONSE_LENGTH) {
        throw IOException("The card kept asking to be read from and never finished")
      }
      return data +
        transceive(
          byteArrayOf(0x00, 0xC0.toByte(), 0x00, 0x00, sw2.toByte()),
          timeoutMs,
          remainingSteps - 1,
          total,
        )
    }
    // Only a command that actually ends in an Le byte has one to correct. Rewriting the last byte
    // of a command that has none — a Case-1 VERIFY, say, whose four bytes end in the password
    // reference — would send something else entirely, and against another password at that.
    if (sw1 == 0x6C && remainingSteps > 0 && hasLeByte(command)) {
      return transceive(
        command.copyOf(command.size - 1) + sw2.toByte(),
        timeoutMs,
        remainingSteps - 1,
        collected,
      )
    }
    throw OpenPgpCardStatusException(sw1, sw2)
  }

  /**
   * The largest command data field that still fits in one exchange on this wire.
   *
   * Bounded by the short-APDU maximum, and by what the wire will carry: a reader that announces a
   * small message buffer is handed commands that fit in it, rather than a full-length APDU it has
   * no room for.
   */
  private val maxPayloadPerCommand: Int
    get() = (transport.maxTransceiveLength - APDU_OVERHEAD).coerceIn(1, MAX_APDU_NC)

  private fun transceiveData(
    ins: Int,
    p1: Int,
    p2: Int,
    payload: ByteArray,
    expectedLength: Int,
  ): ByteArray {
    return if (payload.size <= maxPayloadPerCommand) {
      transceiveShort(ins, p1, p2, payload, expectedLength)
    } else {
      transceiveChained(ins, p1, p2, payload, expectedLength)
    }
  }

  private fun transceiveShort(
    ins: Int,
    p1: Int,
    p2: Int,
    payload: ByteArray,
    expectedLength: Int,
  ): ByteArray {
    val command =
      byteArrayOf(0x00, ins.toByte(), p1.toByte(), p2.toByte(), payload.size.toByte()) +
        payload +
        encodeShortLe(expectedLength)
    return transceive(command)
  }

  private fun transceiveChained(
    ins: Int,
    p1: Int,
    p2: Int,
    payload: ByteArray,
    expectedLength: Int,
  ): ByteArray {
    val chunkSize = maxPayloadPerCommand
    var offset = 0
    var response = byteArrayOf()
    while (offset < payload.size) {
      val end = minOf(offset + chunkSize, payload.size)
      val chunk = payload.copyOfRange(offset, end)
      val isLast = end == payload.size
      val cla = if (isLast) 0x00 else 0x10
      val command =
        byteArrayOf(cla.toByte(), ins.toByte(), p1.toByte(), p2.toByte(), chunk.size.toByte()) +
          chunk +
          if (isLast) encodeShortLe(expectedLength) else byteArrayOf()
      response = transceive(command)
      offset = end
    }
    return response
  }

  companion object {
    private const val MAX_APDU_NC = 254

    /** CLA INS P1 P2 Lc around the data field, and Le after it. */
    private const val APDU_OVERHEAD = 6

    /**
     * How many times a card may send us somewhere else — for the rest of an answer, or for the same
     * command at another length — before it is not being followed any further. The longest
     * legitimate chain is an RSA-4096 answer read 256 bytes at a time, which is two.
     */
    private const val MAX_CHAIN_STEPS = 16

    /** More than any answer an OpenPGP card has to give, and far less than anything that hurts. */
    private const val MAX_RESPONSE_LENGTH = 1 shl 16

    /**
     * The longest PIN that fits in a short APDU's data field.
     *
     * The user cannot type one this long at the card prompt, but a PIN can also arrive seeded from
     * the biometric store — where a key that was once a software key keeps its old passphrase, and
     * a passphrase has no such bound. Encoded into the length byte it would wrap, and what went to
     * the card would be a command it could only refuse for reasons that had nothing to do with the
     * secret.
     */
    private const val MAX_PIN_BYTES = MAX_APDU_NC

    // Opening the OpenPGP application is one command and one answer; a card that has not answered
    // in this long is a card that has gone.
    private const val SELECT_TIMEOUT_MS = 2_000

    /**
     * Whether [command] ends in an Le byte, which is what a `6C xx` offers to correct.
     *
     * The four cases of ISO 7816-4, told apart by length: four bytes is Case 1 and carries neither
     * a data field nor Le; five is Case 2, all Le; longer means a data field whose length byte says
     * whether one byte is left over at the end for Le (Case 4) or not (Case 3).
     */
    private fun hasLeByte(command: ByteArray): Boolean =
      when {
        command.size == 5 -> true
        command.size > 5 -> command.size == 5 + (command[4].toInt() and 0xff) + 1
        else -> false
      }

    private fun encodeShortLe(expectedLength: Int): ByteArray =
      byteArrayOf(if (expectedLength >= 256) 0x00 else expectedLength.toByte())

    private val OPENPGP_AID = byteArrayOf(0xD2.toByte(), 0x76, 0x00, 0x01, 0x24, 0x01)
    private val SELECT_OPENPGP =
      byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, OPENPGP_AID.size.toByte()) +
        OPENPGP_AID +
        byteArrayOf(0x00)
    private val GET_APPLICATION_RELATED_DATA = byteArrayOf(0x00, 0xCA.toByte(), 0x00, 0x6E, 0x00)
    private val GET_URL = byteArrayOf(0x00, 0xCA.toByte(), 0x5F, 0x50, 0x00)
    private val GET_KDF = byteArrayOf(0x00, 0xCA.toByte(), 0x00, 0xF9.toByte(), 0x00)

    /** The only KDF the OpenPGP Card spec defines beyond "none": iterated-and-salted S2K. */
    private const val KDF_ITERSALTED_S2K = 0x03

    /**
     * Reads the KDF-DO's `81`/`82`/`83`/`84` fields — algorithm, hash, iteration count and the salt
     * belonging to PW1. Returns null unless the card actually asks for a derived PIN, which covers
     * both a card without the DO at all and one carrying it with the algorithm set to "none"; in
     * either case the PIN travels as the user typed it.
     *
     * Only PW1's salt (`84`) is read: this app verifies PW1 in both its modes (0x81 signing and
     * 0x82 decryption/authentication), which are two access conditions on one password, and never
     * touches PW3 (`86`) or the resetting code (`85`).
     */
    internal fun parseKdf(data: ByteArray): KdfParameters? {
      val algorithm = findTlv(data, 0x81)?.firstOrNull()?.toInt()?.and(0xff) ?: return null
      if (algorithm != KDF_ITERSALTED_S2K) return null
      val hash = findTlv(data, 0x82)?.firstOrNull()?.toInt()?.and(0xff) ?: return null
      val digestAlgorithm =
        when (hash) {
          0x08 -> "SHA-256"
          0x09 -> "SHA-384"
          0x0A -> "SHA-512"
          else -> return null
        }
      val iterations =
        findTlv(data, 0x83)
          ?.takeIf { it.size == 4 }
          ?.fold(0) { acc, byte ->
            (acc shl 8) or (byte.toInt() and 0xff)
          } ?: return null
      val salt = findTlv(data, 0x84)?.takeIf { it.isNotEmpty() } ?: return null
      return KdfParameters(digestAlgorithm, iterations, salt)
    }

    /**
     * Derives what a KDF card wants to be given in place of the PIN: the OpenPGP
     * iterated-and-salted S2K (RFC 4880 sec. 3.7.1.3) of `salt ‖ pin`, hashed until
     * [KdfParameters.iterations] octets have gone through the digest — and at least once in its
     * entirety, however small that count is. This matches libgcrypt's `openpgp_s2k`, which is what
     * gpg puts on the wire for the same card.
     */
    internal fun deriveKdfPin(pinBytes: ByteArray, kdf: KdfParameters): ByteArray {
      val digest = MessageDigest.getInstance(kdf.digestAlgorithm)
      val input = kdf.salt + pinBytes
      try {
        var remaining = maxOf(kdf.iterations, input.size)
        while (remaining > 0) {
          val chunk = minOf(remaining, input.size)
          digest.update(input, 0, chunk)
          remaining -= chunk
        }
        return digest.digest()
      } finally {
        input.fill(0)
      }
    }

    private fun parseFingerprints(value: ByteArray): List<ByteArray> =
      value
        .asSequence()
        .chunked(20)
        .map { it.toByteArray() }
        .filter { fingerprint -> fingerprint.any { it != 0.toByte() } }
        .toList()

    /**
     * Finds the value of [expectedTag] in a card's BER-TLV answer, or null if it is not in there.
     *
     * Everything this walks came off the card, and a card is not obliged to make sense — a tag that
     * runs off the end of the answer, a length field claiming more than there is, a length so large
     * it wraps. So each step is asked whether it fits before it is taken, and anything that does
     * not simply ends the search: there is no reading a malformed answer more carefully.
     */
    private fun findTlv(data: ByteArray, expectedTag: Int): ByteArray? {
      var offset = 0
      while (offset < data.size) {
        val (tag, tagEnd) = readTag(data, offset) ?: return null
        val (length, valueOffset) = readLength(data, tagEnd) ?: return null
        // Written as a subtraction so that a huge length cannot overflow its way past the check.
        if (length > data.size - valueOffset) return null
        val valueEnd = valueOffset + length
        val value = data.copyOfRange(valueOffset, valueEnd)
        if (tag == expectedTag) return value
        if (tag == 0x6E || tag == 0x73)
          findTlv(value, expectedTag)?.let {
            return it
          }
        // A zero-length value at a one-byte tag still moves; nothing here can stand still.
        offset = if (valueEnd > offset) valueEnd else return null
      }
      return null
    }

    /** The tag at [offset] and where it ends, or null if it runs off the end of [data]. */
    private fun readTag(data: ByteArray, offset: Int): Pair<Int, Int>? {
      var cursor = offset
      if (cursor >= data.size) return null
      var tag = data[cursor++].toInt() and 0xff
      if (tag and 0x1f == 0x1f) {
        var next: Int
        do {
          if (cursor >= data.size) return null
          next = data[cursor++].toInt() and 0xff
          // Four bytes is every tag BER can express that fits in an Int, and far more than any tag
          // an OpenPGP card uses.
          if (cursor - offset > MAX_TAG_BYTES) return null
          tag = (tag shl 8) or next
        } while (next and 0x80 == 0x80)
      }
      return tag to cursor
    }

    /** The length at [offset] and where the value starts, or null if it cannot be read as one. */
    private fun readLength(data: ByteArray, offset: Int): Pair<Int, Int>? {
      var cursor = offset
      if (cursor >= data.size) return null
      val first = data[cursor++].toInt() and 0xff
      if (first and 0x80 == 0) return first to cursor
      val count = first and 0x7f
      // A length needing more than four bytes is longer than an Int holds, and nothing a card has
      // to say is that long. The indefinite form (count 0) has no place in a card's answer either.
      if (count == 0 || count > MAX_LENGTH_BYTES || cursor + count > data.size) return null
      var length = 0
      repeat(count) { length = (length shl 8) or (data[cursor++].toInt() and 0xff) }
      // Four bytes with the top bit set wraps to a negative Int, which is no length at all.
      if (length < 0) return null
      return length to cursor
    }

    private const val MAX_TAG_BYTES = 4
    private const val MAX_LENGTH_BYTES = 4

    fun isTransceiveFailure(error: Throwable?): Boolean {
      var cause = error
      while (cause != null) {
        if (cause is IOException && cause.message?.contains("Transceive failed") == true) {
          return true
        }
        cause = cause.cause
      }
      return false
    }
  }
}

/**
 * The three things this app asks a card's private keys to do, each with the User Interaction Flag
 * that says whether the card wants to be touched before it does that one.
 */
enum class CardOperation(internal val uifTag: Int) {
  /** PSO:CDS, which signs a commit. */
  SIGN(0xD6),
  /** PSO:DEC, which opens an entry. */
  DECRYPT(0xD7),
  /** INTERNAL AUTHENTICATE, which answers an SSH challenge. */
  AUTHENTICATE(0xD8),
}

open class OpenPgpCardStatusException(val sw1: Int, val sw2: Int) :
  IOException(
    "OpenPGP card returned ${sw1.toString(16).padStart(2, '0')} " +
      sw2.toString(16).padStart(2, '0')
  ) {

  val isAuthenticationFailure: Boolean
    // 69 82: security status not satisfied, 69 83: authentication method blocked,
    // 63 Cx: verification failed with x retries remaining.
    get() = sw1 == 0x69 && (sw2 == 0x82 || sw2 == 0x83) || sw1 == 0x63 && sw2 in 0xC0..0xCF

  /**
   * Whether the card rejected the command *data field* itself — `67 xx` (wrong length) or `6A 80`
   * (incorrect parameters in the data field). For a PIN VERIFY, whose data field is only the PIN,
   * this means the PIN did not fit the card's PW length bounds (too long or too short).
   */
  val isDataFieldRejection: Boolean
    get() = sw1 == 0x67 || (sw1 == 0x6A && sw2 == 0x80)

  /**
   * Number of PIN attempts the card reports as still remaining, or `null` when the status word does
   * not carry that information. `63 Cx` encodes the remaining tries in its low nibble; `69 83`
   * (authentication method blocked) means none are left.
   */
  val retriesRemaining: Int?
    get() =
      when {
        sw1 == 0x63 && sw2 in 0xC0..0xCF -> sw2 and 0x0F
        sw1 == 0x69 && sw2 == 0x83 -> 0
        else -> null
      }
}

/**
 * A status word the card answered a PIN VERIFY with — the one command whose rejection is about the
 * PIN. Status words are not unique to a command: `69 82` means "wrong PIN" from a VERIFY but
 * "security status not satisfied" from the PSO/INTERNAL AUTHENTICATE that follows one, and a card
 * that has just accepted a PIN can still answer the next command that way. Typing the rejection at
 * the point it is raised is what lets callers tell the two apart, rather than reading a status word
 * out of an exception chain that no longer says which command produced it.
 */
open class SmartcardPinVerificationException(sw1: Int, sw2: Int) :
  OpenPgpCardStatusException(sw1, sw2)

/**
 * A PIN VERIFY the card rejected because the PIN did not fit its configured PW length bounds (the
 * OpenPGP Card spec defines a per-card min of 6 and a max in the PW Status Bytes). Some cards (e.g.
 * YubiKey) answer an over-long PIN with `6A 80` rather than a normal `63 Cx` wrong-PIN status. This
 * rejection does **not** decrement the retry counter, so it is recoverable: the user can simply
 * re-enter a PIN of acceptable length.
 */
class SmartcardPinFormatException(sw1: Int, sw2: Int) : SmartcardPinVerificationException(sw1, sw2)

/**
 * A PIN too long to fit in the command that would have carried it, turned down here rather than on
 * the card.
 *
 * Recoverable in exactly the way [SmartcardPinFormatException] is — the card never saw it, so no
 * attempt was spent and the user can simply enter something shorter — but it carries no status
 * word, because there was no answer: nothing was ever sent.
 */
class PinTooLongForCardException(maxBytes: Int) :
  IOException("The PIN is longer than the $maxBytes bytes a card can be handed")

data class OpenPgpCardInfo(val fingerprints: List<ByteArray>, val url: String?)

/**
 * What a card's KDF-DO (`00F9`) says about turning a PIN into the value the card wants to be given
 * in its place. [salt] is PW1's, and [iterations] is a count of octets to push through the digest,
 * not a number of passes.
 */
internal class KdfParameters(
  val digestAlgorithm: String,
  val iterations: Int,
  val salt: ByteArray,
)
