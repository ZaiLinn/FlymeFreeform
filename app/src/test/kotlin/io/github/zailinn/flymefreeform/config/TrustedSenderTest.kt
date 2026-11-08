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
}
