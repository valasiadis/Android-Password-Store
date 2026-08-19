/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.util.crypto

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import com.github.michaelbull.result.getOr
import com.github.michaelbull.result.runCatching
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.channels.Channel
import logcat.LogPriority.WARN
import logcat.asLog
import logcat.logcat

/** Carries APDUs over NFC, to a card held against the back of the phone. */
class IsoDepTransport(private val isoDep: IsoDep) : CardTransport {

  override val connection = CardConnection.NFC

  override val maxTransceiveLength: Int
    get() = isoDep.maxTransceiveLength

  // Kept up to date by the platform's own presence check, which is why that check being frequent
  // matters as much as it does.
  override val isConnected: Boolean
    get() = runCatching { isoDep.isConnected }.getOr(false)

  override fun transceive(command: ByteArray, timeoutMs: Int): ByteArray {
    isoDep.timeout = timeoutMs
    return isoDep.transceive(command)
  }

  override fun close() {
    isoDep.close()
  }
}

/**
 * Watches for a card held against the phone, keeping NFC reader mode enabled for the whole of one
 * card operation (such as commit signing with PIN retries), so the platform never falls back to
 * dispatching the card's NDEF URL between two taps. Enable reader mode once via [create], await
 * successive presentations with [awaitCard], and [close] it exactly once when finished.
 */
class NfcCardReader
private constructor(private val activity: Activity, private val adapter: NfcAdapter) : CardReader {

  private val tags = Channel<Tag>(Channel.UNLIMITED)
  private val closed = AtomicBoolean(false)
  private val callback = NfcAdapter.ReaderCallback { tag -> tags.trySend(tag) }

  override val connections = setOf(CardConnection.NFC)

  init {
    // Reader mode is switched off and on again rather than simply on, which restarts the platform's
    // discovery loop. A card already lying on the phone when an operation begins is then found
    // straight away instead of having to be lifted and presented again.
    runCatching { adapter.disableReaderMode(activity) }
    adapter.enableReaderMode(
      activity,
      callback,
      NfcAdapter.FLAG_READER_NFC_A or
        NfcAdapter.FLAG_READER_NFC_B or
        NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
        NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
      Bundle().apply {
        putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, PRESENCE_CHECK_DELAY_MS)
      },
    )
    active.set(this)
  }

  /**
   * Suspends until an OpenPGP card is presented and its applet selected, transparently skipping
   * past transient tag glitches (a card lifted mid-connect, a stale buffered tag, a non-ISO-DEP
   * tag). Throws [OpenPgpCardStatusException] only when the card actively rejects the applet
   * selection.
   */
  override suspend fun awaitCard(onCardDetected: (CardConnection) -> Unit): OpenPgpCard {
    while (true) {
      val tag = tags.receive()
      val isoDep = IsoDep.get(tag) ?: continue
      try {
        isoDep.connect()
        onCardDetected(CardConnection.NFC)
        val card = OpenPgpCard(IsoDepTransport(isoDep))
        card.selectOpenPgpApplet()
        return card
      } catch (e: OpenPgpCardStatusException) {
        runCatching { isoDep.close() }
        throw e
      } catch (e: Throwable) {
        // Tag lost or a transient transport error: wait for the next presentation.
        runCatching { isoDep.close() }
      }
    }
  }

  override fun close() {
    if (closed.compareAndSet(false, true)) {
      tags.close()
      // Only the reader that is still the live one switches reader mode off. An operation's watch
      // for the card being lifted outlives the operation, and can finish after the next one has
      // already opened its own reader — at which point switching off is switching off somebody
      // else's, and the next card presented is never seen at all.
      if (active.compareAndSet(this, null)) runCatching { adapter.disableReaderMode(activity) }
    }
  }

  companion object {
    /** Opens a reader, or returns null when this phone has no NFC or has it switched off. */
    fun create(activity: Activity): NfcCardReader? {
      val adapter = NfcAdapter.getDefaultAdapter(activity) ?: return null
      if (!adapter.isEnabled) return null
      return NfcCardReader(activity, adapter)
    }

    /**
     * How long the platform waits before checking whether the card it is talking to is still there.
     * It is also how long it takes to notice that one has gone — and until it has noticed, it will
     * not report a new one, so this is what decides how quickly a card is picked up on being
     * presented, or lifted mid-operation. The stock value is long enough to feel like the reader is
     * asleep; this is what the Yubico Authenticator uses.
     */
    private const val PRESENCE_CHECK_DELAY_MS = 50

    /**
     * The reader whose mode is currently enabled. Reader mode belongs to the activity rather than
     * to whoever asked for it, so switching it off is not something a reader may do on the strength
     * of being finished — only on being the one still holding it.
     */
    private val active = AtomicReference<NfcCardReader?>(null)

    fun disableReaderMode(activity: Activity) {
      active.set(null)
      val adapter = NfcAdapter.getDefaultAdapter(activity) ?: return
      try {
        adapter.disableReaderMode(activity)
      } catch (e: IllegalStateException) {
        // NfcAdapter.disableReaderMode throws "activity is already destroyed" when it runs as an
        // onDestroy() cleanup (the platform has already torn down the activity's NFC state, so
        // reader mode is gone anyway). Swallow it so finishing the activity never crashes.
        logcat(WARN) { e.asLog() }
      }
    }
  }
}
