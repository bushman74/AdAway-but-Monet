package org.adaway.tile

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.Tile.STATE_ACTIVE
import android.service.quicksettings.Tile.STATE_INACTIVE
import android.service.quicksettings.TileService
import org.adaway.AdAwayApplication
import org.adaway.helper.PreferenceHelper
import org.adaway.model.adblocking.AdBlockMethod.ROOT
import org.adaway.model.adblocking.AdBlockModel
import org.adaway.util.CoroutineDispatchers
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A quick settings tile toggling the DNS request recording.
 *
 * Like the ad-blocking tile, it is an active tile: the model asks it to redraw through
 * [requestUpdate] whenever the recording starts or stops.
 */
class DnsRecordingTileService : TileService() {
    private val toggling = AtomicBoolean(false)

    override fun onTileAdded() {
        updateTile(PreferenceHelper.getLastKnownDnsRecording(this))
    }

    override fun onStartListening() {
        // Reading the real state runs a privileged shell command, so render the last known state.
        updateTile(PreferenceHelper.getLastKnownDnsRecording(this))
    }

    override fun onClick() {
        CoroutineDispatchers.ioExecutor().execute(::toggleRecording)
    }

    private fun updateTile(recording: Boolean) {
        qsTile?.let { tile ->
            tile.state = if (recording) STATE_ACTIVE else STATE_INACTIVE
            tile.updateTile()
        }
    }

    private fun toggleRecording() {
        if (toggling.getAndSet(true)) {
            return
        }
        try {
            // The model is only built here: acting on the tile is an explicit request.
            val model = model
            if (model.method != ROOT) {
                Timber.i("DNS recording is only available with the root method.")
                return
            }
            model.setRecordingLogs(!model.isRecordingLogs)
            // Report the state the capture actually ended in rather than the requested one. The
            // model is what remembers it, so the tile reads it back rather than writing its own.
            updateTile(model.isRecordingLogs)
        } catch (exception: RuntimeException) {
            Timber.w(exception, "Failed to toggle DNS recording.")
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
                    ComponentName(context, DnsRecordingTileService::class.java)
                )
            } catch (exception: RuntimeException) {
                // Redrawing the tile must never get in the way of the recording itself.
                Timber.w(exception, "Failed to request an update of the DNS recording tile.")
            }
        }
    }
}
