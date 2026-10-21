/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */
@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package app.passwordstore.util.crypto

import app.passwordstore.crypto.KeyUtils
import app.passwordstore.crypto.PGPKey
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import org.bouncycastle.bcpg.AEADEncDataPacket
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags
import org.bouncycastle.bcpg.SymmetricEncIntegrityPacket
import org.bouncycastle.openpgp.PGPCompressedData
import org.bouncycastle.openpgp.PGPEncryptedData
import org.bouncycastle.openpgp.PGPEncryptedDataList
import org.bouncycastle.openpgp.PGPException
import org.bouncycastle.openpgp.PGPLiteralData
import org.bouncycastle.openpgp.PGPMarker
import org.bouncycastle.openpgp.PGPObjectFactory
import org.bouncycastle.openpgp.PGPOnePassSignatureList
import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.PGPPublicKeyEncryptedData
import org.bouncycastle.openpgp.PGPSessionKey
import org.bouncycastle.openpgp.PGPSignatureList
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.operator.AbstractPublicKeyDataDecryptorFactory
import org.bouncycastle.openpgp.operator.PGPDataDecryptor
import org.bouncycastle.openpgp.operator.bc.BcPublicKeyDataDecryptorFactory
import org.bouncycastle.openpgp.operator.bc.BcSessionKeyDataDecryptorFactory
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator
import org.bouncycastle.util.io.Streams

/**
 * Whether [data] is protected against having been tampered with on its way here.
 *
 * Three packets are in use and all three arrive, since the message was written by whatever the user
 * encrypts with: SEIPD v1 (tag 18 v1, ciphertext plus an MDC hash), SEIPD v2 (tag 18 v2, RFC 9580's
 * AEAD) and AEAD (tag 20, LibrePGP's AEAD — what GnuPG writes once the recipients' keys say they
 * understand it, so a likely shape for a store kept on a desktop).
 *
 * Only the last fails [PGPEncryptedData.isIntegrityProtected], which asks whether the data sits in
 * a SEIPD packet rather than whether it is protected; tag 20 is authenticated by construction, as
 * much as the v2 it is an alternative spelling of. That question alone once made entries written by
 * an ordinary GnuPG unopenable.
 */
internal fun hasIntegrityProtection(data: PGPEncryptedData): Boolean =
  data.isIntegrityProtected || data.encData is AEADEncDataPacket

/**
 * Writes the message's payload out of [inputStream] into [outputStream].
 *
 * What comes out of the decryption is a sequence of packets, not the text itself. Usually it is one
 * literal packet, sometimes a compressed one wrapping it, and for a message that was signed as well
 * as encrypted it is a one-pass signature, then the literal packet, then the signature — which is
 * what `gpg --sign --encrypt` writes, and what an entry saved by some other tool can easily be.
 *
 * The packets are read from a single [PGPObjectFactory], asked for one object after another. Making
 * a new factory to read the next packet — which is what this did after stepping over a signature
 * header — starts a fresh parse partway into a stream the old one had already read ahead in, so the
 * literal packet was never found and a signed entry died with "No literal OpenPGP data found" while
 * gpg opened it perfectly well.
 *
 * Signatures are stepped over rather than checked. Whether the message is signed, and by whom, is
 * not something this path has ever reported, and quietly accepting a bad signature is no worse than
 * quietly ignoring a good one — but it is worth saying plainly that neither happens here.
 */
internal fun pipeLiteralData(inputStream: InputStream, outputStream: OutputStream) {
  val factory = PGPObjectFactory(inputStream, JcaKeyFingerprintCalculator())
  var current = factory.nextObject()
  while (current != null) {
    when (current) {
      is PGPCompressedData -> {
        pipeLiteralData(current.dataStream, outputStream)
        return
      }
      is PGPLiteralData -> {
        current.inputStream.use { Streams.pipeAll(it, outputStream) }
        return
      }
      // A signed message opens with one of these and closes with the other; the payload is in
      // between, so both are stepped over on the way to it.
      is PGPOnePassSignatureList,
      is PGPSignatureList,
      is PGPMarker -> current = factory.nextObject()
      else -> throw PGPException("Unsupported OpenPGP cleartext packet")
    }
  }
  throw PGPException("No literal OpenPGP data found")
}

/**
 * Establishes that the plaintext just read out of [data] is the plaintext that was written.
 *
 * A tag 20 packet has already answered for itself, chunk by chunk, as its stream was read to the
 * end, and [PGPEncryptedData.verify] refuses to be called on it at all — that check is for the MDC
 * only a SEIPD packet carries.
 *
 * @throws PGPException if the message was altered after it was written.
 */
internal fun verifyIntegrity(data: PGPEncryptedData) {
  if (data.encData is AEADEncDataPacket) return
  if (!data.verify()) {
    throw PGPException("OpenPGP message integrity check failed")
  }
}

/**
 * Writes the payload of [data] — which the card has already unlocked by recovering [sessionKey] —
 * into [outputStream], and does not return until the message has answered for itself.
 *
 * The content cipher is built through BouncyCastle's own lightweight API rather than through the
 * JCE. Asking the platform for one lands on Android's providers, and Android's cut-down
 * BouncyCastle has no OCB: a message GnuPG wrote as tag 20 died there with "cannot create cipher:
 * No provider found for AES/OCB/NoPadding", after the card had already deciphered the session key.
 * The bundled BouncyCastle carries OCB itself, needs no provider registered, and is what the rest
 * of this file — and the app's PGPainless path — already decrypts through.
 *
 * @throws PGPException if the message was altered after it was written.
 */
internal fun readPlaintext(
  data: PGPPublicKeyEncryptedData,
  sessionKey: PGPSessionKey,
  outputStream: OutputStream,
) {
  data.getDataStream(BcSessionKeyDataDecryptorFactory(sessionKey)).use { cleartext ->
    pipeLiteralData(cleartext, outputStream)
    // The literal packet ends before the ciphertext does, and an AEAD message's last chunk is only
    // answered for once the stream has been read out — so read the rest of it here, while it is
    // open, and let a chunk that does not answer throw before the plaintext is used.
    Streams.drain(cleartext)
  }
  verifyIntegrity(data)
}

class OpenPgpSmartcardDecryptor @Inject constructor() {

  fun decrypt(
    key: PGPKey,
    pin: CharArray,
    ciphertextStream: InputStream,
    outputStream: OutputStream,
    card: OpenPgpCard,
    cardFingerprints: List<ByteArray>,
  ) {
    val cert = KeyUtils.tryParseCertificateOrKey(key) ?: throw PGPException("Invalid PGP key")
    // Which of this key's keys the card that is here decrypts with, as the card itself says — read
    // without the PIN, so that a card holding some other key is told so before it is handed one.
    val cardKey =
      cardDecryptionSlot(card)?.let { fingerprint ->
        cert.keys.map { it.pgpPublicKey }.firstOrNull { it.fingerprint.contentEquals(fingerprint) }
          ?: throw PGPException(
            "The OpenPGP card presented does not hold this key's decryption key"
          )
      }
    val keyIds =
      if (cardKey != null) setOf(cardKey.keyID) else keyIdsMatchingCard(cert, cardFingerprints)

    val decoder = PGPUtil.getDecoderStream(ciphertextStream)
    val objectFactory = PGPObjectFactory(decoder, JcaKeyFingerprintCalculator())
    val encryptedDataList =
      generateSequence { objectFactory.nextObject() }
        .filterIsInstance<PGPEncryptedDataList>()
        .firstOrNull() ?: throw PGPException("No encrypted OpenPGP data found")

    val encryptedDataPackets =
      encryptedDataList.asSequence().filterIsInstance<PGPPublicKeyEncryptedData>().toList()
    val explicitMatches = encryptedDataPackets.filter { keyIds.contains(it.keyIdentifier.keyId) }
    val anonymousMatches = encryptedDataPackets.filter {
      it.keyIdentifier.isWildcard || it.keyIdentifier.keyId == 0L
    }
    // Each candidate triggers a card decipher operation. Wildcard-recipient packets (key id 0) all
    // match, so a crafted message could enqueue arbitrarily many; cap the attempts to bound the
    // work a hostile message can push onto the card.
    val candidates = (explicitMatches + anonymousMatches).distinct().take(MAX_DECRYPT_CANDIDATES)
    if (candidates.isEmpty()) throw PGPException("Message is not encrypted to this OpenPGP card")

    // A packet that names nobody is read against the card's decryption key where the card said
    // which that is. Otherwise against this key's encryption keys among those it shares with the
    // card, the ones in use first: an algorithm alone does not tell an expired ECDH subkey from the
    // current one, or an RSA decryption key from the RSA signing key beside it.
    val anonymousRecipients =
      if (cardKey != null) {
        listOf(cardKey)
      } else {
        val current = cert.encryptionKeys.map { it.pgpPublicKey }.filter { it.keyID in keyIds }
        val rest =
          cert.keys
            .map { it.pgpPublicKey }
            .filter { key ->
              key.keyID in keyIds && key.isEncryptionKey && current.none { it.keyID == key.keyID }
            }
        current + rest
      }
    // Whether a packet naming one of [keyIds] names the card's own decryption key. True where the
    // card said which key it holds, or the key was associated with the card; not where nothing is
    // known and [keyIds] is every key there is, software subkeys included.
    val namedKeysAreTheCards = cardKey != null || cardFingerprints.isNotEmpty()

    card.verifyUserPin(pin)

    var firstFailure: Exception? = null
    val (encryptedData, sessionKey) =
      candidates.firstNotNullOfOrNull { candidate ->
        try {
          val recipients =
            if (candidate in explicitMatches) {
              listOfNotNull(cert.getKey(candidate.keyIdentifier)?.pgpPublicKey)
            } else {
              anonymousRecipients
            }
          val decryptorFactory = OpenPgpCardDecryptorFactory(card, recipients)
          candidate to candidate.getSessionKey(decryptorFactory)
        } catch (e: Throwable) {
          if (OpenPgpCard.isTransceiveFailure(e)) throw e
          if (e.isCardAuthenticationFailure()) throw e
          // Addressed to the card's own key, so every other candidate is for the same key and would
          // fail the same way; anything said after this would blame the message instead.
          if (
            e is UnsupportedCardKeyException && candidate in explicitMatches && namedKeysAreTheCards
          ) {
            throw e
          }
          if (firstFailure == null) firstFailure = e as? Exception
          null
        }
      }
        ?: throw PGPException(
          "Message is not encrypted to this OpenPGP card",
          firstFailure,
        )

    // Reject messages that carry no protection at all (legacy SED packets) outright, matching the
    // default policy of the app's main PGPainless decryption path. Without one, the plaintext is
    // unauthenticated and malleable. See [hasIntegrityProtection] for what counts as protected.
    if (!hasIntegrityProtection(encryptedData)) {
      throw PGPException("Refusing to decrypt OpenPGP message without integrity protection")
    }

    // Streaming decryption necessarily writes the plaintext before it can be vouched for;
    // [outputStream] is an in-memory buffer the caller must (and does) discard when this throws.
    readPlaintext(encryptedData, sessionKey, outputStream)
  }

  /**
   * The fingerprint in the card's decryption slot, or null where the card will not say or the slot
   * is empty. A card that cannot be reached is not one that will not say, and that failure goes on.
   */
  private fun cardDecryptionSlot(card: OpenPgpCard): ByteArray? =
    try {
      card.readKeySlots().decryption
    } catch (e: OpenPgpCardStatusException) {
      null
    }

  private fun Throwable.isCardAuthenticationFailure(): Boolean =
    this is OpenPgpCardStatusException && isAuthenticationFailure ||
      cause?.isCardAuthenticationFailure() == true

  private fun keyIdsMatchingCard(
    cert: org.bouncycastle.openpgp.api.OpenPGPCertificate,
    cardFingerprints: List<ByteArray>,
  ): Set<Long> {
    if (cardFingerprints.isEmpty()) return cert.getAllKeyIdentifiers().map { it.getKeyId() }.toSet()
    val matchingKeyIds =
      cert
        .getAllKeyIdentifiers()
        .filter { keyIdentifier ->
          val fingerprint = keyIdentifier.getFingerprint() ?: return@filter false
          cardFingerprints.any { it.contentEquals(fingerprint) }
        }
        .map { it.getKeyId() }
        .toSet()
    if (matchingKeyIds.isEmpty()) {
      throw PGPException("The selected OpenPGP card does not match this key")
    }
    return matchingKeyIds
  }

  /**
   * The message is for the card, but the card's decryption key is of a kind this app cannot yet
   * hand to a card to decrypt with. RSA, ECDH and X25519 can be; X448 works as X25519 does, but no
   * card in common use holds one.
   */
  private class UnsupportedCardKeyException(keyAlgorithm: Int) :
    PGPException(
      "This OpenPGP card's decryption key is ${algorithmName(keyAlgorithm)}; decrypting with a " +
        "card is only supported for RSA, ECDH and X25519 keys so far"
    )

  /**
   * Hands the card the session key encrypted to it, and gives back what the card makes of it.
   *
   * [recipients] are the keys the packet may have been written to: the one it names, or, for a
   * packet that names nobody, the card's own. The one of the packet's kind is what the card's half
   * is checked against.
   */
  internal class OpenPgpCardDecryptorFactory(
    private val card: OpenPgpCard,
    private val recipients: List<PGPPublicKey>,
  ) : AbstractPublicKeyDataDecryptorFactory() {

    private val contentDecryptorFactory = BcPublicKeyDataDecryptorFactory(null)

    override fun recoverSessionData(
      keyAlgorithm: Int,
      secKeyData: Array<ByteArray>,
      pkeskVersion: Int,
    ): ByteArray {
      val recipient = recipients.firstOrNull { it.algorithm == keyAlgorithm }
      val enc = secKeyData.firstOrNull() ?: throw PGPException("Missing encrypted session key")
      return when (keyAlgorithm) {
        PublicKeyAlgorithmTags.RSA_ENCRYPT,
        PublicKeyAlgorithmTags.RSA_GENERAL -> recoverRsaSessionData(enc, recipient?.bitStrength)
        PublicKeyAlgorithmTags.ECDH ->
          CardEcdh.recoverEcdhSessionData(
            card,
            recipient ?: throw PGPException("The card's ECDH key is not part of this key"),
            enc,
          )
        PublicKeyAlgorithmTags.X25519 ->
          CardEcdh.recoverX25519SessionData(
            card,
            recipient ?: throw PGPException("The card's X25519 key is not part of this key"),
            enc,
            pkeskVersion,
          )
        else -> throw UnsupportedCardKeyException(keyAlgorithm)
      }
    }

    /**
     * [modulusBits] is the size of the card's RSA key, where it is known. OpenPGP writes the
     * encrypted value as a number, without the zero bytes it may start with — about one message in
     * 256 has one — while the card wants exactly as many bytes as its key is long, as GnuPG gives
     * it. Sent short, the card refuses the message or decrypts something else.
     */
    private fun recoverRsaSessionData(mpi: ByteArray, modulusBits: Int?): ByteArray {
      if (mpi.size <= 2) throw PGPException("Malformed RSA session key")
      val value = mpi.copyOfRange(2, mpi.size)
      val modulusLength = modulusBits?.let { (it + 7) / 8 } ?: value.size
      return card.decipher(ByteArray((modulusLength - value.size).coerceAtLeast(0)) + value)
    }

    override fun createDataDecryptor(
      withIntegrityPacket: Boolean,
      encAlgorithm: Int,
      key: ByteArray,
    ): PGPDataDecryptor =
      contentDecryptorFactory.createDataDecryptor(withIntegrityPacket, encAlgorithm, key)

    override fun createDataDecryptor(
      aeadEncDataPacket: AEADEncDataPacket,
      sessionKey: PGPSessionKey,
    ): PGPDataDecryptor = contentDecryptorFactory.createDataDecryptor(aeadEncDataPacket, sessionKey)

    override fun createDataDecryptor(
      seipd: SymmetricEncIntegrityPacket,
      sessionKey: PGPSessionKey,
    ): PGPDataDecryptor = contentDecryptorFactory.createDataDecryptor(seipd, sessionKey)
  }

  private companion object {
    private fun algorithmName(keyAlgorithm: Int): String =
      when (keyAlgorithm) {
        PublicKeyAlgorithmTags.X25519 -> "X25519"
        PublicKeyAlgorithmTags.X448 -> "X448"
        PublicKeyAlgorithmTags.ELGAMAL_ENCRYPT,
        PublicKeyAlgorithmTags.ELGAMAL_GENERAL -> "ElGamal"
        else -> "of OpenPGP algorithm $keyAlgorithm"
      }

    // Upper bound on the number of PKESK packets we will try to decrypt on the card per message.
    private const val MAX_DECRYPT_CANDIDATES = 16
  }
}
