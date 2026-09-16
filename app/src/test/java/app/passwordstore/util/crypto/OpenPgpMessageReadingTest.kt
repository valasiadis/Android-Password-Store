/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.passwordstore.util.crypto

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.SecureRandom
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.bouncycastle.bcpg.AEADAlgorithmTags
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags
import org.bouncycastle.crypto.generators.RSAKeyPairGenerator
import org.bouncycastle.crypto.params.RSAKeyGenerationParameters
import org.bouncycastle.openpgp.PGPEncryptedDataGenerator
import org.bouncycastle.openpgp.PGPEncryptedDataList
import org.bouncycastle.openpgp.PGPKeyPair
import org.bouncycastle.openpgp.PGPLiteralData
import org.bouncycastle.openpgp.PGPLiteralDataGenerator
import org.bouncycastle.openpgp.PGPObjectFactory
import org.bouncycastle.openpgp.PGPPublicKeyEncryptedData
import org.bouncycastle.openpgp.operator.bc.BcPGPDataEncryptorBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPKeyPair
import org.bouncycastle.openpgp.operator.bc.BcPublicKeyDataDecryptorFactory
import org.bouncycastle.openpgp.operator.bc.BcPublicKeyKeyEncryptionMethodGenerator
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator

/**
 * Reading the payload out of a message once its session key has been recovered.
 *
 * The session key comes from a software private key rather than from a card, because the step under
 * test begins where the card's part ends: from the session key onwards nothing depends on which of
 * the two produced it.
 *
 * An AEAD message is the case worth guarding. Its cipher is OCB, which neither Android's cut-down
 * BouncyCastle nor a stock JVM offers through the JCE — so building the content cipher that way
 * failed on the phone with "cannot create cipher: No provider found for AES/OCB/NoPadding", with
 * the card having already done its work, and fails here too.
 */
class OpenPgpMessageReadingTest {

  @Test
  fun `reads a message in an AEAD packet`() {
    assertEquals(String(CONTENT), read(encryptedMessage(aead = true)))
  }

  @Test
  fun `reads a message in a SEIPD packet`() {
    assertEquals(String(CONTENT), read(encryptedMessage(aead = false)))
  }

  /**
   * An AEAD message's last chunk is only answered for once the stream has been read past the end of
   * the literal packet, so a message altered after it was written has to throw rather than hand
   * back the plaintext it did produce.
   */
  @Test
  fun `refuses an AEAD message that was altered after it was written`() {
    assertFailsWith<Exception> { read(altered(encryptedMessage(aead = true))) }
  }

  @Test
  fun `refuses a SEIPD message that was altered after it was written`() {
    assertFailsWith<Exception> { read(altered(encryptedMessage(aead = false))) }
  }

  /** Runs [readPlaintext] over [message], returning what it wrote. */
  private fun read(message: ByteArray): String {
    val packets =
      PGPObjectFactory(ByteArrayInputStream(message), JcaKeyFingerprintCalculator()).nextObject()
        as PGPEncryptedDataList
    val data = packets[0] as PGPPublicKeyEncryptedData
    val sessionKey = data.getSessionKey(BcPublicKeyDataDecryptorFactory(KEY_PAIR.privateKey))
    val plaintext = ByteArrayOutputStream()
    readPlaintext(data, sessionKey, plaintext)
    return plaintext.toString(Charsets.UTF_8.name())
  }

  /** The last byte of a message is inside its authentication tag, whichever packet carries it. */
  private fun altered(message: ByteArray): ByteArray = message.copyOf().also { it[it.size - 1]++ }

  /**
   * A literal packet holding [CONTENT], encrypted to [KEY_PAIR] — as tag 20 OCB when [aead], which
   * is what GnuPG writes once the recipients' keys say they understand it, and as a SEIPD v1 packet
   * otherwise.
   */
  private fun encryptedMessage(aead: Boolean): ByteArray {
    val encryptorBuilder =
      BcPGPDataEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256).apply {
        if (aead) {
          setWithAEAD(AEADAlgorithmTags.OCB, AEAD_CHUNK_SIZE)
          setUseV5AEAD()
        } else {
          setWithIntegrityPacket(true)
        }
      }
    val generator =
      PGPEncryptedDataGenerator(encryptorBuilder).apply {
        addMethod(BcPublicKeyKeyEncryptionMethodGenerator(KEY_PAIR.publicKey))
      }
    val message = ByteArrayOutputStream()
    generator.open(message, ByteArray(BUFFER_SIZE)).use { ciphertext ->
      PGPLiteralDataGenerator()
        .open(ciphertext, PGPLiteralData.BINARY, "entry", CONTENT.size.toLong(), PGPLiteralData.NOW)
        .use { it.write(CONTENT) }
    }
    return message.toByteArray()
  }

  private companion object {
    private val CONTENT = "correct horse battery staple".toByteArray()
    private const val BUFFER_SIZE = 512
    private const val AEAD_CHUNK_SIZE = 6
    private const val RSA_KEY_SIZE = 2048
    private const val RSA_CERTAINTY = 100

    /** RSA because that is what the card path accepts; generated once for the whole class. */
    private val KEY_PAIR: PGPKeyPair by lazy {
      val generator =
        RSAKeyPairGenerator().apply {
          init(
            RSAKeyGenerationParameters(
              BigInteger.valueOf(0x10001),
              SecureRandom(),
              RSA_KEY_SIZE,
              RSA_CERTAINTY,
            )
          )
        }
      BcPGPKeyPair(PublicKeyAlgorithmTags.RSA_GENERAL, generator.generateKeyPair(), Date())
    }
  }
}
