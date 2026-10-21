/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.passwordstore.util.crypto

import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.cryptlib.CryptlibObjectIdentifiers
import org.bouncycastle.asn1.edec.EdECObjectIdentifiers
import org.bouncycastle.asn1.x9.ECNamedCurveTable
import org.bouncycastle.bcpg.ECDHPublicBCPGKey
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags
import org.bouncycastle.bcpg.X25519PublicBCPGKey
import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.engines.CamelliaEngine
import org.bouncycastle.crypto.engines.RFC3394WrapEngine
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.openpgp.PGPException
import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.operator.PGPPad
import org.bouncycastle.openpgp.operator.RFC6637Utils
import org.bouncycastle.openpgp.operator.bc.BcKeyFingerprintCalculator
import org.bouncycastle.openpgp.operator.bc.BcPGPDigestCalculatorProvider

/**
 * Recovers a session key encrypted to an elliptic-curve key whose private half is on a card.
 *
 * The sender made a key pair for this one message, agreed a secret between its private half and the
 * recipient's public key, and wrapped the session key with a key derived from that secret. The
 * message carries the public half of the sender's pair and the wrapped session key. The card agrees
 * the same secret from its side — that is all it does — and the rest happens here, the same way
 * BouncyCastle does it for a key held in software, which is what GnuPG does with a card too.
 *
 * Two kinds of key work this way. ECDH (RFC 6637), on Curve25519 or a NIST or Brainpool curve,
 * derives its wrapping key with a hash over the secret and the recipient's parameters, and pads
 * what it wraps. X25519 (RFC 9580) derives it with HKDF over both public keys and the secret, and
 * wraps the bare session key. The card does the same for both.
 *
 * Everything in the message that can be checked without the card is checked first: asking the card
 * costs a PIN and perhaps a touch, and a message that could never be opened should not spend one.
 */
internal object CardEcdh {

  /**
   * The session data — algorithm, key and checksum, as the other key types give it — for [enc], the
   * one value an ECDH PKESK carries, encrypted to [recipient], whose private half is on [card].
   */
  fun recoverEcdhSessionData(
    card: OpenPgpCard,
    recipient: PGPPublicKey,
    enc: ByteArray,
  ): ByteArray {
    val ecdhKey =
      recipient.publicKeyPacket.key as? ECDHPublicBCPGKey
        ?: throw PGPException("The card's decryption key is not an ECDH key")
    // The sender's public point, as an MPI: a bit count, then the point.
    if (enc.size < 3) throw PGPException("Malformed ECDH session key")
    val pointLength = ((((enc[0].toInt() and 0xff) shl 8) or (enc[1].toInt() and 0xff)) + 7) / 8
    if (enc.size < 2 + pointLength + 1) throw PGPException("Malformed ECDH session key")
    val point = enc.copyOfRange(2, 2 + pointLength)
    // Then the wrapped session key, after a single length byte.
    val wrappedLength = enc[2 + pointLength].toInt() and 0xff
    val wrappedOffset = 3 + pointLength
    if (enc.size != wrappedOffset + wrappedLength) throw PGPException("Malformed ECDH session key")
    val wrapped = enc.copyOfRange(wrappedOffset, wrappedOffset + wrappedLength)

    requirePointFits(point, ecdhKey.curveOID)
    requireWrapped(wrapped)
    val hashAlgorithm = ecdhKey.hashAlgorithm.toInt()
    val symmetricAlgorithm = ecdhKey.symmetricKeyAlgorithm.toInt()
    val keyLength = keyLength(symmetricAlgorithm)
    val engine = wrapEngine(symmetricAlgorithm)
    val digestCalculator = BcPGPDigestCalculatorProvider().get(hashAlgorithm)
    val param =
      RFC6637Utils.createUserKeyingMaterial(recipient.publicKeyPacket, BcKeyFingerprintCalculator())

    return withSecret(card, point) { secret ->
      // RFC 6637 section 7: the hash of a counter of one, the secret, and the parameters, cut
      // short.
      digestCalculator.outputStream.use {
        it.write(byteArrayOf(0x00, 0x00, 0x00, 0x01))
        it.write(secret)
        it.write(param)
      }
      val digest = digestCalculator.digest
      try {
        if (digest.size < keyLength) {
          throw PGPException("The ECDH key's hash is too short for its key wrapping algorithm")
        }
        val kek = digest.copyOf(keyLength)
        try {
          PGPPad.unpadSessionData(unwrap(engine, kek, wrapped))
        } finally {
          kek.fill(0)
        }
      } finally {
        digest.fill(0)
      }
    }
  }

  /**
   * The session key for [enc], the one value an X25519 PKESK of version [pkeskVersion] carries,
   * encrypted to [recipient], whose private half is on [card]. Just the key: the algorithm, which a
   * version 3 packet keeps in the clear, is added by the caller as for any other key type.
   */
  fun recoverX25519SessionData(
    card: OpenPgpCard,
    recipient: PGPPublicKey,
    enc: ByteArray,
    pkeskVersion: Int,
  ): ByteArray {
    val recipientKey =
      (recipient.publicKeyPacket.key as? X25519PublicBCPGKey)?.key
        ?: throw PGPException("The card's decryption key is not an X25519 key")
    // The sender's public key, 32 bytes as they are; a length byte; the algorithm in the clear for
    // a
    // version 3 packet; and the wrapped session key.
    val keySize = X25519PublicBCPGKey.LENGTH
    if (enc.size < keySize + 1) throw PGPException("Malformed X25519 session key")
    val ephemeral = enc.copyOf(keySize)
    val size = enc[keySize].toInt() and 0xff
    if (enc.size != keySize + 1 + size) throw PGPException("Malformed X25519 session key")
    val algorithmBytes = if (pkeskVersion == V3_PKESK) 1 else 0
    if (size <= algorithmBytes) throw PGPException("Malformed X25519 session key")
    val wrapped = enc.copyOfRange(keySize + 1 + algorithmBytes, enc.size)
    requireWrapped(wrapped)

    return withSecret(card, ephemeral) { secret ->
      // RFC 9580 section 5.1.6: HKDF-SHA256 over both public keys and the secret, for AES-128.
      val kek = ByteArray(16)
      val ikm = ephemeral + recipientKey + secret
      try {
        HKDFBytesGenerator(SHA256Digest()).run {
          init(HKDFParameters(ikm, null, X25519_INFO))
          generateBytes(kek, 0, kek.size)
        }
        unwrap(RFC3394WrapEngine(AESEngine.newInstance()), kek, wrapped)
      } finally {
        kek.fill(0)
        ikm.fill(0)
      }
    }
  }

  /**
   * Has [card] agree a secret with [point], hands it to [use], and wipes it — and whatever the card
   * answered with, of which it may be only part — however [use] ends.
   */
  private inline fun <T> withSecret(
    card: OpenPgpCard,
    point: ByteArray,
    use: (ByteArray) -> T,
  ): T {
    val answer = card.agreeEcdh(cardPoint(point))
    try {
      val secret = sharedSecret(point, answer)
      try {
        return use(secret)
      } finally {
        secret.fill(0)
      }
    } finally {
      answer.fill(0)
    }
  }

  private fun unwrap(engine: RFC3394WrapEngine, kek: ByteArray, wrapped: ByteArray): ByteArray =
    try {
      engine.init(false, KeyParameter(kek))
      engine.unwrap(wrapped, 0, wrapped.size)
    } catch (e: InvalidCipherTextException) {
      // The secret agreed was not the one the sender agreed: another key, or a card that answered
      // with something other than the secret.
      throw PGPException("The session key could not be unwrapped with the card's secret", e)
    }

  /**
   * The point as the card wants it. A Curve25519 point is written in OpenPGP's ECDH after a `40`
   * byte that says it is a native point rather than an `04 || x || y` one, and the card wants the
   * 32 bytes without it, as GnuPG hands them over. A NIST or Brainpool point, and an X25519 key,
   * which has no such byte, go as they are.
   */
  internal fun cardPoint(point: ByteArray): ByteArray =
    if (point.size == NATIVE_POINT_LENGTH && point[0] == NATIVE_PREFIX) {
      point.copyOfRange(1, point.size)
    } else {
      point
    }

  /**
   * The secret the KDF takes, out of a copy of whatever the card answered with.
   *
   * For Curve25519 that is the 32 bytes of the agreement, which a card may also give with the `40`
   * in front. For the other curves it is the x coordinate of the shared point, which a card gives
   * either on its own or as the whole uncompressed point.
   */
  internal fun sharedSecret(point: ByteArray, answer: ByteArray): ByteArray {
    if (cardPoint(point).size == X25519_LENGTH) {
      return when {
        answer.size == X25519_LENGTH -> answer.copyOf()
        answer.size == X25519_LENGTH + 1 && answer[0] == NATIVE_PREFIX ->
          answer.copyOfRange(1, answer.size)
        else -> throw PGPException("The card's answer is not an ECDH shared secret")
      }
    }
    val coordinateLength = (point.size - 1) / 2
    return when {
      answer.size == coordinateLength -> answer.copyOf()
      answer.size == 2 * coordinateLength + 1 && answer[0] == UNCOMPRESSED_PREFIX ->
        answer.copyOfRange(1, 1 + coordinateLength)
      else -> throw PGPException("The card's answer is not an ECDH shared secret")
    }
  }

  /** Turns away a point that is not one on the recipient's curve, in the form OpenPGP writes. */
  private fun requirePointFits(point: ByteArray, curve: ASN1ObjectIdentifier) {
    val fits =
      if (
        curve == CryptlibObjectIdentifiers.curvey25519 || curve == EdECObjectIdentifiers.id_X25519
      ) {
        point.size == NATIVE_POINT_LENGTH && point[0] == NATIVE_PREFIX
      } else {
        val fieldLength =
          ECNamedCurveTable.getByOID(curve)?.curve?.fieldSize?.let { (it + 7) / 8 }
            ?: throw PGPException("The card's ECDH key is on a curve this app does not know")
        point.size == 2 * fieldLength + 1 && point[0] == UNCOMPRESSED_PREFIX
      }
    if (!fits) throw PGPException("The ECDH session key's point is not on the card key's curve")
  }

  /** RFC 3394 wraps at least two 64-bit blocks of key and adds one: 24 bytes or more, in eights. */
  private fun requireWrapped(wrapped: ByteArray) {
    if (wrapped.size < 24 || wrapped.size % 8 != 0) {
      throw PGPException("Malformed wrapped session key")
    }
  }

  private fun keyLength(symmetricAlgorithm: Int): Int =
    when (symmetricAlgorithm) {
      SymmetricKeyAlgorithmTags.AES_128,
      SymmetricKeyAlgorithmTags.CAMELLIA_128 -> 16
      SymmetricKeyAlgorithmTags.AES_192,
      SymmetricKeyAlgorithmTags.CAMELLIA_192 -> 24
      SymmetricKeyAlgorithmTags.AES_256,
      SymmetricKeyAlgorithmTags.CAMELLIA_256 -> 32
      else -> throw PGPException("Unsupported ECDH key wrapping algorithm $symmetricAlgorithm")
    }

  private fun wrapEngine(symmetricAlgorithm: Int): RFC3394WrapEngine =
    when (symmetricAlgorithm) {
      SymmetricKeyAlgorithmTags.AES_128,
      SymmetricKeyAlgorithmTags.AES_192,
      SymmetricKeyAlgorithmTags.AES_256 -> RFC3394WrapEngine(AESEngine.newInstance())
      SymmetricKeyAlgorithmTags.CAMELLIA_128,
      SymmetricKeyAlgorithmTags.CAMELLIA_192,
      SymmetricKeyAlgorithmTags.CAMELLIA_256 -> RFC3394WrapEngine(CamelliaEngine())
      else -> throw PGPException("Unsupported ECDH key wrapping algorithm $symmetricAlgorithm")
    }

  private const val V3_PKESK = 3
  private const val X25519_LENGTH = 32
  private const val NATIVE_POINT_LENGTH = X25519_LENGTH + 1
  private const val NATIVE_PREFIX: Byte = 0x40
  private const val UNCOMPRESSED_PREFIX: Byte = 0x04
  private val X25519_INFO = "OpenPGP X25519".toByteArray()
}
