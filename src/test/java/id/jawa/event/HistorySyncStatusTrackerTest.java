package id.jawa.event;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HistorySyncStatusTrackerTest {

    /** Manual timer: the test drives firing; no real 120s wait anywhere. */
    private static final class ManualTimer implements HistorySyncStatusTracker.PausedTimer {
        Runnable pending;
        int scheduled;
        int cancelled;

        @Override
        public void schedule(Runnable task, long delayMs) {
            assertThat(delayMs).isEqualTo(HistorySyncStatusTracker.PAUSED_TIMEOUT_MS);
            pending = task;
            scheduled++;
        }

        @Override
        public void cancel() {
            pending = null;
            cancelled++;
        }

        void fire() {
            Runnable task = pending;
            pending = null;
            if (task != null) {
                task.run();
            }
        }
    }

    private static final class Harness {
        final ManualTimer timer = new ManualTimer();
        final List<MessagingHistoryStatus> emitted = new ArrayList<>();
        final HistorySyncStatusTracker tracker =
            new HistorySyncStatusTracker(emitted::add, timer);
    }

    @Test
    void bootstrapFiresImmediatelyAndDedupes() {
        Harness h = new Harness();
        h.tracker.onHistoryMessage("INITIAL_BOOTSTRAP", null);
        assertThat(h.emitted).containsExactly(
            new MessagingHistoryStatus("INITIAL_BOOTSTRAP", "complete", true));
        h.tracker.onHistoryMessage("INITIAL_BOOTSTRAP", null);
        assertThat(h.emitted).hasSize(1);
        assertThat(h.tracker.isInitialBootstrapComplete()).isTrue();
    }

    @Test
    void recentProgress100FiresExplicitCompletionAndDedupes() {
        Harness h = new Harness();
        h.tracker.onHistoryMessage("RECENT", 50);
        assertThat(h.emitted).isEmpty();
        h.tracker.onHistoryMessage("RECENT", 100);
        assertThat(h.emitted).containsExactly(
            new MessagingHistoryStatus("RECENT", "complete", true));
        assertThat(h.timer.pending).isNull();
        h.tracker.onHistoryMessage("RECENT", 100);
        assertThat(h.emitted).hasSize(1);
        assertThat(h.tracker.isRecentSyncComplete()).isTrue();
    }

    @Test
    void recentChunkResetsPausedTimerAndPausedFiresOnlyOnce() {
        Harness h = new Harness();
        h.tracker.onHistoryMessage("RECENT", 10);
        assertThat(h.emitted).isEmpty();
        assertThat(h.timer.scheduled).isEqualTo(1);
        h.tracker.onHistoryMessage("RECENT", 20);
        assertThat(h.timer.scheduled).isEqualTo(2);
        assertThat(h.timer.pending).isNotNull();
        h.timer.fire();
        assertThat(h.emitted).containsExactly(
            new MessagingHistoryStatus("RECENT", "paused", false));
        h.timer.fire();
        assertThat(h.emitted).hasSize(1);
        assertThat(h.tracker.isRecentSyncComplete()).isTrue();
    }

    @Test
    void pausedDoesNotFireAfterExplicitCompletion() {
        Harness h = new Harness();
        h.tracker.onHistoryMessage("RECENT", 10);
        h.tracker.onHistoryMessage("RECENT", 100);
        assertThat(h.timer.pending).isNull();
        h.timer.fire();
        assertThat(h.emitted).containsExactly(
            new MessagingHistoryStatus("RECENT", "complete", true));
    }

    @Test
    void directPausedTimeoutCallDedupes() {
        Harness h = new Harness();
        h.tracker.onPausedTimeout();
        h.tracker.onPausedTimeout();
        assertThat(h.emitted).containsExactly(
            new MessagingHistoryStatus("RECENT", "paused", false));
    }

    @Test
    void resetClearsFlagsAndCancelsTimer() {
        Harness h = new Harness();
        h.tracker.onHistoryMessage("INITIAL_BOOTSTRAP", null);
        h.tracker.onHistoryMessage("RECENT", 10);
        h.tracker.reset();
        assertThat(h.tracker.isInitialBootstrapComplete()).isFalse();
        assertThat(h.tracker.isRecentSyncComplete()).isFalse();
        assertThat(h.timer.pending).isNull();
        h.tracker.onHistoryMessage("INITIAL_BOOTSTRAP", null);
        assertThat(h.emitted).hasSize(2);
    }

    @Test
    void otherSyncTypesAndNullProgressChunkBehaviour() {
        Harness h = new Harness();
        h.tracker.onHistoryMessage("PUSH_NAME", null);
        h.tracker.onHistoryMessage("FULL", 100);
        h.tracker.onHistoryMessage("ON_DEMAND", 100);
        h.tracker.onHistoryMessage(null, null);
        assertThat(h.emitted).isEmpty();
        assertThat(h.timer.scheduled).isEqualTo(0);
        // A RECENT chunk without progress still resets the paused timer.
        h.tracker.onHistoryMessage("RECENT", null);
        assertThat(h.timer.scheduled).isEqualTo(1);
        assertThat(h.emitted).isEmpty();
    }
}
