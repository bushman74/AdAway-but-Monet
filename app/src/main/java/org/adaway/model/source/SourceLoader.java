package org.adaway.model.source;

import static org.adaway.db.entity.ListType.ALLOWED;
import static org.adaway.db.entity.ListType.BLOCKED;
import static org.adaway.db.entity.ListType.REDIRECTED;
import static org.adaway.util.Constants.BOGUS_IPV4;
import static org.adaway.util.Constants.LOCALHOST_HOSTNAME;
import static org.adaway.util.Constants.LOCALHOST_IPV4;
import static org.adaway.util.Constants.LOCALHOST_IPV6;

import org.adaway.db.AppDatabase;
import org.adaway.db.dao.HostListItemDao;
import org.adaway.db.entity.HostListItem;
import org.adaway.db.entity.HostsSource;
import org.adaway.db.entity.ListType;
import org.adaway.util.RegexUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import timber.log.Timber;

/**
 * This class is an {@link HostsSource} loader.<br>
 * It parses a source and loads it to database.
 *
 * @author Bruce BUJON (bruce.bujon(at)gmail(dot)com)
 */
class SourceLoader {
    private static final String TAG = "SourceLoader";
    private static final String END_OF_QUEUE_MARKER = "#EndOfQueueMarker";
    private static final int INSERT_BATCH_SIZE = 2_000;
    private static final int QUEUE_CAPACITY = 10_000;
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
        // Create batch
        int parserCount = 3;
        LinkedBlockingQueue<String> hostsLineQueue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
        LinkedBlockingQueue<HostListItem> hostsListItemQueue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
        AtomicReference<Throwable> readFailure = new AtomicReference<>();
        SourceReader sourceReader = new SourceReader(reader, hostsLineQueue, parserCount, readFailure);
        ItemInserter inserter = new ItemInserter(
                hostsListItemQueue, database, hostListItemDao, this.source.getId(), parserCount,
                readFailure);
        ExecutorService executorService = Executors.newFixedThreadPool(
                parserCount + 2,
                r -> new Thread(r, TAG)
        );
        executorService.execute(sourceReader);
        for (int i = 0; i < parserCount; i++) {
            executorService.execute(new HostListItemParser(this.source, hostsLineQueue, hostsListItemQueue));
        }
        Future<Integer> inserterFuture = executorService.submit(inserter);
        try {
            Integer inserted = inserterFuture.get();
            Timber.i("%s host list items inserted.", inserted);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof SourceReadException && cause.getCause() instanceof IOException) {
                throw (IOException) cause.getCause();
            }
            throw new IOException("Failed to parse hosts source.", cause);
        } catch (InterruptedException e) {
            // Stop the inserter too, so it rolls back instead of committing what it has so far.
            inserterFuture.cancel(true);
            Thread.currentThread().interrupt();
            InterruptedIOException exception = new InterruptedIOException("Interrupted while parsing hosts source.");
            exception.initCause(e);
            throw exception;
        } finally {
            executorService.shutdown();
        }
    }

    private static <T> void putUninterruptibly(BlockingQueue<T> queue, T item) {
        boolean interrupted = Thread.interrupted();
        try {
            while (true) {
                try {
                    queue.put(item);
                    return;
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Thrown by the inserter to roll back its transaction when the source could not be read whole.
     */
    private static class SourceReadException extends RuntimeException {
        private SourceReadException(Throwable cause) {
            super(cause);
        }
    }

    private static class SourceReader implements Runnable {
        private final BufferedReader reader;
        private final BlockingQueue<String> queue;
        private final int parserCount;
        private final AtomicReference<Throwable> failure;

        private SourceReader(BufferedReader reader, BlockingQueue<String> queue, int parserCount,
                             AtomicReference<Throwable> failure) {
            this.reader = reader;
            this.queue = queue;
            this.parserCount = parserCount;
            this.failure = failure;
        }

        @Override
        public void run() {
            try {
                for (String line : (Iterable<String>) this.reader.lines()::iterator) {
                    this.queue.put(line);
                }
            } catch (InterruptedException e) {
                Timber.w(e, "Interrupted while reading hosts source.");
                this.failure.compareAndSet(null, e);
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                // Recorded before the end markers are sent, so the inserter sees it once it has
                // received them all and rolls back rather than keeping a partial source.
                Timber.w(t, "Failed to read hosts source.");
                this.failure.compareAndSet(null, t);
            } finally {
                // Send end of queue marker to parsers
                for (int i = 0; i < this.parserCount; i++) {
                    putUninterruptibly(this.queue, END_OF_QUEUE_MARKER);
                }
            }
        }
    }

    private static class HostListItemParser implements Runnable {
        private final HostsSource source;
        private final BlockingQueue<String> lineQueue;
        private final BlockingQueue<HostListItem> itemQueue;

        private HostListItemParser(HostsSource source, BlockingQueue<String> lineQueue, BlockingQueue<HostListItem> itemQueue) {
            this.source = source;
            this.lineQueue = lineQueue;
            this.itemQueue = itemQueue;
        }

        @Override
        public void run() {
            boolean allowedList = this.source.isAllowEnabled();
            boolean endOfSource = false;
            while (!endOfSource) {
                try {
                    String line = this.lineQueue.take();
                    // Check end of queue marker
                    //noinspection StringEquality
                    if (line == END_OF_QUEUE_MARKER) {
                        endOfSource = true;
                        // Send end of queue marker to inserter
                        HostListItem endItem = new HostListItem();
                        endItem.setHost(line);
                        // The inserter waits for one marker per parser, so it must always arrive.
                        putUninterruptibly(this.itemQueue, endItem);
                    } // Check comments
                    else if (line.isEmpty() || line.charAt(0) == '#') {
                        // Skip comment. Not logged: this runs once per source line.
                    } else {
                        HostListItem item = allowedList ? parseAllowListItem(line) : parseHostListItem(line);
                        if (item != null && isRedirectionValid(item) && isHostValid(item)) {
                            this.itemQueue.put(item);
                        }
                    }
                } catch (InterruptedException e) {
                    Timber.w(e, "Interrupted while parsing hosts list item.");
                    endOfSource = true;
                    Thread.currentThread().interrupt();
                }
            }
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

        private boolean isRedirectionValid(HostListItem item) {
            return item.getType() != REDIRECTED || RegexUtils.isValidIP(item.getRedirection());
        }

        private boolean isHostValid(HostListItem item) {
            String hostname = item.getHost();
            if (item.getType() == BLOCKED) {
                if (hostname.indexOf('?') != -1 || hostname.indexOf('*') != -1) {
                    return false;
                }
                return RegexUtils.isValidHostname(hostname);
            }
            return RegexUtils.isValidWildcardHostname(hostname);
        }
    }

    private static class ItemInserter implements Callable<Integer> {
        private final BlockingQueue<HostListItem> hostListItemQueue;
        private final AppDatabase database;
        private final HostListItemDao hostListItemDao;
        private final int sourceId;
        private final int parserCount;
        private final AtomicReference<Throwable> readFailure;

        private ItemInserter(BlockingQueue<HostListItem> itemQueue, AppDatabase database,
                             HostListItemDao hostListItemDao, int sourceId, int parserCount,
                             AtomicReference<Throwable> readFailure) {
            this.hostListItemQueue = itemQueue;
            this.database = database;
            this.hostListItemDao = hostListItemDao;
            this.sourceId = sourceId;
            this.parserCount = parserCount;
            this.readFailure = readFailure;
        }

        @Override
        public Integer call() {
            // Clear the previous hosts and insert the new ones in a single transaction, on this
            // thread. Readers keep seeing the previous content until it commits, so the host
            // counters no longer drop while a source is being reloaded.
            return this.database.runInTransaction(this::insertAll);
        }

        private Integer insertAll() {
            this.hostListItemDao.clearSourceHosts(this.sourceId);
            int inserted = 0;
            int workerStopped = 0;
            HostListItem[] batch = new HostListItem[INSERT_BATCH_SIZE];
            int cacheSize = 0;
            boolean queueEmptied = false;
            while (!queueEmptied) {
                try {
                    HostListItem item = this.hostListItemQueue.take();
                    // Check end of queue marker
                    //noinspection StringEquality
                    if (item.getHost() == END_OF_QUEUE_MARKER) {
                        workerStopped++;
                        if (workerStopped >= this.parserCount) {
                            queueEmptied = true;
                        }
                    } else {
                        batch[cacheSize++] = item;
                        if (cacheSize >= batch.length) {
                            this.hostListItemDao.insert(batch);
                            inserted += cacheSize;
                            cacheSize = 0;
                        }
                    }
                } catch (InterruptedException e) {
                    // Thrown out of the transaction, which rolls it back: the source keeps its
                    // previous hosts rather than the part inserted so far.
                    Thread.currentThread().interrupt();
                    InterruptedIOException exception = new InterruptedIOException("Interrupted while inserting hosts list items.");
                    exception.initCause(e);
                    throw new SourceReadException(exception);
                }
            }
            // A source that could not be read to its end is not kept: throwing rolls back the
            // transaction, so the previous hosts of the source stay in place.
            Throwable failure = this.readFailure.get();
            if (failure instanceof UncheckedIOException) {
                // How reading the lines of a source reports the I/O failure underneath.
                failure = failure.getCause();
            }
            if (failure != null) {
                throw new SourceReadException(failure instanceof IOException
                        ? failure
                        : new IOException("Failed to read hosts source.", failure));
            }
            // Flush current batch
            HostListItem[] remaining = new HostListItem[cacheSize];
            System.arraycopy(batch, 0, remaining, 0, remaining.length);
            this.hostListItemDao.insert(remaining);
            inserted += cacheSize;
            // Return number of inserted items
            return inserted;
        }
    }
}
