package org.adaway;

import android.app.Application;

import org.adaway.helper.NotificationHelper;
import org.adaway.helper.PreferenceHelper;
import org.adaway.helper.ProgressNotifications;
import org.adaway.model.adblocking.AdBlockMethod;
import org.adaway.model.adblocking.AdBlockModel;
import org.adaway.model.source.SourceModel;
import org.adaway.model.source.SourceUpdateService;
import org.adaway.model.update.ApkUpdateService;
import org.adaway.model.update.UpdateModel;
import org.adaway.util.CoroutineDispatchers;
import org.adaway.util.LegacyHttpCache;
import org.adaway.util.log.ApplicationLog;

/**
 * This class is a custom {@link Application} for AdAway app.
 *
 * @author Bruce BUJON (bruce.bujon(at)gmail(dot)com)
 */
public class AdAwayApplication extends Application {
    /**
     * The common source model for the whole application.
     */
    private SourceModel sourceModel;
    /**
     * The common ad block model for the whole application.
     */
    private AdBlockModel adBlockModel;
    /**
     * The common update model for the whole application.
     */
    private UpdateModel updateModel;

    @Override
    public void onCreate() {
        // Delegate application creation
        super.onCreate();
        // Initialize logging
        ApplicationLog.init(this);
        // Create notification channels
        NotificationHelper.createNotificationChannels(this);
        // Clears progress notifications left by a previous process and follows the
        // application state so they are only shown while it is not visible.
        ProgressNotifications.init(this);
        // Schedule the background work. Done here rather than as a side effect of building a
        // model, so the models can be built only when something actually needs them.
        SourceUpdateService.syncPreferences(this);
        ApkUpdateService.syncPreferences(this);
        // Remove what the former cache of source downloads left behind, up to 100 MB.
        CoroutineDispatchers.ioExecutor().execute(() -> LegacyHttpCache.clear(getCacheDir()));
    }

    /**
     * Get the source model.
     *
     * @return The common source model for the whole application.
     */
    public synchronized SourceModel getSourceModel() {
        if (this.sourceModel == null) {
            this.sourceModel = new SourceModel(this);
        }
        return this.sourceModel;
    }

    /**
     * Get the ad block model.
     *
     * @return The common ad block model for the whole application.
     */
    public AdBlockModel getAdBlockModel() {
        // Check cached model
        AdBlockMethod method = PreferenceHelper.getAdBlockMethod(this);
        if (this.adBlockModel == null || this.adBlockModel.getMethod() != method) {
            this.adBlockModel = AdBlockModel.build(this, method);
        }
        return this.adBlockModel;
    }

    /**
     * Get the update model.
     *
     * @return Teh common update model for the whole application.
     */
    public synchronized UpdateModel getUpdateModel() {
        if (this.updateModel == null) {
            this.updateModel = new UpdateModel(this);
        }
        return this.updateModel;
    }
}
