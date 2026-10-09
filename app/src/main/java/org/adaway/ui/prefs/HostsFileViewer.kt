package org.adaway.ui.prefs

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import org.adaway.util.Constants.ANDROID_SYSTEM_ETC_HOSTS
import java.io.File
import java.io.IOException

/**
 * Hands a copy of the system hosts file to a text viewer.
 *
 * Android refuses to pass a file path to another app since version 7, and the app crashed when it
 * tried, so the file is copied to the cache and shared through the file provider instead. The copy
 * is for reading: AdAway rewrites the system file itself whenever the configuration is applied.
 */
internal object HostsFileViewer {
    private const val SHARED_DIRECTORY = "shared"
    private const val FILE_NAME = "hosts.txt"
    private const val AUTHORITY_SUFFIX = ".files"

    /**
     * Copy the system hosts file where it can be shared. It reads a large file, so it must not
     * run on the main thread.
     *
     * @return The address to share the copy with.
     * @throws IOException If the hosts file could not be read or copied.
     */
    @Throws(IOException::class)
    fun copyHostsFile(context: Context): Uri {
        val directory = File(context.cacheDir, SHARED_DIRECTORY)
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("Failed to create $directory")
        }
        val copy = File(directory, FILE_NAME)
        File(ANDROID_SYSTEM_ETC_HOSTS).inputStream().use { input ->
            copy.outputStream().use { output -> input.copyTo(output) }
        }
        return FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, copy)
    }

    /**
     * Build the request to show the shared copy, with the permission to read it.
     */
    fun viewIntent(uri: Uri): Intent {
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "text/plain")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
