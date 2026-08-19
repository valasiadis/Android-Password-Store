/*
 * Copyright © 2014-2026 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.util.crypto

import android.text.InputType
import android.view.View
import android.view.WindowManager
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.FragmentActivity
import app.passwordstore.R
import app.passwordstore.databinding.DialogPasswordEntryBinding
import app.passwordstore.ui.crypto.BasePGPActivity
import app.passwordstore.ui.dialogs.CardPrompt
import app.passwordstore.ui.dialogs.outlined
import app.passwordstore.util.coroutines.DispatcherProvider
import app.passwordstore.util.extensions.hideKeyboard
import app.passwordstore.util.extensions.sharedPrefs
import app.passwordstore.util.extensions.wipe
import app.passwordstore.util.settings.PreferenceKeys
import com.github.michaelbull.result.get
import com.github.michaelbull.result.onErr
import com.github.michaelbull.result.runCatching
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import logcat.asLog
import logcat.logcat

/**
 * Drives the shared OpenPGP smartcard UX for a single card operation (commit signing, decryption,
 * …). It keeps NFC reader mode enabled for the whole operation via a single [CardReader], shows a
 * reusable "present card" / "keep the card on the phone" dialog, runs the card operation on the
 * card's own thread right after applet selection (so it can't race the NFC presence check), and —
 * on success — keeps reader mode on until the card is physically removed so the platform never
 * dispatches the card's NDEF URL.
 *
 * All UI-touching members are `suspend` so callers on the main thread (e.g. decryption) don't block
 * it; callers running off the main thread (e.g. commit signing) can wrap them in `runBlocking`.
 */
class OpenPgpCardPrompt(
  private val activity: FragmentActivity,
  @StringRes private val titleRes: Int,
  private val dispatcherProvider: DispatcherProvider,
) {

  private val cardPrompt = AtomicReference<CardPrompt?>(null)
  /** Set while the prompt is showing its tick, so nothing takes it down before it is seen. */
  private val finishing = AtomicBoolean(false)
  private val cardDialogCancel = AtomicReference<CompletableDeferred<Unit>?>(null)

  /**
   * Where a card exchange runs. Deliberately nobody's child: an exchange that has been given up on
   * is left to finish in its own time rather than holding up whoever gave up on it.
   */
  private val cardScope = CoroutineScope(SupervisorJob())

  /** Outcome of a single [attempt]. */
  sealed interface Attempt<out T> {
    /** The card this attempt ended up holding, if it got as far as connecting to one. */
    val card: OpenPgpCard?

    /** [card] is left open so reader mode can be released once it is physically removed. */
    class Success<T>(val value: T, override val card: OpenPgpCard) : Attempt<T>

    data object Cancelled : Attempt<Nothing> {
      override val card: OpenPgpCard? = null
    }

    /**
     * [card] is the connected card when the failure happened after connecting (else null); it is
     * left open so the caller can hold reader mode until the card is physically removed (terminal
     * failure) or close it to allow the user to present it again (retry).
     */
    class Error(val error: Throwable, override val card: OpenPgpCard?) : Attempt<Nothing>
  }

  /** Opens every way a card could reach this phone, for the length of the operation. */
  suspend fun createReader(): CardReader? =
    withContext(dispatcherProvider.main()) { openCardReaders(activity) }

  /**
   * Shows (or, on a retry, reuses and re-labels with [message]) the card dialog, awaits a card on
   * the already-open [reader], and runs [block] on it on the same thread, immediately after applet
   * selection. The dialog stays on screen for the whole exchange and for the next attempt; the
   * caller dismisses it via [dismissDialog] when the operation ends.
   *
   * The exchange runs on a scope of its own rather than as a child of the caller's, because
   * cancelling it is not something a coroutine can do: the thread is inside a blocking transceive
   * that runs until the card answers — which, for a card waiting to be touched, can be the better
   * part of a minute. A `coroutineScope` here would dutifully wait for that thread before
   * returning, so pressing cancel appeared to do nothing at all until the card was touched.
   * Cancelling instead takes the card away from the exchange, which ends it where it stands.
   */
  suspend fun <T> attempt(
    reader: CardReader,
    message: String,
    block: (OpenPgpCard) -> T,
  ): Attempt<T> {
    val cancel = CompletableDeferred<Unit>()
    cardDialogCancel.set(cancel)
    withContext(dispatcherProvider.main()) { showOrUpdateDialog(message, reader.connections) }
    // The card in hand, so that cancelling has something to close.
    val connected = AtomicReference<OpenPgpCard?>(null)
    // Whether the outcome is the caller's. Set by whichever of the two gets there first, so the
    // loser knows to clean up rather than hand over a card nobody will close.
    val delivered = AtomicBoolean(false)
    val attemptJob =
      cardScope.async(dispatcherProvider.io()) {
        val outcome =
          try {
            val card = reader.awaitCard { connection -> announceCardDetected(connection) }
            connected.set(card)
            val watch = watchForCardLeaving(card)
            // Only a card in the socket is known to be waiting for a finger. A card held against
            // the phone may have the same flag set and still not wait: the tap can be what
            // satisfies it, and then the operation simply runs — with the prompt telling the user
            // to touch a card that wants nothing of the sort. When such a card does wait, it says
            // so afterwards in its own words, which is what the refusal message is for.
            if (card.connection == CardConnection.USB) {
              card.onTouchRequired = { announceTouchRequired() }
            }
            try {
              Attempt.Success(block(card), card)
            } catch (e: Throwable) {
              if (e is CancellationException) {
                runCatching { card.close() }
                throw e
              }
              // Leave the card open; the caller closes it (retry) or holds reader mode until it is
              // removed (terminal failure).
              Attempt.Error(e, card)
            } finally {
              watch.cancel()
            }
          } catch (e: CancellationException) {
            throw e
          } catch (e: Throwable) {
            // The card wait itself failed: nothing was ever connected.
            Attempt.Error(e, null)
          }
        // Arriving after the user has given up means the card is nobody's, and closing it is the
        // last thing this exchange does.
        if (!delivered.compareAndSet(false, true)) {
          runCatching { outcome.card?.close() }
          throw CancellationException("The card answered after the operation was cancelled")
        }
        outcome
      }
    return try {
      select {
        attemptJob.onAwait { it }
        cancel.onAwait { abandon(attemptJob, connected, delivered) }
      }
    } catch (e: CancellationException) {
      // The caller went away — the screen was destroyed, say. The card exchange is not the
      // caller's any more either.
      abandon(attemptJob, connected, delivered)
      throw e
    }
  }

  /**
   * Watches, for as long as an exchange is running, for the card being taken away mid-way.
   *
   * A card lifted while it is being worked leaves the exchange waiting on an answer that will never
   * come, and nothing else notices until it times out — the best part of a minute during which the
   * prompt still says the card has been found, presenting it again does nothing, because nobody is
   * listening for a card any more, and the whole operation is simply stuck.
   *
   * So the wire is asked whether it still has a card, which costs nothing and reaches past the
   * exchange in flight, and the card is closed the moment it says no. That ends the exchange where
   * it stands, and the prompt goes back to asking for a card — which is the thing the user has in
   * their hand.
   */
  private fun watchForCardLeaving(card: OpenPgpCard): Job =
    cardScope.launch(dispatcherProvider.io()) {
      // Only over NFC, where a card can be taken away without anything else being noticed.
      if (card.connection != CardConnection.NFC) return@launch
      var consecutiveMisses = 0
      while (consecutiveMisses < 2) {
        delay(LIVENESS_POLL_INTERVAL_MS)
        if (card.isConnected) consecutiveMisses = 0 else consecutiveMisses++
      }
      logcat { "The card left in the middle of an operation; ending it rather than waiting it out" }
      runCatching { card.close() }
    }

  /**
   * Gives up on an exchange that is still running, and says so.
   *
   * Closing the card is what actually stops it: a card waiting to be touched holds its answer back
   * for as long as it likes, and the thread blocked on that answer cannot be interrupted. Closing
   * drops the connection under it, which ends the wait here and abandons the operation on the card
   * — so a touch that comes afterwards does nothing, rather than completing something the user has
   * already walked away from.
   */
  /**
   * Lets a card go without waiting for it.
   *
   * Closing one is I/O — powering it down, giving the interface back — and every place that does it
   * is somewhere the answer is of no interest: the card has already failed, or been given up on, or
   * been finished with. Done on the caller's thread it stops the screen for as long as a card that
   * is no longer answering takes to be let go, which is the whole of the delay between lifting a
   * card mid-operation and being told so.
   */
  private fun releaseCard(card: OpenPgpCard?) {
    val toClose = card ?: return
    cardScope.launch(dispatcherProvider.io()) { runCatching { toClose.close() } }
  }

  private fun <T> abandon(
    attemptJob: Deferred<Attempt<T>>,
    connected: AtomicReference<OpenPgpCard?>,
    delivered: AtomicBoolean,
  ): Attempt<T> {
    if (delivered.compareAndSet(false, true)) {
      releaseCard(connected.get())
      attemptJob.cancel()
    }
    return Attempt.Cancelled
  }

  /** The card has answered: say where it is, to leave it there, and that something is happening. */
  private fun announceCardDetected(connection: CardConnection) {
    activity.runOnUiThread {
      cardPrompt
        .get()
        ?.show(
          CardPrompt.State(
            mark = cardMark(connection),
            title = activity.getString(R.string.openpgp_card_hold_title),
            message = cardHoldMessage(activity, connection),
            working = true,
            cropped = cardMarkIsCropped(connection),
          )
        )
    }
  }

  /** The card is holding its answer back until a finger arrives: say so, rather than "working". */
  private fun announceTouchRequired() {
    activity.runOnUiThread {
      cardPrompt
        .get()
        ?.show(
          CardPrompt.State(
            mark = R.drawable.ic_touch_app_24dp,
            title = activity.getString(R.string.openpgp_card_touch_title),
            message = cardTouchMessage(activity),
            working = true,
            framed = false,
          )
        )
    }
  }

  /**
   * How a card names itself: the fingerprints of the keys it carries, read straight off it.
   *
   * A PIN belongs to a card, not to a PGP key, and the key ids a caller happens to be working with
   * are only a guess at which card will be presented. Reading this needs no PIN, so the card can be
   * identified before anything is verified against it — and a PIN that belongs to some other card
   * is never offered, which would spend one of its retries.
   */
  private fun cardIdentity(card: OpenPgpCard): String? = runCatching {
    card.readCardInfo().fingerprints
  }
    .get()
    ?.takeIf { it.isNotEmpty() }
    ?.joinToString(",") { fingerprint ->
      fingerprint.joinToString("") { byte -> "%02x".format(byte) }
    }

  /**
   * Where a card's PIN is filed: under the card itself where it could be identified, and otherwise
   * under whatever the caller named the operation, which is the best guess left.
   */
  private fun pinCacheKey(fallback: String, identity: String?): String =
    if (identity == null) fallback else "${fallback.substringBefore(':')}:card:$identity"

  /** Raised out of the card session to say which card is present and that its PIN is not known. */
  private class PinNotCached(val identity: String?) : Exception()

  /**
   * Where the PIN being offered to the card came from, which decides what may be done with it.
   *
   * Only a PIN typed at this prompt was asked about — the dialog is where the user says whether to
   * keep it — so only that one is ever written back to the cache. Only a PIN the caller handed in
   * is the caller's to forget when the card turns it down. And only a PIN this loop read out of the
   * cache itself is this loop's to wipe; the other two belong to [runWithPin]'s own `pin`, or to
   * whoever passed them in.
   */
  private enum class PinSource {
    TYPED,
    SEEDED,
    CACHED,
  }

  /** Which PW1 access slot a wrong-PIN retry counter should be read from. */
  enum class PinMode {
    /** PW1 mode 0x82 (decryption / INTERNAL AUTHENTICATE). */
    USER,
    /** PW1 mode 0x81 (PSO:CDS commit signing). */
    SIGNATURE,
  }

  /** Terminal outcome of [runWithPin]. Any [card] handed back is left open for the caller. */
  sealed interface CardOutcome<out T> {
    class Success<T>(val value: T, val card: OpenPgpCard) : CardOutcome<T>

    data object Cancelled : CardOutcome<Nothing>

    class Blocked(val card: OpenPgpCard?) : CardOutcome<Nothing>

    class Failed(val error: Throwable, val card: OpenPgpCard?) : CardOutcome<Nothing>
  }

  /**
   * Runs a full smartcard PIN-and-retry session against the already-open [reader], shared by
   * decryption, commit signing and SSH authentication.
   *
   * The PIN is seeded from [seedPin] (e.g. a biometric-unlocked value); failing that the card is
   * asked who it is and its own cached PIN is looked up under [cacheKey]; failing that the user is
   * prompted (with the OpenPGP-mandated [MIN_PIN_LENGTH] minimum). [block] verifies the PIN and
   * performs the card operation via [attempt]. On a rejected PIN the PIN is wiped and dropped from
   * the cache, and the card's own remaining-attempts counter is consulted -- [pinMode] selects the
   * slot -- to either re-prompt inline or report the card as [CardOutcome.Blocked]. A transient
   * transport error re-presents the card, saying how. A PIN typed here is cached only once [block]
   * fully succeeds, so a rejected PIN is never persisted.
   *
   * The present-card dialog is dismissed on success and the PIN is always wiped before returning.
   * Reader-mode release and turning each [CardOutcome] into a user-facing action stay with the
   * caller, since those differ per operation.
   */
  suspend fun <T> runWithPin(
    reader: CardReader,
    cacheKey: String,
    @StringRes pinTitleRes: Int,
    @StringRes pinHintRes: Int,
    identityLabel: String?,
    pinMode: PinMode,
    seedPin: CharArray? = null,
    /**
     * Run when the card rejects the PIN that came in as [seedPin], so the caller can drop whatever
     * it seeded that from. The cache this prompt owns is cleared on any rejection; a secret held
     * anywhere else is the caller's to forget, and one that is not forgotten is offered to the card
     * again on the next attempt — spending another retry on a PIN the user never typed.
     *
     * Not run for a PIN the user typed here, which was never stored anywhere else: a single
     * mistyped PIN is no reason to throw away a working secret and make them enrol it again.
     */
    onPinRejected: () -> Unit = {},
    block: (OpenPgpCard, CharArray) -> T,
  ): CardOutcome<T> {
    // Nothing is read from the cache up front: which card will be presented is not known until it
    // is, and a PIN fetched on a guess is a retry spent on the wrong card. The card is asked who it
    // is first, inside the same session, and only then is its own PIN looked up.
    var pin: CharArray? = seedPin?.takeIf { it.isNotEmpty() }
    var pinSource = PinSource.SEEDED
    var askFirst = false
    var cachePin = false
    var pinErrorMessage: String? = null
    val presentMessage = cardPresentMessage(activity, reader.connections)
    var cardMessage = presentMessage
    // Set inside the card session, read after it: what the card called itself.
    var presentedIdentity: String? = null
    // The one array this loop owns — a PIN it read out of the cache itself, which nothing else
    // holds a reference to and nothing else will wipe.
    var cachedCopy: CharArray? = null
    fun dropCachedCopy() {
      cachedCopy?.wipe()
      cachedCopy = null
    }
    try {
      while (true) {
        if (askFirst) {
          // Take the card dialog down while the PIN dialog is up so they don't stack.
          dismissDialog()
          val entry =
            askSecret(
              titleRes = pinTitleRes,
              hintRes = pinHintRes,
              showCacheOption = true,
              errorMessage = pinErrorMessage,
              minLength = MIN_PIN_LENGTH,
              identityLabel = identityLabel,
            ) ?: return CardOutcome.Cancelled
          pin = entry.secret
          pinSource = PinSource.TYPED
          cachePin = entry.cache
          pinErrorMessage = null
          cardMessage = presentMessage
          askFirst = false
        }
        // Nothing from the previous round is wanted any more, and a cached PIN left lying about is
        // a cached PIN in the heap for the life of the process.
        dropCachedCopy()
        val offered = pin
        when (
          val attempt =
            attempt(reader, cardMessage) { card ->
              val identity = cardIdentity(card)
              presentedIdentity = identity
              // What was typed or seeded takes precedence; otherwise this card's own PIN, if it
              // has one here. Neither means the card is known but its PIN is not, which is asked
              // for outside the session — the card cannot be held through a dialog.
              val usable =
                offered
                  ?: identity
                    ?.let { readCachedPin(pinCacheKey(cacheKey, it)) }
                    ?.also { cachedCopy = it }
                  ?: throw PinNotCached(identity)
              block(card, usable)
            }
        ) {
          is Attempt.Success -> {
            showSuccess()
            // Written back only for a PIN typed here, and only now that the whole operation has
            // succeeded, under the card that did it — the name it gave inside the session that
            // just worked, rather than a fresh question put to it afterwards. Asking again meant
            // one more exchange with a card the user has very likely already lifted, which is a
            // wait for the deadline to pass, with the tick on screen and nothing happening. A
            // seeded or cached PIN is already kept wherever it belongs, and putting one through
            // storeCachedPin with `cache` unset would clear this cache and switch the caching
            // preference off behind the user's back.
            if (pinSource == PinSource.TYPED && offered != null) {
              storeCachedPin(pinCacheKey(cacheKey, presentedIdentity), offered, cachePin)
            }
            return CardOutcome.Success(attempt.value, attempt.card)
          }
          Attempt.Cancelled -> return CardOutcome.Cancelled
          is Attempt.Error -> {
            val e = attempt.error
            if (e is PinNotCached) {
              // The card is known and has no PIN here: let it go, ask, and take it again.
              releaseCard(attempt.card)
              pin?.wipe()
              pin = null
              askFirst = true
              continue
            }
            if (isSmartcardPinFailure(e)) {
              // A rejected PIN must never be kept in the cache. Cleared under the card that turned
              // it down, which is where it was found.
              clearCachedPin(pinCacheKey(cacheKey, presentedIdentity))
              clearCachedPin(cacheKey)
              // Only what the caller handed in is the caller's to forget: a PIN typed here is held
              // nowhere else, and a cached one was only reached because the caller had nothing.
              if (pinSource == PinSource.SEEDED && offered != null) onPinRejected()
              dropCachedCopy()
              pin?.wipe()
              pin = null
              askFirst = true
              // A PIN the card would not even look at, because it was the wrong length for the
              // bounds it was set up with, costs no attempt — so it is re-prompted as what it is,
              // without a count that would have the user believe their retries are running out.
              if (isSmartcardPinFormatFailure(e)) {
                logcat {
                  "Card rejected the PIN's length at VERIFY " +
                    "(${smartcardStatusWord(e) ?: "no status word"}); no attempt was spent"
                }
                releaseCard(attempt.card)
                pinErrorMessage = activity.getString(R.string.openpgp_card_pin_bad_length)
                continue
              }
              // Trust the card's own retry counter; if the status word omitted it, ask the card
              // directly with a non-destructive status check so we learn whether it is now blocked.
              val reportedRemaining = smartcardPinRetriesRemaining(e)
              val remaining =
                reportedRemaining
                  ?: withContext(dispatcherProvider.io()) {
                    runCatching { readPinRetries(attempt.card, pinMode) }.get()
                  }
              // A report that says "the PIN is correct" is settled by where this count came from
              // and whether it moves: a rejection that carries its own counter has just spent an
              // attempt, while a count read back afterwards only says what the counter already
              // stood at.
              logcat {
                "Card turned the PIN down at VERIFY " +
                  "(${smartcardStatusWord(e) ?: "no status word"}), " +
                  "${remaining ?: "unknown"} attempts left " +
                  if (reportedRemaining != null) "per the rejection itself"
                  else "per a follow-up status check"
              }
              if (remaining == 0) return CardOutcome.Blocked(attempt.card)
              releaseCard(attempt.card)
              pinErrorMessage = wrongPinMessage(remaining)
              continue
            }
            // A transient hiccup on the way to the card never reaches the PIN counter: ask for the
            // card again, in the terms of wherever it was.
            if (isRetryableCardError(e)) {
              cardMessage = cardRetryMessage(activity, attempt.card?.connection)
              releaseCard(attempt.card)
              continue
            }
            // Reported as itself rather than as a wrong PIN. A card that answers here has taken the
            // PIN and refused the work that came after it, which no amount of re-typing changes.
            logcat {
              "Card operation failed, and not over the PIN " +
                "(${smartcardStatusWord(e) ?: e::class.java.simpleName}): ${e.asLog()}"
            }
            return CardOutcome.Failed(e, attempt.card)
          }
        }
      }
    } finally {
      pin?.wipe()
      dropCachedCopy()
    }
  }

  private fun readPinRetries(card: OpenPgpCard?, pinMode: PinMode): Int? =
    when (pinMode) {
      PinMode.USER -> card?.readUserPinRetries()
      PinMode.SIGNATURE -> card?.readSignaturePinRetries()
    }

  /**
   * What to tell the user about a card failure that was not the PIN being wrong.
   *
   * Named where the status word says something we can act on, and otherwise reported plainly as a
   * refusal with the status word attached — which is still honest, and still something a bug report
   * can be built from. What it never does is blame the PIN, since by the time this is reached the
   * card has either accepted the PIN or never been asked about it.
   */
  fun cardFailureMessage(error: Throwable?, connection: CardConnection? = null): String {
    val status = smartcardStatusWordPair(error)
    val (sw1, sw2) = status ?: return error?.message ?: activity.getString(R.string.error)
    return when {
      // Conditions of use not satisfied — on a card with UIF set, the touch that never came. A card
      // held against the phone cannot be touched while it is being read; one plugged in can, so the
      // user is told to do it rather than told it cannot be done.
      sw1 == 0x69 && sw2 == 0x85 ->
        activity.getString(
          if (connection == CardConnection.USB) R.string.openpgp_card_error_touch_required_usb
          else R.string.openpgp_card_error_touch_required
        )
      // Referenced data not found: nothing in the key slot the operation needs.
      sw1 == 0x6A && sw2 == 0x88 -> activity.getString(R.string.openpgp_card_error_no_key)
      // Security status not satisfied, raised by something that was not the VERIFY.
      sw1 == 0x69 && sw2 == 0x82 -> activity.getString(R.string.openpgp_card_error_not_allowed)
      else ->
        activity.getString(
          R.string.openpgp_card_error_generic,
          "%02x %02x".format(sw1, sw2),
        )
    }
  }

  private fun wrongPinMessage(remaining: Int?): String =
    if (remaining != null) {
      activity.resources.getQuantityString(
        R.plurals.openpgp_card_wrong_pin_remaining,
        remaining,
        remaining,
      )
    } else {
      activity.getString(R.string.openpgp_card_wrong_pin)
    }

  /**
   * Puts the prompt up asking for a card, or tells the one already up to ask again. Main thread.
   */
  private fun showOrUpdateDialog(message: String, connections: Set<CardConnection>) {
    // Collapse the soft keyboard left over from PIN entry so it doesn't cover the card prompt or
    // the status bar, and keep the prompt from resurrecting it.
    activity.hideKeyboard()
    val asking =
      CardPrompt.State(
        mark = cardMark(connections),
        title = activity.getString(titleRes),
        message = message,
        working = false,
        cropped = cardMarkIsCropped(connections),
      )
    val existing = cardPrompt.get()
    if (existing != null && existing.isShowing) {
      existing.show(asking)
      return
    }
    cardPrompt.set(CardPrompt.show(activity, asking) { cardDialogCancel.get()?.complete(Unit) })
  }

  suspend fun dismissDialog() {
    // A prompt on its way out with a tick showing is not taken down early; it takes itself down.
    if (finishing.get()) return
    val prompt = cardPrompt.getAndSet(null) ?: return
    withContext(dispatcherProvider.main()) { prompt.dismiss() }
  }

  /**
   * Marks the operation done, in the sheet that is already up and without taking it down.
   *
   * What follows a success is either nothing — the sheet goes, having said so — or being asked to
   * lift the card, and that is the same sheet saying something else rather than a second one
   * arriving after the first has left. Whoever ends the operation takes it down; until then
   * [dismissDialog] leaves it alone.
   */
  private suspend fun showSuccess() {
    val prompt = cardPrompt.get() ?: return
    finishing.set(true)
    withContext(dispatcherProvider.main()) {
      prompt.show(CardPrompt.done(activity, R.string.openpgp_card_done_title))
    }
  }

  /** Takes the prompt down, tick or no tick. */
  private suspend fun closePrompt() {
    finishing.set(false)
    val prompt = cardPrompt.getAndSet(null) ?: return
    withContext(dispatcherProvider.main()) { prompt.dismiss() }
  }

  class SecretEntry(val secret: CharArray, val cache: Boolean)

  /**
   * Prompts the user for a card PIN (or passphrase). When [showCacheOption] is true the dialog
   * offers a "keep until screen-off" checkbox; the caller decides whether to actually cache via
   * [storeCachedPin]. [errorMessage] is reported inline on the field (e.g. "Wrong PIN, N tries
   * left"). When [minLength] is > 0 the confirm button stays disabled until at least that many
   * characters are entered (the OpenPGP card spec mandates a 6-character minimum PIN). Returns
   * `null` if the user cancels.
   */
  suspend fun askSecret(
    @StringRes titleRes: Int,
    @StringRes hintRes: Int,
    showCacheOption: Boolean = false,
    errorMessage: String? = null,
    minLength: Int = 0,
    identityLabel: String? = null,
  ): SecretEntry? {
    if (activity.isFinishing || activity.isDestroyed) return null
    val showCache = showCacheOption && AESEncryption.isHardwareBacked()
    val cacheDefault =
      showCache && activity.sharedPrefs.getBoolean(PreferenceKeys.CACHE_PASSPHRASE, false)
    val result = CompletableDeferred<SecretEntry?>()
    withContext(dispatcherProvider.main()) {
      try {
        val binding = DialogPasswordEntryBinding.inflate(activity.layoutInflater)
        binding.passwordField.setHint(hintRes)
        // Tell the user which key/card this PIN unlocks.
        identityLabel?.let {
          binding.userIdList.text = it
          binding.userIdList.visibility = View.VISIBLE
        }
        binding.passwordEditText.inputType =
          InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        if (showCache) {
          binding.cacheEnabled.visibility = View.VISIBLE
          binding.cacheEnabled.setText(R.string.cache_openpgp_card_pin_until_screen_off)
          binding.cacheEnabled.isChecked = cacheDefault
        }
        val dialog =
          MaterialAlertDialogBuilder(activity)
            .outlined(activity)
            .setTitle(titleRes)
            .setView(binding.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
              val text = binding.passwordEditText.text
              val secret =
                text?.let { CharArray(it.length) { index -> it[index] } } ?: charArrayOf()
              text?.clear()
              result.complete(SecretEntry(secret, showCache && binding.cacheEnabled.isChecked))
            }
            .setNegativeButton(R.string.dialog_cancel) { _, _ -> result.complete(null) }
            .setOnCancelListener { result.complete(null) }
            .show()
        // The error and the min-length hint share the caption area below the field, and the error
        // (e.g. "Wrong PIN, N left") takes priority; only fall back to the hint when there is none.
        when {
          errorMessage != null -> binding.passwordField.error = errorMessage
          minLength > 0 ->
            binding.passwordField.helperText =
              activity.resources.getQuantityString(
                R.plurals.openpgp_card_pin_min_length,
                minLength,
                minLength,
              )
        }
        if (minLength > 0) {
          // Enforce the minimum PIN length by keeping the confirm button disabled until enough
          // characters are entered.
          val okButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
          val updateOkEnabled = {
            okButton.isEnabled = (binding.passwordEditText.text?.length ?: 0) >= minLength
          }
          updateOkEnabled()
          binding.passwordEditText.doAfterTextChanged { updateOkEnabled() }
        }
        dialog.window?.setFlags(
          WindowManager.LayoutParams.FLAG_SECURE,
          WindowManager.LayoutParams.FLAG_SECURE,
        )
      } catch (t: Throwable) {
        logcat { t.asLog() }
        result.complete(null)
      }
    }
    return result.await()
  }

  // Callers namespace [cacheKey] per operation ("decrypt:", "sign:", "ssh:"). On most cards the
  // decryption/auth (PW1 mode 0x82) and signing (PW1 mode 0x81) PINs are the same physical PW1, so
  // a user may be prompted -- and the PIN cached -- separately per operation. This is deliberate:
  // it keeps the caches independent and avoids assuming the slots share a secret, which is not
  // guaranteed across cards.

  /** Reads and decrypts a screen-off-cached PIN for [cacheKey], or null if none. */
  fun readCachedPin(cacheKey: String): CharArray? {
    val encrypted = BasePGPActivity.cachedPassphrases[cacheKey] ?: return null
    return AESEncryption.decrypt(encrypted)
  }

  fun clearCachedPin(cacheKey: String) {
    BasePGPActivity.cachedPassphrases[cacheKey]?.wipe()
    BasePGPActivity.cachedPassphrases.remove(cacheKey)
  }

  /** Caches [pin] (AES-encrypted, until screen-off) under [cacheKey] when [cache] is set. */
  fun storeCachedPin(cacheKey: String, pin: CharArray, cache: Boolean) {
    runCatching {
      val hardwareBacked = AESEncryption.isHardwareBacked()
      val encryptedPin = if (cache) AESEncryption.encrypt(pin) else null
      if (hardwareBacked && cache && encryptedPin != null) {
        BasePGPActivity.cachedPassphrases[cacheKey]?.wipe()
        BasePGPActivity.cachedPassphrases[cacheKey] = encryptedPin
      } else {
        clearCachedPin(cacheKey)
      }
      activity.sharedPrefs.edit {
        putBoolean(
          PreferenceKeys.CACHE_PASSPHRASE,
          hardwareBacked && cache && encryptedPin != null,
        )
      }
    }
      .onErr { e -> logcat { e.asLog() } }
  }

  /** Shows a simple informational dialog (used for terminal card errors, e.g. a blocked PIN). */
  suspend fun showError(@StringRes titleRes: Int, message: String) {
    withContext(dispatcherProvider.main()) {
      MaterialAlertDialogBuilder(activity)
        .outlined(activity)
        .setTitle(titleRes)
        .setMessage(message)
        .setPositiveButton(android.R.string.ok, null)
        .setCancelable(true)
        .show()
    }
  }

  /**
   * Keeps reader mode enabled until [card] is lifted (or a timeout elapses), then disables it, so
   * the platform never dispatches the still-present card's NDEF URL after an operation ends —
   * whether it succeeded or failed — for instance while a result dialog is still on screen. When
   * [card] is null (e.g. the card was never connected), reader mode is disabled right away. Runs
   * off the calling thread on the activity scope so it does not delay the operation.
   *
   * A card that was plugged in is simply let go of. Nothing waits to be dispatched behind it, so
   * there is nothing to hold reader mode against and no reason to keep watching a socket the user
   * is entitled to leave a key in.
   */
  fun releaseReaderWhenCardRemoved(card: OpenPgpCard?, reader: CardReader) {
    // On this prompt's own scope rather than the screen's: what it does last is let the reader go,
    // and a screen that has finished in the meantime would take the watch down with it and leave
    // reader mode on behind everything.
    cardScope.launch(dispatcherProvider.main()) {
      // A tick wants reading before it goes. Anything else — a cancellation above all — is over,
      // and a sheet that lingers after it reads as the app still doing something.
      if (finishing.get()) delay(CardPrompt.SUCCESS_MS)
      closePrompt()
      if (card != null && card.connection == CardConnection.NFC) {
        withContext(dispatcherProvider.io()) {
          try {
            awaitAbsence(card, READER_MODE_RELEASE_TIMEOUT_MS)
          } finally {
            runCatching { card.close() }
          }
        }
      } else if (card != null) {
        withContext(dispatcherProvider.io()) { runCatching { card.close() } }
      }
      reader.close()
    }
  }

  /**
   * Shows a modal "remove your card" dialog and **suspends** until [card] is physically lifted (or
   * a timeout elapses), then dismisses the dialog and releases [reader].
   *
   * Unlike [releaseReaderWhenCardRemoved] this blocks the caller. NFC reader mode is only active
   * while the hosting activity is resumed, so when an operation would otherwise let its activity
   * pause/finish right after the card exchange (e.g. an SSH authentication during a git push, whose
   * activity moves on once auth succeeds), holding the caller here keeps the activity foreground —
   * and the card in reader mode — until the user removes it, so the platform never dispatches the
   * still-present card's NDEF URL.
   *
   * A card that was plugged in is let go of and the caller carries straight on. There is nothing
   * waiting to be dispatched, so standing between the user and their commit until they unplug their
   * key would be asking for a ritual that serves nothing.
   */
  suspend fun awaitCardRemoval(card: OpenPgpCard, reader: CardReader) {
    try {
      if (card.connection != CardConnection.NFC) {
        delay(CardPrompt.SUCCESS_MS)
        return
      }
      // The tick has its moment, and the card usually goes within it.
      val lifted =
        withContext(dispatcherProvider.io()) { awaitAbsence(card, CardPrompt.SUCCESS_MS) }
      closePrompt()
      // If it has not, the wait carries on for a moment with nothing on the screen at all. Telling
      // somebody to lift a card they are already lifting is worth nobody's attention, and the only
      // reason to wait is one the user has no part in: reader mode is what keeps the platform from
      // throwing the card's own URL on screen, and it ends with the screen that holds it.
      //
      // Only for a moment, though. This runs on the thread that asked — for an SSH authentication,
      // the one carrying the whole git operation — and a card left lying on the phone would
      // otherwise hold that up for a minute with nothing to show for it. Now that nobody is asked
      // to lift their card, a card left there is the ordinary case rather than the odd one. Past
      // this point the worst that happens is the platform putting the card's URL on screen, which
      // is a nuisance; stalling a push until the user notices is a hang.
      if (!lifted) {
        withContext(dispatcherProvider.io()) { awaitAbsence(card, READER_HOLD_MS) }
      }
    } finally {
      closePrompt()
      runCatching { card.close() }
      reader.close()
    }
  }

  /**
   * Waits for [card] to leave the field, for at most [timeoutMs]. Returns whether it went.
   *
   * Asks the wire rather than the card. Addressing a card to find out whether it is still there
   * means a full exchange every time round, on a card that by then has nothing left to say — and on
   * the thread that is waiting, which for an SSH authentication is the one carrying the whole git
   * operation. The wire's own answer is free, and the platform keeps it fresh.
   *
   * Two consecutive misses are what counts as gone, since a single one can be a glitch on a card
   * that is still sitting there.
   */
  private suspend fun awaitAbsence(card: OpenPgpCard, timeoutMs: Long): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    var consecutiveMisses = 0
    while (consecutiveMisses < 2 && System.currentTimeMillis() < deadline) {
      if (card.isConnected) {
        consecutiveMisses = 0
        delay(LIVENESS_POLL_INTERVAL_MS)
      } else {
        consecutiveMisses++
      }
    }
    return consecutiveMisses >= 2
  }

  companion object {
    /** The minimum PW1 (user/signing) PIN length mandated by the OpenPGP Card specification. */
    const val MIN_PIN_LENGTH = 6

    private const val READER_MODE_RELEASE_TIMEOUT_MS = 30_000L
    // How long an operation whose screen is about to close will hold on, unseen, for the card to
    // be lifted before giving up and letting go of the reader.
    private const val READER_HOLD_MS = 4_000L
    private const val READER_MODE_POLL_INTERVAL_MS = 300L
    // How often the wire is asked whether it still has a card while an exchange is running. Two
    // consecutive misses end it, so a card that goes is noticed inside a third of a second.
    private const val LIVENESS_POLL_INTERVAL_MS = 150L
    private val PIN_FAILURE_REGEX = Regex("""63 c[0-9a-f]""", RegexOption.IGNORE_CASE)

    /**
     * Whether [error] is a smartcard PIN rejection — that is, whether the card turned the PIN down
     * at the VERIFY, the only command that can.
     *
     * The command matters as much as the status word. `69 82` from a VERIFY is a wrong PIN; the
     * same `69 82` from the PSO or INTERNAL AUTHENTICATE that follows one means the card would not
     * perform that operation, which a correct PIN does nothing to fix. Reading the status word
     * alone cannot tell them apart, and calling the second one a wrong PIN puts the user in an
     * endless prompt: they re-enter a PIN the card keeps accepting, the operation keeps failing,
     * and the card's retry counter never moves. So the answer comes from the type raised at the
     * VERIFY itself ([SmartcardPinVerificationException]) rather than from the digits.
     *
     * Only when nothing in the chain says it reached the card at all — no typed status survived the
     * trip — is the wire text worth reading, as a last resort for a layer that flattened its cause.
     */
    fun isSmartcardPinFailure(error: Throwable?): Boolean {
      var cause = error
      var answeredWithStatusWord = false
      while (cause != null) {
        // Covers SmartcardPinFormatException too: a PIN the card rejected for its length/format is
        // a (recoverable) PIN problem raised at the same VERIFY.
        if (cause is SmartcardPinVerificationException) return true
        if (cause is OpenPgpCardStatusException) answeredWithStatusWord = true
        cause = cause.cause
      }
      // The card answered, and not to a VERIFY: whatever it said, the PIN is not what it objected
      // to.
      if (answeredWithStatusWord) return false
      cause = error
      while (cause != null) {
        val message = cause.message.orEmpty()
        if (message.contains("69 82", ignoreCase = true)) return true
        if (message.contains("69 83", ignoreCase = true)) return true
        if (PIN_FAILURE_REGEX.containsMatchIn(message)) return true
        cause = cause.cause
      }
      return false
    }

    /** The card status word behind [error] (e.g. `63 c2`), or null if the card never answered. */
    fun smartcardStatusWord(error: Throwable?): String? =
      smartcardStatusWordPair(error)?.let { (sw1, sw2) -> "%02x %02x".format(sw1, sw2) }

    private fun smartcardStatusWordPair(error: Throwable?): Pair<Int, Int>? {
      var cause = error
      while (cause != null) {
        if (cause is OpenPgpCardStatusException) return cause.sw1 to cause.sw2
        cause = cause.cause
      }
      return null
    }

    /**
     * Whether the card turned the PIN down over its *shape* rather than its value — too long or too
     * short for the bounds it was set up with. Recoverable in the same breath as a wrong PIN, but
     * not the same thing: the card does not count it as an attempt, so saying how many attempts are
     * left would be telling the user they are burning through retries they still have.
     */
    fun isSmartcardPinFormatFailure(error: Throwable?): Boolean {
      var cause = error
      while (cause != null) {
        if (cause is SmartcardPinFormatException) return true
        cause = cause.cause
      }
      return false
    }

    /** The card-reported number of PIN attempts still available, or null if the card didn't say. */
    fun smartcardPinRetriesRemaining(error: Throwable?): Int? {
      var cause = error
      while (cause != null) {
        if (cause is OpenPgpCardStatusException) {
          cause.retriesRemaining?.let {
            return it
          }
        }
        cause = cause.cause
      }
      return null
    }

    /**
     * Whether [error] is a transient NFC/card *transport* problem (tag lost mid-exchange, a
     * malformed/short response, a transceive glitch), for which the user should simply present the
     * card again.
     *
     * Crucially, an [OpenPgpCardStatusException] is *not* retryable even though it extends
     * [IOException]: the card answered with a status word, so it was read just fine — that's a card
     * error to report (or, if it's a PIN rejection, to re-prompt for), never a "couldn't read the
     * card". Only a plain transport [IOException] (no card status word anywhere in the chain)
     * counts.
     */
    fun isRetryableCardError(error: Throwable?): Boolean {
      var cause = error
      var transportFailure = false
      while (cause != null) {
        // The card responded — whatever the status word, this was not a failed read.
        if (cause is OpenPgpCardStatusException) return false
        if (cause is IOException) transportFailure = true
        cause = cause.cause
      }
      return transportFailure
    }

    /** Whether [error] (or a cause) is an already-reported smartcard failure (see below). */
    fun isHandled(error: Throwable?): Boolean {
      var cause = error
      while (cause != null) {
        if (cause is SmartcardOperationHandledException) return true
        cause = cause.cause
      }
      return false
    }
  }
}

/**
 * Thrown when a smartcard operation (e.g. commit signing) has already reported its failure to the
 * user via a dialog, so callers should not additionally surface it (e.g. as a snackbar).
 */
class SmartcardOperationHandledException(message: String? = null) : Exception(message)
