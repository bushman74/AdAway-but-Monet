package org.adaway.model.source

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/**
 * Tests of [SourceFailure.of], which tells why a source could not be retrieved.
 */
class SourceFailureTest {
    /**
     * The download wraps what it caught, so the reason is usually one level down.
     */
    private fun wrapped(cause: Throwable) = IOException("Exception while downloading hosts file.", cause)

    @Test
    fun errorStatusIsNamedByItsCode() {
        assertEquals("http:403", SourceFailure.of(wrapped(SourceHttpException(403)), false))
    }

    @Test
    fun timeoutsAreRecognised() {
        assertEquals(SourceFailure.TIMEOUT, SourceFailure.of(wrapped(SocketTimeoutException()), false))
        assertEquals(SourceFailure.TIMEOUT, SourceFailure.of(wrapped(InterruptedIOException("timeout")), false))
    }

    @Test
    fun connectionProblemsAreRecognised() {
        assertEquals(SourceFailure.UNKNOWN_HOST, SourceFailure.of(wrapped(UnknownHostException("example.org")), false))
        assertEquals(SourceFailure.CONNECTION, SourceFailure.of(wrapped(ConnectException()), false))
        assertEquals(
            SourceFailure.SECURE_CONNECTION,
            SourceFailure.of(wrapped(SSLHandshakeException("bad certificate")), false)
        )
    }

    @Test
    fun anyFailureOfAFileSourceIsAFileFailure() {
        assertEquals(SourceFailure.FILE, SourceFailure.of(wrapped(FileNotFoundException()), true))
        assertEquals(SourceFailure.FILE, SourceFailure.of(SecurityException(), true))
    }

    @Test
    fun anythingElseIsUnknown() {
        assertEquals(SourceFailure.OTHER, SourceFailure.of(IOException("something else"), false))
        assertEquals(SourceFailure.OTHER, SourceFailure.of(InterruptedIOException("canceled"), false))
    }
}
