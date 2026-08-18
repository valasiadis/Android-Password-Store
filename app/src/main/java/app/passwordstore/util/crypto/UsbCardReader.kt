/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.util.crypto

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import com.github.michaelbull.result.onErr
import com.github.michaelbull.result.runCatching
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.channels.Channel
import logcat.asLog
import logcat.logcat

/**
 * Watches for a card plugged into the phone.
 *
 * Unlike NFC there is no reader mode to hold open, and nothing to stop the platform doing: a
 * plugged-in token is simply there or not. What there is instead is a permission — Android asks the
 * user, once per token per connection, whether this app may talk to it — and a token that turns up
 * after the prompt is already on screen, which is the ordinary way of it: the user reads "plug in
 * your key" and then plugs in their key. So this listens for the token arriving as well as looking
 * for one already there, and asks for permission the first time it sees one.
 */
class UsbCardReader
private constructor(private val context: Context, private val usbManager: UsbManager) : CardReader {

  override val connections = setOf(CardConnection.USB)

  /** Anything that might have changed the answer to "is there a card here?": look again. */
  private val changes = Channel<Unit>(Channel.CONFLATED)
  private val closed = AtomicBoolean(false)

  /**
   * Tokens already asked about. A user who says no is not asked again for the same token while
   * this reader is open — Android would put the same dialog up as many times a second as the loop
   * goes round, and "no" was already an answer.
   */
  private val asked = mutableSetOf<String>()

  private val receiver =
    object : BroadcastReceiver() {
      override fun onReceive(context: Context, intent: Intent) {
        changes.trySend(Unit)
      }
    }

  init {
    ContextCompat.registerReceiver(
      context,
      receiver,
      IntentFilter().apply {
        addAction(ACTION_USB_PERMISSION)
        addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
      },
      // Only the system (for the attach and detach) and this app's own permission reply, which is
      // all that has any business waking this up.
      ContextCompat.RECEIVER_NOT_EXPORTED,
    )
  }

  /**
   * Suspends until a card reader this app is allowed to talk to has a card in it and its OpenPGP
   * applet has been selected.
   *
   * Anything that goes wrong on the way — a token that turns out not to speak OpenPGP, a
   * connection that could not be opened, a permission the user declined — puts this back to
   * waiting, exactly as a card lifted too early does over NFC: the next attach is another go. What
   * does come out is the card refusing the applet outright, and a reader this app cannot hold up
   * its end of the conversation with, since neither improves by trying again.
   */
  override suspend fun awaitCard(onCardDetected: (CardConnection) -> Unit): OpenPgpCard {
    while (true) {
      val device = cardReaderDevice()
      if (device != null && !usbManager.hasPermission(device)) {
        requestPermission(device)
      } else if (device != null) {
        val deviceConnection = usbManager.openDevice(device)
        if (deviceConnection != null) {
          onCardDetected(CardConnection.USB)
          var card: OpenPgpCard? = null
          try {
            card = OpenPgpCard(CcidTransport.open(device, deviceConnection))
            card.selectOpenPgpApplet()
            return card
          } catch (e: UnsupportedCardReaderException) {
            runCatching { card?.close() ?: deviceConnection.close() }
            throw e
          } catch (e: OpenPgpCardStatusException) {
            // The card answered and said no. Presenting it again would get the same answer.
            runCatching { card?.close() ?: deviceConnection.close() }
            throw e
          } catch (e: Throwable) {
            runCatching { card?.close() ?: deviceConnection.close() }
            logcat { "Could not bring up the card in ${device.deviceName}: ${e.asLog()}" }
          }
        }
      }
      changes.receive()
    }
  }

  /** The first attached device offering a smartcard reader interface, if any is attached. */
  private fun cardReaderDevice(): UsbDevice? =
    usbManager.deviceList.values.firstOrNull { device ->
      (0 until device.interfaceCount).any { index ->
        device.getInterface(index).interfaceClass == UsbConstants.USB_CLASS_CSCID
      }
    }

  private fun requestPermission(device: UsbDevice) {
    if (!asked.add(device.deviceName)) return
    val intent =
      PendingIntent.getBroadcast(
        context,
        0,
        Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
        PendingIntent.FLAG_IMMUTABLE,
      )
    runCatching { usbManager.requestPermission(device, intent) }
      .onErr { e -> logcat { "Could not ask about ${device.deviceName}: ${e.asLog()}" } }
  }

  override fun close() {
    if (closed.compareAndSet(false, true)) {
      changes.close()
      runCatching { context.unregisterReceiver(receiver) }
    }
  }

  companion object {
    private const val ACTION_USB_PERMISSION = "app.passwordstore.action.USB_PERMISSION"

    /**
     * Opens a reader, or returns null when this phone cannot host a USB device at all — which is
     * about the phone rather than about what is plugged into it, since the whole point is to be
     * listening when the user plugs something in.
     */
    fun create(context: Context): UsbCardReader? {
      if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)) return null
      val usbManager = ContextCompat.getSystemService(context, UsbManager::class.java) ?: return null
      return UsbCardReader(context.applicationContext, usbManager)
    }
  }
}
