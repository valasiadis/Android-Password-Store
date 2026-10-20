/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.passwordstore.util.crypto

import app.passwordstore.crypto.KeyUtils
import app.passwordstore.crypto.PGPEncryptOptions
import app.passwordstore.crypto.PGPKeyManager
import app.passwordstore.crypto.PGPainlessCryptoHandler
import com.github.michaelbull.result.getOrThrow
import java.io.ByteArrayOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags
import org.bouncycastle.openpgp.PGPException

/**
 * What the user is told when the card cannot decrypt a message that is addressed to it.
 *
 * A YubiKey holding Curve25519 keys has an ECDH decryption key, which this app cannot decrypt with
 * on a card yet. That used to come out as "Message is not encrypted to this OpenPGP card" — about a
 * message encrypted to exactly that card, sending the user off to check recipients and fingerprints
 * that were all in order.
 */
class OpenPgpSmartcardDecryptorTest {

  /** A card that accepts whatever it is sent, PIN included, and has nothing to say about itself. */
  private class AgreeableTransport : CardTransport {
    val commands = mutableListOf<ByteArray>()

    override val maxTransceiveLength = 261
    override val connection = CardConnection.USB
    override val isConnected = true

    override fun transceive(command: ByteArray, timeoutMs: Int): ByteArray {
      commands += command.copyOf()
      return byteArrayOf(0x90.toByte(), 0x00)
    }

    override fun close() = Unit
  }

  @Test
  fun `a message for the card's ECDH key says the key type is what is unsupported`() {
    // The app's own kind of key: EdDSA to sign, ECDH to encrypt to — what a YubiKey set up with
    // Curve25519 holds.
    val keyDir = createTempDirectory().toFile()
    val key =
      try {
        PGPKeyManager(keyDir.absolutePath)
          .generateKey("Alice <alice@example.com>", null)
          .getOrThrow()
      } finally {
        keyDir.deleteRecursively()
      }
    val cert = KeyUtils.tryParseCertificateOrKey(key)!!
    val encryptionKey = cert.encryptionKeys.single().pgpPublicKey
    val ciphertext = ByteArrayOutputStream()
    PGPainlessCryptoHandler()
      .encrypt(
        listOf(key),
        null,
        "secret".byteInputStream(),
        ciphertext,
        PGPEncryptOptions.Builder().build(),
      )
      .getOrThrow()
    val transport = AgreeableTransport()

    val error =
      assertFailsWith<PGPException> {
        OpenPgpSmartcardDecryptor()
          .decrypt(
            key,
            "123456".toCharArray(),
            ciphertext.toByteArray().inputStream(),
            ByteArrayOutputStream(),
            OpenPgpCard(transport),
            listOf(encryptionKey.fingerprint),
          )
      }

    assertContains(error.message.orEmpty(), "ECDH")
    assertContains(error.message.orEmpty(), "only supported for RSA")
    // Turned down before anything is sent to decrypt with: the card was asked for its PIN and its
    // KDF settings, and for nothing that would cost a touch.
    assertTrue(transport.commands.none { it[1] == 0x2A.toByte() })
  }

  /**
   * Collects what a PSO: DECIPHER hands the card, across however many chained commands it takes.
   */
  private class DecipherTransport : CardTransport {
    var deciphered = byteArrayOf()

    override val maxTransceiveLength = 261
    override val connection = CardConnection.USB
    override val isConnected = true

    override fun transceive(command: ByteArray, timeoutMs: Int): ByteArray {
      val ok = byteArrayOf(0x90.toByte(), 0x00)
      if (command[1] == 0x2A.toByte() && command[2] == 0x80.toByte()) {
        val length = command[4].toInt() and 0xff
        deciphered += command.copyOfRange(5, 5 + length)
        // The last command of a chain is the one without the chaining bit, and is answered.
        if (command[0].toInt() and 0x10 == 0) return byteArrayOf(0x02, 0x01) + ok
      }
      return ok
    }

    override fun close() = Unit
  }

  /** The encrypted session key as OpenPGP writes it: a bit count, then the number itself. */
  private fun mpi(value: ByteArray): ByteArray {
    val bits = value.size * 8 - Integer.numberOfLeadingZeros(value[0].toInt() and 0xff) + 24
    return byteArrayOf((bits shr 8).toByte(), bits.toByte()) + value
  }

  @Test
  fun `a session key that starts with a zero byte reaches the card at the key's full length`() {
    val transport = DecipherTransport()
    // 255 bytes: a 2048-bit value whose top byte happened to be zero, written without it.
    val value = ByteArray(255) { 0x5A }
    OpenPgpSmartcardDecryptor.OpenPgpCardDecryptorFactory(OpenPgpCard(transport), 2048)
      .recoverSessionData(PublicKeyAlgorithmTags.RSA_GENERAL, arrayOf(mpi(value)), 3)
    // The padding indicator byte first, then the value padded back to 256 bytes.
    assertContentEquals(byteArrayOf(0x00, 0x00) + value, transport.deciphered)
  }

  @Test
  fun `a session key already as long as the key is sent unchanged`() {
    val transport = DecipherTransport()
    val value = ByteArray(256) { 0x5A }
    OpenPgpSmartcardDecryptor.OpenPgpCardDecryptorFactory(OpenPgpCard(transport), 2048)
      .recoverSessionData(PublicKeyAlgorithmTags.RSA_GENERAL, arrayOf(mpi(value)), 3)
    assertContentEquals(byteArrayOf(0x00) + value, transport.deciphered)
  }
}
