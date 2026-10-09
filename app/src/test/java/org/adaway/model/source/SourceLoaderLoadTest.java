package org.adaway.model.source;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tests of {@link SourceLoader#load(BufferedReader, SourceLoader.Store)}: a source is either
 * stored whole or left as it was, and no thread is left behind either way.
 */
public class SourceLoaderLoadTest {
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

        FakeStore store = new FakeStore();
        int stored = loader().load(reader(content.toString()), store);

        assertEquals(expected.size(), stored);
        assertEquals(expected, store.committedHosts());
        for (HostListItem item : store.committed) {
            assertEquals(ListType.BLOCKED, item.getType());
            assertEquals(7, item.getSourceId());
            assertTrue(item.isEnabled());
        }
        assertNoLoaderThreadLeft();
    }

    @Test(timeout = 10_000)
    public void emptySourceRemovesThePreviousHosts() throws IOException {
        FakeStore store = new FakeStore();
        assertEquals(0, loader().load(reader("# Nothing listed\n"), store));
        assertEquals(Collections.emptyList(), store.committedHosts());
    }

    @Test(timeout = 10_000)
    public void readFailureKeepsThePreviousHosts() {
        IOException failure = new IOException("Connection reset");
        // Fails after several batches were already stored.
        Reader reader = new FailingReader(hostsLines(SourceLoader.BATCH_SIZE * 3 + 10), failure);
        FakeStore store = new FakeStore();
        try {
            loader().load(new BufferedReader(reader), store);
            fail("The read failure must be reported.");
        } catch (IOException e) {
            assertSame(failure, e);
        }
        assertEquals(Collections.singletonList(PREVIOUS_HOST), store.committedHosts());
        assertNoLoaderThreadLeft();
    }

    @Test(timeout = 10_000)
    public void unexpectedParserFailureKeepsThePreviousHosts() {
        RuntimeException failure = new IllegalStateException("Broken stream");
        Reader reader = new FailingReader(hostsLines(SourceLoader.BATCH_SIZE + 10), failure);
        FakeStore store = new FakeStore();
        try {
            loader().load(new BufferedReader(reader), store);
            fail("The parser failure must be reported.");
        } catch (IOException e) {
            assertSame(failure, e.getCause());
        }
        assertEquals(Collections.singletonList(PREVIOUS_HOST), store.committedHosts());
        assertNoLoaderThreadLeft();
    }

    @Test(timeout = 10_000)
    public void storeFailureKeepsThePreviousHostsAndStopsTheParser() {
        // Far more than the queue holds, so the parser is blocked handing hosts over when the
        // store fails, and must still be stopped.
        String content = hostsLines(SourceLoader.BATCH_SIZE * 100);
        RuntimeException failure = new IllegalStateException("Disk full");
        FakeStore store = new FakeStore();
        store.failOnInsert = 2;
        store.failure = failure;
        try {
            loader().load(reader(content), store);
            fail("The store failure must be reported.");
        } catch (IOException e) {
            assertSame(failure, e.getCause());
        }
        assertEquals(Collections.singletonList(PREVIOUS_HOST), store.committedHosts());
        assertNoLoaderThreadLeft();
    }

    @Test(timeout = 10_000)
    public void interruptionKeepsThePreviousHosts() throws InterruptedException {
        CountDownLatch blocked = new CountDownLatch(1);
        // Hands over one batch, then blocks as a stalled download would.
        Reader reader = new StallingReader(hostsLines(SourceLoader.BATCH_SIZE), blocked);
        FakeStore store = new FakeStore();
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicReference<Boolean> interruptedAfter = new AtomicReference<>();
        Thread loading = new Thread(() -> {
            try {
                loader().load(new BufferedReader(reader), store);
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
        assertEquals(Collections.singletonList(PREVIOUS_HOST), store.committedHosts());
        assertNoLoaderThreadLeft();
    }

    private static SourceLoader loader() {
        HostsSource source = new HostsSource();
        source.setId(7);
        source.setLabel("Test");
        source.setUrl("https://example.com/hosts");
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
        assertFalse(loaderThreadAlive());
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
     * Commits what a transaction did only when its body returns, as the database does.
     */
    private static class FakeStore implements SourceLoader.Store {
        List<HostListItem> committed = new ArrayList<>();
        private List<HostListItem> pending;
        int failOnInsert = -1;
        RuntimeException failure;
        private int inserts;

        FakeStore() {
            HostListItem previous = new HostListItem();
            previous.setHost(PREVIOUS_HOST);
            this.committed.add(previous);
        }

        @Override
        public void runInTransaction(Runnable body) {
            this.pending = new ArrayList<>(this.committed);
            body.run();
            this.committed = this.pending;
        }

        @Override
        public void clear() {
            this.pending.clear();
        }

        @Override
        public void insert(List<HostListItem> items) {
            if (this.inserts++ == this.failOnInsert) {
                throw this.failure;
            }
            this.pending.addAll(items);
        }

        List<String> committedHosts() {
            List<String> hosts = new ArrayList<>();
            for (HostListItem item : this.committed) {
                hosts.add(item.getHost());
            }
            return hosts;
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
