package org.adaway.util

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build

/**
 * Tells whether Android's Private DNS carries the DNS requests, encrypted over TLS.
 */
object PrivateDns {
    /**
     * Get the Private DNS server the active network sends its requests to.
     *
     * With the VPN on, the active network is the VPN's. Private DNS is only active there when its
     * requests go to the server named in the settings, which the VPN does not route through
     * itself.
     *
     * @param context The application context.
     * @return The name of the server, `automatic` when the system chose it, or `null` when the
     * requests are plain DNS.
     */
    @JvmStatic
    fun getActiveServerName(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return null
        }
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
            ?: return null
        val network = connectivityManager.activeNetwork ?: return null
        val linkProperties = connectivityManager.getLinkProperties(network) ?: return null
        if (!linkProperties.isPrivateDnsActive) {
            return null
        }
        return linkProperties.privateDnsServerName ?: "automatic"
    }
}
