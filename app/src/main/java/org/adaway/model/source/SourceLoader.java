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
import org.adaway.db.entity.HostListItem;
import org.adaway.db.entity.HostsSource;
import org.adaway.db.entity.ListType;
import org.adaway.util.RegexUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.LongConsumer;

import timber.log.Timber;

/**
 * This class is an {@link HostsSource} loader.<br>
 * It parses a source and loads it to database.
 * <p>
 * Reading and parsing run on a thread of their own, while the calling thread stores the hosts, so
 * the download and the database writes overlap. The hosts are handed over in batches rather than
 * one by one: a hand-over costs about as much as parsing a line, so doing it per line used to take
 * longer than the parsing itself.
 * <p>
 * Only what changed is written. A source usually changes by a few hosts between two updates, yet
 * every host used to be deleted and inserted again, rewriting its rows and both indexes of the
 * lists each time. Each host read is now looked up first: the ones already listed are kept as they
 * are, only the new ones are inserted, and only the ones the source no longer lists are deleted.
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
     * The number of hosts sorted together before they are handed over. Stored in index order, the
     * hosts are looked up and inserted walking the indexes forward rather than jumping around
     * them; sorting a whole source gains nothing more, and a hundred thousand hosts take about
     * 12 MB.
     */
    static final int SORT_CHUNK_SIZE = 100_000;
    /**
     * The number of batches parsed ahead of the ones being stored. Together with the hosts being
     * sorted, it bounds the memory held by a source being loaded, however large the source is.
     */
    private static final int QUEUE_CAPACITY = 16;
    /**
     * How long the storing thread waits for a batch before checking the parsing one is still
     * running, so a parser that died without a word can never leave it waiting forever.
     */
    private static final long POLL_TIMEOUT_SECONDS = 1;

    /**
     * The order of the indexes of the lists: by type, then host. Strings compare as SQLite
     * compares them for host names, which are ASCII; any other name only lands a little out of
     * place, which costs nothing but a less orderly walk.
     */
    private static final Comparator<HostListItem> INDEX_ORDER = Comparator
            .comparingInt((HostListItem item) -> item.getType().getValue())
            .thenComparing(HostListItem::getHost);

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
    void parse(BufferedReader reader, AppDatabase database) throws IOException {
        Changes changes = load(reader, new DatabaseSourceStore(database, this.source.getId()));
        Timber.i("Source %s: %d hosts kept, %d added, %d removed.", this.source.getUrl(),
                changes.kept, changes.added, changes.removed);
    }

    /**
     * Read the source and store its hosts.
     *
     * @param reader The source content.
     * @param store  Where the hosts are stored.
     * @return What changed.
     * @throws IOException If the source could not be read or stored whole. Nothing is changed then.
     */
    Changes load(BufferedReader reader, Store store) throws IOException {
        BlockingQueue<Batch> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> new Thread(r, TAG));
        Future<?> parsing = executor.submit(() -> parseAll(reader, queue));
        Changes changes = new Changes();
        try {
            // Change the hosts of the source in a single transaction, on this thread. Readers keep
            // seeing the previous content until it commits, and throwing out of it rolls it back,
            // so a source is either updated whole or left as it was.
            store.runInTransaction(() -> store(queue, parsing, store, changes));
            return changes;
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
     * Store the hosts read, keeping the ones already listed. Runs in the transaction.
     */
    private static void store(BlockingQueue<Batch> queue, Future<?> parsing, Store store, Changes changes) {
        // A source being turned off while it updates gets its new hosts turned off too. Its rows
        // all share its state, as only the hosts the user added can be turned off one by one.
        boolean enabled = store.isSourceEnabled();
        // Every row added from now on gets a higher id, which tells the previous rows apart.
        long previousMaxId = store.getMaxId();
        // A source loaded for the first time has nothing to look up or remove.
        boolean listed = store.hasHosts();
        LongList keptIds = new LongList();
        List<HostListItem> items;
        while ((items = take(queue, parsing)) != null) {
            List<HostListItem> added = new ArrayList<>(items.size());
            for (HostListItem item : items) {
                long id = listed ? store.findId(item) : Store.NOT_FOUND;
                if (id == Store.NOT_FOUND) {
                    item.setEnabled(enabled);
                    added.add(item);
                } else {
                    keptIds.add(id);
                }
            }
            if (!added.isEmpty()) {
                store.insert(added);
            }
            changes.added += added.size();
            changes.kept += items.size() - added.size();
        }
        if (listed) {
            // Remove the previous rows that no host read matched.
            keptIds.sort();
            LongList removedIds = new LongList();
            store.forEachId(id -> {
                if (id <= previousMaxId && !keptIds.contains(id)) {
                    removedIds.add(id);
                }
            });
            for (int index = 0; index < removedIds.size(); index++) {
                store.delete(removedIds.get(index));
            }
            changes.removed = removedIds.size();
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
     * Read and parse the whole source, handing the hosts over sorted in batches, then the end of
     * the source or the failure that stopped the reading.
     */
    private void parseAll(BufferedReader reader, BlockingQueue<Batch> queue) {
        try {
            List<HostListItem> chunk = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                parseLine(line, chunk);
                if (chunk.size() >= SORT_CHUNK_SIZE) {
                    handOver(chunk, queue);
                    chunk = new ArrayList<>();
                }
            }
            handOver(chunk, queue);
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
     * Sort hosts in the order of the indexes of the lists, by type then host, and hand them over
     * in batches. Sorting runs here, on the parsing thread, while the previous hosts are stored.
     */
    private static void handOver(List<HostListItem> chunk, BlockingQueue<Batch> queue)
            throws InterruptedException {
        chunk.sort(INDEX_ORDER);
        for (int start = 0; start < chunk.size(); start += BATCH_SIZE) {
            int end = Math.min(start + BATCH_SIZE, chunk.size());
            queue.put(new Batch(new ArrayList<>(chunk.subList(start, end)), null));
        }
    }

    /**
     * Parse a line of the source, adding the hosts it lists. A comment, a blank or an invalid line
     * lists none; a hosts line can list several, each checked on its own.
     *
     * @param line  The line to parse.
     * @param items Where to add the hosts listed.
     */
    void parseLine(String line, List<HostListItem> items) {
        // Skip comments. Not logged: this runs once per source line.
        if (line.isEmpty() || line.charAt(0) == '#') {
            return;
        }
        if (this.source.isAllowEnabled()) {
            HostListItem item = parseAllowListItem(line);
            if (isHostValid(item)) {
                items.add(item);
            }
        } else {
            parseHostsLine(line, items);
        }
    }

    private void parseHostsLine(String line, List<HostListItem> items) {
        String[] fields = splitHostsLine(line);
        if (fields == null) {
            // Not logged: this runs once per source line.
            return;
        }
        // check if ip is 127.0.0.1 or 0.0.0.0
        String ip = fields[0];
        ListType type;
        if (LOCALHOST_IPV4.equals(ip)
                || BOGUS_IPV4.equals(ip)
                || LOCALHOST_IPV6.equals(ip)) {
            type = BLOCKED;
        } else if (this.source.isRedirectEnabled() && RegexUtils.isValidIP(ip)) {
            type = REDIRECTED;
        } else {
            return;
        }
        // Every host name the line lists, as a hosts file allows several per address.
        for (int index = 1; index < fields.length; index++) {
            String hostname = fields[index];
            // Skip localhost name
            if (LOCALHOST_HOSTNAME.equals(hostname)) {
                continue;
            }
            HostListItem item = new HostListItem();
            item.setType(type);
            item.setHost(hostname);
            item.setEnabled(true);
            if (type == REDIRECTED) {
                item.setRedirection(ip);
            }
            item.setSourceId(this.source.getId());
            if (isHostValid(item)) {
                items.add(item);
            }
        }
    }

    /**
     * Split a hosts line into its address and the host names that follow it.
     * <p>
     * Leading blanks, an address, blanks, then host names separated by blanks, up to a {@code #}
     * starting a comment or the end of the line. A field ends at a blank or at a {@code #}, and
     * the line is rejected when the address is not followed by a blank or when no host name
     * follows it.
     * <p>
     * The address and the first host name are read exactly as the regular expression
     * {@code ^\s*([^#\s]+)\s+([^#\s]+).*$} used to read them, which ignored the other names. A
     * line that expression rejected, for holding a line terminator after the first name, is
     * still rejected.
     *
     * @param line The line to split.
     * @return The address then the host names, or {@code null} when the line has no host name.
     */
    @Nullable
    static String[] splitHostsLine(String line) {
        int length = line.length();
        int index = 0;
        while (index < length && isBlank(line.charAt(index))) {
            index++;
        }
        int addressStart = index;
        while (index < length && !endsField(line.charAt(index))) {
            index++;
        }
        // The address must be followed by a blank, not by a comment or the end of the line.
        if (index == addressStart || index == length || !isBlank(line.charAt(index))) {
            return null;
        }
        int addressEnd = index;
        while (index < length && isBlank(line.charAt(index))) {
            index++;
        }
        int hostStart = index;
        while (index < length && !endsField(line.charAt(index))) {
            index++;
        }
        if (index == hostStart) {
            return null;
        }
        // Nothing that spans lines follows the first host name: the expression matched a single
        // line.
        for (int rest = index; rest < length; rest++) {
            if (isLineTerminator(line.charAt(rest))) {
                return null;
            }
        }
        List<String> fields = new ArrayList<>(2);
        fields.add(line.substring(addressStart, addressEnd));
        fields.add(line.substring(hostStart, index));
        // The other host names, up to a comment.
        while (true) {
            while (index < length && isBlank(line.charAt(index))) {
                index++;
            }
            if (index == length || line.charAt(index) == '#') {
                break;
            }
            int nameStart = index;
            while (index < length && !endsField(line.charAt(index))) {
                index++;
            }
            fields.add(line.substring(nameStart, index));
        }
        return fields.toArray(new String[0]);
    }

    /**
     * Tell whether a character is a blank, as {@code \s} matches in a regular expression.
     */
    private static boolean isBlank(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\f' || c == '\r';
    }

    private static boolean endsField(char c) {
        return c == '#' || isBlank(c);
    }

    /**
     * Tell whether a character ends a line, as {@code .} does not match in a regular expression.
     */
    private static boolean isLineTerminator(char c) {
        return c == '\n' || c == '\r' || c == '\u0085' || c == '\u2028' || c == '\u2029';
    }

    private HostListItem parseAllowListItem(String line) {
        // Drop a comment ending the line, as hosts lines do. A line starting with one was skipped
        // already, and one left empty is rejected as an invalid host name.
        int commentStart = line.indexOf('#');
        if (commentStart != -1) {
            line = line.substring(0, commentStart);
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
         * What {@link #findId(HostListItem)} returns for a host not listed yet.
         */
        long NOT_FOUND = -1;

        /**
         * Run the body in a transaction, committed when it returns and rolled back when it throws.
         * The other methods are called from the body only.
         */
        void runInTransaction(Runnable body);

        /**
         * Tell whether the source is enabled.
         */
        boolean isSourceEnabled();

        /**
         * Get the highest id in use by any row. Rows added afterwards get higher ones.
         */
        long getMaxId();

        /**
         * Tell whether the source lists any host.
         */
        boolean hasHosts();

        /**
         * Find a row of the source listing a host with the same type and redirection.
         *
         * @return The id of the row, or {@link #NOT_FOUND}.
         */
        long findId(HostListItem item);

        /**
         * Add hosts to the source.
         */
        void insert(List<HostListItem> items);

        /**
         * Visit the id of every row of the source.
         */
        void forEachId(LongConsumer action);

        /**
         * Remove a row.
         */
        void delete(long id);
    }

    /**
     * What storing a source changed.
     */
    static final class Changes {
        int kept;
        int added;
        int removed;
    }

    /**
     * A growable list of ids, without a boxed object per id: a large source has a million.
     */
    private static final class LongList {
        private long[] values = new long[1024];
        private int size;
        private boolean sorted = true;

        void add(long value) {
            if (this.size == this.values.length) {
                this.values = Arrays.copyOf(this.values, this.size * 2);
            }
            this.values[this.size++] = value;
            this.sorted = false;
        }

        long get(int index) {
            return this.values[index];
        }

        int size() {
            return this.size;
        }

        void sort() {
            Arrays.sort(this.values, 0, this.size);
            this.sorted = true;
        }

        /**
         * Tell whether the list holds a value. Sort it first.
         */
        boolean contains(long value) {
            if (!this.sorted) {
                throw new IllegalStateException("The list must be sorted first.");
            }
            return Arrays.binarySearch(this.values, 0, this.size, value) >= 0;
        }
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
