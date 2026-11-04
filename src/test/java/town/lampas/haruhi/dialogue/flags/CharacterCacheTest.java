package town.lampas.haruhi.dialogue.flags;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;
import java.util.OptionalInt;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import town.lampas.haruhi.storage.FlagKey;
import town.lampas.haruhi.storage.VisitKey;
import town.lampas.haruhi.storage.VisitRecord;

class CharacterCacheTest {
    private static final FlagKey MET = new FlagKey("haruhi:test/npc", "met");
    private static final VisitKey GREETER = new VisitKey(
            Identifier.fromNamespaceAndPath("haruhi", "test/graph"), VisitKey.NO_NPC);
    private static final Instant NOW = Instant.parse("2026-11-04T00:00:00Z");

    @Test
    void anUnsetFlagReadsAsZero() {
        CharacterCache cache = new CharacterCache();
        cache.load(Map.of(), Map.of());
        assertEquals(0, cache.flag(MET));
    }

    @Test
    void aStoredZeroReadsAsZeroAndAnotherValueIsKept() {
        CharacterCache cache = new CharacterCache();
        cache.load(Map.of(MET, 0), Map.of());
        assertEquals(0, cache.flag(MET));
        cache.setFlag(MET, 4);
        assertEquals(4, cache.flag(MET));
    }

    @Test
    void restoreReturnsThePreviousValueOnlyWhileTheNewOneStands() {
        CharacterCache cache = new CharacterCache();
        cache.load(Map.of(MET, 1), Map.of());
        OptionalInt previous = cache.setFlag(MET, 2);
        assertEquals(OptionalInt.of(1), previous);

        cache.setFlag(MET, 7);
        cache.restoreFlag(MET, 2, previous);
        assertEquals(7, cache.flag(MET), "a newer value is kept");

        cache.setFlag(MET, 2);
        cache.restoreFlag(MET, 2, previous);
        assertEquals(1, cache.flag(MET));
    }

    @Test
    void visitsCountUpFromTheLoadedCount() {
        CharacterCache cache = new CharacterCache();
        cache.load(Map.of(), Map.of(GREETER, new VisitRecord(4, NOW)));
        assertEquals(4, cache.visitCount(GREETER));
        assertEquals(5, cache.recordVisit(GREETER, NOW));
        assertEquals(5, cache.visitCount(GREETER));
    }

    @Test
    void aFirstVisitCountsAsOne() {
        CharacterCache cache = new CharacterCache();
        cache.load(Map.of(), Map.of());
        assertEquals(1, cache.recordVisit(GREETER, NOW));
    }

    @Test
    void loadReplacesTheContentsAndMarksTheCacheLoaded() {
        CharacterCache cache = new CharacterCache();
        assertFalse(cache.isLoaded());
        cache.setFlag(MET, 9);
        cache.load(Map.of(), Map.of());
        assertTrue(cache.isLoaded());
        assertEquals(0, cache.flag(MET), "the old in-memory value is replaced by what storage returned");
    }
}
