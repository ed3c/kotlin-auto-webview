package dev.ed3c.autowebview.device.effects

import dev.ed3c.autowebview.device.contract.DeviceEffectState
import dev.ed3c.autowebview.device.verifier.VerificationVerdict
import dev.ed3c.autowebview.device.verifier.VerificationVerdictCode
import kotlinx.serialization.Serializable

@Serializable
data class EffectKey(
    val proposalId: String,
    val canonicalActionId: String,
    val idempotencyKey: String,
) {
    init {
        listOf(proposalId, canonicalActionId, idempotencyKey).forEach {
            require(it.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))) {
                "Effect identity must use bounded canonical identifiers"
            }
        }
    }
}

@Serializable
enum class EffectLedgerState {
    PROPOSED,
    PRECONDITION_CAPTURED,
    DISPATCH_ADMITTED,
    DISPATCHING,
    VERIFYING,
    TERMINAL_NONE,
    TERMINAL_APPLIED,
    TERMINAL_UNKNOWN,
}

@Serializable
enum class EffectLedgerEventKind {
    PRECONDITION_CAPTURED,
    DISPATCH_ADMITTED,
    DISPATCH_STARTED,
    NOT_DISPATCHED,
    USER_ACTION_REQUIRED,
    DISPATCHED,
    PLATFORM_FAILURE_BEFORE_EFFECT,
    PLATFORM_FAILURE_UNKNOWN,
    POSTCONDITION_APPLIED,
    POSTCONDITION_NO_EFFECT,
    POSTCONDITION_INCONCLUSIVE,
    OBSERVER_LOST,
    CONTRADICTORY_EVIDENCE,
}

@Serializable
data class EffectLedgerTransition(
    val eventId: String,
    val kind: EffectLedgerEventKind,
    val atEpochMs: Long,
    val effectState: DeviceEffectState,
    val verificationDigestSha256: String? = null,
) {
    init {
        require(eventId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))) { "Effect event id is invalid" }
        require(atEpochMs >= 0) { "Effect event timestamp cannot be negative" }
        verificationDigestSha256?.let {
            require(it.matches(Regex("[0-9a-f]{64}"))) { "Effect verification digest is invalid" }
        }
    }
}

@Serializable
enum class EffectLedgerDurability {
    NON_DURABLE_FIXTURE,
    DURABLE_ADAPTER,
}

@Serializable
data class EffectRecord(
    val key: EffectKey,
    val state: EffectLedgerState,
    val effectState: DeviceEffectState,
    val reconciliationRequired: Boolean,
    val history: List<EffectLedgerTransition>,
    val durability: EffectLedgerDurability,
) {
    init {
        require(history.size <= 256) { "Effect history exceeds bounded size" }
        require(history.map(EffectLedgerTransition::eventId).toSet().size == history.size) {
            "Effect history contains duplicate event ids"
        }
        if (effectState == DeviceEffectState.UNKNOWN) {
            require(reconciliationRequired || state == EffectLedgerState.VERIFYING) {
                "Unknown terminal effect must require reconciliation"
            }
        }
    }
}

interface EffectLedgerStore {
    val durability: EffectLedgerDurability
    fun load(key: EffectKey): EffectRecord?
    fun create(record: EffectRecord): Boolean
    fun replace(record: EffectRecord)
}

@Serializable
data class VersionedEffectRecord(
    val version: Long,
    val record: EffectRecord,
) {
    init { require(version >= 0) { "Effect store version cannot be negative" } }
}

enum class EffectStoreWriteResult {
    APPLIED,
    VERSION_CONFLICT,
    MISSING,
}

interface VersionedEffectLedgerStore : EffectLedgerStore {
    fun loadVersioned(key: EffectKey): VersionedEffectRecord?
    fun compareAndSwap(expectedVersion: Long, record: EffectRecord): EffectStoreWriteResult
}

class EffectLedgerConflictException(message: String) : IllegalStateException(message)

class InMemoryEffectLedgerStore : EffectLedgerStore {
    override val durability: EffectLedgerDurability = EffectLedgerDurability.NON_DURABLE_FIXTURE
    private val records = mutableMapOf<EffectKey, EffectRecord>()

    override fun load(key: EffectKey): EffectRecord? = records[key]

    override fun create(record: EffectRecord): Boolean {
        if (record.key in records) return false
        records[record.key] = record
        return true
    }

    override fun replace(record: EffectRecord) {
        require(record.key in records) { "Effect record does not exist" }
        records[record.key] = record
    }
}

class VersionedInMemoryEffectLedgerStore : VersionedEffectLedgerStore {
    override val durability: EffectLedgerDurability = EffectLedgerDurability.NON_DURABLE_FIXTURE
    private val records = mutableMapOf<EffectKey, VersionedEffectRecord>()

    override fun load(key: EffectKey): EffectRecord? = records[key]?.record

    override fun loadVersioned(key: EffectKey): VersionedEffectRecord? = records[key]

    override fun create(record: EffectRecord): Boolean {
        if (record.key in records) return false
        records[record.key] = VersionedEffectRecord(0, record)
        return true
    }

    override fun replace(record: EffectRecord) {
        val current = records[record.key] ?: error("Effect record does not exist")
        records[record.key] = VersionedEffectRecord(current.version + 1, record)
    }

    override fun compareAndSwap(expectedVersion: Long, record: EffectRecord): EffectStoreWriteResult {
        val current = records[record.key] ?: return EffectStoreWriteResult.MISSING
        if (current.version != expectedVersion) return EffectStoreWriteResult.VERSION_CONFLICT
        records[record.key] = VersionedEffectRecord(current.version + 1, record)
        return EffectStoreWriteResult.APPLIED
    }
}

@Serializable
enum class EffectPersistenceState {
    ABSENT,
    NON_DURABLE_FIXTURE,
    DURABLE_ADAPTER_BOUND,
}

@Serializable
data class EffectPersistenceEvidence(
    val state: EffectPersistenceState,
    val adapterId: String? = null,
    val bindingDigestSha256: String? = null,
) {
    init {
        when (state) {
            EffectPersistenceState.ABSENT,
            EffectPersistenceState.NON_DURABLE_FIXTURE,
            -> require(adapterId == null && bindingDigestSha256 == null) {
                "Absent or fixture persistence cannot claim a durable adapter binding"
            }
            EffectPersistenceState.DURABLE_ADAPTER_BOUND -> {
                require(adapterId?.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) == true) {
                    "Durable adapter id is invalid"
                }
                require(bindingDigestSha256?.matches(Regex("[0-9a-f]{64}")) == true) {
                    "Durable adapter binding requires an exact SHA-256 digest"
                }
            }
        }
    }
}

interface BoundDurableEffectLedgerStore : VersionedEffectLedgerStore {
    val adapterId: String
    val bindingDigestSha256: String
}

fun effectPersistenceEvidence(store: EffectLedgerStore?): EffectPersistenceEvidence = when {
    store == null -> EffectPersistenceEvidence(EffectPersistenceState.ABSENT)
    store is BoundDurableEffectLedgerStore && store.durability == EffectLedgerDurability.DURABLE_ADAPTER ->
        EffectPersistenceEvidence(
            state = EffectPersistenceState.DURABLE_ADAPTER_BOUND,
            adapterId = store.adapterId,
            bindingDigestSha256 = store.bindingDigestSha256,
        )
    store.durability == EffectLedgerDurability.NON_DURABLE_FIXTURE ->
        EffectPersistenceEvidence(EffectPersistenceState.NON_DURABLE_FIXTURE)
    else -> error("A store cannot claim DURABLE_ADAPTER without an exact durable adapter binding")
}

enum class RetryDecision {
    ALLOWED_IDEMPOTENT,
    DENIED_UNKNOWN_EFFECT,
    DENIED_NOT_IDEMPOTENT,
}

class EffectLedger(
    private val store: EffectLedgerStore,
) {
    fun open(key: EffectKey): EffectRecord {
        store.load(key)?.let { return it }
        val record = EffectRecord(
            key = key,
            state = EffectLedgerState.PROPOSED,
            effectState = DeviceEffectState.NONE,
            reconciliationRequired = false,
            history = emptyList(),
            durability = store.durability,
        )
        store.create(record)
        return store.load(key) ?: record
    }

    fun apply(
        key: EffectKey,
        eventId: String,
        event: EffectLedgerEventKind,
        atEpochMs: Long,
        verification: VerificationVerdict? = null,
    ): EffectRecord {
        val versionedStore = store as? VersionedEffectLedgerStore
        val versioned = versionedStore?.loadVersioned(key)
        val current = versioned?.record ?: store.load(key) ?: error("Effect record must be opened before transitions")
        current.history.firstOrNull { it.eventId == eventId }?.let { return current }
        require(current.history.lastOrNull()?.atEpochMs?.let { atEpochMs >= it } != false) {
            "Effect transition time cannot move backwards"
        }

        val (nextState, nextEffect, reconciliation) = transition(current.state, event, verification)
        val digest = verification?.evidenceDigestSha256
        val next = current.copy(
            state = nextState,
            effectState = nextEffect,
            reconciliationRequired = reconciliation,
            history = current.history + EffectLedgerTransition(
                eventId = eventId,
                kind = event,
                atEpochMs = atEpochMs,
                effectState = nextEffect,
                verificationDigestSha256 = digest,
            ),
        )
        if (versionedStore != null && versioned != null) {
            when (versionedStore.compareAndSwap(versioned.version, next)) {
                EffectStoreWriteResult.APPLIED -> return next
                EffectStoreWriteResult.VERSION_CONFLICT -> throw EffectLedgerConflictException("Concurrent effect update conflict")
                EffectStoreWriteResult.MISSING -> error("Effect record disappeared during compare-and-swap")
            }
        }
        store.replace(next)
        return next
    }

    fun retryDecision(record: EffectRecord, idempotent: Boolean): RetryDecision = when {
        record.effectState == DeviceEffectState.UNKNOWN -> RetryDecision.DENIED_UNKNOWN_EFFECT
        !idempotent -> RetryDecision.DENIED_NOT_IDEMPOTENT
        else -> RetryDecision.ALLOWED_IDEMPOTENT
    }

    private fun transition(
        state: EffectLedgerState,
        event: EffectLedgerEventKind,
        verification: VerificationVerdict?,
    ): Triple<EffectLedgerState, DeviceEffectState, Boolean> = when (state to event) {
        EffectLedgerState.PROPOSED to EffectLedgerEventKind.PRECONDITION_CAPTURED ->
            Triple(EffectLedgerState.PRECONDITION_CAPTURED, DeviceEffectState.NONE, false)
        EffectLedgerState.PRECONDITION_CAPTURED to EffectLedgerEventKind.DISPATCH_ADMITTED ->
            Triple(EffectLedgerState.DISPATCH_ADMITTED, DeviceEffectState.NONE, false)
        EffectLedgerState.DISPATCH_ADMITTED to EffectLedgerEventKind.DISPATCH_STARTED ->
            Triple(EffectLedgerState.DISPATCHING, DeviceEffectState.NONE, false)
        EffectLedgerState.DISPATCHING to EffectLedgerEventKind.NOT_DISPATCHED,
        EffectLedgerState.DISPATCHING to EffectLedgerEventKind.USER_ACTION_REQUIRED,
        EffectLedgerState.DISPATCHING to EffectLedgerEventKind.PLATFORM_FAILURE_BEFORE_EFFECT,
        -> Triple(EffectLedgerState.TERMINAL_NONE, DeviceEffectState.NONE, false)
        EffectLedgerState.DISPATCHING to EffectLedgerEventKind.DISPATCHED ->
            Triple(EffectLedgerState.VERIFYING, DeviceEffectState.UNKNOWN, false)
        EffectLedgerState.DISPATCHING to EffectLedgerEventKind.PLATFORM_FAILURE_UNKNOWN ->
            Triple(EffectLedgerState.TERMINAL_UNKNOWN, DeviceEffectState.UNKNOWN, true)
        EffectLedgerState.VERIFYING to EffectLedgerEventKind.POSTCONDITION_APPLIED -> {
            require(verification?.code == VerificationVerdictCode.APPLIED) { "APPLIED transition requires matching verifier verdict" }
            Triple(EffectLedgerState.TERMINAL_APPLIED, DeviceEffectState.APPLIED, false)
        }
        EffectLedgerState.VERIFYING to EffectLedgerEventKind.POSTCONDITION_NO_EFFECT -> {
            require(verification?.code == VerificationVerdictCode.NO_EFFECT) { "NO_EFFECT transition requires matching verifier verdict" }
            Triple(EffectLedgerState.TERMINAL_NONE, DeviceEffectState.NONE, false)
        }
        EffectLedgerState.VERIFYING to EffectLedgerEventKind.POSTCONDITION_INCONCLUSIVE,
        EffectLedgerState.VERIFYING to EffectLedgerEventKind.OBSERVER_LOST,
        EffectLedgerState.VERIFYING to EffectLedgerEventKind.CONTRADICTORY_EVIDENCE,
        -> {
            require(verification == null || verification.effectState == DeviceEffectState.UNKNOWN) {
                "Unknown-effect transition cannot consume a definitive verifier verdict"
            }
            Triple(EffectLedgerState.TERMINAL_UNKNOWN, DeviceEffectState.UNKNOWN, true)
        }
        else -> error("Illegal effect transition: $state + $event")
    }
}
