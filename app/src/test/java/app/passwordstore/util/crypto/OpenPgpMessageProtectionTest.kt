/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.passwordstore.util.crypto

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.bouncycastle.bcpg.AEADAlgorithmTags
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags
import org.bouncycastle.openpgp.PGPEncryptedData
import org.bouncycastle.openpgp.PGPEncryptedDataGenerator
import org.bouncycastle.openpgp.PGPEncryptedDataList
import org.bouncycastle.openpgp.PGPLiteralData
import org.bouncycastle.openpgp.PGPLiteralDataGenerator
import org.bouncycastle.openpgp.PGPObjectFactory
import org.bouncycastle.openpgp.operator.bc.BcPBEKeyEncryptionMethodGenerator
import org.bouncycastle.openpgp.operator.bc.BcPGPDataEncryptorBuilder
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator

/**
 * A message the card decrypts has to be one nobody could have tampered with. Both packets that
 * guarantee that must be recognised: the message an OpenPGP card is asked to open was written
 * somewhere else, by whatever the user encrypts with, and refusing the AEAD packet GnuPG writes
 * made such entries unopenable.
 */
class OpenPgpMessageProtectionTest {

  @Test
  fun `accepts a message in a SEIPD packet`() {
    assertTrue(hasIntegrityProtection(encryptedMessage(Protection.SEIPD)))
  }

  @Test
  fun `accepts a message in an AEAD packet`() {
    assertTrue(hasIntegrityProtection(encryptedMessage(Protection.AEAD)))
  }

  @Test
  fun `refuses a message that carries no protection at all`() {
    assertFalse(hasIntegrityProtection(encryptedMessage(Protection.NONE)))
  }

  /**
   * An AEAD packet answers for itself as it is read, and BouncyCastle's verify() throws rather than
   * say so — asking it anyway would refuse the message a second time, one step further on.
   */
  @Test
  fun `does not ask an AEAD packet for the check it cannot answer`() {
    verifyIntegrity(encryptedMessage(Protection.AEAD))
  }

  private enum class Protection {
    SEIPD,
    AEAD,
    NONE,
  }

  private fun encryptedMessage(protection: Protection): PGPEncryptedData {
    val encryptorBuilder =
      BcPGPDataEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256).apply {
        when (protection) {
          Protection.SEIPD -> setWithIntegrityPacket(true)
          Protection.AEAD -> {
            setWithAEAD(AEADAlgorithmTags.OCB, AEAD_CHUNK_SIZE)
            setUseV5AEAD()
          }
          Protection.NONE -> setWithIntegrityPacket(false)
        }
      }
    val generator =
      PGPEncryptedDataGenerator(encryptorBuilder).apply {
        addMethod(BcPBEKeyEncryptionMethodGenerator(PASSPHRASE))
      }
    val message = ByteArrayOutputStream()
    generator.open(message, ByteArray(BUFFER_SIZE)).use { ciphertext ->
      PGPLiteralDataGenerator()
        .open(ciphertext, PGPLiteralData.BINARY, "entry", CONTENT.size.toLong(), PGPLiteralData.NOW)
        .use { it.write(CONTENT) }
    }
    val packets =
      PGPObjectFactory(ByteArrayInputStream(message.toByteArray()), JcaKeyFingerprintCalculator())
        .nextObject() as PGPEncryptedDataList
    return packets[0]
  }

  private companion object {
    private val PASSPHRASE = "passphrase".toCharArray()
    private val CONTENT = "correct horse battery staple".toByteArray()
    private const val BUFFER_SIZE = 512
    private const val AEAD_CHUNK_SIZE = 6
  }
}
