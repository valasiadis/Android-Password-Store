/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.pgp

import androidx.annotation.StringRes
import app.passwordstore.R
import java.time.Instant
import java.util.Date
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.nist.NISTNamedCurves
import org.bouncycastle.asn1.teletrust.TeleTrusTNamedCurves
import org.bouncycastle.asn1.x9.ECNamedCurveTable
import org.bouncycastle.bcpg.ECPublicBCPGKey
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags
import org.bouncycastle.bcpg.S2K
import org.bouncycastle.bcpg.sig.KeyFlags
import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.api.OpenPGPCertificate
import org.bouncycastle.openpgp.api.OpenPGPKey

/** What the key info screen shows about a key: who it belongs to, and each of its parts. */
internal data class KeyDetails(val userIds: List<String>, val parts: List<KeyPart>) {

  /** The primary key, which is the key's identity; it is always listed first. */
  val primary: KeyPart
    get() = parts.first()

  val subkeys: List<KeyPart>
    get() = parts.drop(1)

  companion object {

    /**
     * [cardFingerprints] are the parts a smartcard was set up as holding: a key read from a card is
     * only its public half, and whether a part is on a card is not written into the key itself.
     */
    // BouncyCastle gives a key's times as Dates and asks for the moment to judge it at as one; they
    // are turned into Instants here, and go no further.
    @Suppress("DenyListedApi")
    fun of(cert: OpenPGPCertificate, cardFingerprints: List<ByteArray> = emptyList()): KeyDetails {
      val now = Date.from(Instant.now())
      val authKeys =
        cert.getComponentKeysWithFlag(now, KeyFlags.AUTHENTICATION).map { it.keyIdentifier }
      val secretKeys = (cert as? OpenPGPKey)?.secretKeys.orEmpty()
      val parts =
        cert.keys.map { key ->
          val publicKey = key.pgpPublicKey
          val usages = buildList {
            if (key.isCertificationKey(now)) add(Usage.Certify)
            if (key.isSigningKey(now)) add(Usage.Sign)
            if (key.isEncryptionKey(now)) add(Usage.Encrypt)
            if (key.keyIdentifier in authKeys) add(Usage.Authenticate)
          }
          val secret = secretKeys[key.keyIdentifier]?.pgpSecretKey
          val location =
            when {
              cardFingerprints.any { it.contentEquals(publicKey.fingerprint) } -> Location.OnCard
              secret == null -> Location.Absent
              !secret.isPrivateKeyEmpty -> Location.OnDevice
              secret.s2K?.let {
                it.type == S2K.GNU_DUMMY_S2K &&
                  it.protectionMode == S2K.GNU_PROTECTION_MODE_DIVERT_TO_CARD
              } == true -> Location.OnCard
              else -> Location.Absent
            }
          KeyPart(
            keyId = "0x%016X".format(publicKey.keyID),
            fingerprint = formatFingerprint(publicKey.fingerprint),
            algorithm = algorithmName(publicKey),
            usages = usages,
            created = publicKey.creationTime.toInstant(),
            expires =
              publicKey.validSeconds
                .takeIf { it > 0 }
                ?.let { publicKey.creationTime.toInstant().plusSeconds(it) },
            isRevoked = publicKey.hasRevocation(),
            location = location,
          )
        }
      return KeyDetails(userIds = cert.allUserIds.map { it.userId }, parts = parts)
    }

    /** Groups of four, as GnuPG prints a fingerprint, so it can be read out and compared. */
    fun formatFingerprint(fingerprint: ByteArray): String =
      fingerprint
        .joinToString(separator = "") { "%02X".format(it.toInt() and BYTE_MASK) }
        .chunked(FINGERPRINT_GROUP)
        .joinToString(separator = " ")

    /**
     * The algorithm as people name it: RSA with its size, an elliptic curve by its own name rather
     * than by the protocol that uses it, which is what a key is told apart by.
     */
    // The RSA encrypt-only and sign-only tags and the ElGamal general one are deprecated for making
    // keys, but keys made with them long ago are still read, and still need a name.
    @Suppress("DEPRECATION")
    private fun algorithmName(key: PGPPublicKey): String =
      when (key.algorithm) {
        PublicKeyAlgorithmTags.RSA_GENERAL,
        PublicKeyAlgorithmTags.RSA_ENCRYPT,
        PublicKeyAlgorithmTags.RSA_SIGN -> "RSA ${key.bitStrength}"
        PublicKeyAlgorithmTags.DSA -> "DSA ${key.bitStrength}"
        PublicKeyAlgorithmTags.ELGAMAL_ENCRYPT,
        PublicKeyAlgorithmTags.ELGAMAL_GENERAL -> "ElGamal ${key.bitStrength}"
        PublicKeyAlgorithmTags.ECDH,
        PublicKeyAlgorithmTags.ECDSA,
        PublicKeyAlgorithmTags.EDDSA_LEGACY ->
          (key.publicKeyPacket.key as? ECPublicBCPGKey)?.curveOID?.let(::curveName) ?: "ECC"
        PublicKeyAlgorithmTags.X25519 -> "X25519"
        PublicKeyAlgorithmTags.X448 -> "X448"
        PublicKeyAlgorithmTags.Ed25519 -> "Ed25519"
        PublicKeyAlgorithmTags.Ed448 -> "Ed448"
        else -> "#${key.algorithm}"
      }

    private fun curveName(oid: ASN1ObjectIdentifier): String =
      when (oid.id) {
        // The curves OpenPGP used before RFC 9580 gave them algorithms of their own, which no table
        // of curve names knows under these identifiers.
        "1.3.6.1.4.1.3029.1.5.1" -> "Curve25519"
        "1.3.6.1.4.1.11591.15.1" -> "Ed25519"
        "1.3.101.110" -> "X25519"
        "1.3.101.111" -> "X448"
        "1.3.101.112" -> "Ed25519"
        "1.3.101.113" -> "Ed448"
        else ->
          NISTNamedCurves.getName(oid)
            ?: TeleTrusTNamedCurves.getName(oid)
            ?: ECNamedCurveTable.getName(oid)
            ?: oid.id
      }

    private const val BYTE_MASK = 0xFF
    private const val FINGERPRINT_GROUP = 4
  }
}

/** The primary key or one of the subkeys. */
internal data class KeyPart(
  val keyId: String,
  val fingerprint: String,
  val algorithm: String,
  val usages: List<Usage>,
  val created: Instant,
  val expires: Instant?,
  val isRevoked: Boolean,
  val location: Location,
)

/** What a part of a key is for, in the order GnuPG lists the letters. */
internal enum class Usage(@StringRes val label: Int) {
  Certify(R.string.pgp_key_usage_certify),
  Sign(R.string.pgp_key_usage_sign),
  Encrypt(R.string.pgp_key_usage_encrypt),
  Authenticate(R.string.pgp_key_usage_authenticate),
}

/** Where the secret half of a part of a key is, which decides what it can do here. */
internal enum class Location(@StringRes val label: Int) {
  OnDevice(R.string.pgp_key_location_device),
  OnCard(R.string.pgp_key_location_card),
  Absent(R.string.pgp_key_location_absent),
}
