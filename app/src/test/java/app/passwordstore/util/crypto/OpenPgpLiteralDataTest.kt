/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.passwordstore.util.crypto

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import org.bouncycastle.bcpg.CompressionAlgorithmTags
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags
import org.bouncycastle.openpgp.PGPCompressedDataGenerator
import org.bouncycastle.openpgp.PGPKeyPair
import org.bouncycastle.openpgp.PGPLiteralData
import org.bouncycastle.openpgp.PGPLiteralDataGenerator
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.PGPSignatureGenerator
import org.bouncycastle.openpgp.operator.bc.BcPGPContentSignerBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPKeyPair
import org.bouncycastle.crypto.generators.RSAKeyPairGenerator
import org.bouncycastle.crypto.params.RSAKeyGenerationParameters
import java.math.BigInteger
import java.security.SecureRandom

/**
 * The payload of a message the card opens is not always a bare literal packet. `gpg --sign
 * --encrypt` wraps it in a one-pass signature and closes with the signature itself, and an entry
 * written that way was reported as having no literal data in it at all while gpg read it fine.
 */
class OpenPgpLiteralDataTest {

  private val payload = "correct horse battery staple\n".toByteArray()

  @Test
  fun `reads a bare literal packet`() {
    assertEquals(payload.toList(), piped(literalMessage()).toList())
  }

  @Test
  fun `reads a literal packet inside a compressed one`() {
    assertEquals(payload.toList(), piped(compressed(literalMessage())).toList())
  }

  @Test
  fun `reads a literal packet between a one-pass signature and its signature`() {
    assertEquals(payload.toList(), piped(signedMessage()).toList())
  }

  @Test
  fun `reads a signed message inside a compressed packet`() {
    assertEquals(payload.toList(), piped(compressed(signedMessage())).toList())
  }

  private fun piped(message: ByteArray): ByteArray =
    ByteArrayOutputStream().also { pipeLiteralData(ByteArrayInputStream(message), it) }.toByteArray()

  private fun literalMessage(): ByteArray =
    ByteArrayOutputStream()
      .also { out ->
        PGPLiteralDataGenerator().let { generator ->
          generator
            .open(out, PGPLiteralData.BINARY, "", payload.size.toLong(), Date())
            .use { it.write(payload) }
          generator.close()
        }
      }
      .toByteArray()

  /** One-pass signature, then the literal packet, then the signature — what a signed message is. */
  private fun signedMessage(): ByteArray {
    val keyPair = rsaKeyPair()
    val signatureGenerator =
      PGPSignatureGenerator(
        BcPGPContentSignerBuilder(keyPair.publicKey.algorithm, HashAlgorithmTags.SHA256),
        keyPair.publicKey,
      )
    signatureGenerator.init(PGPSignature.BINARY_DOCUMENT, keyPair.privateKey)
    return ByteArrayOutputStream()
      .also { out ->
        signatureGenerator.generateOnePassVersion(false).encode(out)
        PGPLiteralDataGenerator().let { generator ->
          generator
            .open(out, PGPLiteralData.BINARY, "", payload.size.toLong(), Date())
            .use { it.write(payload) }
          generator.close()
        }
        signatureGenerator.update(payload)
        signatureGenerator.generate().encode(out)
      }
      .toByteArray()
  }

  private fun compressed(message: ByteArray): ByteArray =
    ByteArrayOutputStream()
      .also { out ->
        PGPCompressedDataGenerator(CompressionAlgorithmTags.ZIP).let { generator ->
          generator.open(out as OutputStream).use { it.write(message) }
          generator.close()
        }
      }
      .toByteArray()

  private fun rsaKeyPair(): PGPKeyPair {
    val generator = RSAKeyPairGenerator()
    generator.init(RSAKeyGenerationParameters(BigInteger.valueOf(0x10001), SecureRandom(), 2048, 80))
    return BcPGPKeyPair(
      org.bouncycastle.bcpg.PublicKeyPacket.VERSION_4,
      PublicKeyAlgorithmTags.RSA_GENERAL,
      generator.generateKeyPair(),
      Date(),
    )
  }
}
