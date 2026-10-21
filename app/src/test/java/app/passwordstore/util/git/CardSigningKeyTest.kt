/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.passwordstore.util.git

import app.passwordstore.crypto.KeyUtils
import app.passwordstore.crypto.PGPKey
import java.security.SecureRandom
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags
import org.bouncycastle.bcpg.sig.KeyFlags
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.openpgp.PGPException
import org.bouncycastle.openpgp.PGPKeyPair
import org.bouncycastle.openpgp.PGPKeyRingGenerator
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.PGPSignatureSubpacketGenerator
import org.bouncycastle.openpgp.api.OpenPGPCertificate
import org.bouncycastle.openpgp.operator.bc.BcPGPContentSignerBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPDigestCalculatorProvider
import org.bouncycastle.openpgp.operator.bc.BcPGPKeyPair

/**
 * Which of a card's keys a commit is signed with.
 *
 * On a card set up with Curve25519, the signing key and the authentication key are both EdDSA, and
 * both are on the card: the fingerprints alone match either. A commit signed by the card's signing
 * key and labelled with the authentication key does not verify, and nothing says why.
 */
class CardSigningKeyTest {

  private class CardKeys(
    val cert: OpenPGPCertificate,
    val signing: ByteArray,
    val authentication: ByteArray,
  ) {
    val onCard = listOf(signing, authentication)
  }

  /**
   * A certify-only primary, then signing and authentication subkeys, in the order [authFirst] says.
   * Without [backSigned], the signing subkey lacks the signature it owes back over its binding, so
   * the certificate does not count it as one that signs.
   */
  private fun edDsaCardKey(authFirst: Boolean, backSigned: Boolean = true): CardKeys {
    val now = Date()
    fun pair(): PGPKeyPair =
      BcPGPKeyPair(
        PublicKeyAlgorithmTags.EDDSA_LEGACY,
        Ed25519KeyPairGenerator()
          .apply { init(Ed25519KeyGenerationParameters(SecureRandom())) }
          .generateKeyPair(),
        now,
      )
    fun flags(flags: Int) =
      PGPSignatureSubpacketGenerator().apply { setKeyFlags(false, flags) }.generate()
    val signer = {
      BcPGPContentSignerBuilder(PublicKeyAlgorithmTags.EDDSA_LEGACY, HashAlgorithmTags.SHA256)
    }
    val primary = pair()
    val signing = pair()
    val authentication = pair()
    val generator =
      PGPKeyRingGenerator(
        PGPSignature.POSITIVE_CERTIFICATION,
        primary,
        "Alice <alice@example.com>",
        BcPGPDigestCalculatorProvider().get(HashAlgorithmTags.SHA1),
        flags(KeyFlags.CERTIFY_OTHER),
        null,
        signer(),
        null,
      )
    val addSigning = {
      // A signing subkey carries a signature of its own back over its binding, or it is not one.
      if (backSigned) generator.addSubKey(signing, flags(KeyFlags.SIGN_DATA), null, signer())
      else generator.addSubKey(signing, flags(KeyFlags.SIGN_DATA), null)
    }
    val addAuthentication = {
      generator.addSubKey(authentication, flags(KeyFlags.AUTHENTICATION), null)
    }
    if (authFirst) {
      addAuthentication()
      addSigning()
    } else {
      addSigning()
      addAuthentication()
    }
    val cert =
      KeyUtils.tryParseCertificateOrKey(PGPKey(generator.generateSecretKeyRing().encoded))!!
    return CardKeys(cert, signing.publicKey.fingerprint, authentication.publicKey.fingerprint)
  }

  @Test
  fun `the key in the card's signature slot signs`() {
    val keys = edDsaCardKey(authFirst = true)
    val chosen = OpenPgpCommitSigner.findCardSigningKey(keys.cert, keys.onCard, slot = keys.signing)
    assertContentEquals(keys.signing, chosen.fingerprint)
  }

  @Test
  fun `without the slot, the key flagged for signing signs, wherever it is in the key`() {
    val keys = edDsaCardKey(authFirst = true)
    val chosen = OpenPgpCommitSigner.findCardSigningKey(keys.cert, keys.onCard, slot = null)
    assertContentEquals(keys.signing, chosen.fingerprint)
  }

  @Test
  fun `a card whose signature slot holds no key of this one's is turned away`() {
    val keys = edDsaCardKey(authFirst = false)
    val error =
      assertFailsWith<PGPException> {
        OpenPgpCommitSigner.findCardSigningKey(keys.cert, keys.onCard, slot = ByteArray(20) { 1 })
      }
    assertContains(error.message.orEmpty(), "does not hold this key's signing key")
  }

  @Test
  fun `a key with nothing flagged and two keys that could sign is not guessed at`() {
    val keys = edDsaCardKey(authFirst = true, backSigned = false)
    // Only the fingerprints are left to go on, and both of the card's EdDSA keys fit them.
    val error =
      assertFailsWith<PGPException> {
        OpenPgpCommitSigner.findCardSigningKey(keys.cert, keys.onCard, slot = null)
      }
    assertContains(error.message.orEmpty(), "Cannot tell which")
  }
}
