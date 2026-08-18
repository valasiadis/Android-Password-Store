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
import app.passwordstore.R
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
import logcat.LogPriority.WARN
import logcat.asLog
import logcat.logcat

/** Carries APDUs over NFC, to a card held against the back of the phone. */
class IsoDepTransport(private val isoDep: IsoDep) : CardTransport {

  override val connection = CardConnection.NFC

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
    adapter.enableReaderMode(
      activity,
      callback,
      NfcAdapter.FLAG_READER_NFC_A or
        NfcAdapter.FLAG_READER_NFC_B or
        NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
        NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
      Bundle().apply { putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 500) },
    )
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
      runCatching { adapter.disableReaderMode(activity) }
    }
  }

  companion object {
    /** Opens a reader, or returns null when this phone has no NFC or has it switched off. */
    fun create(activity: Activity): NfcCardReader? {
      val adapter = NfcAdapter.getDefaultAdapter(activity) ?: return null
      if (!adapter.isEnabled) return null
      return NfcCardReader(activity, adapter)
    }

    fun disableReaderMode(activity: Activity) {
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

    suspend fun waitForCard(
      activity: Activity,
      disableReaderModeOnError: Boolean = true,
      disableReaderModeOnClose: Boolean = true,
      onCardDetected: () -> Unit = {},
    ): OpenPgpCard = suspendCancellableCoroutine { continuation ->
      val adapter = NfcAdapter.getDefaultAdapter(activity)
      if (adapter == null || !adapter.isEnabled) {
        continuation.resumeWithException(
          IOException(activity.getString(R.string.openpgp_card_reader_unavailable))
        )
        return@suspendCancellableCoroutine
      }
      val completed = AtomicBoolean(false)

      val callback = NfcAdapter.ReaderCallback { tag: Tag ->
        if (!completed.compareAndSet(false, true)) return@ReaderCallback
        try {
          val isoDep =
            IsoDep.get(tag)
              ?: throw IOException(activity.getString(R.string.openpgp_nfc_not_iso_dep))
          activity.runOnUiThread { onCardDetected() }
          isoDep.connect()
          val card =
            OpenPgpCard(IsoDepTransport(isoDep)) {
              if (disableReaderModeOnClose) {
                activity.runOnUiThread { disableReaderMode(activity) }
              }
            }
          card.selectOpenPgpApplet()
          if (continuation.isActive) {
            continuation.resume(card)
          } else {
            card.close()
          }
        } catch (e: Throwable) {
          if (disableReaderModeOnError) {
            activity.runOnUiThread { disableReaderMode(activity) }
          }
          if (continuation.isActive) {
            continuation.resumeWithException(e)
          }
        }
      }

      adapter.enableReaderMode(
        activity,
        callback,
        NfcAdapter.FLAG_READER_NFC_A or
          NfcAdapter.FLAG_READER_NFC_B or
          NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
          NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
        Bundle().apply { putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 500) },
      )
      continuation.invokeOnCancellation {
        if (completed.compareAndSet(false, true)) disableReaderMode(activity)
      }
    }

    suspend fun waitForCardOrNull(
      activity: Activity,
      cancelSignal: Deferred<Unit>,
      disableReaderModeOnError: Boolean = true,
      disableReaderModeOnClose: Boolean = true,
      onCardDetected: () -> Unit = {},
    ): OpenPgpCard? = coroutineScope {
      val wait = async {
        waitForCard(
          activity,
          disableReaderModeOnError,
          disableReaderModeOnClose,
          onCardDetected,
        )
      }
      try {
        select {
          wait.onAwait { it }
          cancelSignal.onAwait {
            wait.cancel()
            disableReaderMode(activity)
            null
          }
        }
      } finally {
        if (!wait.isCompleted) wait.cancel()
      }
    }
  }
}
