package id.ja_wa.event;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Stateful {@code messaging-history.status} state machine from Baileys
 * {@code lib/Socket/chats.js} (the history-message handler), with the
 * 120s paused timeout from {@code HISTORY_SYNC_PAUSED_TIMEOUT_MS} in
 * {@code lib/Defaults/index.js}.
 *
 * <p>Baileys' rules, reproduced in call order:
 * <ol>
 *   <li>{@code INITIAL_BOOTSTRAP} fires {@code {syncType, status:
 *       'complete', explicit: true}} immediately, once (deduped by
 *       {@code initialBootstrapComplete}).</li>
 *   <li>{@code RECENT} with progress {@code 100} fires {@code {syncType:
 *       RECENT, status: 'complete', explicit: true}} once, cancelling
 *       any pending paused timer.</li>
 *   <li>Any other {@code RECENT} chunk (while not complete) resets the
 *       paused timer: on expiry, if still not complete, it marks the
 *       sync complete and fires {@code {syncType: RECENT, status:
 *       'paused', explicit: false}} once.</li>
 *   <li>Any other sync type produces no status emission.</li>
 * </ol>
 *
 * <p>Callers gate {@link #onHistoryMessage} on Baileys' processability
 * rule (see
 * {@link id.ja_wa.feature.messaging.HistorySyncNotificationParser#shouldProcessHistoryMessage}:
 * the sync type must be in {@code PROCESSABLE_HISTORY_TYPES} and, under
 * Baileys' default config, must not be {@code FULL}); this tracker
 * itself only reacts to {@code INITIAL_BOOTSTRAP} and {@code RECENT},
 * so an ungated call for another type is still a no-op, but the
 * {@code FULL} exclusion and the self-origin guard live with the
 * caller, matching Baileys' split between {@code chats.js} (status
 * machine) and {@code process-message.js} (processing gate).
 *
 * <p>The paused timer is injectable: pass a {@link PausedTimer} in
 * tests (a manual fake whose firing the test drives — never a real
 * 120s sleep), and use the default constructor in production, which
 * uses a single daemon-thread scheduler. {@link #reset()} reproduces
 * Baileys' connection close/open reset (flags cleared, timer
 * cancelled). Emissions go to the sink supplied at construction; the
 * sink is invoked outside the internal lock.
 */
public final class HistorySyncStatusTracker {

    /** Baileys {@code HISTORY_SYNC_PAUSED_TIMEOUT_MS}: 120 seconds. */
    public static final long PAUSED_TIMEOUT_MS = 120_000L;

    /**
     * Minimal timer abstraction for the paused timeout: schedule a
     * task after a delay, or cancel the pending one. Only one task is
     * ever pending (Baileys keeps a single timeout handle).
     */
    public interface PausedTimer {
        void schedule(Runnable task, long delayMs);
        void cancel();
    }

    /** Production timer backed by a single daemon-thread scheduler. */
    public static PausedTimer realTimer() {
        ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "history-sync-paused-timer");
            t.setDaemon(true);
            return t;
        });
        return new PausedTimer() {
            private volatile ScheduledFuture<?> pending;

            @Override
            public synchronized void schedule(Runnable task, long delayMs) {
                cancel();
                pending = exec.schedule(task, delayMs, TimeUnit.MILLISECONDS);
            }

            @Override
            public synchronized void cancel() {
                if (pending != null) {
                    pending.cancel(false);
                    pending = null;
                }
            }
        };
    }

    private final Consumer<MessagingHistoryStatus> sink;
    private final PausedTimer timer;
    private boolean initialBootstrapComplete;
    private boolean recentSyncComplete;

    /**
     * Creates a tracker with the production timer.
     *
     * @param sink receives every status emission; null sinks are ignored
     */
    public HistorySyncStatusTracker(Consumer<MessagingHistoryStatus> sink) {
        this(sink, realTimer());
    }

    /**
     * Creates a tracker with an injectable timer (tests).
     *
     * @param sink receives every status emission; null sinks are ignored
     * @param timer paused-timeout timer; null behaves as a no-op timer
     */
    public HistorySyncStatusTracker(Consumer<MessagingHistoryStatus> sink, PausedTimer timer) {
        this.sink = sink;
        this.timer = timer != null ? timer : new PausedTimer() {
            @Override
            public void schedule(Runnable task, long delayMs) {}
            @Override
            public void cancel() {}
        };
    }

    /**
     * Feeds one history-sync notification's sync type and progress
     * through the status machine, in Baileys' order (bootstrap,
     * then RECENT-100, then RECENT chunk timer reset).
     *
     * @param syncType Baileys sync-type name ({@code INITIAL_BOOTSTRAP},
     *                 {@code RECENT}, ...); null is ignored
     * @param progress notification progress, null when not carried
     */
    public void onHistoryMessage(String syncType, Integer progress) {
        List<MessagingHistoryStatus> emit = new ArrayList<>();
        synchronized (this) {
            if (syncType == null) {
                // fall through: no emission
            } else if ("INITIAL_BOOTSTRAP".equals(syncType) && !initialBootstrapComplete) {
                initialBootstrapComplete = true;
                emit.add(new MessagingHistoryStatus(syncType, "complete", true));
            } else if ("RECENT".equals(syncType)) {
                if (progress != null && progress == 100 && !recentSyncComplete) {
                    recentSyncComplete = true;
                    timer.cancel();
                    emit.add(new MessagingHistoryStatus(syncType, "complete", true));
                } else if (!recentSyncComplete) {
                    timer.cancel();
                    timer.schedule(this::onPausedTimeout, PAUSED_TIMEOUT_MS);
                }
            }
        }
        emit.forEach(this::sink);
    }

    /**
     * Fires the paused timeout: if the RECENT sync is still not
     * complete, marks it complete and emits {@code paused} with
     * {@code explicit=false}. Called by the timer; also directly
     * drivable in tests. A second call after completion is a no-op
     * (dedup).
     */
    public void onPausedTimeout() {
        List<MessagingHistoryStatus> emit = new ArrayList<>();
        synchronized (this) {
            if (!recentSyncComplete) {
                recentSyncComplete = true;
                emit.add(new MessagingHistoryStatus("RECENT", "paused", false));
            }
        }
        emit.forEach(this::sink);
    }

    /** Clears both completion flags and cancels any pending paused timer. */
    public synchronized void reset() {
        initialBootstrapComplete = false;
        recentSyncComplete = false;
        timer.cancel();
    }

    /** @return true once an INITIAL_BOOTSTRAP completion has been emitted */
    public synchronized boolean isInitialBootstrapComplete() {
        return initialBootstrapComplete;
    }

    /** @return true once a RECENT completion or pause has been emitted */
    public synchronized boolean isRecentSyncComplete() {
        return recentSyncComplete;
    }

    private void sink(MessagingHistoryStatus status) {
        if (sink != null) {
            sink.accept(status);
        }
    }
}
