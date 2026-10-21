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
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.bouncycastle.asn1.sec.SECObjectIdentifiers
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags
import org.bouncycastle.bcpg.sig.KeyFlags
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.generators.ECKeyPairGenerator
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.AsymmetricKeyParameter
import org.bouncycastle.crypto.params.ECKeyGenerationParameters
import org.bouncycastle.crypto.params.ECNamedDomainParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.openpgp.PGPEncryptedDataGenerator
import org.bouncycastle.openpgp.PGPException
import org.bouncycastle.openpgp.PGPKeyRingGenerator
import org.bouncycastle.openpgp.PGPLiteralData
import org.bouncycastle.openpgp.PGPLiteralDataGenerator
import org.bouncycastle.openpgp.PGPSecretKeyRingCollection
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.PGPSignatureSubpacketGenerator
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.operator.bc.BcKeyFingerprintCalculator
import org.bouncycastle.openpgp.operator.bc.BcPGPContentSignerBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPDataEncryptorBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPDigestCalculatorProvider
import org.bouncycastle.openpgp.operator.bc.BcPGPKeyConverter
import org.bouncycastle.openpgp.operator.bc.BcPGPKeyPair
import org.bouncycastle.openpgp.operator.bc.BcPublicKeyKeyEncryptionMethodGenerator

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
  private class EcdhCard(
    private val privateKey: AsymmetricKeyParameter,
    /** What the card says is in its decryption slot, or null for a card that says nothing. */
    private val decryptionSlot: ByteArray? = null,
  ) : CardTransport {
    val pointsSent = mutableListOf<ByteArray>()
    var pinsSent = 0

    override val maxTransceiveLength = 261
    override val connection = CardConnection.USB
    override val isConnected = true

    override fun transceive(command: ByteArray, timeoutMs: Int): ByteArray {
      val ok = byteArrayOf(0x90.toByte(), 0x00)
      if (command[1] == 0xCA.toByte() && command[3] == 0x6E.toByte()) {
        return applicationData() + ok
      }
      if (command[1] == 0x20.toByte() && command.size > 4) pinsSent++
      if (command[1] != 0x2A.toByte() || command[2] != 0x80.toByte()) return ok
      val data = command.copyOfRange(5, 5 + (command[4].toInt() and 0xff))
      // A6 { 7F49 { 86 point } }, each tag checked: a card answers 6A 80 to anything else.
      val cipher = value(data, byteArrayOf(0xA6.toByte()))
      val publicKey = value(cipher, byteArrayOf(0x7F, 0x49))
      val point = value(publicKey, byteArrayOf(0x86.toByte()))
      pointsSent += point
      return agree(point) + ok
    }

    /** 6E { 73 { C5 signature || decryption || authentication } }, with only decryption set. */
    private fun applicationData(): ByteArray {
      val slot = decryptionSlot ?: return byteArrayOf()
      val fingerprints = byteArrayOf(0xC5.toByte(), 60) + ByteArray(20) + slot + ByteArray(20)
      val discretionary = byteArrayOf(0x73, fingerprints.size.toByte()) + fingerprints
      return byteArrayOf(0x6E, discretionary.size.toByte()) + discretionary
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

    /** The value of the TLV at the start of [tlv], whose tag must be [tag]. */
    private fun value(tlv: ByteArray, tag: ByteArray): ByteArray {
      assertContentEquals(tag, tlv.copyOf(tag.size), "the card was sent the wrong tag")
      var offset = tag.size
      val first = tlv[offset++].toInt() and 0xff
      val length =
        when (first) {
          0x81 -> tlv[offset++].toInt() and 0xff
          0x82 -> ((tlv[offset++].toInt() and 0xff) shl 8) or (tlv[offset++].toInt() and 0xff)
          else -> first
        }
      assertEquals(tlv.size, offset + length, "the length does not cover the value")
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

  /**
   * ECDSA to sign and ECDH to encrypt to, both on NIST P-256, as a card can also hold — with
   * [subkeys] ECDH subkeys, of which the last is the one on the card.
   */
  private fun p256Key(subkeys: Int = 1): CardKey {
    val domain = ECNamedDomainParameters.lookup(SECObjectIdentifiers.secp256r1)
    val generator =
      ECKeyPairGenerator().apply { init(ECKeyGenerationParameters(domain, SecureRandom())) }
    val now = Date()
    val primary = BcPGPKeyPair(PublicKeyAlgorithmTags.ECDSA, generator.generateKeyPair(), now)
    val primaryFlags =
      PGPSignatureSubpacketGenerator()
        .apply { setKeyFlags(false, KeyFlags.CERTIFY_OTHER or KeyFlags.SIGN_DATA) }
        .generate()
    val subFlags =
      PGPSignatureSubpacketGenerator()
        .apply { setKeyFlags(false, KeyFlags.ENCRYPT_COMMS or KeyFlags.ENCRYPT_STORAGE) }
        .generate()
    val ringGenerator =
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
    val subPairs = List(subkeys) { generator.generateKeyPair() }
    val subs = subPairs.map { BcPGPKeyPair(PublicKeyAlgorithmTags.ECDH, it, now) }
    subs.forEach { ringGenerator.addSubKey(it, subFlags, null) }
    val ring = ringGenerator.generateSecretKeyRing()
    return CardKey(PGPKey(ring.encoded), subs.last().publicKey.fingerprint, subPairs.last().private)
  }

  /** A message to [cardKey]'s card key that does not say who it is for: a wildcard key ID. */
  private fun encryptToHiddenRecipient(cardKey: CardKey, plaintext: String): ByteArray =
    encryptWithBouncyCastle(cardKey, plaintext, hidden = true)

  /** A message to [cardKey]'s card key alone, written by BouncyCastle rather than PGPainless. */
  private fun encryptWithBouncyCastle(
    cardKey: CardKey,
    plaintext: String,
    hidden: Boolean,
  ): ByteArray {
    val publicKey =
      PGPSecretKeyRingCollection(
          PGPUtil.getDecoderStream(cardKey.key.contents.inputStream()),
          BcKeyFingerprintCalculator(),
        )
        .keyRings
        .asSequence()
        .flatMap { it.publicKeys.asSequence() }
        .single { it.fingerprint.contentEquals(cardKey.fingerprint) }
    val generator =
      PGPEncryptedDataGenerator(
        BcPGPDataEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256)
          .setWithIntegrityPacket(true)
          .setSecureRandom(SecureRandom())
      )
    generator.addMethod(
      BcPublicKeyKeyEncryptionMethodGenerator(publicKey).setUseWildcardKeyID(hidden)
    )
    val out = ByteArrayOutputStream()
    generator.open(out, ByteArray(BUFFER_SIZE)).use { encrypted ->
      PGPLiteralDataGenerator()
        .open(encrypted, PGPLiteralData.UTF8, "", Date(), ByteArray(BUFFER_SIZE))
        .use { it.write(plaintext.toByteArray()) }
    }
    return out.toByteArray()
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

  private fun decryptOnCard(
    cardKey: CardKey,
    card: EcdhCard,
    ciphertext: ByteArray,
    storedFingerprints: List<ByteArray> = listOf(cardKey.fingerprint),
  ): String {
    val plaintext = ByteArrayOutputStream()
    OpenPgpSmartcardDecryptor()
      .decrypt(
        cardKey.key,
        "123456".toCharArray(),
        ciphertext.inputStream(),
        plaintext,
        OpenPgpCard(card),
        storedFingerprints,
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
    // A card that says nothing about its slots, so it is only found out at the unwrap.
    val otherCard = EcdhCard(curve25519Key().private)
    val error =
      assertFailsWith<PGPException> {
        decryptOnCard(cardKey, otherCard, encryptTo(cardKey.key, "hunter2"))
      }
    assertContains(error.cause?.message.orEmpty(), "could not be unwrapped")
  }

  @Test
  fun `a card that says it holds some other key is turned away before its PIN is sent`() {
    val cardKey = curve25519Key()
    val other = curve25519Key()
    val otherCard = EcdhCard(other.private, decryptionSlot = other.fingerprint)
    val error =
      assertFailsWith<PGPException> {
        decryptOnCard(cardKey, otherCard, encryptTo(cardKey.key, "hunter2"))
      }
    assertContains(error.message.orEmpty(), "does not hold this key's decryption key")
    assertEquals(0, otherCard.pinsSent)
    assertTrue(otherCard.pointsSent.isEmpty())
  }

  @Test
  fun `a message naming nobody is read against the key the card says it holds`() {
    // Two ECDH subkeys, the card holding the second: nothing in the message or the key says which
    // to read it against, and the first is the wrong one. The card's own slot does.
    val cardKey = p256Key(subkeys = 2)
    val card = EcdhCard(cardKey.private, decryptionSlot = cardKey.fingerprint)
    val ciphertext = encryptToHiddenRecipient(cardKey, "hunter2")
    assertEquals(
      "hunter2",
      decryptOnCard(cardKey, card, ciphertext, storedFingerprints = emptyList()),
    )
  }

  @Test
  fun `a message naming nobody decrypts on a card that says nothing about its slots`() {
    val cardKey = curve25519Key()
    val card = EcdhCard(cardKey.private)
    assertEquals(
      "hunter2",
      decryptOnCard(cardKey, card, encryptToHiddenRecipient(cardKey, "hunter2")),
    )
  }

  /** Ed25519 to sign and X25519 to encrypt to, the key types of RFC 9580, as a card can hold. */
  private fun x25519Key(): CardKey {
    val now = Date()
    val primary =
      BcPGPKeyPair(
        PublicKeyAlgorithmTags.Ed25519,
        Ed25519KeyPairGenerator()
          .apply { init(Ed25519KeyGenerationParameters(SecureRandom())) }
          .generateKeyPair(),
        now,
      )
    val subPair =
      X25519KeyPairGenerator()
        .apply { init(X25519KeyGenerationParameters(SecureRandom())) }
        .generateKeyPair()
    val sub = BcPGPKeyPair(PublicKeyAlgorithmTags.X25519, subPair, now)
    fun flags(flags: Int) =
      PGPSignatureSubpacketGenerator().apply { setKeyFlags(false, flags) }.generate()
    val ring =
      PGPKeyRingGenerator(
          PGPSignature.POSITIVE_CERTIFICATION,
          primary,
          "Carol <carol@example.com>",
          BcPGPDigestCalculatorProvider().get(HashAlgorithmTags.SHA1),
          flags(KeyFlags.CERTIFY_OTHER or KeyFlags.SIGN_DATA),
          null,
          BcPGPContentSignerBuilder(PublicKeyAlgorithmTags.Ed25519, HashAlgorithmTags.SHA256),
          null,
        )
        .apply { addSubKey(sub, flags(KeyFlags.ENCRYPT_COMMS or KeyFlags.ENCRYPT_STORAGE), null) }
        .generateSecretKeyRing()
    return CardKey(PGPKey(ring.encoded), sub.publicKey.fingerprint, subPair.private)
  }

  @Test
  fun `a message for an X25519 card key of RFC 9580's kind decrypts`() {
    val cardKey = x25519Key()
    val card = EcdhCard(cardKey.private)
    val ciphertext = encryptWithBouncyCastle(cardKey, "hunter2", hidden = false)
    assertEquals("hunter2", decryptOnCard(cardKey, card, ciphertext))
    // The sender's key as it is: 32 bytes, with no 40 to take off.
    assertEquals(32, card.pointsSent.single().size)
  }

  @Test
  fun `a session key that could never unwrap is turned away before the card is asked`() {
    val cardKey = curve25519Key()
    val card = EcdhCard(cardKey.private)
    val recipient = publicKey(cardKey)
    val point = byteArrayOf(0x40) + ByteArray(32) { 9 }
    // A point, then a "wrapped key" of five bytes, which no RFC 3394 wrap is.
    val enc = byteArrayOf(0x01, 0x07) + point + byteArrayOf(5) + ByteArray(5)
    assertFailsWith<PGPException> {
      OpenPgpSmartcardDecryptor.OpenPgpCardDecryptorFactory(OpenPgpCard(card), listOf(recipient))
        .recoverSessionData(PublicKeyAlgorithmTags.ECDH, arrayOf(enc), 3)
    }
    assertTrue(card.pointsSent.isEmpty())
  }

  @Test
  fun `a point that is not on the card key's curve is turned away before the card is asked`() {
    val cardKey = p256Key()
    val card = EcdhCard(cardKey.private)
    // A Curve25519 point, for a key on P-256.
    val point = byteArrayOf(0x40) + ByteArray(32) { 9 }
    val enc = byteArrayOf(0x01, 0x07) + point + byteArrayOf(24) + ByteArray(24)
    val error =
      assertFailsWith<PGPException> {
        OpenPgpSmartcardDecryptor.OpenPgpCardDecryptorFactory(
            OpenPgpCard(card),
            listOf(publicKey(cardKey)),
          )
          .recoverSessionData(PublicKeyAlgorithmTags.ECDH, arrayOf(enc), 3)
      }
    assertContains(error.message.orEmpty(), "not on the card key's curve")
    assertTrue(card.pointsSent.isEmpty())
  }

  private fun publicKey(cardKey: CardKey) =
    PGPSecretKeyRingCollection(
        PGPUtil.getDecoderStream(cardKey.key.contents.inputStream()),
        BcKeyFingerprintCalculator(),
      )
      .keyRings
      .asSequence()
      .flatMap { it.publicKeys.asSequence() }
      .single { it.fingerprint.contentEquals(cardKey.fingerprint) }

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

  private companion object {
    const val BUFFER_SIZE = 1 shl 12
  }
}
