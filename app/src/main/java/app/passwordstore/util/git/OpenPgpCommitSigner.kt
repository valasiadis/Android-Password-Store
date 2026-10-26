/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.util.git

import androidx.fragment.app.FragmentActivity
import app.passwordstore.R
import app.passwordstore.crypto.KeyUtils
import app.passwordstore.crypto.PGPIdentifier
import app.passwordstore.crypto.PGPKey
import app.passwordstore.crypto.PGPKeyManager
import app.passwordstore.data.repo.PasswordRepository
import app.passwordstore.ui.dialogs.AppDialogBuilder
import app.passwordstore.util.coroutines.DispatcherProvider
import app.passwordstore.util.crypto.CardConnection
import app.passwordstore.util.crypto.CardReader
import app.passwordstore.util.crypto.OpenPgpCard
import app.passwordstore.util.crypto.OpenPgpCardPrompt
import app.passwordstore.util.crypto.OpenPgpCardStatusException
import app.passwordstore.util.crypto.OpenPgpSmartcardStore
import app.passwordstore.util.crypto.SmartcardOperationHandledException
import app.passwordstore.util.extensions.hideKeyboard
import app.passwordstore.util.extensions.wipe
import com.github.michaelbull.result.get
import com.github.michaelbull.result.runCatching
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.math.BigInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import logcat.asLog
import logcat.logcat
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DERNull
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.edec.EdECObjectIdentifiers
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.DigestInfo
import org.bouncycastle.bcpg.ArmoredOutputStream
import org.bouncycastle.bcpg.EdDSAPublicBCPGKey
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags
import org.bouncycastle.openpgp.PGPException
import org.bouncycastle.openpgp.PGPPrivateKey
import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.PGPSecretKey
import org.bouncycastle.openpgp.PGPSecretKeyRingCollection
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.PGPSignatureGenerator
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.api.OpenPGPCertificate
import org.bouncycastle.openpgp.operator.PGPContentSigner
import org.bouncycastle.openpgp.operator.PGPContentSignerBuilder
import org.bouncycastle.openpgp.operator.bc.BcPBESecretKeyDecryptorBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPContentSignerBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPDigestCalculatorProvider
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator
import org.eclipse.jgit.api.errors.CanceledException
import org.eclipse.jgit.lib.CommitBuilder
import org.eclipse.jgit.lib.GpgSignature
import org.eclipse.jgit.lib.GpgSigner
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.transport.CredentialsProvider

class OpenPgpCommitSigner(
  private val activity: FragmentActivity,
  private val keyManager: PGPKeyManager,
  private val smartcardStore: OpenPgpSmartcardStore,
  private val dispatcherProvider: DispatcherProvider,
) : GpgSigner() {

  override fun sign(
    commit: CommitBuilder,
    gpgSigningKey: String?,
    committer: PersonIdent,
    credentialsProvider: CredentialsProvider?,
  ) {
    val signingKey = resolveSigningKey(gpgSigningKey)
    val primaryKeyId =
      KeyUtils.tryGetKeyId(signingKey)
        ?: throw PGPException("Cannot determine OpenPGP signing key ID")
    val payload = commit.build()
    val signature =
      if (smartcardStore.hasAssociation(primaryKeyId)) {
        signWithSmartcard(signingKey, primaryKeyId, payload)
      } else {
        signWithSecretKey(signingKey, payload)
      }
    // A null signature means the user chose to proceed with an unsigned commit.
    if (signature != null) {
      commit.setGpgSignature(GpgSignature(signature))
    }
  }

  override fun canLocateSigningKey(
    gpgSigningKey: String?,
    committer: PersonIdent,
    credentialsProvider: CredentialsProvider?,
  ): Boolean = runCatching { resolveSigningKey(gpgSigningKey) }.isOk

  private fun resolveSigningKey(gpgSigningKey: String?): PGPKey {
    val identifiers =
      gpgSigningKey?.let(PGPIdentifier::fromString)?.let(::listOf) ?: rootGpgIdentifiers()
    identifiers.forEach { identifier ->
      keyManager.getKeyById(identifier).get()?.let {
        return it
      }
    }
    throw PGPException("No OpenPGP key from .gpg-id is available for Git commit signing")
  }

  private fun rootGpgIdentifiers(): List<PGPIdentifier> {
    val gpgIdFile = PasswordRepository.getRepositoryDirectory().resolve(".gpg-id")
    if (!gpgIdFile.isFile) throw PGPException("No root .gpg-id found for Git commit signing")
    return gpgIdFile
      .readLines()
      .map { it.substringBefore('#').substringBefore('!').trim() }
      .filter { it.isNotEmpty() && it != "gpg-id" }
      .mapNotNull(PGPIdentifier::fromString)
  }

  private fun signWithSecretKey(key: PGPKey, payload: ByteArray): ByteArray {
    val secretKey = findSecretSigningKey(key)
    if (secretKey.isPrivateKeyEmpty) {
      throw PGPException("Git commit signing key is a smartcard stub without a card association")
    }
    // A key stored without a passphrase has none to ask for, as decryption already recognises.
    // Asking anyway teaches the user to answer a prompt that no answer can satisfy.
    val passphrase =
      if (secretKey.keyEncryptionAlgorithm == SymmetricKeyAlgorithmTags.NULL) null
      else
        runBlocking {
          OpenPgpCardPrompt(activity, R.string.git_signing_passphrase_title, dispatcherProvider)
            .askSecret(
              titleRes = R.string.git_signing_passphrase_title,
              hintRes = R.string.ssh_keygen_passphrase,
              identityLabel = identityLabel(key),
            )
        }
          ?.secret ?: throw CanceledException(activity.getString(R.string.dialog_cancel))
    try {
      val decryptor = passphrase?.let {
        BcPBESecretKeyDecryptorBuilder(BcPGPDigestCalculatorProvider()).build(it)
      }
      val privateKey = secretKey.extractPrivateKey(decryptor)
      return buildDetachedSignature(
        secretKey.publicKey,
        BcPGPContentSignerBuilder(secretKey.publicKey.algorithm, HashAlgorithmTags.SHA256),
        privateKey,
        payload,
      )
    } finally {
      passphrase?.wipe()
    }
  }

  /**
   * Signs [payload] with the OpenPGP smartcard associated with [primaryKeyId].
   *
   * Returns `null` when the user explicitly opts to proceed with an unsigned commit, in which case
   * the caller must not attach a signature to the commit.
   */
  private fun signWithSmartcard(
    key: PGPKey,
    primaryKeyId: PGPIdentifier.KeyId,
    payload: ByteArray,
  ): ByteArray? {
    // Collapse (and unfocus) the entry form's keyboard up front so it can't linger behind the
    // signing dialogs or resurface over the status/error UI when they close.
    activity.runOnUiThread { activity.hideKeyboard() }
    when (confirmSmartcardSigning()) {
      SigningChoice.CANCEL -> throw CanceledException(activity.getString(R.string.dialog_cancel))
      SigningChoice.SKIP -> return null
      SigningChoice.SIGN -> {}
    }
    // The shared prompt keeps reader mode enabled for the whole operation, shows the reused
    // present/hold-card dialog, and runs the card exchange on the card's own thread via
    // OpenPgpCardPrompt.runWithPin. Any smartcard failure is reported to the user in a dialog
    // (never a snackbar) by the outer catch below.
    val prompt = OpenPgpCardPrompt(activity, R.string.openpgp_card_sign_title, dispatcherProvider)
    var reader: CardReader? = null
    // Reader mode is released via the removal watcher (which disables it once the card leaves) on
    // every terminal outcome — success or failure — so the finally only closes it if we exit
    // unexpectedly.
    var readerHandedOff = false
    // Where the card that failed was, so the report below can be about that card rather than about
    // cards in general. The failure itself is thrown on and caught outside this when.
    var failedConnection: CardConnection? = null
    try {
      // Namespaced so the signing PIN cache is kept separate from the decryption PIN cache.
      val cacheKey = "sign:$primaryKeyId"
      val cardFingerprints = smartcardStore.getFingerprints(primaryKeyId)
      // Checked before a card is asked for, so that a key with nothing on it that can sign is
      // said to be one without the user being sent to fetch the card first. Which of its keys signs
      // is settled once the card is here and can say.
      val cert = cardKeyCertificate(key)
      requireSomeCardSigningKey(cert, cardFingerprints)
      val activeReader =
        runBlocking { prompt.createReader() }
          ?: throw IOException(activity.getString(R.string.openpgp_card_reader_unavailable))
      reader = activeReader
      val outcome = runBlocking {
        prompt.runWithPin(
          reader = activeReader,
          cacheKey = cacheKey,
          pinTitleRes = R.string.git_signing_card_pin_title,
          pinHintRes = R.string.openpgp_card_pin_hint,
          identityLabel = identityLabel(key),
          pinMode = OpenPgpCardPrompt.PinMode.SIGNATURE,
        ) { card, currentPin ->
          // The whole card exchange (applet select -> verify -> sign) runs on a single thread
          // with no hop, so a genuine wrong PIN reliably comes back as a card status word (e.g.
          // 63 Cx) rather than a transceive error caused by racing the NFC presence check.
          // Which key signs is the card's to say: its signature slot, read before the PIN.
          val publicKey = findCardSigningKey(cert, cardFingerprints, slot = cardSignatureSlot(card))
          card.verifySignaturePin(currentPin)
          val privateKey = PGPPrivateKey(publicKey.keyID, publicKey.publicKeyPacket, null)
          buildDetachedSignature(
            publicKey,
            CardContentSignerBuilder(publicKey, card),
            privateKey,
            payload,
          )
        }
      }
      when (outcome) {
        is OpenPgpCardPrompt.CardOutcome.Success -> {
          readerHandedOff = true
          val signature = outcome.value
          // Block until the card is lifted, rather than watching for it in the background. What
          // asked for this signature was a save, and a save that has been committed closes the
          // editor immediately — taking the activity, its lifecycle scope, and with it any watcher
          // running there. Reader mode would then end with the card still on the phone, and the
          // platform would dispatch its NDEF URL as a pop-up over whatever came next. Holding here
          // keeps the activity foreground until the user lifts the card, as the SSH path does for
          // the same reason.
          runBlocking { prompt.awaitCardRemoval(outcome.card, activeReader) }
          return signature
        }
        OpenPgpCardPrompt.CardOutcome.Cancelled -> {
          readerHandedOff = true
          prompt.releaseReaderWhenCardRemoved(null, activeReader)
          throw CanceledException(activity.getString(R.string.dialog_cancel))
        }
        is OpenPgpCardPrompt.CardOutcome.Blocked -> {
          // Hold reader mode until the card is lifted, then abort - the outer catch reports it.
          readerHandedOff = true
          prompt.releaseReaderWhenCardRemoved(outcome.card, activeReader)
          throw PGPException(activity.getString(R.string.openpgp_card_pin_blocked))
        }
        is OpenPgpCardPrompt.CardOutcome.Failed -> {
          readerHandedOff = true
          failedConnection = outcome.card?.connection
          prompt.releaseReaderWhenCardRemoved(outcome.card, activeReader)
          throw outcome.error
        }
      }
    } catch (e: Throwable) {
      // Cancellation and already-reported failures propagate untouched; every other smartcard
      // failure is reported in a dialog (never a snackbar), then marked handled.
      if (e is CanceledException || OpenPgpCardPrompt.isHandled(e)) throw e
      runBlocking {
        prompt.dismissDialog()
        prompt.showError(
          R.string.error,
          prompt.cardFailureMessage(e, failedConnection).ifBlank {
            activity.getString(R.string.git_unknown_error)
          },
        )
      }
      throw SmartcardOperationHandledException(e.message)
    } finally {
      runBlocking { prompt.dismissDialog() }
      if (!readerHandedOff && reader != null) prompt.releaseReaderWhenCardRemoved(null, reader)
    }
  }

  private enum class SigningChoice {
    SIGN,
    SKIP,
    CANCEL,
  }

  private fun confirmSmartcardSigning(): SigningChoice {
    if (activity.isFinishing || activity.isDestroyed) return SigningChoice.CANCEL
    val choice = AtomicReference(SigningChoice.CANCEL)
    val latch = CountDownLatch(1)
    val error = AtomicReference<Throwable?>(null)
    activity.runOnUiThread {
      try {
        val dialog =
          AppDialogBuilder(activity)
            .setTitle(R.string.git_signing_card_title)
            .setMessage(R.string.git_signing_confirm_message)
            .setPositiveButton(R.string.git_signing_confirm_positive) { _, _ ->
              choice.set(SigningChoice.SIGN)
              latch.countDown()
            }
            .setNegativeButton(R.string.git_signing_confirm_unsigned) { _, _ ->
              choice.set(SigningChoice.SKIP)
              latch.countDown()
            }
            // Neither answer can be avoided: a commit either carries a signature or does not,
            // and dismissing the question used to abandon the commit itself — which is not what
            // tapping beside a dialog means anywhere else in the app.
            .setCancelable(false)
            .show()
        // "Commit without signing" stands quieter than "Sign", as every dialog's second answer
        // does, so it does not invite a tap meant for signing.
        dialog.setCanceledOnTouchOutside(false)
      } catch (t: Throwable) {
        error.set(t)
        latch.countDown()
      }
    }
    latch.await()
    error.get()?.let { logcat { it.asLog() } }
    return choice.get()
  }

  /**
   * Short label naming the signing key, so the passphrase/PIN prompt shows which key it unlocks.
   */
  private fun identityLabel(key: PGPKey): String? =
    KeyUtils.tryGetUserId(key)?.toString()?.takeIf { it.isNotBlank() && it != "null" }
      ?: KeyUtils.tryGetKeyId(key)?.toString()

  private fun findSecretSigningKey(key: PGPKey): PGPSecretKey {
    val rings =
      PGPSecretKeyRingCollection(
        PGPUtil.getDecoderStream(key.contents.inputStream()),
        JcaKeyFingerprintCalculator(),
      )
    return rings.keyRings
      .asSequence()
      .flatMap { it.secretKeys.asSequence() }
      .firstOrNull { it.isSigningKey }
      ?: throw PGPException("No signing-capable OpenPGP secret key found")
  }

  private fun cardKeyCertificate(key: PGPKey): OpenPGPCertificate =
    KeyUtils.tryParseCertificateOrKey(key) ?: throw PGPException("Invalid PGP key")

  /**
   * The fingerprint in the card's signature slot, or null where the card will not say or the slot
   * is empty. A card that cannot be reached is not one that will not say, and that failure goes on.
   */
  private fun cardSignatureSlot(card: OpenPgpCard): ByteArray? =
    try {
      card.readKeySlots().signature
    } catch (e: OpenPgpCardStatusException) {
      null
    }

  private fun buildDetachedSignature(
    publicKey: PGPPublicKey,
    signerBuilder: PGPContentSignerBuilder,
    privateKey: PGPPrivateKey,
    payload: ByteArray,
  ): ByteArray {
    val generator = PGPSignatureGenerator(signerBuilder, publicKey)
    generator.init(PGPSignature.BINARY_DOCUMENT, privateKey)
    generator.update(payload)
    val out = ByteArrayOutputStream()
    ArmoredOutputStream(out).use { armored -> generator.generate().encode(armored) }
    return out.toByteArray()
  }

  // The PIN is verified explicitly (see signWithSmartcard) before this builder runs, so it only has
  // to compute the signature.
  internal class CardContentSignerBuilder(
    private val publicKey: PGPPublicKey,
    private val card: OpenPgpCard,
  ) : PGPContentSignerBuilder {

    override fun build(signatureType: Int, privateKey: PGPPrivateKey): PGPContentSigner {
      if (publicKey.algorithm !in CARD_SIGNING_ALGORITHMS || isEd448(publicKey)) {
        throw PGPException(
          "OpenPGP card commit signing supports RSA, Ed25519 and ECDSA card keys only"
        )
      }
      val hashAlgorithm = hashAlgorithmFor(publicKey)
      val digestCalculator = BcPGPDigestCalculatorProvider().get(hashAlgorithm)
      // Reading the calculator's digest finishes the hash and starts a new one, and BouncyCastle
      // asks for it twice: for the signature, then for the two bytes of it kept beside the
      // signature. Read twice, the second answer is the hash of nothing — a prefix that matches no
      // signed commit — so it is read once, after everything has been written.
      val digest by lazy { digestCalculator.digest }
      return object : PGPContentSigner {
        override fun getOutputStream(): OutputStream = digestCalculator.outputStream

        override fun getSignature(): ByteArray =
          when (publicKey.algorithm) {
            // EdDSA signs the hash as it is, and the card gives back R and S, 32 bytes each, which
            // is what BouncyCastle splits into the signature's two values.
            PublicKeyAlgorithmTags.EDDSA_LEGACY,
            PublicKeyAlgorithmTags.Ed25519 ->
              card.computeDigitalSignature(digest, expectedLength = EDDSA_SIGNATURE_LENGTH)
            // ECDSA signs the hash as it is too, but the card gives R and S side by side while
            // BouncyCastle reads them as the DER sequence a software signer makes.
            PublicKeyAlgorithmTags.ECDSA -> {
              val coordinateLength = (publicKey.bitStrength + 7) / 8
              val raw = card.computeDigitalSignature(digest, expectedLength = 2 * coordinateLength)
              if (raw.size != 2 * coordinateLength) {
                throw PGPException("The card's ECDSA signature is malformed")
              }
              val half = raw.size / 2
              DERSequence(
                  arrayOf(
                    ASN1Integer(BigInteger(1, raw.copyOfRange(0, half))),
                    ASN1Integer(BigInteger(1, raw.copyOfRange(half, raw.size))),
                  )
                )
                .encoded
            }
            // RSA is handed the DigestInfo, which the card pads and signs.
            else -> {
              val digestInfo =
                DigestInfo(
                    AlgorithmIdentifier(NISTObjectIdentifiers.id_sha256, DERNull.INSTANCE),
                    digest,
                  )
                  .encoded
              card.computeDigitalSignature(
                digestInfo,
                expectedLength = (publicKey.bitStrength + 7) / 8,
              )
            }
          }

        override fun getDigest(): ByteArray = digest.copyOf()

        override fun getType(): Int = signatureType

        override fun getHashAlgorithm(): Int = hashAlgorithm

        override fun getKeyAlgorithm(): Int = publicKey.algorithm

        override fun getKeyID(): Long = publicKey.keyID
      }
    }
  }

  companion object {
    // RSA_SIGN is a legacy OpenPGP algorithm id that BouncyCastle deprecates in favour of
    // RSA_GENERAL, but keys carrying the old tag still exist and must be recognized here.
    @Suppress("DEPRECATION")
    private val RSA_SIGNING_ALGORITHMS =
      setOf(PublicKeyAlgorithmTags.RSA_GENERAL, PublicKeyAlgorithmTags.RSA_SIGN)

    /** What a card can sign a commit with: RSA, and EdDSA and ECDSA over the curves cards hold. */
    private val CARD_SIGNING_ALGORITHMS =
      RSA_SIGNING_ALGORITHMS +
        setOf(
          PublicKeyAlgorithmTags.EDDSA_LEGACY,
          PublicKeyAlgorithmTags.Ed25519,
          PublicKeyAlgorithmTags.ECDSA,
        )

    /**
     * The key on the card that commits are signed with.
     *
     * A card holds three keys, and on one set up with Curve25519 the signing key and the
     * authentication key are both EdDSA: a commit signed by the card's signing key but labelled
     * with the other fails to verify, and says nothing about why. So the key is the one in the
     * card's signature [slot], where the card says. Where it does not, it is the key among the
     * card's that the certificate says signs data, or the only one of them that can sign at all —
     * and if that still leaves more than one, the user is told, rather than handed a signature that
     * will not verify.
     */
    internal fun findCardSigningKey(
      cert: OpenPGPCertificate,
      cardFingerprints: List<ByteArray>,
      slot: ByteArray?,
    ): PGPPublicKey {
      val keys = cert.keys.map { it.pgpPublicKey }
      if (slot != null) {
        val inSlot =
          keys.firstOrNull { it.fingerprint.contentEquals(slot) }
            ?: throw PGPException("The OpenPGP card presented does not hold this key's signing key")
        return inSlot.also(::requireCardCanSignWith)
      }
      fun PGPPublicKey.isOnCard() = cardFingerprints.any { it.contentEquals(fingerprint) }
      val flagged = cert.signingKeys.map { it.pgpPublicKey }.filter { it.isOnCard() }
      flagged.singleOrNull()?.let {
        return it.also(::requireCardCanSignWith)
      }
      val signing = keys.filter { it.isOnCard() && it.algorithm in CARD_SIGNING_ALGORITHMS }
      return when {
        flagged.size > 1 || signing.size > 1 ->
          throw PGPException(
            "Cannot tell which of this OpenPGP card's keys signs: present the card, so it can say"
          )
        signing.size == 1 -> signing.single().also(::requireCardCanSignWith)
        else -> throw PGPException("No signing key matching this OpenPGP card was found")
      }
    }

    /** Turns away a key with nothing among its card's keys that a commit could be signed with. */
    internal fun requireSomeCardSigningKey(
      cert: OpenPGPCertificate,
      cardFingerprints: List<ByteArray>,
    ) {
      val onCard =
        cert.keys
          .map { it.pgpPublicKey }
          .filter { key -> cardFingerprints.any { it.contentEquals(key.fingerprint) } }
      if (onCard.isEmpty())
        throw PGPException("No signing key matching this OpenPGP card was found")
      if (onCard.none { it.algorithm in CARD_SIGNING_ALGORITHMS && !isEd448(it) }) {
        requireCardCanSignWith(onCard.first())
      }
    }

    /** Turns away a card key whose kind a commit cannot be signed with on a card. */
    private fun requireCardCanSignWith(publicKey: PGPPublicKey) {
      if (publicKey.algorithm !in CARD_SIGNING_ALGORITHMS || isEd448(publicKey)) {
        throw PGPException(
          "This OpenPGP card's signing key is of a kind commits cannot be signed with on a card; " +
            "RSA, Ed25519 and ECDSA keys can be"
        )
      }
    }

    /** An Ed25519 signature: R and S, 32 bytes each. */
    private const val EDDSA_SIGNATURE_LENGTH = 64

    /**
     * The hash a card key signs with. RSA takes SHA-256, which is what the DigestInfo it is handed
     * names. ECDSA takes a hash as long as its curve, as GnuPG does and RFC 6637 asks: SHA-256 for
     * a 256-bit curve, SHA-384 for a 384-bit one, SHA-512 above. Ed25519 takes SHA-256, which is as
     * strong as the curve.
     */
    private fun hashAlgorithmFor(publicKey: PGPPublicKey): Int =
      when {
        publicKey.algorithm != PublicKeyAlgorithmTags.ECDSA -> HashAlgorithmTags.SHA256
        publicKey.bitStrength <= 256 -> HashAlgorithmTags.SHA256
        publicKey.bitStrength <= 384 -> HashAlgorithmTags.SHA384
        else -> HashAlgorithmTags.SHA512
      }

    /**
     * Whether [publicKey] is EdDSA on Ed448 — either the algorithm RFC 9580 gives it, or the older
     * EdDSA one, which names its curve inside the key. Signing that needs a 114-byte signature and
     * SHAKE256-strength hashing, which no card in common use offers and this does not do.
     */
    private fun isEd448(publicKey: PGPPublicKey): Boolean =
      publicKey.algorithm == PublicKeyAlgorithmTags.Ed448 ||
        (publicKey.publicKeyPacket.key as? EdDSAPublicBCPGKey)?.curveOID ==
          EdECObjectIdentifiers.id_Ed448
  }
}
