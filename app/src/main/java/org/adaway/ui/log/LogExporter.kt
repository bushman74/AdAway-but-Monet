package org.adaway.ui.log

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.annotation.UiThread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.adaway.R
import org.adaway.util.ExpressiveToast
import timber.log.Timber
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.io.OutputStreamWriter
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Writes the recorded DNS requests to a file the user picked.
 *
 * The file is written through the picked location the same way a configuration backup is, so the
 * user chooses where it goes and no storage permission is involved.
 */
object LogExporter {
    /**
     * The name proposed for the exported file.
     */
    const val FILE_NAME = "adaway-dns-requests.log"

    /**
     * The type the file is created with.
     *
     * A plain text type would have the file named after the extension of that type instead, so
     * asking for `.log` would produce a `.log.txt`.
     */
    const val MIME_TYPE = "application/octet-stream"

    /**
     * Times are written to the second, in a form that sorts and reads the same everywhere, rather
     * than in the form the screen shows them in, which follows the language of the device.
     */
    private val TIME_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /**
     * The width of a written time, as many characters as the format above writes.
     */
    private const val TIME_WIDTH = 19

    /**
     * The column standing in for the time of a request recorded without one.
     */
    private val NO_TIME = "-".repeat(TIME_WIDTH)

    /**
     * The column standing in for the type of a host that is not listed.
     */
    private const val NOT_LISTED = "NOT LISTED"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Export the given requests, reporting the outcome to the user.
     *
     * @param context The context to write and report through.
     * @param uri The file to write to, as picked by the user.
     * @param entries The requests to write.
     */
    fun export(context: Context, uri: Uri, entries: List<LogEntry>) {
        scope.launch {
            val exported = withContext(Dispatchers.IO) {
                try {
                    write(context, uri, entries)
                    true
                } catch (exception: IOException) {
                    Timber.e(exception, "Failed to export the recorded DNS requests.")
                    false
                }
            }
            notifyExportEnd(context, exported, uri.path?.let { File(it).name }.orEmpty())
        }
    }

    @UiThread
    private fun notifyExportEnd(context: Context, successful: Boolean, fileName: String) {
        ExpressiveToast.makeText(
            context,
            context.getString(
                if (successful) R.string.log_export_success else R.string.log_export_failed,
                fileName
            ),
            Toast.LENGTH_LONG
        ).show()
    }

    @Throws(IOException::class)
    private fun write(context: Context, uri: Uri, entries: List<LogEntry>) {
        val outputStream = context.contentResolver.openOutputStream(uri)
            ?: throw IOException("Could not open the export file.")
        outputStream.use { stream ->
            BufferedWriter(OutputStreamWriter(stream)).use { writer ->
                for (entry in entries) {
                    writer.write(format(entry))
                    writer.newLine()
                }
            }
        }
    }

    /**
     * Write one request as its time, how it is listed and the host name, in columns wide enough to
     * stay aligned, so the file reads as a log and can be searched line by line.
     */
    private fun format(entry: LogEntry): String {
        val time = entry.lastSeen
            ?.atZone(ZoneId.systemDefault())
            ?.format(TIME_FORMATTER)
            ?: NO_TIME
        val type = entry.type?.name ?: NOT_LISTED
        return "$time  ${type.padEnd(NOT_LISTED.length)}  ${entry.host}"
    }
}
