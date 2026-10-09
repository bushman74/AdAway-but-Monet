package org.adaway.model.root

import android.content.Context
import android.content.Context.MODE_PRIVATE
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.adaway.R
import org.adaway.db.AppDatabase
import org.adaway.db.dao.HostEntryDao
import org.adaway.db.dao.HostsSourceDao
import org.adaway.db.dao.MetadataDao
import org.adaway.db.entity.ListType.REDIRECTED
import org.adaway.helper.PreferenceHelper
import org.adaway.model.adblocking.AdBlockMethod
import org.adaway.model.adblocking.AdBlockMethod.ROOT
import org.adaway.model.adblocking.AdBlockModel
import org.adaway.model.adblocking.DnsRequest
import org.adaway.model.error.HostError.COPY_FAIL
import org.adaway.model.error.HostError.NOT_ENOUGH_SPACE
import org.adaway.model.error.HostError.PRIVATE_FILE_FAILED
import org.adaway.model.error.HostError.REVERT_FAIL
import org.adaway.helper.NotificationHelper
import org.adaway.helper.ProgressNotifications
import org.adaway.model.error.HostErrorException
import org.adaway.model.root.MountType.READ_ONLY
import org.adaway.model.root.MountType.READ_WRITE
import org.adaway.tile.DnsRecordingTileService
import org.adaway.util.Constants.ANDROID_SYSTEM_ETC_HOSTS
import org.adaway.util.Constants.COMMAND_CHMOD_644
import org.adaway.util.Constants.COMMAND_CHOWN
import org.adaway.util.Constants.DEFAULT_HOSTS_FILENAME
import org.adaway.util.Constants.HOSTS_FILENAME
import org.adaway.util.Constants.LINE_SEPARATOR
import org.adaway.util.Constants.LOCALHOST_HOSTNAME
import org.adaway.util.Constants.LOCALHOST_IPV4
import org.adaway.util.Constants.LOCALHOST_IPV6
import org.adaway.util.WebServerUtils
import timber.log.Timber
import java.io.BufferedOutputStream
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * This class is the model to represent hosts file installation.
 *
 * @author Bruce BUJON (bruce.bujon(at)gmail(dot)com)
 */
class RootModel(context: Context) : AdBlockModel(context) {

    private val hostsSourceDao: HostsSourceDao
    private val hostEntryDao: HostEntryDao
    private val metadataDao: MetadataDao
    private val modelScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Held while the hosts file is read or changed. The check made when the model is built runs
     * in the background, and a quick settings tile builds the model only to change the file at
     * once. Without it the check could read the file before the change and record what it read
     * after the change, undoing the state the change recorded.
     */
    private val hostsFileLock = Any()

    init {
        val database = AppDatabase.getInstance(context)
        this.hostsSourceDao = database.hostsSourceDao()
        this.hostEntryDao = database.hostEntryDao()
        this.metadataDao = database.metadataDao()

        modelScope.launch { checkApplied() }
        modelScope.launch { syncPreferences(context) }
    }

    override fun getMethod(): AdBlockMethod = ROOT

    @Throws(HostErrorException::class)
    override fun apply() = synchronized(hostsFileLock) {
        setState(R.string.status_apply_sources)
        setState(R.string.status_create_new_hosts)
        createNewHostsFile()
        setState(R.string.status_copy_new_hosts)
        copyNewHostsFile()
        setState(R.string.status_check_copy)
        setState(R.string.status_hosts_updated)
        setApplied(true)
    }

    @Throws(HostErrorException::class)
    override fun revert() = synchronized(hostsFileLock) {
        setState(R.string.status_revert)
        try {
            revertHostFile()
            setState(R.string.status_revert_done)
            setApplied(false)
        } catch (exception: IOException) {
            throw HostErrorException(REVERT_FAIL, exception)
        }
    }

    override fun isRecordingLogs(): Boolean = TcpdumpUtils.isTcpdumpRunning()

    @Volatile
    private var recordingFailure: String? = null

    @Volatile
    private var recordingWarning: String? = null

    override fun getRecordingFailure(): String? = this.recordingFailure

    override fun getRecordingWarning(): String? = this.recordingWarning

    override fun setRecordingLogs(recording: Boolean) {
        if (recording) {
            // Only advertise a running capture once it is confirmed to be alive, and report the
            // reason when it is not: the capture runs outside the application, so a silent failure
            // is indistinguishable from the toggle not working.
            val failure = TcpdumpUtils.startTcpdump(this.context)
            this.recordingFailure = failure
            this.recordingWarning =
                if (failure == null) TcpdumpUtils.getEncryptedDnsWarning(this.context) else null
            if (failure == null) {
                NotificationHelper.showDnsRecordingNotification(this.context)
            } else {
                NotificationHelper.clearDnsRecordingNotification(this.context)
                NotificationHelper.showDnsRecordingFailureNotification(this.context, failure)
            }
            rememberRecordingState(failure == null)
        } else {
            this.recordingFailure = null
            this.recordingWarning = null
            TcpdumpUtils.stopTcpdump()
            NotificationHelper.clearDnsRecordingNotification(this.context)
            rememberRecordingState(false)
        }
    }

    /**
     * Remember whether the capture is running, for whoever cannot afford to ask.
     *
     * The quick settings tile cannot afford to run a privileged shell command to draw itself, so
     * it shows what was remembered here, and is asked to redraw. Recording it in the model rather
     * than in the tile keeps it true however the recording was turned on or off.
     */
    private fun rememberRecordingState(recording: Boolean) {
        PreferenceHelper.setLastKnownDnsRecording(this.context, recording)
        DnsRecordingTileService.requestUpdate(this.context)
    }

    override fun getRequests(): List<DnsRequest> = TcpdumpUtils.getRequests(this.context)

    override fun clearLogs() {
        TcpdumpUtils.clearLogFile(this.context)
    }

    private fun checkApplied() = synchronized(hostsFileLock) {
        var isApplied = false
        val result = Shell.cmd("head -n 1 $ANDROID_SYSTEM_ETC_HOSTS").exec()
        if (!result.isSuccess) {
            Timber.e("Failed to read first line of hosts file. Error code: %s", result.code)
        } else {
            isApplied = ShellUtils.mergeAllLines(result.out).startsWith(HEADER1)
        }
        setApplied(isApplied)
    }

    private fun syncPreferences(context: Context) {
        if (PreferenceHelper.getWebServerEnabled(context) && !WebServerUtils.isWebServerRunning()) {
            WebServerUtils.startWebServer(context)
        }
    }

    @Throws(HostErrorException::class)
    private fun copyNewHostsFile() {
        try {
            copyHostsFile(HOSTS_FILENAME)
        } catch (exception: CommandException) {
            throw HostErrorException(COPY_FAIL, exception)
        }
    }

    @Throws(HostErrorException::class)
    private fun createNewHostsFile() {
        val fingerprint = computeFingerprint()
        if (fingerprint != null && fingerprint == readGeneratedFingerprint()) {
            Timber.d("Reusing the generated hosts file: its fingerprint is unchanged.")
            return
        }
        // Only the regeneration is worth reporting: reusing the file is immediate.
        ProgressNotifications.report(this.context, ProgressNotifications.Kind.APPLY_CONFIGURATION, 0)
        // Written aside, then moved over the previous file in one step. A file written in place
        // and cut short, by a full storage or the process being killed, kept the fingerprint at
        // its top and was later reused, then copied to the system, as if it were complete.
        val temporaryFile = this.context.getFileStreamPath(TEMPORARY_HOSTS_FILENAME)
        try {
            this.context.openFileOutput(TEMPORARY_HOSTS_FILENAME, MODE_PRIVATE).use { output ->
                val writer = BufferedWriter(
                    OutputStreamWriter(BufferedOutputStream(output, WRITE_BUFFER_SIZE), Charsets.UTF_8),
                    WRITE_BUFFER_SIZE
                )
                writeHostsHeader(writer, fingerprint)
                writeLoopbackToHosts(writer)
                writeHosts(writer)
                writer.flush()
                // On the storage before the move, so the moved file is never one still partly
                // in memory when the device stops.
                output.fd.sync()
            }
            if (!temporaryFile.renameTo(this.context.getFileStreamPath(HOSTS_FILENAME))) {
                throw IOException("Failed to replace the generated hosts file.")
            }
        } catch (exception: IOException) {
            temporaryFile.delete()
            throw HostErrorException(PRIVATE_FILE_FAILED, exception)
        } finally {
            ProgressNotifications.done(this.context, ProgressNotifications.Kind.APPLY_CONFIGURATION)
        }
    }

    /**
     * Compute the fingerprint of the hosts file that would be generated now.
     *
     * @return The fingerprint, or {@code null} when it could not be computed, in which case the
     * file must be regenerated rather than reused.
     */
    private fun computeFingerprint(): String? {
        return try {
            HostsFingerprint.compute(
                entriesRevision = this.metadataDao.getHostEntriesRevision(),
                entryCount = this.hostEntryDao.count,
                redirectionIpv4 = PreferenceHelper.getRedirectionIpv4(this.context),
                redirectionIpv6 = PreferenceHelper.getRedirectionIpv6(this.context),
                ipv6Enabled = PreferenceHelper.getEnableIpv6(this.context),
                enabledSources = this.hostsSourceDao.enabled.map { "${it.id}:${it.label}:${it.url}" }
            )
        } catch (exception: RuntimeException) {
            Timber.w(exception, "Failed to compute the hosts file fingerprint.")
            null
        }
    }

    /**
     * Read the fingerprint embedded in the previously generated hosts file.
     *
     * @return The fingerprint, or {@code null} when there is no readable one.
     */
    private fun readGeneratedFingerprint(): String? {
        return try {
            this.context.openFileInput(HOSTS_FILENAME).bufferedReader().use { reader ->
                HostsFingerprint.fromHeaderLines(reader.lineSequence().take(FINGERPRINT_HEADER_SCAN_LINES).asIterable())
            }
        } catch (exception: IOException) {
            null
        }
    }

    @Throws(IOException::class)
    private fun writeHostsHeader(writer: BufferedWriter, fingerprint: String?) {
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val now = Date()
        val date = formatter.format(now)

        writer.write(HEADER1)
        writer.write(date)
        writer.newLine()
        writer.write(HEADER2)
        writer.newLine()

        if (fingerprint != null) {
            writer.write(HostsFingerprint.toHeaderLine(fingerprint))
            writer.newLine()
        }

        writer.write(HEADER_SOURCES)
        writer.newLine()
        for (hostsSource in this.hostsSourceDao.enabled) {
            writer.write("# - ${hostsSource.label}:${hostsSource.url}")
            writer.newLine()
        }
        writer.newLine()
    }

    @Throws(IOException::class)
    private fun writeLoopbackToHosts(writer: BufferedWriter) {
        writer.write("$LOCALHOST_IPV4 $LOCALHOST_HOSTNAME")
        writer.newLine()
        writer.write("$LOCALHOST_IPV6 $LOCALHOST_HOSTNAME")
        writer.newLine()
    }

    @Throws(IOException::class)
    private fun writeHosts(writer: BufferedWriter) {
        val redirectionIpv4 = PreferenceHelper.getRedirectionIpv4(this.context)
        val redirectionIpv6 = PreferenceHelper.getRedirectionIpv6(this.context)
        val enableIpv6 = PreferenceHelper.getEnableIpv6(this.context)

        // The start of every blocked line, built once rather than once per line.
        val blockedPrefix = "$redirectionIpv4 "
        val blockedPrefixIpv6 = "$redirectionIpv6 "
        // Read the entries in pages: materialising millions of them at once was a large
        // allocation spike for no benefit, since each one is written and then discarded.
        val progress = ProgressReporter(this.hostEntryDao.count) { percent ->
            ProgressNotifications.report(
                this.context, ProgressNotifications.Kind.APPLY_CONFIGURATION, percent
            )
        }
        HostEntryPager.forEachEntry(
            fetch = { afterHost, limit -> this.hostEntryDao.getEntriesAfter(afterHost, limit) },
            hostOf = { it.host },
            action = { entry ->
                progress.increment()
                // Written piece by piece: a line built as a string first made two short-lived
                // objects per line, millions per file.
                val hostname = entry.host
                if (entry.type == REDIRECTED) {
                    writer.write(entry.redirection ?: "null")
                    writer.write(' '.code)
                    writer.write(hostname)
                    writer.newLine()
                } else {
                    writer.write(blockedPrefix)
                    writer.write(hostname)
                    writer.newLine()
                    if (enableIpv6) {
                        writer.write(blockedPrefixIpv6)
                        writer.write(hostname)
                        writer.newLine()
                    }
                }
            }
        )
    }

    @Throws(IOException::class)
    private fun revertHostFile() {
        try {
            this.context.openFileOutput(DEFAULT_HOSTS_FILENAME, MODE_PRIVATE).use { fos ->
                val localhost = "$LOCALHOST_IPV4 $LOCALHOST_HOSTNAME$LINE_SEPARATOR$LOCALHOST_IPV6 $LOCALHOST_HOSTNAME$LINE_SEPARATOR"
                fos.write(localhost.toByteArray())

                copyHostsFile(DEFAULT_HOSTS_FILENAME)

                this.context.deleteFile(DEFAULT_HOSTS_FILENAME)
            }
        } catch (exception: Exception) {
            throw IOException("Unable to revert hosts file.", exception)
        }
    }

    @Throws(HostErrorException::class, CommandException::class)
    private fun copyHostsFile(source: String) {
        val privateDir = this.context.filesDir.absolutePath
        val privateFile = privateDir + File.separator + source

        val target = ANDROID_SYSTEM_ETC_HOSTS
        val targetFile = File(target)

        val size = File(privateFile).length()
        Timber.i("Size of hosts file: %s.", size)
        if (!hasEnoughSpaceOnPartition(targetFile, size)) {
            throw HostErrorException(NOT_ENOUGH_SPACE)
        }

        val writable = ShellUtils.isWritable(targetFile)
        try {
            if (!writable) {
                Timber.i("Remounting for RW…")
                if (!ShellUtils.remountPartition(targetFile, READ_WRITE)) {
                    throw CommandException("Failed to remount hosts file partition as read-write.")
                }
            }
            // Copied a megabyte at a time: by default dd copies 512 bytes at a time, which takes
            // hundreds of thousands of reads and writes for a large hosts file.
            val result = Shell.cmd(
                "dd if=$privateFile of=$target bs=$COPY_BLOCK_SIZE",
                "$COMMAND_CHOWN $target",
                "$COMMAND_CHMOD_644 $target"
            ).exec()
            if (!result.isSuccess) {
                throw CommandException("Failed to copy hosts file: ${ShellUtils.mergeAllLines(result.err)}")
            }
        } finally {
            if (!writable) {
                ShellUtils.remountPartition(targetFile, READ_ONLY)
            }
        }
    }

    private fun hasEnoughSpaceOnPartition(target: File, size: Long): Boolean {
        val freeSpace = target.freeSpace
        return freeSpace == 0L || freeSpace > size
    }

    companion object {
        private const val HEADER1 = "# This hosts file has been generated by AdAway on: "
        private const val HEADER2 = "# Please do not modify it directly, it will be overwritten when AdAway is applied again."
        private const val HEADER_SOURCES = "# This file is generated from the following sources:"

        /**
         * The number of leading lines scanned for the fingerprint header.
         * Generous enough to cover the source list, bounded so a corrupted file is not read whole.
         */
        private const val FINGERPRINT_HEADER_SCAN_LINES = 200

        /**
         * The file the hosts file is written to before it replaces the previous one.
         */
        private const val TEMPORARY_HOSTS_FILENAME = "$HOSTS_FILENAME.tmp"

        /**
         * The size of the buffers the hosts file is written through, in characters then bytes.
         */
        private const val WRITE_BUFFER_SIZE = 64 * 1024

        /**
         * The number of bytes dd copies at a time.
         */
        private const val COPY_BLOCK_SIZE = 1024 * 1024
    }
}
