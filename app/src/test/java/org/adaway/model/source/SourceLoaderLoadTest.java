package org.adaway.model.source;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.adaway.db.entity.HostListItem;
import org.adaway.db.entity.HostsSource;
import org.adaway.db.entity.ListType;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongConsumer;

/**
 * Tests of {@link SourceLoader#load(BufferedReader, SourceLoader.Store)}: a source is either
 * stored whole or left as it was, only what changed is written, and no thread is left behind.
 */
public class SourceLoaderLoadTest {
    private static final int SOURCE_ID = 7;
    private static final int OTHER_SOURCE_ID = 8;
    private static final String PREVIOUS_HOST = "previous.example.com";

    @Test(timeout = 10_000)
    public void storesEveryHostInOrder() throws IOException {
        StringBuilder content = new StringBuilder("# A comment\n\n127.0.0.1 localhost\n");
        List<String> expected = new ArrayList<>();
        // Several batches, and a last one that is not full.
        int count = SourceLoader.BATCH_SIZE * 2 + 500;
        for (int i = 0; i < count; i++) {
            String ip = i % 3 == 0 ? "0.0.0.0" : i % 3 == 1 ? "127.0.0.1" : "::1";
            content.append(ip).append(" host").append(i).append(".example.com # note\n");
            expected.add("host" + i + ".example.com");
            // Lines that list no host to block are skipped.
            content.append("not a hosts line\n1.2.3.4 redirected.example.com\n0.0.0.0 bad..name\n");
        }
        // The last line has no line break.
        content.append("0.0.0.0 last.example.com");
        expected.add("last.example.com");

        FakeStore store = new FakeStore().withRow(SOURCE_ID, ListType.BLOCKED, PREVIOUS_HOST, null);
        SourceLoader.Changes changes = loader(false).load(reader(content.toString()), store);

        assertEquals(0, changes.kept);
        assertEquals(expected.size(), changes.added);
        assertEquals(1, changes.removed);
        assertEquals(expected, store.committedHosts(SOURCE_ID));
        for (FakeStore.Row row : store.committed) {
            assertEquals(ListType.BLOCKED, row.type);
            assertTrue(row.enabled);
            assertNull(row.redirection);
        }
        assertNoLoaderThreadLeft();
    }

    @Test(timeout = 10_000)
    public void emptySourceRemovesThePreviousHosts() throws IOException {
        FakeStore store = new FakeStore().withRow(SOURCE_ID, ListType.BLOCKED, PREVIOUS_HOST, null);
        SourceLoader.Changes changes = loader(false).load(reader("# Nothing listed\n"), store);
        assertEquals(1, changes.removed);
        assertEquals(Collections.emptyList(), store.committedHosts(SOURCE_ID));
    }

    @Test(timeout = 10_000)
    public void keepsTheHostsStillListedAndChangesOnlyTheOthers() throws IOException {
        FakeStore store = new FakeStore()
                .withRow(SOURCE_ID, ListType.BLOCKED, "a.example.com", null)
                .withRow(OTHER_SOURCE_ID, ListType.BLOCKED, "b.example.com", null)
                .withRow(SOURCE_ID, ListType.BLOCKED, "b.example.com", null)
                .withRow(SOURCE_ID, ListType.BLOCKED, "c.example.com", null);
        long idOfA = store.idOf(SOURCE_ID, "a.example.com");
        long idOfC = store.idOf(SOURCE_ID, "c.example.com");
        List<FakeStore.Row> otherSource = store.rowsOf(OTHER_SOURCE_ID);

        SourceLoader.Changes changes = loader(false).load(
                reader("0.0.0.0 c.example.com\n0.0.0.0 d.example.com\n0.0.0.0 a.example.com\n"),
                store
        );

        assertEquals(2, changes.kept);
        assertEquals(1, changes.added);
        assertEquals(1, changes.removed);
        // The rows still listed are the same rows, untouched.
        assertEquals(idOfA, store.idOf(SOURCE_ID, "a.example.com"));
        assertEquals(idOfC, store.idOf(SOURCE_ID, "c.example.com"));
        assertEquals(Arrays.asList("a.example.com", "c.example.com", "d.example.com"), store.committedHosts(SOURCE_ID));
        // The new row has an id never used before.
        assertTrue(store.idOf(SOURCE_ID, "d.example.com") > store.initialMaxId);
        // The other source is left alone, including its copy of the removed host.
        assertEquals(otherSource, store.rowsOf(OTHER_SOURCE_ID));
    }

    @Test(timeout = 10_000)
    public void unchangedSourceWritesNothing() throws IOException {
        String content = hostsLines(SourceLoader.BATCH_SIZE * 2 + 3);
        FakeStore store = new FakeStore();
        loader(false).load(reader(content), store);
        List<FakeStore.Row> loaded = store.rowsOf(SOURCE_ID);
        store.writes = 0;

        SourceLoader.Changes changes = loader(false).load(reader(content), store);

        assertEquals(loaded.size(), changes.kept);
        assertEquals(0, changes.added);
        assertEquals(0, changes.removed);
        assertEquals(0, store.writes);
        assertEquals(loaded, store.rowsOf(SOURCE_ID));
    }

    @Test(timeout = 10_000)
    public void firstLoadLooksNothingUp() throws IOException {
        FakeStore store = new FakeStore().withRow(OTHER_SOURCE_ID, ListType.BLOCKED, "a.example.com", null);
        SourceLoader.Changes changes = loader(false).load(reader(hostsLines(10)), store);
        assertEquals(10, changes.added);
        assertEquals(0, store.lookups);
    }

    @Test(timeout = 10_000)
    public void changedRedirectionReplacesTheRow() throws IOException {
        FakeStore store = new FakeStore()
                .withRow(SOURCE_ID, ListType.REDIRECTED, "moved.example.com", "1.2.3.4")
                .withRow(SOURCE_ID, ListType.REDIRECTED, "same.example.com", "1.2.3.4");
        long idOfSame = store.idOf(SOURCE_ID, "same.example.com");

        SourceLoader.Changes changes = loader(true).load(
                reader("5.6.7.8 moved.example.com\n1.2.3.4 same.example.com\n"),
                store
        );

        assertEquals(1, changes.kept);
        assertEquals(1, changes.added);
        assertEquals(1, changes.removed);
        assertEquals(idOfSame, store.idOf(SOURCE_ID, "same.example.com"));
        assertEquals("5.6.7.8", store.rowOf(SOURCE_ID, "moved.example.com").redirection);
    }

    @Test(timeout = 10_000)
    public void changedTypeReplacesTheRow() throws IOException {
        // A redirected host now blocked.
        FakeStore store = new FakeStore()
                .withRow(SOURCE_ID, ListType.REDIRECTED, "a.example.com", "1.2.3.4");
        loader(true).load(reader("0.0.0.0 a.example.com\n"), store);
        FakeStore.Row row = store.rowOf(SOURCE_ID, "a.example.com");
        assertEquals(ListType.BLOCKED, row.type);
        assertNull(row.redirection);
        assertEquals(1, store.rowsOf(SOURCE_ID).size());
    }

    @Test(timeout = 10_000)
    public void hostListedTwiceIsKeptOnce() throws IOException {
        FakeStore store = new FakeStore()
                .withRow(SOURCE_ID, ListType.BLOCKED, "a.example.com", null)
                .withRow(SOURCE_ID, ListType.BLOCKED, "a.example.com", null);
        long firstId = store.rowsOf(SOURCE_ID).get(0).id;

        loader(false).load(reader("0.0.0.0 a.example.com\n"), store);

        List<FakeStore.Row> rows = store.rowsOf(SOURCE_ID);
        assertEquals(1, rows.size());
        assertEquals(firstId, rows.get(0).id);
    }

    @Test(timeout = 10_000)
    public void disabledSourceAddsDisabledHosts() throws IOException {
        FakeStore store = new FakeStore().withRow(SOURCE_ID, ListType.BLOCKED, "a.example.com", null);
        store.committed.get(0).enabled = false;
        store.sourceEnabled = false;

        loader(false).load(reader("0.0.0.0 a.example.com\n0.0.0.0 b.example.com\n"), store);

        for (FakeStore.Row row : store.rowsOf(SOURCE_ID)) {
            assertFalse(row.host, row.enabled);
        }
    }

    @Test(timeout = 10_000)
    public void readFailureKeepsThePreviousHosts() {
        IOException failure = new IOException("Connection reset");
        // Fails after several batches were already stored.
        Reader reader = new FailingReader(hostsLines(SourceLoader.BATCH_SIZE * 3 + 10), failure);
        FakeStore store = new FakeStore().withRow(SOURCE_ID, ListType.BLOCKED, PREVIOUS_HOST, null);
        try {
            loader(false).load(new BufferedReader(reader), store);
            fail("The read failure must be reported.");
        } catch (IOException e) {
            assertSame(failure, e);
        }
        assertEquals(Collections.singletonList(PREVIOUS_HOST), store.committedHosts(SOURCE_ID));
        assertNoLoaderThreadLeft();
    }

    @Test(timeout = 10_000)
    public void unexpectedParserFailureKeepsThePreviousHosts() {
        RuntimeException failure = new IllegalStateException("Broken stream");
        Reader reader = new FailingReader(hostsLines(SourceLoader.BATCH_SIZE + 10), failure);
        FakeStore store = new FakeStore().withRow(SOURCE_ID, ListType.BLOCKED, PREVIOUS_HOST, null);
        try {
            loader(false).load(new BufferedReader(reader), store);
            fail("The parser failure must be reported.");
        } catch (IOException e) {
            assertSame(failure, e.getCause());
        }
        assertEquals(Collections.singletonList(PREVIOUS_HOST), store.committedHosts(SOURCE_ID));
        assertNoLoaderThreadLeft();
    }

    @Test(timeout = 10_000)
    public void storeFailureKeepsThePreviousHostsAndStopsTheParser() {
        // Far more than the queue holds, so the parser is blocked handing hosts over when the
        // store fails, and must still be stopped.
        String content = hostsLines(SourceLoader.BATCH_SIZE * 100);
        RuntimeException failure = new IllegalStateException("Disk full");
        FakeStore store = new FakeStore().withRow(SOURCE_ID, ListType.BLOCKED, PREVIOUS_HOST, null);
        store.failOnInsert = 2;
        store.failure = failure;
        try {
            loader(false).load(reader(content), store);
            fail("The store failure must be reported.");
        } catch (IOException e) {
            assertSame(failure, e.getCause());
        }
        assertEquals(Collections.singletonList(PREVIOUS_HOST), store.committedHosts(SOURCE_ID));
        assertNoLoaderThreadLeft();
    }

    @Test(timeout = 10_000)
    public void interruptionKeepsThePreviousHosts() throws InterruptedException {
        CountDownLatch blocked = new CountDownLatch(1);
        // Hands over one batch, then blocks as a stalled download would.
        Reader reader = new StallingReader(hostsLines(SourceLoader.BATCH_SIZE), blocked);
        FakeStore store = new FakeStore().withRow(SOURCE_ID, ListType.BLOCKED, PREVIOUS_HOST, null);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicReference<Boolean> interruptedAfter = new AtomicReference<>();
        Thread loading = new Thread(() -> {
            try {
                loader(false).load(new BufferedReader(reader), store);
            } catch (Throwable t) {
                thrown.set(t);
            }
            interruptedAfter.set(Thread.currentThread().isInterrupted());
        });
        loading.start();
        blocked.await();
        loading.interrupt();
        loading.join();

        assertTrue(thrown.get() instanceof InterruptedIOException);
        assertTrue("The interruption must be kept for the caller.", interruptedAfter.get());
        assertEquals(Collections.singletonList(PREVIOUS_HOST), store.committedHosts(SOURCE_ID));
        assertNoLoaderThreadLeft();
    }

    private static SourceLoader loader(boolean redirectEnabled) {
        HostsSource source = new HostsSource();
        source.setId(SOURCE_ID);
        source.setLabel("Test");
        source.setUrl("https://example.com/hosts");
        source.setRedirectEnabled(redirectEnabled);
        return new SourceLoader(source);
    }

    private static BufferedReader reader(String content) {
        return new BufferedReader(new StringReader(content));
    }

    private static String hostsLines(int count) {
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < count; i++) {
            content.append("0.0.0.0 host").append(i).append(".example.com\n");
        }
        return content.toString();
    }

    private static void assertNoLoaderThreadLeft() {
        long deadline = System.currentTimeMillis() + 5_000;
        while (loaderThreadAlive()) {
            if (System.currentTimeMillis() > deadline) {
                fail("The parsing thread is still running.");
            }
            Thread.yield();
        }
    }

    private static boolean loaderThreadAlive() {
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if ("SourceLoader".equals(thread.getName()) && thread.isAlive()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Behaves as the lists table does: ids are never reused, and what a transaction did is
     * committed only when its body returns.
     */
    private static class FakeStore implements SourceLoader.Store {
        List<Row> committed = new ArrayList<>();
        long initialMaxId;
        boolean sourceEnabled = true;
        int failOnInsert = -1;
        RuntimeException failure;
        int lookups;
        int writes;
        private long committedSequence;
        private List<Row> rows;
        private long sequence;
        private int inserts;

        FakeStore withRow(int sourceId, ListType type, String host, String redirection) {
            Row row = new Row(++this.committedSequence, sourceId, type, host, redirection, true);
            this.committed.add(row);
            this.initialMaxId = this.committedSequence;
            return this;
        }

        @Override
        public void runInTransaction(Runnable body) {
            this.rows = new ArrayList<>();
            for (Row row : this.committed) {
                this.rows.add(row.copy());
            }
            this.sequence = this.committedSequence;
            body.run();
            this.committed = this.rows;
            this.committedSequence = this.sequence;
        }

        @Override
        public boolean isSourceEnabled() {
            return this.sourceEnabled;
        }

        @Override
        public long getMaxId() {
            long max = 0;
            for (Row row : this.rows) {
                max = Math.max(max, row.id);
            }
            return max;
        }

        @Override
        public boolean hasHosts() {
            for (Row row : this.rows) {
                if (row.sourceId == SOURCE_ID) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public long findId(HostListItem item) {
            this.lookups++;
            long found = NOT_FOUND;
            for (Row row : this.rows) {
                if (row.sourceId == SOURCE_ID && row.type == item.getType() && row.host.equals(item.getHost())
                        && (item.getType() != ListType.REDIRECTED || Objects.equals(row.redirection, item.getRedirection()))
                        && (found == NOT_FOUND || row.id < found)) {
                    found = row.id;
                }
            }
            return found;
        }

        @Override
        public void insert(List<HostListItem> items) {
            if (this.inserts++ == this.failOnInsert) {
                throw this.failure;
            }
            this.writes++;
            for (HostListItem item : items) {
                assertEquals(SOURCE_ID, item.getSourceId());
                this.rows.add(new Row(++this.sequence, item.getSourceId(), item.getType(), item.getHost(),
                        item.getRedirection(), item.isEnabled()));
            }
        }

        @Override
        public void forEachId(LongConsumer action) {
            List<Row> sourceRows = new ArrayList<>();
            for (Row row : this.rows) {
                if (row.sourceId == SOURCE_ID) {
                    sourceRows.add(row);
                }
            }
            sourceRows.sort(Comparator.<Row>comparingInt(row -> row.type.getValue())
                    .thenComparing(row -> row.host)
                    .thenComparingLong(row -> row.id));
            for (Row row : sourceRows) {
                action.accept(row.id);
            }
        }

        @Override
        public void delete(long id) {
            this.writes++;
            assertTrue("No row " + id, this.rows.removeIf(row -> row.id == id));
        }

        List<Row> rowsOf(int sourceId) {
            List<Row> sourceRows = new ArrayList<>();
            for (Row row : this.committed) {
                if (row.sourceId == sourceId) {
                    sourceRows.add(row.copy());
                }
            }
            return sourceRows;
        }

        List<String> committedHosts(int sourceId) {
            List<String> hosts = new ArrayList<>();
            for (Row row : rowsOf(sourceId)) {
                hosts.add(row.host);
            }
            return hosts;
        }

        Row rowOf(int sourceId, String host) {
            Row found = null;
            for (Row row : rowsOf(sourceId)) {
                if (row.host.equals(host)) {
                    assertNull("Host listed twice: " + host, found);
                    found = row;
                }
            }
            assertTrue("Host not listed: " + host, found != null);
            return found;
        }

        long idOf(int sourceId, String host) {
            return rowOf(sourceId, host).id;
        }

        static final class Row {
            final long id;
            final int sourceId;
            final ListType type;
            final String host;
            final String redirection;
            boolean enabled;

            Row(long id, int sourceId, ListType type, String host, String redirection, boolean enabled) {
                this.id = id;
                this.sourceId = sourceId;
                this.type = type;
                this.host = host;
                this.redirection = redirection;
                this.enabled = enabled;
            }

            Row copy() {
                return new Row(this.id, this.sourceId, this.type, this.host, this.redirection, this.enabled);
            }

            @Override
            public boolean equals(Object other) {
                if (!(other instanceof Row)) {
                    return false;
                }
                Row row = (Row) other;
                return this.id == row.id && this.sourceId == row.sourceId && this.type == row.type
                        && this.host.equals(row.host) && Objects.equals(this.redirection, row.redirection)
                        && this.enabled == row.enabled;
            }

            @Override
            public int hashCode() {
                return Long.hashCode(this.id);
            }

            @Override
            public String toString() {
                return this.id + ":" + this.sourceId + ":" + this.type + ":" + this.host + ":" + this.redirection + ":" + this.enabled;
            }
        }
    }

    /**
     * Serves some content, then fails as a dropped connection would.
     */
    private static class FailingReader extends Reader {
        private final StringReader content;
        private final Throwable failure;

        FailingReader(String content, Throwable failure) {
            this.content = new StringReader(content);
            this.failure = failure;
        }

        @Override
        public int read(char[] buffer, int offset, int length) throws IOException {
            int read = this.content.read(buffer, offset, length);
            if (read != -1) {
                return read;
            }
            if (this.failure instanceof IOException) {
                throw (IOException) this.failure;
            }
            throw (RuntimeException) this.failure;
        }

        @Override
        public void close() {
        }
    }

    /**
     * Serves some content, then blocks until interrupted.
     */
    private static class StallingReader extends Reader {
        private final StringReader content;
        private final CountDownLatch blocked;

        StallingReader(String content, CountDownLatch blocked) {
            this.content = new StringReader(content);
            this.blocked = blocked;
        }

        @Override
        public int read(char[] buffer, int offset, int length) throws IOException {
            int read = this.content.read(buffer, offset, length);
            if (read != -1) {
                return read;
            }
            this.blocked.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException e) {
                throw new InterruptedIOException("Stalled read interrupted.");
            }
            return -1;
        }

        @Override
        public void close() {
        }
    }
}
