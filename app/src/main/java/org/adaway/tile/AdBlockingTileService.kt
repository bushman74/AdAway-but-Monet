package org.adaway.tile

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile.STATE_ACTIVE
import android.service.quicksettings.Tile.STATE_INACTIVE
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.annotation.StringRes
import org.adaway.AdAwayApplication
import org.adaway.R
import org.adaway.helper.PreferenceHelper
import org.adaway.model.adblocking.AdBlockModel
import org.adaway.model.error.HostErrorException
import org.adaway.util.CoroutineDispatchers
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A quick settings tile turning the ad blocking on and off.
 *
 * It is declared an active tile: the system binds it only when it is added, tapped, or asked to
 * redraw through [requestUpdate], which the ad block model does whenever the state changes. That
 * keeps it accurate without anything running in the background, and without being woken every
 * time the quick settings panel is expanded.
 */
class AdBlockingTileService : TileService() {
    override fun onTileAdded() {
        updateTile()
    }

    override fun onStartListening() {
        updateTile()
    }

    override fun onClick() {
        // A tap flips what the tile shows. The model may have just been built for this tap and
        // still be reading the hosts file, so its own state cannot be trusted yet, and the user
        // asked for the opposite of what they see anyway. Should the shown state be stale, the
        // outcome still matches it: applying again or reverting again leaves the tile right.
        val adBlocked = PreferenceHelper.getLastKnownAdBlocked(this)
        if (!toggling.compareAndSet(false, true)) {
            return
        }
        turningOn = !adBlocked
        updateTile()
        val context = applicationContext
        CoroutineDispatchers.ioExecutor().execute { toggleAdBlocking(context, adBlocked) }
    }

    /**
     * Draw the state the model remembered, as reading the real one opens a privileged shell, and
     * say so while it is being changed.
     */
    private fun updateTile() {
        val tile = qsTile ?: return
        tile.state = if (PreferenceHelper.getLastKnownAdBlocked(this)) STATE_ACTIVE else STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (toggling.get()) {
                getString(
                    if (turningOn) {
                        R.string.tile_ad_blocking_turning_on
                    } else {
                        R.string.tile_ad_blocking_turning_off
                    }
                )
            } else {
                null
            }
        }
        tile.updateTile()
    }

    private fun toggleAdBlocking(context: Context, adBlocked: Boolean) {
        try {
            // Only here is the model built on demand: acting on the tile is an explicit request.
            val model = model
            if (adBlocked) {
                model.revert()
            } else {
                model.apply()
            }
        } catch (exception: HostErrorException) {
            Timber.w(exception, "Failed to toggle ad-blocking.")
            showError(context, exception.error.messageKey)
        } finally {
            toggling.set(false)
            // The model asked for a redraw when it recorded the change, while the tile still said
            // it was busy. Ask again now that it is done, and also when nothing changed.
            requestUpdate(context)
        }
    }

    /**
     * Report a failure the user would otherwise never see: the tile has no room for it, and
     * release builds keep no log.
     */
    private fun showError(context: Context, @StringRes message: Int) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    private val model: AdBlockModel
        get() = (application as AdAwayApplication).adBlockModel

    companion object {
        /**
         * Whether a tap is being acted on. It is shared by every instance of the service, as the
         * system may release the one that was tapped and bind a new one before the change is done.
         */
        private val toggling = AtomicBoolean(false)

        /**
         * Whether the change being made turns the ad blocking on.
         */
        @Volatile
        private var turningOn = false

        /**
         * Ask the system to redraw the tile from the remembered state, even while the quick
         * settings panel is closed.
         *
         * @param context The application context.
         */
        @JvmStatic
        fun requestUpdate(context: Context) {
            try {
                TileService.requestListeningState(
                    context,
                    ComponentName(context, AdBlockingTileService::class.java)
                )
            } catch (exception: RuntimeException) {
                // Redrawing the tile must never get in the way of turning the ad blocking on or off.
                Timber.w(exception, "Failed to request an update of the ad-blocking tile.")
            }
        }
    }
}
