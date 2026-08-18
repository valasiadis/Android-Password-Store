/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.passwordstore.util.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A card whose KDF-DO is set expects the derived hash of the PIN rather than the PIN, and gets one
 * VERIFY to be right about it. The vectors below come from an independent implementation of RFC
 * 4880 sec. 3.7.1.3, cross-checked against the shape of libgcrypt's `openpgp_s2k` — the derivation
 * gpg performs for the same card — since the only other way to find out is to spend a retry.
 */
class OpenPgpCardKdfTest {

  private val salt = byteArrayOf(0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18)
  private val pin = "123456".toByteArray(Charsets.UTF_8)

  private fun derive(iterations: Int, digest: String = "SHA-256") =
    OpenPgpNfcCard.deriveKdfPin(
        pin.copyOf(),
        KdfParameters(digestAlgorithm = digest, iterations = iterations, salt = salt),
      )
      .joinToString("") { "%02x".format(it) }

  @Test
  fun `derives the S2K hash gpg would send`() {
    assertEquals(
      "6d353fb909948358c6d39f167e6eb39c69cb6844bf0c4f5f8a6af8f521a3daac",
      derive(100_000),
    )
    assertEquals(
      "c6f8f4d9006be91bdb21d593591c37b6719b8b9f765642d9eba232c16eff4d30",
      derive(1_000),
    )
  }

  @Test
  fun `hashes salt and pin whole even when the count is smaller`() {
    // RFC 4880: a count below the size of salt+passphrase is treated as though it were that size.
    val whole = "4c5cb27b3cf020de73d0b8a4298235280694529268ea958c813572041863a813"
    assertEquals(whole, derive(0))
    assertEquals(whole, derive(7))
    assertEquals(whole, derive(salt.size + pin.size))
  }

  @Test
  fun `derives with SHA-512 when the card asks for it`() {
    assertEquals(
      "2289ea9be2d66f0e3ad8d5fe856136ae8f861cd39f9eb3b3256ea5f2bc1418df" +
        "368927825c87c3a4dc8b7ac7272079fe797b0021d970489aa9688d37e8b38690",
      derive(100_000, "SHA-512"),
    )
  }

  @Test
  fun `reads an iterated-salted KDF-DO`() {
    val kdf = OpenPgpNfcCard.parseKdf(kdfDo(algorithm = 0x03, hash = 0x08, iterations = 100_000))
    requireNotNull(kdf)
    assertEquals("SHA-256", kdf.digestAlgorithm)
    assertEquals(100_000, kdf.iterations)
    assertContentEquals(salt, kdf.salt)
  }

  @Test
  fun `treats a card without a KDF as one that wants the PIN itself`() {
    // Algorithm 0x00 is "none": the DO is present, but the PIN travels as the user typed it.
    assertNull(OpenPgpNfcCard.parseKdf(kdfDo(algorithm = 0x00, hash = 0x08, iterations = 100_000)))
    assertNull(OpenPgpNfcCard.parseKdf(byteArrayOf()))
    // An unknown hash is not something to guess at: guessing costs a retry.
    assertNull(OpenPgpNfcCard.parseKdf(kdfDo(algorithm = 0x03, hash = 0x63, iterations = 100_000)))
    // Algorithm and hash present, but no salt to derive with.
    assertNull(
      OpenPgpNfcCard.parseKdf(
        byteArrayOf(
          0x81.toByte(),
          0x01,
          0x03,
          0x82.toByte(),
          0x01,
          0x08,
          0x83.toByte(),
          0x04,
          0x00,
          0x01,
          0x86.toByte(),
          0xA0.toByte(),
        )
      )
    )
  }

  /** The `81`/`82`/`83`/`84` TLVs of DO `00F9`, as a card lays them out. */
  private fun kdfDo(algorithm: Int, hash: Int, iterations: Int): ByteArray =
    byteArrayOf(0x81.toByte(), 0x01, algorithm.toByte()) +
      byteArrayOf(0x82.toByte(), 0x01, hash.toByte()) +
      byteArrayOf(
        0x83.toByte(),
        0x04,
        (iterations ushr 24).toByte(),
        (iterations ushr 16).toByte(),
        (iterations ushr 8).toByte(),
        iterations.toByte(),
      ) +
      byteArrayOf(0x84.toByte(), salt.size.toByte()) +
      salt
}
