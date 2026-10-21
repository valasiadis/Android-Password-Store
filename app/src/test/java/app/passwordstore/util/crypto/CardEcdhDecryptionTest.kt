/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.passwordstore.util.crypto

import app.passwordstore.crypto.PGPEncryptOptions
import app.passwordstore.crypto.PGPKey
import app.passwordstore.crypto.PGPKeyManager
import app.passwordstore.crypto.PGPainlessCryptoHandler
import com.github.michaelbull.result.getOrThrow
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.Date
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.bouncycastle.asn1.sec.SECObjectIdentifiers
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags
import org.bouncycastle.bcpg.sig.KeyFlags
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.generators.ECKeyPairGenerator
import org.bouncycastle.crypto.params.AsymmetricKeyParameter
import org.bouncycastle.crypto.params.ECKeyGenerationParameters
import org.bouncycastle.crypto.params.ECNamedDomainParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.openpgp.PGPException
import org.bouncycastle.openpgp.PGPKeyRingGenerator
import org.bouncycastle.openpgp.PGPSecretKeyRingCollection
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.PGPSignatureSubpacketGenerator
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.operator.bc.BcKeyFingerprintCalculator
import org.bouncycastle.openpgp.operator.bc.BcPGPContentSignerBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPDigestCalculatorProvider
import org.bouncycastle.openpgp.operator.bc.BcPGPKeyConverter
import org.bouncycastle.openpgp.operator.bc.BcPGPKeyPair

/**
 * Decrypting with a card whose key is ECDH, end to end: a message encrypted to a real key, read
 * back through a card that holds that key's private half and does what an OpenPGP card does with
 * it.
 *
 * The card only agrees a secret; everything after that — the KDF and its parameters, the key
 * unwrap, the padding — is done here, and gets a single byte wrong at the cost of every message. So
 * the card below is not told the answer: it works the agreement out from the point it is sent, and
 * the session key is only recovered if every step around it matches the sender's.
 */
class CardEcdhDecryptionTest {

  /**
   * A card holding [privateKey] as its decryption key. It takes any PIN, and answers PSO: DECIPHER
   * the way the OpenPGP Card spec has it: given the sender's point, the shared secret — as the raw
   * agreement for Curve25519, and as the whole shared point for a NIST curve, which is what a card
   * may give and GnuPG takes the x coordinate of.
   */
  private class EcdhCard(private val privateKey: AsymmetricKeyParameter) : CardTransport {
    val pointsSent = mutableListOf<ByteArray>()

    override val maxTransceiveLength = 261
    override val connection = CardConnection.USB
    override val isConnected = true

    override fun transceive(command: ByteArray, timeoutMs: Int): ByteArray {
      val ok = byteArrayOf(0x90.toByte(), 0x00)
      if (command[1] != 0x2A.toByte() || command[2] != 0x80.toByte()) return ok
      val data = command.copyOfRange(5, 5 + (command[4].toInt() and 0xff))
      // A6 { 7F49 { 86 point } }
      val cipher = value(data, tagLength = 1)
      val publicKey = value(cipher, tagLength = 2)
      val point = value(publicKey, tagLength = 1)
      pointsSent += point
      return agree(point) + ok
    }

    private fun agree(point: ByteArray): ByteArray =
      when (val key = privateKey) {
        is X25519PrivateKeyParameters -> {
          val agreement = X25519Agreement().apply { init(key) }
          ByteArray(agreement.agreementSize).also {
            agreement.calculateAgreement(X25519PublicKeyParameters(point, 0), it, 0)
          }
        }
        is ECPrivateKeyParameters ->
          key.parameters.curve.decodePoint(point).multiply(key.d).normalize().getEncoded(false)
        else -> error("not an ECDH key: $key")
      }

    /** The value of the TLV at the start of [tlv], with a tag of [tagLength] bytes. */
    private fun value(tlv: ByteArray, tagLength: Int): ByteArray {
      var offset = tagLength
      val first = tlv[offset++].toInt() and 0xff
      val length =
        when (first) {
          0x81 -> tlv[offset++].toInt() and 0xff
          0x82 -> ((tlv[offset++].toInt() and 0xff) shl 8) or (tlv[offset++].toInt() and 0xff)
          else -> first
        }
      return tlv.copyOfRange(offset, offset + length)
    }

    override fun close() = Unit
  }

  /** A key with its ECDH subkey's private half, which goes on the card. */
  private class CardKey(
    val key: PGPKey,
    val fingerprint: ByteArray,
    val private: AsymmetricKeyParameter,
  )

  /**
   * The app's own kind of key: EdDSA to sign and Curve25519 ECDH to encrypt to, as on a YubiKey.
   */
  private fun curve25519Key(): CardKey {
    val keyDir = createTempDirectory().toFile()
    val key =
      try {
        PGPKeyManager(keyDir.absolutePath)
          .generateKey("Alice <alice@example.com>", null)
          .getOrThrow()
      } finally {
        keyDir.deleteRecursively()
      }
    val secretKey =
      PGPSecretKeyRingCollection(
          PGPUtil.getDecoderStream(key.contents.inputStream()),
          BcKeyFingerprintCalculator(),
        )
        .keyRings
        .asSequence()
        .flatMap { it.secretKeys.asSequence() }
        .single { it.publicKey.algorithm == PublicKeyAlgorithmTags.ECDH }
    val private = BcPGPKeyConverter().getPrivateKey(secretKey.extractPrivateKey(null))
    return CardKey(key, secretKey.publicKey.fingerprint, private)
  }

  /** ECDSA to sign and ECDH to encrypt to, both on NIST P-256, as a card can also hold. */
  private fun p256Key(): CardKey {
    val domain = ECNamedDomainParameters.lookup(SECObjectIdentifiers.secp256r1)
    val generator =
      ECKeyPairGenerator().apply { init(ECKeyGenerationParameters(domain, SecureRandom())) }
    val now = Date()
    val primary = BcPGPKeyPair(PublicKeyAlgorithmTags.ECDSA, generator.generateKeyPair(), now)
    val subPair = generator.generateKeyPair()
    val sub = BcPGPKeyPair(PublicKeyAlgorithmTags.ECDH, subPair, now)
    val primaryFlags =
      PGPSignatureSubpacketGenerator()
        .apply { setKeyFlags(false, KeyFlags.CERTIFY_OTHER or KeyFlags.SIGN_DATA) }
        .generate()
    val subFlags =
      PGPSignatureSubpacketGenerator()
        .apply { setKeyFlags(false, KeyFlags.ENCRYPT_COMMS or KeyFlags.ENCRYPT_STORAGE) }
        .generate()
    val ring =
      PGPKeyRingGenerator(
          PGPSignature.POSITIVE_CERTIFICATION,
          primary,
          "Bob <bob@example.com>",
          BcPGPDigestCalculatorProvider().get(HashAlgorithmTags.SHA1),
          primaryFlags,
          null,
          BcPGPContentSignerBuilder(PublicKeyAlgorithmTags.ECDSA, HashAlgorithmTags.SHA256),
          null,
        )
        .apply { addSubKey(sub, subFlags, null) }
        .generateSecretKeyRing()
    return CardKey(PGPKey(ring.encoded), sub.publicKey.fingerprint, subPair.private)
  }

  private fun encryptTo(key: PGPKey, plaintext: String): ByteArray {
    val ciphertext = ByteArrayOutputStream()
    PGPainlessCryptoHandler()
      .encrypt(
        listOf(key),
        null,
        plaintext.byteInputStream(),
        ciphertext,
        PGPEncryptOptions.Builder().build(),
      )
      .getOrThrow()
    return ciphertext.toByteArray()
  }

  private fun decryptOnCard(cardKey: CardKey, card: EcdhCard, ciphertext: ByteArray): String {
    val plaintext = ByteArrayOutputStream()
    OpenPgpSmartcardDecryptor()
      .decrypt(
        cardKey.key,
        "123456".toCharArray(),
        ciphertext.inputStream(),
        plaintext,
        OpenPgpCard(card),
        listOf(cardKey.fingerprint),
      )
    return plaintext.toString(Charsets.UTF_8)
  }

  @Test
  fun `a message for a Curve25519 card key decrypts`() {
    val cardKey = curve25519Key()
    val card = EcdhCard(cardKey.private)
    assertEquals("hunter2", decryptOnCard(cardKey, card, encryptTo(cardKey.key, "hunter2")))
    // The point went to the card as its 32 bytes, without the 40 OpenPGP writes in front.
    assertEquals(32, card.pointsSent.single().size)
  }

  @Test
  fun `a message for a NIST P-256 card key decrypts`() {
    val cardKey = p256Key()
    val card = EcdhCard(cardKey.private)
    assertEquals("hunter2", decryptOnCard(cardKey, card, encryptTo(cardKey.key, "hunter2")))
    // The whole uncompressed point.
    assertEquals(65, card.pointsSent.single().size)
  }

  @Test
  fun `a card holding some other key cannot unwrap the session key`() {
    val cardKey = curve25519Key()
    val otherCard = EcdhCard(curve25519Key().private)
    assertFailsWith<PGPException> {
      decryptOnCard(cardKey, otherCard, encryptTo(cardKey.key, "hunter2"))
    }
  }

  @Test
  fun `a Curve25519 secret given with its 40 in front is the same secret`() {
    val point = byteArrayOf(0x40) + ByteArray(32) { 1 }
    val secret = ByteArray(32) { 7 }
    assertContentEquals(secret, CardEcdh.sharedSecret(point, secret))
    assertContentEquals(secret, CardEcdh.sharedSecret(point, byteArrayOf(0x40) + secret))
  }

  @Test
  fun `a NIST secret given as its x coordinate alone is the same secret`() {
    val point = byteArrayOf(0x04) + ByteArray(64) { 1 }
    val x = ByteArray(32) { 7 }
    assertContentEquals(x, CardEcdh.sharedSecret(point, x))
    assertContentEquals(x, CardEcdh.sharedSecret(point, byteArrayOf(0x04) + x + ByteArray(32)))
  }
}
