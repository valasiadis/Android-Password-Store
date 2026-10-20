/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.passwordstore.util.crypto

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest

/**
 * Telling a card that has been lifted from one the wire lost sight of for a moment.
 *
 * The wire's answer only changes with time, so a glitch is modelled as a stretch of time during
 * which it says there is no card. A glitch used to be read twice in a row with nothing in between,
 * which counted it as the card leaving — and reader mode was let go while the card was still on the
 * phone, for the platform to put the card's own URL on screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CardAbsenceTest {

  /** The wire as it is at the test's current moment: missing from [from] until [until]. */
  private fun TestScope.missingBetween(from: Long, until: Long): () -> Boolean = {
    currentTime !in from until until
  }

  @Test
  fun `a moment's glitch is not a card that has gone`() = runTest {
    val gone = awaitCardAbsence(missingBetween(0, 10), timeoutMs = 1_000, pollIntervalMs = 150)
    assertFalse(gone)
  }

  @Test
  fun `a card that stays away is noticed`() = runTest {
    val gone =
      awaitCardAbsence(missingBetween(300, Long.MAX_VALUE), timeoutMs = 1_000, pollIntervalMs = 150)
    assertTrue(gone)
  }

  @Test
  fun `a card left on the phone is waited for no longer than asked`() = runTest {
    val gone = awaitCardAbsence({ true }, timeoutMs = 1_000, pollIntervalMs = 150)
    assertFalse(gone)
    assertTrue(currentTime <= 1_000)
  }
}
