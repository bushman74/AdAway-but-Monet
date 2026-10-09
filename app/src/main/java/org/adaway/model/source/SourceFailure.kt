package org.adaway.model.source

import android.content.Context
import androidx.annotation.StringRes
import org.adaway.R
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * A server answered a source request with an error status, such as 403 or 404.
 *
 * @param code The HTTP status code.
 */
class SourceHttpException(val code: Int) : IOException("HTTP status $code")

/**
 * Why a source could not be retrieved, stored with the source as a short code and shown under it.
 */
object SourceFailure {
    /** The run stopped before it reached the source, having spent its time or failed too often. */
    const val ABORTED = "aborted"
    const val TIMEOUT = "timeout"
    const val UNKNOWN_HOST = "unknown_host"
    const val CONNECTION = "connection"
    const val SECURE_CONNECTION = "secure_connection"
    const val FILE = "file"
    const val OTHER = "other"
    private const val HTTP_PREFIX = "http:"

    /**
     * Tell why a retrieval failed.
     *
     * @param exception What the retrieval threw. The cause is often wrapped, so the whole chain is
     * looked at.
     * @param fromFile Whether the source is a file on the device rather than an address.
     * @return The failure code.
     */
    @JvmStatic
    fun of(exception: Throwable, fromFile: Boolean): String {
        if (fromFile) {
            return FILE
        }
        var cause: Throwable? = exception
        while (cause != null) {
            when (cause) {
                is SourceHttpException -> return HTTP_PREFIX + cause.code
                is SocketTimeoutException -> return TIMEOUT
                is UnknownHostException -> return UNKNOWN_HOST
                is SSLException -> return SECURE_CONNECTION
                is ConnectException, is NoRouteToHostException, is SocketException -> return CONNECTION
                // OkHttp reports its own call and read timeouts this way.
                is InterruptedIOException -> if (cause.message == "timeout") return TIMEOUT
            }
            cause = cause.cause
        }
        return OTHER
    }

    /**
     * Describe a failure code the way the screen shows it, such as "Error 403".
     */
    @JvmStatic
    fun describe(context: Context, code: String): String {
        if (code.startsWith(HTTP_PREFIX)) {
            val status = code.removePrefix(HTTP_PREFIX).toIntOrNull()
            if (status != null) {
                return context.getString(R.string.source_failure_http, status)
            }
        }
        return context.getString(descriptionOf(code))
    }

    @StringRes
    private fun descriptionOf(code: String): Int = when (code) {
        ABORTED -> R.string.source_failure_aborted
        TIMEOUT -> R.string.source_failure_timeout
        UNKNOWN_HOST -> R.string.source_failure_unknown_host
        CONNECTION -> R.string.source_failure_connection
        SECURE_CONNECTION -> R.string.source_failure_secure_connection
        FILE -> R.string.source_failure_file
        else -> R.string.source_failure_other
    }
}
