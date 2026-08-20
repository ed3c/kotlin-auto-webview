package dev.ed3c.autowebview.workspace.registry

import dev.ed3c.autowebview.workspace.contract.SyncState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkspaceSyncTransitionsTest {
    @Test
    fun happyPathRequiresAcknowledgementBeforeReadBack() {
        assertTrue(WorkspaceSyncTransitions.allows(SyncState.PENDING, SyncState.WRITE_SENT))
        assertTrue(WorkspaceSyncTransitions.allows(SyncState.WRITE_SENT, SyncState.WRITE_ACKNOWLEDGED))
        assertTrue(
            WorkspaceSyncTransitions.allows(
                SyncState.WRITE_ACKNOWLEDGED,
                SyncState.READ_BACK_VERIFIED,
            ),
        )
        assertFalse(WorkspaceSyncTransitions.allows(SyncState.PENDING, SyncState.READ_BACK_VERIFIED))
        assertFalse(WorkspaceSyncTransitions.allows(SyncState.WRITE_SENT, SyncState.READ_BACK_VERIFIED))
    }

    @Test
    fun retryMustPassThroughAnotherWriteAttempt() {
        assertTrue(WorkspaceSyncTransitions.allows(SyncState.WRITE_SENT, SyncState.RETRYABLE_FAILURE))
        assertTrue(WorkspaceSyncTransitions.allows(SyncState.RETRYABLE_FAILURE, SyncState.WRITE_SENT))
        assertFalse(
            WorkspaceSyncTransitions.allows(
                SyncState.RETRYABLE_FAILURE,
                SyncState.WRITE_ACKNOWLEDGED,
            ),
        )
    }

    @Test
    fun terminalStatesCannotSilentlyReopen() {
        assertFalse(WorkspaceSyncTransitions.allows(SyncState.READ_BACK_VERIFIED, SyncState.PENDING))
        assertFalse(WorkspaceSyncTransitions.allows(SyncState.CONFLICT, SyncState.PENDING))
        assertFalse(WorkspaceSyncTransitions.allows(SyncState.FAILED, SyncState.PENDING))
        assertFalse(WorkspaceSyncTransitions.allows(SyncState.CLEANED_UP, SyncState.PENDING))
        assertTrue(WorkspaceSyncTransitions.allows(SyncState.READ_BACK_VERIFIED, SyncState.CLEANED_UP))
        assertTrue(WorkspaceSyncTransitions.allows(SyncState.CONFLICT, SyncState.CLEANED_UP))
    }

    @Test
    fun completeTransitionMatrixFailsClosed() {
        val allowed = mapOf(
            SyncState.PENDING to setOf(
                SyncState.WRITE_SENT,
                SyncState.RETRYABLE_FAILURE,
                SyncState.FAILED,
            ),
            SyncState.WRITE_SENT to setOf(
                SyncState.WRITE_ACKNOWLEDGED,
                SyncState.RETRYABLE_FAILURE,
                SyncState.FAILED,
            ),
            SyncState.WRITE_ACKNOWLEDGED to setOf(
                SyncState.READ_BACK_VERIFIED,
                SyncState.CONFLICT,
                SyncState.RETRYABLE_FAILURE,
                SyncState.FAILED,
            ),
            SyncState.RETRYABLE_FAILURE to setOf(
                SyncState.WRITE_SENT,
                SyncState.FAILED,
            ),
            SyncState.READ_BACK_VERIFIED to setOf(SyncState.CLEANED_UP),
            SyncState.CONFLICT to setOf(SyncState.CLEANED_UP),
            SyncState.FAILED to setOf(SyncState.CLEANED_UP),
            SyncState.CLEANED_UP to emptySet(),
        )

        SyncState.entries.forEach { from ->
            SyncState.entries.forEach { to ->
                assertEquals(
                    to in allowed.getValue(from),
                    WorkspaceSyncTransitions.allows(from, to),
                    "Unexpected transition verdict for $from -> $to",
                )
            }
        }
    }
}
