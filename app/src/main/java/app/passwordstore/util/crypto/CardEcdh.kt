/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.passwordstore.util.crypto

import org.bouncycastle.bcpg.ECDHPublicBCPGKey
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags
import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.engines.CamelliaEngine
import org.bouncycastle.crypto.engines.RFC3394WrapEngine
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.openpgp.PGPException
import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.operator.PGPPad
import org.bouncycastle.openpgp.operator.RFC6637Utils
import org.bouncycastle.openpgp.operator.bc.BcKeyFingerprintCalculator
import org.bouncycastle.openpgp.operator.bc.BcPGPDigestCalculatorProvider

/**
 * Recovers a session key encrypted to an ECDH key whose private half is on a card (RFC 6637).
 *
 * The sender made a key pair for this one message, agreed a secret between its private half and the
 * recipient's public key, and wrapped the session key with a key derived from that secret. The
 * message carries the public half of the sender's pair and the wrapped session key. The card agrees
 * the same secret from its side — that is all it does — and the rest happens here, the same way
 * BouncyCastle does it for a key held in software, which is what GnuPG does with a card too.
 */
internal object CardEcdh {

  /**
   * The session data — algorithm, key and checksum, as the other key types give it — for [enc], the
   * one value an ECDH PKESK carries, encrypted to [recipient], whose private half is on [card].
   */
  fun recoverSessionData(card: OpenPgpCard, recipient: PGPPublicKey, enc: ByteArray): ByteArray {
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
    if (enc.size < wrappedOffset + wrappedLength) throw PGPException("Malformed ECDH session key")
    val wrapped = enc.copyOfRange(wrappedOffset, wrappedOffset + wrappedLength)

    val secret = sharedSecret(point, card.agreeEcdh(cardPoint(point)))
    val kek =
      kdf(
        hashAlgorithm = ecdhKey.hashAlgorithm.toInt(),
        keyLength = keyLength(ecdhKey.symmetricKeyAlgorithm.toInt()),
        secret = secret,
        param =
          RFC6637Utils.createUserKeyingMaterial(
            recipient.publicKeyPacket,
            BcKeyFingerprintCalculator(),
          ),
      )
    val unwrapped =
      try {
        wrapEngine(ecdhKey.symmetricKeyAlgorithm.toInt()).run {
          init(false, KeyParameter(kek))
          unwrap(wrapped, 0, wrapped.size)
        }
      } catch (e: InvalidCipherTextException) {
        // The secret agreed was not the one the sender agreed: another key, or a card that
        // answered with something other than the secret.
        throw PGPException("The session key could not be unwrapped with the card's secret", e)
      } finally {
        secret.fill(0)
        kek.fill(0)
      }
    return PGPPad.unpadSessionData(unwrapped)
  }

  /**
   * The point as the card wants it. A Curve25519 point is written in OpenPGP after a `40` byte that
   * says it is a native point rather than an `04 || x || y` one, and the card wants the 32 bytes
   * without it, as GnuPG hands them over. A NIST or Brainpool point goes as it is.
   */
  internal fun cardPoint(point: ByteArray): ByteArray =
    if (isNative(point)) point.copyOfRange(1, point.size) else point

  /**
   * The secret the KDF takes, out of whatever the card answered with.
   *
   * For Curve25519 that is the 32 bytes of the agreement, which a card may also give with the `40`
   * in front. For the other curves it is the x coordinate of the shared point, which a card gives
   * either on its own or as the whole uncompressed point.
   */
  internal fun sharedSecret(point: ByteArray, answer: ByteArray): ByteArray {
    if (isNative(point)) {
      val length = point.size - 1
      return when {
        answer.size == length -> answer
        answer.size == length + 1 && answer[0] == NATIVE_PREFIX ->
          answer.copyOfRange(1, answer.size)
        else -> throw PGPException("The card's answer is not an ECDH shared secret")
      }
    }
    val coordinateLength = (point.size - 1) / 2
    return when {
      answer.size == coordinateLength -> answer
      answer.size == 2 * coordinateLength + 1 && answer[0] == UNCOMPRESSED_PREFIX ->
        answer.copyOfRange(1, 1 + coordinateLength)
      else -> throw PGPException("The card's answer is not an ECDH shared secret")
    }
  }

  /**
   * RFC 6637 section 7: the hash of a counter of one, the secret, and the parameters, cut short.
   */
  private fun kdf(
    hashAlgorithm: Int,
    keyLength: Int,
    secret: ByteArray,
    param: ByteArray,
  ): ByteArray {
    val digestCalculator = BcPGPDigestCalculatorProvider().get(hashAlgorithm)
    digestCalculator.outputStream.use {
      it.write(byteArrayOf(0x00, 0x00, 0x00, 0x01))
      it.write(secret)
      it.write(param)
    }
    val digest = digestCalculator.digest
    if (digest.size < keyLength) throw PGPException("The ECDH key's hash is too short for its KEK")
    return digest.copyOf(keyLength)
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

  private fun isNative(point: ByteArray) = point.isNotEmpty() && point[0] == NATIVE_PREFIX

  private const val NATIVE_PREFIX: Byte = 0x40
  private const val UNCOMPRESSED_PREFIX: Byte = 0x04
}
