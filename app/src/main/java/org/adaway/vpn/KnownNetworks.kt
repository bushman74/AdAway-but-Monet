package org.adaway.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN
import android.net.NetworkRequest
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap

/**
 * The networks the system currently has, VPN ones included.
 *
 * It replaces [ConnectivityManager.getAllNetworks], deprecated since Android 12 in favour of a
 * network callback. The callback is registered once, on first use, and kept for the life of the
 * process: it only records networks as they come and go.
 */
object KnownNetworks {
    /**
     * How long a first call waits for the system to report the networks it already has. They are
     * reported one after the other right after the callback is registered, well within this time.
     */
    private const val FIRST_REPORT_MILLIS = 300L

    private val networks = ConcurrentHashMap.newKeySet<Network>()

    @Volatile
    private var registered = false

    @Volatile
    private var registrationTime = 0L

    /**
     * Get the networks the system currently has.
     *
     * @param context The application context.
     * @return The networks, in no particular order.
     */
    @JvmStatic
    fun all(context: Context): List<Network> {
        if (!registered) {
            register(context.applicationContext)
        }
        // The existing networks are reported just after registering. Off the main thread that is
        // waited for, so a caller asking at once does not get a partial list; the main thread is
        // never held.
        val remaining = registrationTime + FIRST_REPORT_MILLIS - SystemClock.elapsedRealtime()
        if (remaining > 0 && Looper.myLooper() != Looper.getMainLooper()) {
            SystemClock.sleep(remaining)
        }
        return networks.toList()
    }

    @Synchronized
    private fun register(context: Context) {
        if (registered) {
            return
        }
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
        // A request matches only non VPN networks by default, while the VPN ones are wanted too.
        val request = NetworkRequest.Builder()
            .removeCapability(NET_CAPABILITY_NOT_VPN)
            .build()
        connectivityManager.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                networks.add(network)
            }

            override fun onLost(network: Network) {
                networks.remove(network)
            }
        })
        registrationTime = SystemClock.elapsedRealtime()
        registered = true
    }
}
