/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.passwordstore.util.git

import app.passwordstore.util.crypto.CardConnection
import app.passwordstore.util.crypto.CardTransport
import app.passwordstore.util.crypto.OpenPgpCard
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue
import org.bouncycastle.asn1.sec.SECObjectIdentifiers
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags
import org.bouncycastle.crypto.encodings.PKCS1Encoding
import org.bouncycastle.crypto.engines.RSABlindedEngine
import org.bouncycastle.crypto.generators.ECKeyPairGenerator
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.generators.RSAKeyPairGenerator
import org.bouncycastle.crypto.params.AsymmetricKeyParameter
import org.bouncycastle.crypto.params.ECKeyGenerationParameters
import org.bouncycastle.crypto.params.ECNamedDomainParameters
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.RSAKeyGenerationParameters
import org.bouncycastle.crypto.signers.ECDSASigner
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.openpgp.PGPPrivateKey
import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.PGPSignatureGenerator
import org.bouncycastle.openpgp.operator.bc.BcPGPContentVerifierBuilderProvider
import org.bouncycastle.openpgp.operator.bc.BcPGPKeyPair
import org.bouncycastle.util.BigIntegers

/**
 * A commit signature made on a card, as anyone checking it will read it.
 *
 * Beside the signature itself an OpenPGP signature keeps the first two bytes of the hash it signs,
 * and a verifier compares them before anything else. Those two bytes once came from reading the
 * hash a second time — by which point it had been finished and started afresh — so every commit
 * signed on a card carried `e3 b0`, the start of the hash of nothing, whatever it said.
 */
class CardContentSignerTest {

  /** A card that signs what it is sent with [sign], as a card holding the key would. */
  private class SigningTransport(private val sign: (ByteArray) -> ByteArray) : CardTransport {
    val signedInputs = mutableListOf<ByteArray>()

    override val maxTransceiveLength = 261
    override val connection = CardConnection.USB
    override val isConnected = true

    override fun transceive(command: ByteArray, timeoutMs: Int): ByteArray {
      val ok = byteArrayOf(0x90.toByte(), 0x00)
      // PSO: COMPUTE DIGITAL SIGNATURE, sent as a short APDU: header, Lc, data, Le.
      if (command[1] == 0x2A.toByte() && command[2] == 0x9E.toByte()) {
        val length = command[4].toInt() and 0xff
        val input = command.copyOfRange(5, 5 + length)
        signedInputs += input
        return sign(input) + ok
      }
      // Anything else, the application data asked for before signing included: an empty answer.
      return ok
    }

    override fun close() = Unit
  }

  /** An RSA card: handed a DigestInfo, it pads it and signs it. */
  private fun rsaCard(key: AsymmetricKeyParameter) = SigningTransport { digestInfo ->
    PKCS1Encoding(RSABlindedEngine())
      .apply { init(true, key) }
      .processBlock(digestInfo, 0, digestInfo.size)
  }

  /** Signs [payload] as a commit is signed, with [transport] as the card. */
  private fun signOnCard(publicKey: PGPPublicKey, transport: SigningTransport): PGPSignature {
    val generator =
      PGPSignatureGenerator(
        OpenPgpCommitSigner.CardContentSignerBuilder(publicKey, OpenPgpCard(transport)),
        publicKey,
      )
    generator.init(
      PGPSignature.BINARY_DOCUMENT,
      PGPPrivateKey(publicKey.keyID, publicKey.publicKeyPacket, null),
    )
    generator.update(PAYLOAD)
    return generator.generate()
  }

  private fun PGPSignature.verifies(publicKey: PGPPublicKey): Boolean {
    init(BcPGPContentVerifierBuilderProvider(), publicKey)
    update(PAYLOAD)
    return verify()
  }

  @Test
  fun `an EdDSA card signs the hash itself, and the signature verifies`() {
    val pair =
      Ed25519KeyPairGenerator()
        .apply { init(Ed25519KeyGenerationParameters(SecureRandom())) }
        .generateKeyPair()
    val publicKey = BcPGPKeyPair(PublicKeyAlgorithmTags.EDDSA_LEGACY, pair, Date()).publicKey
    val transport = SigningTransport { hash ->
      Ed25519Signer().run {
        init(true, pair.private)
        update(hash, 0, hash.size)
        generateSignature()
      }
    }
    val signature = signOnCard(publicKey, transport)
    // Handed the SHA-256 itself, not a DigestInfo around it.
    assertContentEquals(sha256(signature), transport.signedInputs.single())
    assertTrue(signature.verifies(publicKey))
  }

  @Test
  fun `an ECDSA card's R and S side by side make a signature that verifies`() {
    val domain = ECNamedDomainParameters.lookup(SECObjectIdentifiers.secp256r1)
    val pair =
      ECKeyPairGenerator()
        .apply { init(ECKeyGenerationParameters(domain, SecureRandom())) }
        .generateKeyPair()
    val publicKey = BcPGPKeyPair(PublicKeyAlgorithmTags.ECDSA, pair, Date()).publicKey
    val transport = SigningTransport { hash ->
      val (r, s) =
        ECDSASigner().run {
          init(true, pair.private)
          generateSignature(hash)
        }
      BigIntegers.asUnsignedByteArray(32, r) + BigIntegers.asUnsignedByteArray(32, s)
    }
    val signature = signOnCard(publicKey, transport)
    assertContentEquals(sha256(signature), transport.signedInputs.single())
    assertTrue(signature.verifies(publicKey))
  }

  /** The hash [signature] was made over: the payload, then the signature's own trailer. */
  private fun sha256(signature: PGPSignature): ByteArray =
    MessageDigest.getInstance("SHA-256").run {
      update(PAYLOAD)
      update(signature.signatureTrailer)
      digest()
    }

  @Test
  fun `keeps the first two bytes of the hash that was signed`() {
    val keyPair = rsaKeyPair()
    val transport = rsaCard(keyPair.privateKey)
    val publicKey = keyPair.pgpKeyPair.publicKey
    val signature = signOnCard(publicKey, transport)

    // The DigestInfo the card was handed ends in the SHA-256 it signed.
    val signedHash = transport.signedInputs.single().takeLast(32).toByteArray()
    assertContentEquals(signedHash.copyOf(2), signature.digestPrefix)
    assertTrue(
      !signature.digestPrefix.contentEquals(EMPTY_SHA256.copyOf(2)),
      "the prefix is the hash of nothing",
    )
    assertTrue(signature.verifies(publicKey))
  }

  private class TestKeyPair(
    val pgpKeyPair: BcPGPKeyPair,
    val privateKey: AsymmetricKeyParameter,
  )

  private fun rsaKeyPair(): TestKeyPair {
    val generator =
      RSAKeyPairGenerator().apply {
        init(RSAKeyGenerationParameters(BigInteger.valueOf(0x10001), SecureRandom(), 2048, 80))
      }
    val pair = generator.generateKeyPair()
    return TestKeyPair(
      BcPGPKeyPair(PublicKeyAlgorithmTags.RSA_GENERAL, pair, Date()),
      pair.private,
    )
  }

  private companion object {
    val PAYLOAD = "tree 4b825dc642cb6eb9a060e54bf8d69288fbee4904\n".toByteArray()

    val EMPTY_SHA256: ByteArray = MessageDigest.getInstance("SHA-256").digest(byteArrayOf())
  }
}
