package town.lampas.haruhi.dialogue.flags;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import town.lampas.haruhi.dialogue.TestServerThread;
import town.lampas.haruhi.storage.FlagKey;

class PlayerFlagsTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final FlagKey ONCE = new FlagKey("haruhi:test/npc", "got_reward");
    private static final FlagKey COUNT = new FlagKey("haruhi:test/npc", "count");

    private final ExecutorService storageThread = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("haruhi-test-storage").daemon(true).factory());
    private FakeStorage storage;
    private CharacterStore store;
    private TestServerThread server;
    private PlayerFlags flags;

    @BeforeEach
    void setUp() {
        storage = new FakeStorage();
        store = new CharacterStore(() -> storage);
        CharacterCache cache = new CharacterCache();
        cache.load(Map.of(), Map.of());
        server = new TestServerThread();
        flags = new PlayerFlags(ALICE, cache, store, server);
    }

    @AfterEach
    void stopStorageThread() throws InterruptedException {
        storageThread.shutdownNow();
        storageThread.awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void anUnsetFlagIsNotSetZeroIsNotSetAndAnyOtherValueIs() {
        assertFalse(flags.isSet(ONCE));
        flags.set(ONCE, 0);
        assertFalse(flags.isSet(ONCE), "0 reads as not set");
        flags.set(ONCE, 4);
        assertTrue(flags.isSet(ONCE), "a value other than 0 reads as set");
        flags.clear(ONCE);
        assertFalse(flags.isSet(ONCE), "a cleared flag reads as not set");
    }

    @Test
    void addAddsToTheCachedValueAndStorageGetsTheSum() {
        flags.add(COUNT, 2);
        flags.add(COUNT, 2);

        assertEquals(4, flags.get(COUNT));
        store.flushPending();
        assertEquals(Optional.of(4), storage.stored(ALICE, COUNT));
    }

    @Test
    void aSuccessfulDurableWriteIsInStorageAtOnce() {
        CompletableFuture<Void> written = flags.setDurable(ONCE, 1);

        assertFalse(written.isCompletedExceptionally());
        assertEquals(Optional.of(1), storage.stored(ALICE, ONCE));
        assertTrue(flags.isSet(ONCE));
    }

    @Test
    void aFailedDurableWriteRestoresTheCacheAndStorageNeverKeepsTheFailedValue() {
        storage.failNextWrites(1);

        CompletableFuture<Void> written = flags.setDurable(ONCE, 1);
        assertTrue(written.isCompletedExceptionally(), "the caller learns the write failed");
        assertFalse(flags.isSet(ONCE), "the cache goes back to unset");

        store.flushPending();
        assertEquals(Optional.empty(), storage.stored(ALICE, ONCE),
                "the next flush must not write the value whose write failed");
    }

    @Test
    void aFailedDurableWriteThatArrivesOffTheServerThreadRestoresTheCacheOnTheServerThread() {
        storage.holdNextWrite();
        flags.setDurable(ONCE, 1);

        onStorageThread(() -> storage.failHeldWrite(new IllegalStateException("late failure")));

        assertTrue(flags.isSet(ONCE), "the restore waits for the server thread");
        server.runQueued();
        assertFalse(flags.isSet(ONCE), "running the server's queue restores the cache");
    }

    @Test
    void aFailedDurableWriteThatArrivesAfterTheServerStoppedIsDroppedAndNeverRestoresOffTheServerThread() {
        storage.holdNextWrite();
        flags.setDurable(ONCE, 1);
        server.stop();

        onStorageThread(() -> storage.failHeldWrite(new IllegalStateException("late failure")));

        assertTrue(flags.isSet(ONCE), "the restore must not run on the storage thread after the server stopped");
        assertEquals(0, server.queued(), "a stopped server queues nothing");
    }

    /** Runs {@code action} on the storage thread and waits for it, so whatever it does has happened on return. */
    private void onStorageThread(Runnable action) {
        CompletableFuture.runAsync(action, storageThread).join();
    }
}
