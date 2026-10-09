package org.adaway.tile

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.Tile.STATE_ACTIVE
import android.service.quicksettings.Tile.STATE_INACTIVE
import android.service.quicksettings.TileService
import org.adaway.AdAwayApplication
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
    private val toggling = AtomicBoolean(false)

    override fun onTileAdded() {
        updateTile(PreferenceHelper.getLastKnownAdBlocked(this))
    }

    override fun onStartListening() {
        // Draw the state the model remembered: reading the real one opens a privileged shell.
        updateTile(PreferenceHelper.getLastKnownAdBlocked(this))
    }

    override fun onClick() {
        CoroutineDispatchers.ioExecutor().execute(::toggleAdBlocking)
    }

    private fun updateTile(adBlocked: Boolean) {
        qsTile?.let { tile ->
            tile.state = if (adBlocked) STATE_ACTIVE else STATE_INACTIVE
            tile.updateTile()
        }
    }

    private fun toggleAdBlocking() {
        if (toggling.getAndSet(true)) {
            return
        }
        try {
            // Only here is the model built on demand: acting on the tile is an explicit request.
            val model = model
            val applied = model.isApplied.value == true
            if (applied) {
                model.revert()
            } else {
                model.apply()
            }
            PreferenceHelper.setLastKnownAdBlocked(this, !applied)
            updateTile(!applied)
        } catch (exception: HostErrorException) {
            Timber.w(exception, "Failed to toggle ad-blocking.")
        } finally {
            toggling.set(false)
        }
    }

    private val model: AdBlockModel
        get() = (application as AdAwayApplication).adBlockModel

    companion object {
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
