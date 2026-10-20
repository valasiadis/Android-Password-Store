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
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags
import org.bouncycastle.crypto.encodings.PKCS1Encoding
import org.bouncycastle.crypto.engines.RSABlindedEngine
import org.bouncycastle.crypto.generators.RSAKeyPairGenerator
import org.bouncycastle.crypto.params.AsymmetricKeyParameter
import org.bouncycastle.crypto.params.RSAKeyGenerationParameters
import org.bouncycastle.openpgp.PGPPrivateKey
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.PGPSignatureGenerator
import org.bouncycastle.openpgp.operator.bc.BcPGPContentVerifierBuilderProvider
import org.bouncycastle.openpgp.operator.bc.BcPGPKeyPair

/**
 * A commit signature made on a card, as anyone checking it will read it.
 *
 * Beside the signature itself an OpenPGP signature keeps the first two bytes of the hash it signs,
 * and a verifier compares them before anything else. Those two bytes once came from reading the
 * hash a second time — by which point it had been finished and started afresh — so every commit
 * signed on a card carried `e3 b0`, the start of the hash of nothing, whatever it said.
 */
class CardContentSignerTest {

  /** A card holding [signingKey]: it signs the DigestInfo it is sent, as a card would. */
  private class SigningTransport(private val signingKey: AsymmetricKeyParameter) : CardTransport {
    val signedDigestInfos = mutableListOf<ByteArray>()

    override val maxTransceiveLength = 261
    override val connection = CardConnection.USB
    override val isConnected = true

    override fun transceive(command: ByteArray, timeoutMs: Int): ByteArray {
      val ok = byteArrayOf(0x90.toByte(), 0x00)
      // PSO: COMPUTE DIGITAL SIGNATURE, sent as a short APDU: header, Lc, data, Le.
      if (command[1] == 0x2A.toByte() && command[2] == 0x9E.toByte()) {
        val length = command[4].toInt() and 0xff
        val digestInfo = command.copyOfRange(5, 5 + length)
        signedDigestInfos += digestInfo
        val rsa = PKCS1Encoding(RSABlindedEngine()).apply { init(true, signingKey) }
        return rsa.processBlock(digestInfo, 0, digestInfo.size) + ok
      }
      // Anything else, the application data asked for before signing included: an empty answer.
      return ok
    }

    override fun close() = Unit
  }

  @Test
  fun `keeps the first two bytes of the hash that was signed`() {
    val keyPair = rsaKeyPair()
    val transport = SigningTransport(keyPair.privateKey)
    val publicKey = keyPair.pgpKeyPair.publicKey
    val payload = "tree 4b825dc642cb6eb9a060e54bf8d69288fbee4904\n".toByteArray()

    val generator =
      PGPSignatureGenerator(
        OpenPgpCommitSigner.CardContentSignerBuilder(publicKey, OpenPgpCard(transport)),
        publicKey,
      )
    generator.init(
      PGPSignature.BINARY_DOCUMENT,
      PGPPrivateKey(publicKey.keyID, publicKey.publicKeyPacket, null),
    )
    generator.update(payload)
    val signature = generator.generate()

    // The DigestInfo the card was handed ends in the SHA-256 it signed.
    val signedHash = transport.signedDigestInfos.single().takeLast(32).toByteArray()
    assertContentEquals(signedHash.copyOf(2), signature.digestPrefix)
    assertTrue(
      !signature.digestPrefix.contentEquals(EMPTY_SHA256.copyOf(2)),
      "the prefix is the hash of nothing",
    )

    signature.init(BcPGPContentVerifierBuilderProvider(), publicKey)
    signature.update(payload)
    assertTrue(signature.verify())
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
    val EMPTY_SHA256: ByteArray = MessageDigest.getInstance("SHA-256").digest(byteArrayOf())
  }
}
