/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.passwordstore.data.crypto

import app.passwordstore.crypto.PGPEncryptOptions
import app.passwordstore.crypto.PGPKeyManager
import app.passwordstore.crypto.PGPainlessCryptoHandler
import app.passwordstore.util.coroutines.DispatcherProvider
import app.passwordstore.util.crypto.CardConnection
import app.passwordstore.util.crypto.CardTransport
import app.passwordstore.util.crypto.OpenPgpCard
import app.passwordstore.util.crypto.OpenPgpSmartcardDecryptor
import app.passwordstore.util.crypto.OpenPgpSmartcardStore
import com.github.ivanshafran.sharedpreferencesmock.SPMockBuilder
import com.github.michaelbull.result.getOrThrow
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/**
 * How many of a card's PIN retries one decryption can cost.
 *
 * An entry can name several keys whose secrets are on cards — a card and its backup, or every key
 * in the store when the entry hides its recipients — and each key tried hands the card the PIN
 * again. A PIN typed wrong used to be sent once per key, so one slip could cost two or three of the
 * card's three retries.
 */
class SmartcardDecryptionPinTest {

  @get:Rule val keyDir = TemporaryFolder()

  /** Counts PIN VERIFYs, and answers them, and anything asked to decrypt, as the card says. */
  private class CountingTransport(
    private val decipherAnswer: () -> ByteArray = { byteArrayOf(0x90.toByte(), 0x00) },
    private val verifyAnswer: () -> ByteArray,
  ) : CardTransport {
    var pinsSent = 0

    override val maxTransceiveLength = 261
    override val connection = CardConnection.USB
    override val isConnected = true

    override fun transceive(command: ByteArray, timeoutMs: Int): ByteArray {
      // A VERIFY that carries a PIN, as opposed to the four-byte one that only asks about it.
      if (command[1] == 0x20.toByte() && command.size > 4) {
        pinsSent++
        return verifyAnswer()
      }
      if (command[1] == 0x2A.toByte() && command[2] == 0x80.toByte()) return decipherAnswer()
      return byteArrayOf(0x90.toByte(), 0x00)
    }

    override fun close() = Unit
  }

  private fun repositoryWithTwoKeys(): Pair<CryptoRepository, ByteArray> {
    val keyManager = PGPKeyManager(keyDir.root.absolutePath)
    val keys =
      listOf("Alice <alice@example.com>", "Alice (backup) <alice@example.com>").map {
        keyManager.generateKey(it, null).getOrThrow()
      }
    val ciphertext = ByteArrayOutputStream()
    PGPainlessCryptoHandler()
      .encrypt(
        keys,
        null,
        "secret".byteInputStream(),
        ciphertext,
        PGPEncryptOptions.Builder().build(),
      )
      .getOrThrow()
    val repository =
      CryptoRepository(
        keyManager,
        PGPainlessCryptoHandler(),
        OpenPgpSmartcardDecryptor(),
        OpenPgpSmartcardStore(SPMockBuilder().createSharedPreferences()),
        object : DispatcherProvider {},
        SPMockBuilder().createSharedPreferences(),
      )
    return repository to ciphertext.toByteArray()
  }

  private fun decrypt(transport: CountingTransport) {
    val (repository, ciphertext) = repositoryWithTwoKeys()
    repository.decryptWithSmartcard(
      "123456".toCharArray(),
      repository.allKeyIds(),
      ByteArrayInputStream(ciphertext),
      ByteArrayOutputStream(),
      OpenPgpCard(transport),
    )
  }

  @Test
  fun `a wrong PIN is sent to the card once, however many keys there are to try`() {
    // 63 C2: wrong, two tries left.
    val transport = CountingTransport { byteArrayOf(0x63, 0xC2.toByte()) }
    decrypt(transport)
    assertEquals(1, transport.pinsSent)
  }

  @Test
  fun `a card that stops answering is not spoken to again for the next key`() {
    val transport = CountingTransport { throw IOException("Transceive failed") }
    decrypt(transport)
    assertEquals(1, transport.pinsSent)
  }

  @Test
  fun `a key that fails without the card refusing anything lets the next one be tried`() {
    // The card takes the PIN and answers everything with an empty success, which is no shared
    // secret: the first key fails over what the card gave back rather than over the card refusing
    // anything, so the second key must still be reached rather than given up on at the first.
    val transport = CountingTransport { byteArrayOf(0x90.toByte(), 0x00) }
    decrypt(transport)
    assertEquals(2, transport.pinsSent)
  }

  @Test
  fun `a card that refuses one key's ciphertext still has the next key put to it`() {
    // The PIN is right, but the first key tried is not the card's, and the card turns down what was
    // written to it — 6A 80, wrong data. That is about the key, not the card: the entry's other key
    // may be this card's, as with a card and its backup, so it must still be tried.
    val transport =
      CountingTransport(decipherAnswer = { byteArrayOf(0x6A, 0x80.toByte()) }) {
        byteArrayOf(0x90.toByte(), 0x00)
      }
    decrypt(transport)
    assertEquals(2, transport.pinsSent)
  }
}
