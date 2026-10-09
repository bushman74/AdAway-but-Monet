package org.adaway.model.source;

import static org.adaway.db.entity.ListType.ALLOWED;
import static org.adaway.db.entity.ListType.BLOCKED;
import static org.adaway.db.entity.ListType.REDIRECTED;
import static org.adaway.util.Constants.BOGUS_IPV4;
import static org.adaway.util.Constants.LOCALHOST_HOSTNAME;
import static org.adaway.util.Constants.LOCALHOST_IPV4;
import static org.adaway.util.Constants.LOCALHOST_IPV6;

import androidx.annotation.Nullable;

import org.adaway.db.AppDatabase;
import org.adaway.db.dao.HostListItemDao;
import org.adaway.db.entity.HostListItem;
import org.adaway.db.entity.HostsSource;
import org.adaway.db.entity.ListType;
import org.adaway.util.RegexUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import timber.log.Timber;

/**
 * This class is an {@link HostsSource} loader.<br>
 * It parses a source and loads it to database.
 * <p>
 * Reading and parsing run on a thread of their own, while the calling thread stores the hosts, so
 * the download and the database writes overlap. The hosts are handed over in batches rather than
 * one by one: a hand-over costs about as much as parsing a line, so doing it per line used to take
 * longer than the parsing itself.
 *
 * @author Bruce BUJON (bruce.bujon(at)gmail(dot)com)
 */
class SourceLoader {
    private static final String TAG = "SourceLoader";
    /**
     * The number of hosts handed over, then inserted, at a time.
     */
    static final int BATCH_SIZE = 1_000;
    /**
     * The number of batches parsed ahead of the ones being stored. It bounds the memory held by a
     * source being loaded to a few thousand hosts, however large the source is.
     */
    private static final int QUEUE_CAPACITY = 16;
    /**
     * How long the storing thread waits for a batch before checking the parsing one is still
     * running, so a parser that died without a word can never leave it waiting forever.
     */
    private static final long POLL_TIMEOUT_SECONDS = 1;
    private static final String HOSTS_PARSER = "^\\s*([^#\\s]+)\\s+([^#\\s]+).*$";
    static final Pattern HOSTS_PARSER_PATTERN = Pattern.compile(HOSTS_PARSER);

    private final HostsSource source;

    SourceLoader(HostsSource hostsSource) {
        this.source = hostsSource;
    }

    /**
     * Parse a source and replace its hosts with the ones read.
     *
     * The previous hosts are replaced only once the whole source was read. When reading fails
     * part way, for instance because the connection drops, nothing is changed and the failure is
     * thrown, so the source keeps its previous hosts and is reported as not updated.
     *
     * @throws IOException If the source could not be read to its end.
     */
    void parse(BufferedReader reader, AppDatabase database, HostListItemDao hostListItemDao)
            throws IOException {
        int sourceId = this.source.getId();
        int inserted = load(reader, new Store() {
            @Override
            public void runInTransaction(Runnable body) {
                database.runInTransaction(body);
            }

            @Override
            public void clear() {
                hostListItemDao.clearSourceHosts(sourceId);
            }

            @Override
            public void insert(List<HostListItem> items) {
                hostListItemDao.insert(items);
            }
        });
        Timber.i("%s host list items inserted.", inserted);
    }

    /**
     * Read the source and store its hosts.
     *
     * @param reader The source content.
     * @param store  Where the hosts are stored.
     * @return The number of hosts stored.
     * @throws IOException If the source could not be read or stored whole. Nothing is changed then.
     */
    int load(BufferedReader reader, Store store) throws IOException {
        BlockingQueue<Batch> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> new Thread(r, TAG));
        Future<?> parsing = executor.submit(() -> parseAll(reader, queue));
        int[] inserted = {0};
        try {
            // Clear the previous hosts and insert the new ones in a single transaction, on this
            // thread. Readers keep seeing the previous content until it commits, and throwing out
            // of it rolls it back, so a source is either replaced whole or left as it was.
            store.runInTransaction(() -> {
                store.clear();
                List<HostListItem> items;
                while ((items = take(queue, parsing)) != null) {
                    store.insert(items);
                    inserted[0] += items.size();
                }
            });
            return inserted[0];
        } catch (SourceReadException e) {
            throw e.getCause();
        } catch (RuntimeException e) {
            // A database failure: reported as the source failing, like a failed download, so the
            // update goes on with the other sources.
            throw new IOException("Failed to store hosts source.", e);
        } finally {
            // Stops the parser when the storing ended early. Waiting for it is not needed: it
            // stops at its next hand-over, and closing the reader ends a read in progress.
            parsing.cancel(true);
            executor.shutdown();
        }
    }

    /**
     * Take the next batch of hosts.
     *
     * @return The next hosts, or {@code null} once the whole source was read.
     * @throws SourceReadException If the source could not be read to its end.
     */
    @Nullable
    private static List<HostListItem> take(BlockingQueue<Batch> queue, Future<?> parsing) {
        try {
            while (true) {
                Batch batch = queue.poll(POLL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (batch == null && parsing.isDone()) {
                    // Whatever the parser handed over before ending is in the queue by now.
                    batch = queue.poll();
                    if (batch == null) {
                        throw new SourceReadException(new IOException("Hosts source parser stopped unexpectedly."));
                    }
                }
                if (batch != null) {
                    if (batch.failure != null) {
                        throw new SourceReadException(batch.failure);
                    }
                    return batch.items;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            InterruptedIOException exception = new InterruptedIOException("Interrupted while loading hosts source.");
            exception.initCause(e);
            throw new SourceReadException(exception);
        }
    }

    /**
     * Read and parse the whole source, handing the hosts over in batches, then the end of the
     * source or the failure that stopped the reading.
     */
    private void parseAll(BufferedReader reader, BlockingQueue<Batch> queue) {
        try {
            List<HostListItem> items = new ArrayList<>(BATCH_SIZE);
            String line;
            while ((line = reader.readLine()) != null) {
                HostListItem item = parseLine(line);
                if (item != null) {
                    items.add(item);
                    if (items.size() == BATCH_SIZE) {
                        queue.put(new Batch(items, null));
                        items = new ArrayList<>(BATCH_SIZE);
                    }
                }
            }
            if (!items.isEmpty()) {
                queue.put(new Batch(items, null));
            }
            queue.put(Batch.END);
        } catch (InterruptedException e) {
            // The storing thread gave up and stopped this one: nothing waits for what follows.
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            Timber.w(t, "Failed to read hosts source.");
            IOException failure = t instanceof IOException
                    ? (IOException) t
                    : new IOException("Failed to read hosts source.", t);
            try {
                queue.put(new Batch(null, failure));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Parse a line of the source.
     *
     * @return The host it lists, or {@code null} for a comment, a blank or an invalid line.
     */
    @Nullable
    HostListItem parseLine(String line) {
        // Skip comments. Not logged: this runs once per source line.
        if (line.isEmpty() || line.charAt(0) == '#') {
            return null;
        }
        HostListItem item = this.source.isAllowEnabled()
                ? parseAllowListItem(line)
                : parseHostListItem(line);
        if (item == null || !isRedirectionValid(item) || !isHostValid(item)) {
            return null;
        }
        return item;
    }

    private HostListItem parseHostListItem(String line) {
        Matcher matcher = HOSTS_PARSER_PATTERN.matcher(line);
        if (!matcher.matches()) {
            // Not logged: this runs once per source line.
            return null;
        }
        // Check IP address validity or while list entry (if allowed)
        String ip = matcher.group(1);
        String hostname = matcher.group(2);
        assert hostname != null;
        // Skip localhost name
        if (LOCALHOST_HOSTNAME.equals(hostname)) {
            return null;
        }
        // check if ip is 127.0.0.1 or 0.0.0.0
        ListType type;
        if (LOCALHOST_IPV4.equals(ip)
                || BOGUS_IPV4.equals(ip)
                || LOCALHOST_IPV6.equals(ip)) {
            type = BLOCKED;
        } else if (this.source.isRedirectEnabled()) {
            type = REDIRECTED;
        } else {
            return null;
        }
        HostListItem item = new HostListItem();
        item.setType(type);
        item.setHost(hostname);
        item.setEnabled(true);
        if (type == REDIRECTED) {
            item.setRedirection(ip);
        }
        item.setSourceId(this.source.getId());
        return item;
    }

    private HostListItem parseAllowListItem(String line) {
        // Extract hostname
        int indexOf = line.indexOf('#');
        if (indexOf == 1) {
            line = line.substring(0, indexOf);
        }
        line = line.trim();
        // Create item
        HostListItem item = new HostListItem();
        item.setType(ALLOWED);
        item.setHost(line);
        item.setEnabled(true);
        item.setSourceId(this.source.getId());
        return item;
    }

    private static boolean isRedirectionValid(HostListItem item) {
        return item.getType() != REDIRECTED || RegexUtils.isValidIP(item.getRedirection());
    }

    private static boolean isHostValid(HostListItem item) {
        String hostname = item.getHost();
        if (item.getType() == BLOCKED) {
            if (hostname.indexOf('?') != -1 || hostname.indexOf('*') != -1) {
                return false;
            }
            return RegexUtils.isValidHostname(hostname);
        }
        return RegexUtils.isValidWildcardHostname(hostname);
    }

    /**
     * Where the hosts of a source are stored.
     */
    interface Store {
        /**
         * Run the body in a transaction, committed when it returns and rolled back when it throws.
         */
        void runInTransaction(Runnable body);

        /**
         * Remove every host of the source.
         */
        void clear();

        /**
         * Add hosts to the source.
         */
        void insert(List<HostListItem> items);
    }

    /**
     * Thrown out of the transaction to roll it back when the source could not be read whole.
     */
    private static class SourceReadException extends RuntimeException {
        private SourceReadException(IOException cause) {
            super(cause);
        }

        @Override
        public synchronized IOException getCause() {
            return (IOException) super.getCause();
        }
    }

    /**
     * What the parser hands over: hosts, the end of the source, or why it could not be read.
     */
    private static final class Batch {
        static final Batch END = new Batch(null, null);

        @Nullable
        final List<HostListItem> items;
        @Nullable
        final IOException failure;

        Batch(@Nullable List<HostListItem> items, @Nullable IOException failure) {
            this.items = items;
            this.failure = failure;
        }
    }
}
