package dev.ed3c.autowebview.device.effects

import dev.ed3c.autowebview.device.contract.DeviceEffectState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EffectLedgerCompletionTest {
    @Test
    fun stale_compare_and_swap_is_a_deterministic_conflict() {
        val store = VersionedInMemoryEffectLedgerStore()
        val key = EffectKey("proposal-1", "own-webview.click", "idempotency-1")
        val ledger = EffectLedger(store)
        ledger.open(key)
        val initial = store.loadVersioned(key)!!
        val external = initial.record.copy(
            history = initial.record.history + EffectLedgerTransition(
                eventId = "external-1",
                kind = EffectLedgerEventKind.PRECONDITION_CAPTURED,
                atEpochMs = 1,
                effectState = DeviceEffectState.NONE,
            ),
            state = EffectLedgerState.PRECONDITION_CAPTURED,
        )
        assertEquals(EffectStoreWriteResult.APPLIED, store.compareAndSwap(initial.version, external))
        assertEquals(
            EffectStoreWriteResult.VERSION_CONFLICT,
            store.compareAndSwap(initial.version, initial.record),
        )
    }

    @Test
    fun versioned_store_advances_monotonically_through_ledger_transitions() {
        val store = VersionedInMemoryEffectLedgerStore()
        val key = EffectKey("proposal-1", "own-webview.click", "idempotency-1")
        val ledger = EffectLedger(store)
        ledger.open(key)
        ledger.apply(key, "event-1", EffectLedgerEventKind.PRECONDITION_CAPTURED, 1)
        ledger.apply(key, "event-2", EffectLedgerEventKind.DISPATCH_ADMITTED, 2)
        assertEquals(2, store.loadVersioned(key)!!.version)
    }

    @Test
    fun persistence_evidence_cannot_promote_absent_or_fixture_store() {
        assertEquals(EffectPersistenceState.ABSENT, effectPersistenceEvidence(null).state)
        assertEquals(
            EffectPersistenceState.NON_DURABLE_FIXTURE,
            effectPersistenceEvidence(InMemoryEffectLedgerStore()).state,
        )
        assertFailsWith<IllegalStateException> {
            effectPersistenceEvidence(object : EffectLedgerStore {
                override val durability = EffectLedgerDurability.DURABLE_ADAPTER
                override fun load(key: EffectKey): EffectRecord? = null
                override fun create(record: EffectRecord): Boolean = true
                override fun replace(record: EffectRecord) = Unit
            })
        }
    }
}
