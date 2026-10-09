package org.adaway.vpn;

import static android.content.Context.CONNECTIVITY_SERVICE;
import static android.net.NetworkCapabilities.TRANSPORT_VPN;
import static org.adaway.broadcast.Command.START;
import static org.adaway.broadcast.Command.STOP;
import static org.adaway.vpn.VpnStatus.STOPPED;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import org.adaway.helper.PreferenceHelper;


/**
 * This utility class allows controlling (start and stop) the AdAway VPN service.
 *
 * @author Bruce BUJON (bruce.bujon(at)gmail(dot)com)
 */
public final class VpnServiceControls {
    /**
     * Private constructor.
     */
    private VpnServiceControls() {

    }

    /**
     * Check if the VPN service is currently running.
     *
     * @param context The application context.
     * @return {@code true} if the VPN service is currently running, {@code false} otherwise.
     */
    public static boolean isRunning(Context context) {
        boolean networkVpnCapability = checkActiveNetworkVpnCapability(context);
        VpnStatus status = PreferenceHelper.getVpnServiceStatus(context);
        if (status.isStarted() && !networkVpnCapability) {
            status = STOPPED;
            PreferenceHelper.setVpnServiceStatus(context, status);
        }
        return status.isStarted();
    }

    /**
     * Check if the VPN service is started.
     *
     * @param context The application context.
     * @return {@code true} if the VPN service is started, {@code false} otherwise.
     */
    public static boolean isStarted(Context context) {
        return PreferenceHelper.getVpnServiceStatus(context).isStarted();
    }

    /**
     * Start the VPN service.
     *
     * @param context The application context.
     * @return {@code true} if the service is started, {@code false} otherwise.
     */
    public static boolean start(Context context) {
        // Check if VPN is already running
        if (isRunning(context)) {
            return true;
        }
        // Start the VPN service
        Intent intent = new Intent(context, VpnService.class);
        START.appendToIntent(intent);
        boolean started = context.startForegroundService(intent) != null;
        if (started) {
            // Start the heartbeat
            VpnServiceHeartbeat.start(context);
        }
        return started;
    }

    /**
     * Stop the VPN service.
     *
     * @param context The application context.
     */
    public static void stop(Context context) {
        // Stop the heartbeat
        VpnServiceHeartbeat.stop(context);
        // Stop the service
        Intent intent = new Intent(context, VpnService.class);
        STOP.appendToIntent(intent);
        context.startService(intent);
    }

    /**
     * Check whether the network this app uses goes through a VPN. AdAway never excludes itself
     * from its own VPN, so while that runs it is the app's active network. Asking about it needs
     * none of the deprecated list of every network.
     */
    private static boolean checkActiveNetworkVpnCapability(Context context) {
        ConnectivityManager connectivityManager = (ConnectivityManager) context.getSystemService(CONNECTIVITY_SERVICE);
        Network activeNetwork = connectivityManager.getActiveNetwork();
        if (activeNetwork == null) {
            return false;
        }
        NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(activeNetwork);
        return capabilities != null && capabilities.hasTransport(TRANSPORT_VPN);
    }
}
