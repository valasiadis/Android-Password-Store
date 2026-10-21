/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.passwordstore.util.crypto

import java.math.BigInteger
import java.security.SecureRandom
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags
import org.bouncycastle.crypto.generators.RSAKeyPairGenerator
import org.bouncycastle.crypto.params.RSAKeyGenerationParameters
import org.bouncycastle.openpgp.PGPException
import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.operator.bc.BcPGPKeyPair

/**
 * What the card is handed to recover an RSA session key with, and what the user is told when its
 * key is of a kind that cannot be.
 */
class OpenPgpSmartcardDecryptorTest {

  @Test
  fun `a card key of a kind that cannot decrypt on a card says so`() {
    val error =
      assertFailsWith<PGPException> {
        OpenPgpSmartcardDecryptor.OpenPgpCardDecryptorFactory(
            OpenPgpCard(DecipherTransport()),
            emptyList(),
          )
          .recoverSessionData(
            PublicKeyAlgorithmTags.ELGAMAL_ENCRYPT,
            arrayOf(byteArrayOf(0x00, 0x08, 0x01)),
            3,
          )
      }
    assertContains(error.message.orEmpty(), "ElGamal")
    assertContains(error.message.orEmpty(), "only supported for RSA, ECDH and X25519")
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
    OpenPgpSmartcardDecryptor.OpenPgpCardDecryptorFactory(OpenPgpCard(transport), listOf(RSA_2048))
      .recoverSessionData(PublicKeyAlgorithmTags.RSA_GENERAL, arrayOf(mpi(value)), 3)
    // The padding indicator byte first, then the value padded back to 256 bytes.
    assertContentEquals(byteArrayOf(0x00, 0x00) + value, transport.deciphered)
  }

  @Test
  fun `a session key already as long as the key is sent unchanged`() {
    val transport = DecipherTransport()
    val value = ByteArray(256) { 0x5A }
    OpenPgpSmartcardDecryptor.OpenPgpCardDecryptorFactory(OpenPgpCard(transport), listOf(RSA_2048))
      .recoverSessionData(PublicKeyAlgorithmTags.RSA_GENERAL, arrayOf(mpi(value)), 3)
    assertContentEquals(byteArrayOf(0x00) + value, transport.deciphered)
  }

  private companion object {
    val RSA_2048: PGPPublicKey by lazy {
      val generator =
        RSAKeyPairGenerator().apply {
          init(RSAKeyGenerationParameters(BigInteger.valueOf(0x10001), SecureRandom(), 2048, 80))
        }
      BcPGPKeyPair(PublicKeyAlgorithmTags.RSA_GENERAL, generator.generateKeyPair(), Date())
        .publicKey
    }
  }
}
