package org.adaway.helper

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.os.Build
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import org.adaway.R
import org.adaway.ui.home.HomeActivity
import org.adaway.ui.navigation.AdAwayRoute
import org.adaway.ui.navigation.NavigationRequest

object NotificationHelper {
    const val UPDATE_NOTIFICATION_CHANNEL = "UpdateChannel"
    const val VPN_SERVICE_NOTIFICATION_CHANNEL = "VpnServiceChannel"
    const val DNS_RECORDING_NOTIFICATION_CHANNEL = "DnsRecordingChannel"
    
    private const val UPDATE_HOSTS_NOTIFICATION_ID = 10
    private const val UPDATE_APP_NOTIFICATION_ID = 11
    private const val UPDATE_HOSTS_PROGRESS_NOTIFICATION_ID = 16
    private const val UPDATE_APP_PROGRESS_NOTIFICATION_ID = 17
    private const val DNS_RECORDING_NOTIFICATION_ID = 18
    private const val APPLY_CONFIGURATION_NOTIFICATION_ID = 19
    private const val DNS_RECORDING_FAILURE_NOTIFICATION_ID = 22
    private const val SOURCE_UPDATE_FAILURE_NOTIFICATION_ID = 23
    
    @JvmField
    val VPN_RUNNING_SERVICE_NOTIFICATION_ID = 20
    
    @JvmField
    val VPN_RESUME_SERVICE_NOTIFICATION_ID = 21

    @JvmStatic
    fun createNotificationChannels(context: Context) {
        val notificationManager = context.getSystemService(NotificationManager::class.java) ?: return

        // Create update notification channel
        val updateChannel = NotificationChannel(
            UPDATE_NOTIFICATION_CHANNEL,
            context.getString(R.string.notification_update_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.notification_update_channel_description)
        }

        // Create VPN service notification channel
        val vpnServiceChannel = NotificationChannel(
            VPN_SERVICE_NOTIFICATION_CHANNEL,
            context.getString(R.string.notification_vpn_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.notification_vpn_channel_description)
        }

        // Create DNS recording notification channel
        val dnsRecordingChannel = NotificationChannel(
            DNS_RECORDING_NOTIFICATION_CHANNEL,
            context.getString(R.string.notification_dns_recording_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.notification_dns_recording_channel_description)
        }

        notificationManager.createNotificationChannel(updateChannel)
        notificationManager.createNotificationChannel(vpnServiceChannel)
        notificationManager.createNotificationChannel(dnsRecordingChannel)
    }

    /**
     * Show the progress of a configuration being applied.
     *
     * @param percent The share of the hosts file already written, between 0 and 100.
     */
    @JvmStatic
    fun showApplyConfigurationProgressNotification(context: Context, percent: Int) {
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        if (notificationManager == null || !notificationManager.areNotificationsEnabled()) {
            return
        }

        val builder = buildUpdateProgressNotification(
            context = context,
            notificationManager = notificationManager,
            title = context.getString(R.string.notification_apply_configuration_title),
            text = context.getString(R.string.notification_apply_configuration_text),
            progress = percent.coerceIn(0, 100),
            route = AdAwayRoute.HOME
        )

        notificationManager.notify(APPLY_CONFIGURATION_NOTIFICATION_ID, builder.build())
    }

    /**
     * Clear the configuration application notification.
     */
    @JvmStatic
    fun clearApplyConfigurationNotification(context: Context) {
        val notificationManager = context.getSystemService(NotificationManager::class.java) ?: return
        notificationManager.cancel(APPLY_CONFIGURATION_NOTIFICATION_ID)
    }

    /**
     * Show the ongoing notification for an active DNS request recording.
     */
    @JvmStatic
    fun showDnsRecordingNotification(context: Context) {
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        if (notificationManager == null || !notificationManager.areNotificationsEnabled()) {
            return
        }

        val intent = Intent(context, HomeActivity::class.java).apply {
            putExtra(NavigationRequest.EXTRA_ROUTE, AdAwayRoute.LOG)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, DNS_RECORDING_NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.logo)
            .setColor(context.getColor(R.color.notification))
            .setContentTitle(context.getString(R.string.notification_dns_recording_title))
            .setContentText(context.getString(R.string.notification_dns_recording_text))
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setShowWhen(false)

        notificationManager.notify(DNS_RECORDING_NOTIFICATION_ID, builder.build())
    }

    /**
     * Report that a DNS request recording could not be started, and why.
     */
    @JvmStatic
    fun showDnsRecordingFailureNotification(context: Context, reason: String) {
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        if (notificationManager == null || !notificationManager.areNotificationsEnabled()) {
            return
        }

        val intent = Intent(context, HomeActivity::class.java).apply {
            putExtra(NavigationRequest.EXTRA_ROUTE, AdAwayRoute.LOG)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(context, DNS_RECORDING_NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.logo)
            .setColor(context.getColor(R.color.notification))
            .setContentTitle(context.getString(R.string.notification_dns_recording_failed_title))
            .setContentText(reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)

        notificationManager.notify(DNS_RECORDING_FAILURE_NOTIFICATION_ID, builder.build())
    }

    /**
     * Clear the DNS request recording notification.
     */
    @JvmStatic
    fun clearDnsRecordingNotification(context: Context) {
        val notificationManager = context.getSystemService(NotificationManager::class.java) ?: return
        notificationManager.cancel(DNS_RECORDING_NOTIFICATION_ID)
        notificationManager.cancel(DNS_RECORDING_FAILURE_NOTIFICATION_ID)
    }

    /**
     * Name the sources an update had to skip. It can be swiped away, and opens the source list,
     * where each of them says why it failed.
     */
    @JvmStatic
    fun showSourceUpdateFailureNotification(context: Context, sourceLabels: List<String>) {
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        if (notificationManager == null || !notificationManager.areNotificationsEnabled()) {
            return
        }

        val intent = Intent(context, HomeActivity::class.java).apply {
            putExtra(NavigationRequest.EXTRA_ROUTE, AdAwayRoute.HOSTS)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            SOURCE_UPDATE_FAILURE_NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val title = context.resources.getQuantityString(
            R.plurals.notification_source_update_failed_title,
            sourceLabels.size,
            sourceLabels.size
        )
        val text = sourceLabels.joinToString(separator = ", ")

        val builder = NotificationCompat.Builder(context, UPDATE_NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.logo)
            .setColor(context.getColor(R.color.notification))
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(sourceLabels.joinToString("\n")))
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOngoing(false)
            .setAutoCancel(true)

        notificationManager.notify(SOURCE_UPDATE_FAILURE_NOTIFICATION_ID, builder.build())
    }

    /**
     * Withdraw the list of skipped sources, once an update reaches all of them.
     */
    @JvmStatic
    fun clearSourceUpdateFailureNotification(context: Context) {
        val notificationManager = context.getSystemService(NotificationManager::class.java) ?: return
        notificationManager.cancel(SOURCE_UPDATE_FAILURE_NOTIFICATION_ID)
    }

    @JvmStatic
    fun showUpdateHostsNotification(context: Context) {
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        if (notificationManager == null || !notificationManager.areNotificationsEnabled()) {
            return
        }

        val color = context.getColor(R.color.notification)
        val title = context.getString(R.string.notification_update_host_available_title)
        val text = context.getString(R.string.notification_update_host_available_text)
        
        val intent = Intent(context, HomeActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, UPDATE_NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.logo)
            .setColorized(true)
            .setColor(color)
            .setShowWhen(false)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)

        notificationManager.notify(UPDATE_HOSTS_NOTIFICATION_ID, builder.build())
    }

    /**
     * Show the progress of a hosts sources update.
     *
     * @param percent The completion, or {@code null} when it is not known yet.
     * @param text The text describing what is happening, or {@code null} for the default one.
     */
    @JvmStatic
    @JvmOverloads
    fun showUpdateHostsProgressNotification(
        context: Context,
        percent: Int? = null,
        text: String? = null
    ) {
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        if (notificationManager == null || !notificationManager.areNotificationsEnabled()) {
            return
        }

        val builder = buildUpdateProgressNotification(
            context = context,
            notificationManager = notificationManager,
            title = context.getString(R.string.notification_update_host_progress_title),
            text = text ?: context.getString(R.string.notification_update_host_progress_text),
            progress = percent?.coerceIn(0, 100),
            route = AdAwayRoute.HOSTS
        )

        notificationManager.notify(UPDATE_HOSTS_PROGRESS_NOTIFICATION_ID, builder.build())
    }

    @JvmStatic
    fun clearUpdateHostsProgressNotification(context: Context) {
        val notificationManager = context.getSystemService(NotificationManager::class.java) ?: return
        notificationManager.cancel(UPDATE_HOSTS_PROGRESS_NOTIFICATION_ID)
    }

    @JvmStatic
    fun showUpdateApplicationNotification(context: Context) {
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        if (notificationManager == null || !notificationManager.areNotificationsEnabled()) {
            return
        }

        val color = context.getColor(R.color.notification)
        val title = context.getString(R.string.notification_update_app_available_title)
        val text = context.getString(R.string.notification_update_app_available_text)
        
        val intent = Intent(context, HomeActivity::class.java).apply {
            putExtra(NavigationRequest.EXTRA_ROUTE, AdAwayRoute.UPDATE)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, UPDATE_NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.logo)
            .setColorized(true)
            .setColor(color)
            .setShowWhen(false)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)

        notificationManager.notify(UPDATE_APP_NOTIFICATION_ID, builder.build())
    }

    @JvmStatic
    fun showUpdateApplicationProgressNotification(context: Context, progress: Int, text: String) {
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        if (notificationManager == null || !notificationManager.areNotificationsEnabled()) {
            return
        }

        val builder = buildUpdateProgressNotification(
            context = context,
            notificationManager = notificationManager,
            title = context.getString(R.string.notification_update_app_progress_title),
            text = text,
            progress = progress.coerceIn(0, 100),
            route = AdAwayRoute.UPDATE
        )

        notificationManager.notify(UPDATE_APP_PROGRESS_NOTIFICATION_ID, builder.build())
    }

    @JvmStatic
    fun showUpdateApplicationProgressNotification(context: Context) {
        showUpdateApplicationProgressNotification(
            context,
            0,
            context.getString(R.string.update_notification_description)
        )
    }

    @JvmStatic
    fun clearUpdateApplicationProgressNotification(context: Context) {
        val notificationManager = context.getSystemService(NotificationManager::class.java) ?: return
        notificationManager.cancel(UPDATE_APP_PROGRESS_NOTIFICATION_ID)
    }

    @JvmStatic
    fun clearUpdateNotifications(context: Context) {
        val notificationManager = context.getSystemService(NotificationManager::class.java) ?: return
        notificationManager.cancel(UPDATE_HOSTS_NOTIFICATION_ID)
        notificationManager.cancel(UPDATE_APP_NOTIFICATION_ID)
        notificationManager.cancel(UPDATE_HOSTS_PROGRESS_NOTIFICATION_ID)
        notificationManager.cancel(UPDATE_APP_PROGRESS_NOTIFICATION_ID)
    }

    private fun buildUpdateProgressNotification(
        context: Context,
        notificationManager: NotificationManager,
        title: String,
        text: String,
        progress: Int?,
        route: String
    ): NotificationCompat.Builder {
        val intent = Intent(context, HomeActivity::class.java).apply {
            putExtra(NavigationRequest.EXTRA_ROUTE, route)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            route.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, UPDATE_NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.logo)
            .setColor(context.getColor(R.color.notification))
            .setColorized(false)
            .setShowWhen(false)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setProgress(100, progress ?: 0, progress == null)
            .apply {
                requestPromotedOngoing(notificationManager)
            }
    }

    private fun NotificationCompat.Builder.requestPromotedOngoing(notificationManager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= 36 && notificationManager.canPostPromotedNotifications()) {
            extras.putBoolean("android.requestPromotedOngoing", true)
        }
    }
}
