package org.adaway.model.update;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;


import timber.log.Timber;

/**
 * This class is a {@link BroadcastReceiver} to install downloaded application updates.
 *
 * @author Bruce BUJON (bruce.bujon(at)gmail(dot)com)
 */
public class ApkDownloadReceiver extends BroadcastReceiver {
    private static final String APK_MIME_TYPE = "application/vnd.android.package-archive";
    private final long downloadId;

    public ApkDownloadReceiver(long downloadId) {
        this.downloadId = downloadId;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        //Fetching the download id received with the broadcast
        long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
        //Checking if the received broadcast is for our enqueued download by matching download id
        if (this.downloadId == id) {
            DownloadManager downloadManager = context.getSystemService(DownloadManager.class);
            Uri apkUri = downloadManager.getUriForDownloadedFile(id);
            if (apkUri == null) {
                Timber.w("Failed to download id: %s.", id);
            } else {
                installApk(context, apkUri);
            }
        }
    }

    private void installApk(Context context, Uri apkUri) {
        // Viewing an APK opens the system package installer, as the install action deprecated
        // since Android 10 did.
        Intent install = new Intent(Intent.ACTION_VIEW);
        install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        install.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        install.setDataAndType(apkUri, APK_MIME_TYPE);
        context.startActivity(install);
    }
}
