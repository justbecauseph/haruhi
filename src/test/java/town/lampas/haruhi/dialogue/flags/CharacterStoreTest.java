package town.lampas.haruhi.dialogue.flags;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import town.lampas.haruhi.storage.FlagChange;
import town.lampas.haruhi.storage.FlagKey;

class CharacterStoreTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final FlagKey MET = new FlagKey("haruhi:test/npc", "met");

    @Test
    void aLoadReadsWhatStorageHasWhenNothingIsWaiting() {
        FakeStorage storage = new FakeStorage();
        CharacterStore store = new CharacterStore(() -> storage);
        store.recordFlag(ALICE, FlagChange.set(MET, 1));
        store.flushPending();

        assertEquals(Map.of(MET, 1), store.load(ALICE).join().flags());
    }

    @Test
    void aLoadAfterAFailedFlushSeesTheChangeThatIsNotStoredYet() {
        FakeStorage storage = new FakeStorage();
        CharacterStore store = new CharacterStore(() -> storage);
        store.recordFlag(ALICE, FlagChange.set(MET, 1));
        storage.failNextWrites(1);
        store.flush(ALICE);

        CharacterStore.Loaded loaded = store.load(ALICE).join();

        assertEquals(Map.of(MET, 1), loaded.flags(), "the server decided met=1, and the rejoin must see it");
        assertEquals(Optional.empty(), storage.stored(ALICE, MET), "storage itself still lacks it");
    }

    @Test
    void theChangeTheLoadSawIsStoredByTheNextFlush() {
        FakeStorage storage = new FakeStorage();
        CharacterStore store = new CharacterStore(() -> storage);
        store.recordFlag(ALICE, FlagChange.set(MET, 1));
        storage.failNextWrites(1);
        store.flush(ALICE);
        store.load(ALICE).join();

        store.flushPending();

        assertEquals(Optional.of(1), storage.stored(ALICE, MET));
        assertEquals(Map.of(MET, 1), store.load(ALICE).join().flags());
    }
}
