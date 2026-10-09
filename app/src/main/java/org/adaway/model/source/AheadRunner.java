package org.adaway.model.source;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Runs a task for the next item while the caller works on the current one, such as downloading
 * the next hosts source while the current one is stored.
 * <p>
 * The tasks run one at a time, on a thread of their own, in the order of the items, and never
 * more than one item ahead of the caller. So at most two results are held at once: the one the
 * caller works on, and the next one.
 *
 * @param <T> The type of the items.
 * @param <R> The type of the results.
 */
final class AheadRunner<T, R> implements AutoCloseable {
    private final List<T> items;
    private final Task<T, R> task;
    private final ExecutorService executor;
    /**
     * The task of each item once started, and until its result is taken.
     */
    private final List<Future<R>> started;

    /**
     * @param items      The items, in the order they will be taken.
     * @param task       What to run for each item.
     * @param threadName The name of the thread the tasks run on.
     */
    AheadRunner(List<T> items, Task<T, R> task, String threadName) {
        this.items = items;
        this.task = task;
        // A daemon thread: a task left running never keeps the process alive.
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, threadName);
            thread.setDaemon(true);
            return thread;
        });
        this.started = new ArrayList<>(Collections.nCopies(items.size(), null));
    }

    /**
     * Get the result for an item, waiting for its task if it is still running, and start the
     * task of the next item so it runs while the caller works on this one.
     * <p>
     * Items are taken in order. One that is not needed is simply never taken.
     *
     * @param index The index of the item.
     * @return The result of its task.
     * @throws ExecutionException   If its task failed. The cause is what the task threw.
     * @throws InterruptedException If the caller was interrupted while waiting.
     */
    R take(int index) throws ExecutionException, InterruptedException {
        Future<R> current = start(index);
        if (index + 1 < this.items.size()) {
            start(index + 1);
        }
        try {
            return current.get();
        } finally {
            // Released, so the result does not outlive the caller's use of it.
            this.started.set(index, null);
        }
    }

    private Future<R> start(int index) {
        Future<R> future = this.started.get(index);
        if (future == null) {
            T item = this.items.get(index);
            future = this.executor.submit(() -> this.task.run(item));
            this.started.set(index, future);
        }
        return future;
    }

    /**
     * Stop the tasks: the one running is interrupted, and the ones waiting never run.
     */
    @Override
    public void close() {
        for (Future<R> future : this.started) {
            if (future != null) {
                future.cancel(true);
            }
        }
        this.executor.shutdownNow();
    }

    /**
     * What is run for each item.
     */
    interface Task<T, R> {
        R run(T item) throws Exception;
    }
}
