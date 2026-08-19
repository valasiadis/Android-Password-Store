/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.util.crypto

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import com.github.michaelbull.result.runCatching
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import logcat.logcat

/**
 * Carries APDUs over USB to a card plugged into the phone, speaking the USB CCID class protocol
 * (`Universal Serial Bus Device Class: Smart Card CCID`, rev 1.1).
 *
 * NFC hands the card an APDU and gives back its answer; USB does not. Between the two sits a reader
 * — on a token like a YubiKey or a Nitrokey it is a reader welded to a card inside one package, but
 * it is a reader all the same — and everything it is told arrives wrapped in a CCID message: a
 * ten-byte header saying what kind of message this is, how long it is, which slot it is for and
 * which command it answers, then the APDU itself. This turns one into the other, and nothing above
 * it knows the difference.
 *
 * Only readers that do their own T=1 framing are spoken to (see [CcidExchangeLevel]), which is
 * every token that plugs straight into the phone. A reader that wants to be handed T=1 blocks is
 * refused by name rather than fed short APDUs it will not understand.
 */
class CcidTransport
private constructor(
  private val deviceConnection: UsbDeviceConnection,
  private val usbInterface: UsbInterface,
  private val bulkIn: UsbEndpoint,
  private val bulkOut: UsbEndpoint,
  private val maxMessageLength: Int,
  private val onClose: () -> Unit,
) : CardTransport {

  override val connection = CardConnection.USB

  // A plugged-in card does not wander off mid-operation; when the cable does go, the transfer
  // itself fails at once rather than waiting out a timeout.
  override val isConnected = true

  /**
   * How much of an APDU fits in one message. Bounded by the short-APDU maximum as well as by the
   * reader, since a reader that offers more than that is still being handed short APDUs.
   */
  override val maxTransceiveLength: Int
    get() = (maxMessageLength - CCID_HEADER_LENGTH).coerceIn(MIN_APDU_LENGTH, MAX_SHORT_APDU_LENGTH)

  /**
   * Wraps around at a byte, which is all the field is; only equality with the request matters.
   *
   * Atomic because [close] can be called from another thread while an exchange is in flight — that
   * is how a cancelled operation ends one — and a sequence number two threads both think they own
   * is a sequence number that cannot tell a stale answer from the real one.
   */
  private val sequence = AtomicInteger(0)

  /**
   * Whether a thread is currently mid-exchange on these endpoints.
   *
   * [close] consults it: an orderly power-down is one more conversation with the reader, and
   * starting one while another thread is halfway through its own means two writers on one endpoint
   * and two readers on the other, each liable to take the other's answer.
   */
  private val exchanging = AtomicBoolean(false)

  override fun transceive(command: ByteArray, timeoutMs: Int): ByteArray =
    exchange(MESSAGE_XFR_BLOCK, command, parameter = 0, timeoutMs = timeoutMs).data

  /**
   * Powers up the card in the reader's slot and returns its ATR.
   *
   * The voltages are tried in the order the spec puts them: whatever the reader picks for itself
   * first, then each one that can be asked for by name, since a reader that cannot choose answers
   * the automatic request with a plain refusal rather than with a card.
   */
  private fun powerOn(): ByteArray {
    var lastFailure: IOException? = null
    for (voltage in POWER_SELECTIONS) {
      try {
        return exchange(
            MESSAGE_ICC_POWER_ON,
            byteArrayOf(),
            parameter = voltage,
            timeoutMs = POWER_ON_TIMEOUT_MS,
          )
          .data
      } catch (e: IOException) {
        lastFailure = e
      }
    }
    throw lastFailure ?: IOException("The card reader would not power up the card")
  }

  /**
   * Sends one CCID message and waits for the answer to that message.
   *
   * Two things can come back that are not the answer: a stale response to a command that timed out
   * earlier, told apart by the sequence number, and the reader saying the card has asked for more
   * time — which is what a card does while it is busy with a 4096-bit RSA operation, and which is
   * not a failure. Both mean read again. The caller's [timeoutMs] bounds the whole wait, however
   * many times the card asks.
   */
  private fun exchange(
    messageType: Int,
    payload: ByteArray,
    parameter: Int,
    timeoutMs: Int,
  ): CcidResponse {
    val expectedSequence = sequence.incrementAndGet() and 0xff
    val deadline = System.currentTimeMillis() + timeoutMs
    exchanging.set(true)
    try {
      write(
        ccidMessage(messageType, expectedSequence, payload, parameter),
        remainingUntil(deadline),
      )
      while (true) {
        val response = read(deadline)
        if (response.sequence != expectedSequence) {
          // An answer to something we have already given up on; the one we are waiting for follows.
          logcat {
            "Ignoring stale CCID response ${response.sequence} (waiting for $expectedSequence)"
          }
          continue
        }
        if (response.isTimeExtensionRequest) continue
        if (response.failed) {
          throw IOException(
            "The card reader refused the command (status %02x, error %02x)"
              .format(
                response.status,
                response.error,
              )
          )
        }
        return response
      }
    } finally {
      exchanging.set(false)
    }
  }

  /**
   * What is left of the caller's patience, as a timeout a bulk transfer will accept.
   *
   * Every transfer in one exchange is bounded by the exchange's own deadline rather than each being
   * given the whole of it afresh. A reader that answers with a packet a moment before the deadline,
   * over and over, would otherwise keep a thread here for as long as it cared to.
   */
  private fun remainingUntil(deadline: Long): Int {
    val remaining = deadline - System.currentTimeMillis()
    if (remaining <= 0) throw IOException("The card reader did not answer in time")
    return remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
  }

  private fun write(message: ByteArray, timeoutMs: Int) {
    val written = deviceConnection.bulkTransfer(bulkOut, message, message.size, timeoutMs)
    if (written != message.size) {
      throw IOException("Could not send the command to the card reader")
    }
  }

  private fun read(deadline: Long): CcidResponse {
    val buffer = ByteArray(maxMessageLength.coerceIn(CCID_HEADER_LENGTH + 2, MAX_MESSAGE_LENGTH))
    var filled = readInto(buffer, 0, buffer.size, deadline)
    if (filled < CCID_HEADER_LENGTH) throw IOException("Truncated answer from the card reader")
    val declared = readLittleEndianInt(buffer, 1)
    if (declared < 0 || declared > buffer.size - CCID_HEADER_LENGTH) {
      throw IOException("The card reader announced an answer of $declared bytes")
    }
    val total = CCID_HEADER_LENGTH + declared
    // A long answer arrives in as many bulk packets as it takes; the header said how many bytes to
    // expect, so keep reading until they are all here — or until the deadline says the rest is not
    // coming. A reader that answers with nothing at all, which a zero-length packet is, would
    // otherwise be waited on for ever.
    while (filled < total) {
      filled += readInto(buffer, filled, total - filled, deadline)
    }
    return parseCcidResponse(buffer, total)
  }

  private fun readInto(buffer: ByteArray, offset: Int, length: Int, deadline: Long): Int {
    val read =
      deviceConnection.bulkTransfer(bulkIn, buffer, offset, length, remainingUntil(deadline))
    if (read < 0) throw IOException("The card reader stopped answering")
    return read
  }

  override fun close() {
    // The card is powered down rather than merely let go of, so that a PIN verified for this
    // operation does not stay verified on a card that goes on being powered by the phone. Over NFC
    // this is what lifting the card off does, and there the user cannot forget to do it.
    //
    // Only when these endpoints are quiet, though. Closing is also how a cancelled operation is
    // stopped, and then another thread is sitting inside a transfer of its own — waiting on a card
    // that is waiting for a finger. Talking over it would have the two of them taking each other's
    // packets, and the power-down waiting out its own timeout for an answer already read by
    // somebody else. Dropping the connection is what ends that wait, and a card left powered is a
    // card the user is about to unplug anyway.
    if (!exchanging.get()) {
      runCatching { exchange(MESSAGE_ICC_POWER_OFF, byteArrayOf(), 0, POWER_OFF_TIMEOUT_MS) }
    }
    runCatching { deviceConnection.releaseInterface(usbInterface) }
    runCatching { deviceConnection.close() }
    onClose()
  }

  companion object {
    /** Every CCID message opens with the same ten bytes. */
    private const val CCID_HEADER_LENGTH = 10
    private const val MESSAGE_ICC_POWER_ON = 0x62
    private const val MESSAGE_ICC_POWER_OFF = 0x63
    private const val MESSAGE_XFR_BLOCK = 0x6F

    /** Automatic first, then 5.0V, 3.0V and 1.8V by name. */
    private val POWER_SELECTIONS = intArrayOf(0x00, 0x01, 0x02, 0x03)

    /** CLA INS P1 P2 Lc, up to 255 bytes of data, Le. */
    private const val MAX_SHORT_APDU_LENGTH = 261
    private const val MIN_APDU_LENGTH = 5
    private const val MAX_MESSAGE_LENGTH = 1 shl 16
    private const val POWER_ON_TIMEOUT_MS = 5_000
    private const val POWER_OFF_TIMEOUT_MS = 1_000

    /**
     * Opens the CCID interface of [device] and powers up the card in it.
     *
     * [deviceConnection] is taken over: it is closed by [close], and also here if the card cannot
     * be brought up, so the caller never has to unpick a half-open connection.
     */
    fun open(
      device: UsbDevice,
      deviceConnection: UsbDeviceConnection,
      onClose: () -> Unit = {},
    ): CcidTransport {
      try {
        val descriptor =
          parseCcidInterfaces(deviceConnection.rawDescriptors ?: byteArrayOf()).firstOrNull()
            ?: throw IOException("This USB device has no smartcard reader interface")
        if (!descriptor.exchangeLevel.carriesWholeApdus) {
          throw UnsupportedCardReaderException(
            "This reader exchanges ${descriptor.exchangeLevel.description} rather than whole APDUs"
          )
        }
        val usbInterface =
          (0 until device.interfaceCount).map(device::getInterface).firstOrNull {
            it.id == descriptor.interfaceNumber && it.interfaceClass == UsbConstants.USB_CLASS_CSCID
          } ?: throw IOException("The smartcard reader interface went missing")
        val endpoints = (0 until usbInterface.endpointCount).map(usbInterface::getEndpoint)
        val bulkIn =
          endpoints.firstOrNull {
            it.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
              it.direction == UsbConstants.USB_DIR_IN
          } ?: throw IOException("The smartcard reader has no bulk-in endpoint")
        val bulkOut =
          endpoints.firstOrNull {
            it.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
              it.direction == UsbConstants.USB_DIR_OUT
          } ?: throw IOException("The smartcard reader has no bulk-out endpoint")
        if (!deviceConnection.claimInterface(usbInterface, true)) {
          throw IOException("Another app is using this smartcard reader")
        }
        val transport =
          CcidTransport(
            deviceConnection,
            usbInterface,
            bulkIn,
            bulkOut,
            descriptor.maxMessageLength,
            onClose,
          )
        val atr = transport.powerOn()
        logcat { "Powered up a USB card, ATR ${atr.joinToString("") { "%02x".format(it) }}" }
        return transport
      } catch (e: Throwable) {
        runCatching { deviceConnection.close() }
        throw e
      }
    }
  }
}

/**
 * Raised when a reader speaks CCID but not in a dialect this app knows how to hold up its end of.
 */
class UnsupportedCardReaderException(message: String) : IOException(message)

/**
 * How much of the T=1 conversation with the card a reader handles by itself.
 *
 * Only the two APDU levels are any use here: they take a whole command APDU and give back a whole
 * response, which is exactly what [OpenPgpCard] builds and reads. Below them the reader expects to
 * be handed T=1 blocks, or individual characters, and framing those is a protocol of its own that
 * no token plugged straight into a phone needs.
 */
internal enum class CcidExchangeLevel(val description: String, val carriesWholeApdus: Boolean) {
  CHARACTER("single characters", false),
  TPDU("T=1 blocks", false),
  SHORT_APDU("short APDUs", true),
  EXTENDED_APDU("extended APDUs", true),
}

/** What the CCID class descriptor of one interface says about it. */
internal class CcidInterfaceDescriptor(
  val interfaceNumber: Int,
  features: Int,
  val maxMessageLength: Int,
) {
  val exchangeLevel: CcidExchangeLevel =
    when {
      features and 0x00040000 != 0 -> CcidExchangeLevel.EXTENDED_APDU
      features and 0x00020000 != 0 -> CcidExchangeLevel.SHORT_APDU
      features and 0x00010000 != 0 -> CcidExchangeLevel.TPDU
      else -> CcidExchangeLevel.CHARACTER
    }
}

/** One answer from the reader, with the header read off it. */
internal class CcidResponse(
  val messageType: Int,
  val sequence: Int,
  val status: Int,
  val error: Int,
  val data: ByteArray,
) {
  /** The card needs longer than the reader's own waiting time; the real answer is still coming. */
  val isTimeExtensionRequest: Boolean
    get() = (status shr 6) and 0x03 == 0x02

  val failed: Boolean
    get() = (status shr 6) and 0x03 == 0x01
}

/**
 * Builds one CCID message: the ten-byte header — kind, length, slot, sequence number, then three
 * bytes whose meaning depends on the kind — followed by [payload].
 *
 * [parameter] is the first of those three: the voltage to power the card at for an ICC power-on,
 * and the block waiting time extension for a transfer, where zero asks for the reader's default.
 * The two bytes after it are the level parameter, which is zero for a whole short APDU.
 */
internal fun ccidMessage(
  messageType: Int,
  sequence: Int,
  payload: ByteArray,
  parameter: Int,
): ByteArray {
  val message = ByteArray(10 + payload.size)
  message[0] = messageType.toByte()
  message[1] = payload.size.toByte()
  message[2] = (payload.size shr 8).toByte()
  message[3] = (payload.size shr 16).toByte()
  message[4] = (payload.size shr 24).toByte()
  message[5] = 0 // Slot 0: the only slot a token has, and the first of any reader.
  message[6] = sequence.toByte()
  message[7] = parameter.toByte()
  payload.copyInto(message, 10)
  return message
}

/** Reads the header off an answer of [length] bytes sitting at the front of [buffer]. */
internal fun parseCcidResponse(buffer: ByteArray, length: Int): CcidResponse =
  CcidResponse(
    messageType = buffer[0].toInt() and 0xff,
    sequence = buffer[6].toInt() and 0xff,
    status = buffer[7].toInt() and 0xff,
    error = buffer[8].toInt() and 0xff,
    data = buffer.copyOfRange(10, length),
  )

/**
 * Finds the smartcard reader interfaces of a USB device in its raw descriptors.
 *
 * Android's [UsbInterface] says an interface is class 0x0B but not what the class descriptor that
 * follows it holds, and that descriptor is where a reader says whether it frames T=1 itself and how
 * long a message it will take. Both only exist in the raw bytes, so they are walked here: every
 * descriptor announces its own length, so the list is stepped through by it, and a class descriptor
 * belongs to the interface most recently declared before it.
 */
internal fun parseCcidInterfaces(raw: ByteArray): List<CcidInterfaceDescriptor> {
  val interfaces = mutableListOf<CcidInterfaceDescriptor>()
  var offset = 0
  var currentInterface: Int? = null
  while (offset + 2 <= raw.size) {
    val length = raw[offset].toInt() and 0xff
    val type = raw[offset + 1].toInt() and 0xff
    // A descriptor shorter than its own header, or running past the end, means the rest is not
    // worth guessing at.
    if (length < 2 || offset + length > raw.size) break
    when {
      type == DESCRIPTOR_TYPE_INTERFACE && length >= 9 -> {
        val interfaceClass = raw[offset + 5].toInt() and 0xff
        currentInterface =
          if (interfaceClass == UsbConstants.USB_CLASS_CSCID) raw[offset + 2].toInt() and 0xff
          else null
      }
      type == DESCRIPTOR_TYPE_CCID && length >= 54 -> {
        currentInterface?.let { interfaceNumber ->
          interfaces +=
            CcidInterfaceDescriptor(
              interfaceNumber = interfaceNumber,
              features = readLittleEndianInt(raw, offset + 40),
              maxMessageLength = readLittleEndianInt(raw, offset + 44),
            )
        }
      }
    }
    offset += length
  }
  return interfaces
}

private const val DESCRIPTOR_TYPE_INTERFACE = 0x04
private const val DESCRIPTOR_TYPE_CCID = 0x21

internal fun readLittleEndianInt(data: ByteArray, offset: Int): Int =
  (data[offset].toInt() and 0xff) or
    ((data[offset + 1].toInt() and 0xff) shl 8) or
    ((data[offset + 2].toInt() and 0xff) shl 16) or
    ((data[offset + 3].toInt() and 0xff) shl 24)
