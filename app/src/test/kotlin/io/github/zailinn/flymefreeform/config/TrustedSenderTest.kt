package io.github.zailinn.flymefreeform.config

import org.junit.Assert.*
import org.junit.Test

class TrustedSenderTest {
    @Test fun failedLookupAndUnknownSenderCannotAuthenticate() {
        assertFalse(TrustedSender.matches(-1, -1))
        assertFalse(TrustedSender.matches(10123, -1))
        assertFalse(TrustedSender.matches(-1, 10123))
        assertFalse(TrustedSender.matches(10124, 10123))
        assertTrue(TrustedSender.matches(10123, 10123))
    }
    @Test fun checkedLookupFailuresAreDeniedWithoutEscapingReceiver() {
        assertFalse(TrustedSender.matches(10123) { throw java.io.IOException("package unavailable") })
        assertFalse(TrustedSender.matches(-1) { error("unknown sender must not query PM") })
        assertTrue(TrustedSender.matches(10123) { 10123 })
    }

}
