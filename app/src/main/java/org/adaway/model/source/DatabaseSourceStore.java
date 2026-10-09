package org.adaway.model.source;

import static org.adaway.db.entity.ListType.REDIRECTED;

import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.sqlite.db.SupportSQLiteStatement;

import org.adaway.db.AppDatabase;
import org.adaway.db.dao.HostListItemDao;
import org.adaway.db.dao.HostsSourceDao;
import org.adaway.db.entity.HostListItem;
import org.adaway.db.entity.ListType;
import org.adaway.db.entity.SourceRow;

import java.io.IOException;
import java.util.List;
import java.util.function.LongConsumer;

import timber.log.Timber;

/**
 * Stores the hosts of a source in the application database.
 * <p>
 * The lookups run once per host read, a million times for a large source, so they go through
 * statements compiled once per transaction: a query made through the DAO opens a cursor and its
 * window each time, which would cost more than the lookup itself.
 */
class DatabaseSourceStore implements SourceLoader.Store {
    /**
     * Find a row of the source listing a host with a type, answered by the source, type and host
     * index alone. The scalar sub-query always gives a row, so a host not listed reads as
     * {@link #NOT_FOUND} rather than as an error.
     */
    private static final String FIND_HOST = "SELECT IFNULL((SELECT id FROM hosts_lists WHERE source_id = ? AND type = ? AND host = ? LIMIT 1), " + NOT_FOUND + ")";
    /**
     * Find a row of the source redirecting a host to an address.
     */
    private static final String FIND_REDIRECTION = "SELECT IFNULL((SELECT id FROM hosts_lists WHERE source_id = ? AND type = ? AND host = ? AND redirection = ? LIMIT 1), " + NOT_FOUND + ")";
    private static final String DELETE_ROW = "DELETE FROM hosts_lists WHERE id = ?";
    /**
     * The number of rows read at a time while visiting the rows of the source.
     */
    private static final int PAGE_SIZE = 5_000;

    private final AppDatabase database;
    private final HostListItemDao hostListItemDao;
    private final HostsSourceDao hostsSourceDao;
    private final int sourceId;
    private SupportSQLiteStatement findHost;
    private SupportSQLiteStatement findRedirection;
    private SupportSQLiteStatement deleteRow;

    DatabaseSourceStore(AppDatabase database, int sourceId) {
        this.database = database;
        this.hostListItemDao = database.hostsListItemDao();
        this.hostsSourceDao = database.hostsSourceDao();
        this.sourceId = sourceId;
    }

    @Override
    public void runInTransaction(Runnable body) {
        this.database.runInTransaction(() -> {
            // Compiled on the database the transaction runs on, so they run in it too.
            SupportSQLiteDatabase db = this.database.getOpenHelper().getWritableDatabase();
            try {
                this.findHost = db.compileStatement(FIND_HOST);
                this.findRedirection = db.compileStatement(FIND_REDIRECTION);
                this.deleteRow = db.compileStatement(DELETE_ROW);
                body.run();
            } finally {
                close(this.findHost);
                close(this.findRedirection);
                close(this.deleteRow);
                this.findHost = null;
                this.findRedirection = null;
                this.deleteRow = null;
            }
        });
    }

    @Override
    public boolean isSourceEnabled() {
        // A source removed meanwhile counts as disabled. Adding its hosts then fails, as they
        // would belong to no source, and the update reports it.
        return Boolean.TRUE.equals(this.hostsSourceDao.isSourceEnabled(this.sourceId));
    }

    @Override
    public long getMaxId() {
        return this.hostListItemDao.getMaxId();
    }

    @Override
    public boolean hasHosts() {
        return this.hostListItemDao.hasSourceHosts(this.sourceId);
    }

    @Override
    public long findId(HostListItem item) {
        SupportSQLiteStatement statement;
        // Hosts read as blocked or allowed never carry a redirection, so only the redirected ones
        // need theirs compared, which the index does not hold.
        if (item.getType() == REDIRECTED) {
            statement = this.findRedirection;
            statement.bindString(4, item.getRedirection());
        } else {
            statement = this.findHost;
        }
        statement.bindLong(1, this.sourceId);
        statement.bindLong(2, item.getType().getValue());
        statement.bindString(3, item.getHost());
        return statement.simpleQueryForLong();
    }

    @Override
    public void insert(List<HostListItem> items) {
        this.hostListItemDao.insert(items);
    }

    @Override
    public void forEachId(LongConsumer action) {
        for (ListType type : ListType.values()) {
            String afterHost = "";
            long afterId = 0;
            while (true) {
                List<SourceRow> rows = this.hostListItemDao.getSourceRowsAfter(
                        this.sourceId, type.getValue(), afterHost, afterId, PAGE_SIZE);
                for (SourceRow row : rows) {
                    action.accept(row.getId());
                }
                if (rows.size() < PAGE_SIZE) {
                    break;
                }
                SourceRow last = rows.get(rows.size() - 1);
                afterHost = last.getHost();
                afterId = last.getId();
            }
        }
    }

    @Override
    public void delete(long id) {
        this.deleteRow.bindLong(1, id);
        this.deleteRow.executeUpdateDelete();
    }

    private static void close(SupportSQLiteStatement statement) {
        if (statement == null) {
            return;
        }
        try {
            statement.close();
        } catch (IOException e) {
            Timber.w(e, "Failed to close statement.");
        }
    }
}
