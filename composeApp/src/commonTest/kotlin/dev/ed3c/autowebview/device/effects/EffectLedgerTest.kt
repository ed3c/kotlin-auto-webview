package dev.ed3c.autowebview.device.effects

import dev.ed3c.autowebview.device.contract.DeviceEffectState
import dev.ed3c.autowebview.device.verifier.VerificationVerdict
import dev.ed3c.autowebview.device.verifier.VerificationVerdictCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EffectLedgerTest {
    @Test
    fun dispatch_success_is_unknown_until_postcondition_verifies_application() {
        val ledger = EffectLedger(InMemoryEffectLedgerStore())
        val key = key()
        ledger.open(key)
        advanceToDispatching(ledger, key)
        val dispatched = ledger.apply(key, "event-4", EffectLedgerEventKind.DISPATCHED, 4)
        assertEquals(EffectLedgerState.VERIFYING, dispatched.state)
        assertEquals(DeviceEffectState.UNKNOWN, dispatched.effectState)

        val applied = ledger.apply(
            key,
            "event-5",
            EffectLedgerEventKind.POSTCONDITION_APPLIED,
            5,
            verification = verdict(VerificationVerdictCode.APPLIED, DeviceEffectState.APPLIED, false),
        )
        assertEquals(EffectLedgerState.TERMINAL_APPLIED, applied.state)
        assertEquals(DeviceEffectState.APPLIED, applied.effectState)
    }

    @Test
    fun replayed_event_is_idempotent_and_cannot_duplicate_history() {
        val ledger = EffectLedger(InMemoryEffectLedgerStore())
        val key = key()
        ledger.open(key)
        val first = ledger.apply(key, "event-1", EffectLedgerEventKind.PRECONDITION_CAPTURED, 1)
        val replay = ledger.apply(key, "event-1", EffectLedgerEventKind.PRECONDITION_CAPTURED, 1)
        assertEquals(first, replay)
        assertEquals(1, replay.history.size)
    }

    @Test
    fun unknown_effect_blocks_automatic_retry() {
        val ledger = EffectLedger(InMemoryEffectLedgerStore())
        val key = key()
        ledger.open(key)
        advanceToDispatching(ledger, key)
        val unknown = ledger.apply(key, "event-4", EffectLedgerEventKind.PLATFORM_FAILURE_UNKNOWN, 4)
        assertEquals(DeviceEffectState.UNKNOWN, unknown.effectState)
        assertEquals(RetryDecision.DENIED_UNKNOWN_EFFECT, ledger.retryDecision(unknown, idempotent = true))
    }

    @Test
    fun out_of_order_or_unverified_terminal_transition_is_rejected() {
        val ledger = EffectLedger(InMemoryEffectLedgerStore())
        val key = key()
        ledger.open(key)
        assertFailsWith<IllegalStateException> {
            ledger.apply(key, "event-x", EffectLedgerEventKind.DISPATCHED, 1)
        }
        ledger.apply(key, "event-1", EffectLedgerEventKind.PRECONDITION_CAPTURED, 1)
        ledger.apply(key, "event-2", EffectLedgerEventKind.DISPATCH_ADMITTED, 2)
        ledger.apply(key, "event-3", EffectLedgerEventKind.DISPATCH_STARTED, 3)
        ledger.apply(key, "event-4", EffectLedgerEventKind.DISPATCHED, 4)
        assertFailsWith<IllegalArgumentException> {
            ledger.apply(key, "event-5", EffectLedgerEventKind.POSTCONDITION_APPLIED, 5, verification = null)
        }
    }

    @Test
    fun in_memory_store_is_explicitly_non_durable_evidence() {
        val store = InMemoryEffectLedgerStore()
        val record = EffectLedger(store).open(key())
        assertEquals(EffectLedgerDurability.NON_DURABLE_FIXTURE, record.durability)
    }

    private fun advanceToDispatching(ledger: EffectLedger, key: EffectKey) {
        ledger.apply(key, "event-1", EffectLedgerEventKind.PRECONDITION_CAPTURED, 1)
        ledger.apply(key, "event-2", EffectLedgerEventKind.DISPATCH_ADMITTED, 2)
        ledger.apply(key, "event-3", EffectLedgerEventKind.DISPATCH_STARTED, 3)
    }

    private fun key() = EffectKey("proposal-1", "own-webview.click", "idempotency-1")

    private fun verdict(
        code: VerificationVerdictCode,
        effect: DeviceEffectState,
        reconciliation: Boolean,
    ) = VerificationVerdict(
        code = code,
        effectState = effect,
        reconciliationRequired = reconciliation,
        evidenceDigestSha256 = "c".repeat(64),
    )
}
