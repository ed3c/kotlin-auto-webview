package dev.ed3c.autowebview.workspace.google

import kotlin.test.Test
import kotlin.test.assertFailsWith

class GoogleProjectionContractTest {
    @Test
    fun providerReasonCodesRejectFreeFormOrIdentifierBearingText() {
        assertFailsWith<IllegalArgumentException> {
            GoogleProjectionReadResult.Blocked("permission denied for file private_123")
        }
        assertFailsWith<IllegalArgumentException> {
            GoogleProjectionWriteResult.RetryableFailure("retry later: https://docs.google.com/private")
        }
    }

    @Test
    fun machineReasonCodesRemainAdmitted() {
        GoogleProjectionReadResult.Blocked("PERMISSION_REVOKED")
        GoogleProjectionWriteResult.RetryableFailure("RATE_LIMITED:RETRY")
    }
}
