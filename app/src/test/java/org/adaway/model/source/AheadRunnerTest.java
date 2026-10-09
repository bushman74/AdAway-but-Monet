package org.adaway.model.source;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tests of {@link AheadRunner}: the next item's task runs while the caller works on the current
 * one, never further ahead, and stops with the runner.
 */
public class AheadRunnerTest {
    private static final String THREAD_NAME = "AheadRunnerTest";
    private static final List<String> ITEMS = Arrays.asList("a", "b", "c", "d");

    @Test(timeout = 10_000)
    public void givesEachResultInOrderRunningEachTaskOnce() throws Exception {
        List<String> runs = new CopyOnWriteArrayList<>();
        try (AheadRunner<String, String> runner = new AheadRunner<>(ITEMS, item -> {
            runs.add(item);
            return item.toUpperCase();
        }, THREAD_NAME)) {
            for (int index = 0; index < ITEMS.size(); index++) {
                assertEquals(ITEMS.get(index).toUpperCase(), runner.take(index));
            }
        }
        assertEquals(ITEMS, runs);
    }

    @Test(timeout = 10_000)
    public void runsTheNextTaskWhileTheCallerWorks() throws Exception {
        CountDownLatch nextStarted = new CountDownLatch(1);
        try (AheadRunner<String, String> runner = new AheadRunner<>(ITEMS, item -> {
            if (item.equals("b")) {
                nextStarted.countDown();
            }
            return item;
        }, THREAD_NAME)) {
            assertEquals("a", runner.take(0));
            // The caller has not asked for b yet, but its task runs meanwhile.
            assertTrue(nextStarted.await(5, TimeUnit.SECONDS));
            assertEquals("b", runner.take(1));
        }
    }

    @Test(timeout = 10_000)
    public void neverRunsMoreThanOneItemAhead() throws Exception {
        AtomicInteger furthest = new AtomicInteger(-1);
        try (AheadRunner<String, String> runner = new AheadRunner<>(ITEMS, item -> {
            furthest.accumulateAndGet(ITEMS.indexOf(item), Math::max);
            return item;
        }, THREAD_NAME)) {
            for (int index = 0; index < ITEMS.size(); index++) {
                runner.take(index);
                // Leave time for any task that would wrongly start.
                Thread.sleep(50);
                assertTrue("Ran " + furthest.get() + " while at " + index, furthest.get() <= index + 1);
            }
        }
    }

    @Test(timeout = 10_000)
    public void reportsAFailureForItsItemOnly() throws Exception {
        IOException failure = new IOException("Download failed");
        try (AheadRunner<String, String> runner = new AheadRunner<>(ITEMS, item -> {
            if (item.equals("b")) {
                throw failure;
            }
            return item;
        }, THREAD_NAME)) {
            assertEquals("a", runner.take(0));
            try {
                runner.take(1);
                fail("The failure must be reported.");
            } catch (ExecutionException e) {
                assertSame(failure, e.getCause());
            }
            assertEquals("c", runner.take(2));
            assertEquals("d", runner.take(3));
        }
    }

    @Test(timeout = 10_000)
    public void closingStopsTheRunningTaskAndTheWaitingOnes() throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        List<String> runs = new CopyOnWriteArrayList<>();
        AheadRunner<String, String> runner = new AheadRunner<>(ITEMS, item -> {
            runs.add(item);
            if (item.equals("b")) {
                running.countDown();
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException e) {
                    interrupted.countDown();
                    throw e;
                }
            }
            return item;
        }, THREAD_NAME);
        assertEquals("a", runner.take(0));
        assertTrue(running.await(5, TimeUnit.SECONDS));
        runner.close();
        assertTrue("The running task must be interrupted.", interrupted.await(5, TimeUnit.SECONDS));
        Thread.sleep(50);
        assertEquals(Arrays.asList("a", "b"), runs);
        assertNoRunnerThreadLeft();
    }

    @Test(timeout = 10_000)
    public void itemsNeverTakenAreNeverRunPastTheNextOne() throws Exception {
        ConcurrentHashMap<String, Boolean> runs = new ConcurrentHashMap<>();
        try (AheadRunner<String, String> runner = new AheadRunner<>(ITEMS, item -> {
            runs.put(item, true);
            return item;
        }, THREAD_NAME)) {
            runner.take(0);
            Thread.sleep(100);
        }
        assertFalse(runs.containsKey("c"));
        assertFalse(runs.containsKey("d"));
    }

    private static void assertNoRunnerThreadLeft() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (true) {
            boolean alive = false;
            for (Thread thread : Thread.getAllStackTraces().keySet()) {
                if (THREAD_NAME.equals(thread.getName()) && thread.isAlive()) {
                    alive = true;
                }
            }
            if (!alive) {
                return;
            }
            if (System.currentTimeMillis() > deadline) {
                fail("The runner thread is still running.");
            }
            Thread.sleep(10);
        }
    }
}
