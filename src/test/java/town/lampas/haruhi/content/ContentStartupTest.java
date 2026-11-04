package town.lampas.haruhi.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import town.lampas.haruhi.storage.StoredRevision;

import static town.lampas.haruhi.content.ContentTestSupport.NPC;
import static town.lampas.haruhi.content.ContentTestSupport.id;
import static town.lampas.haruhi.content.ContentTestSupport.npc;

/**
 * The startup decision: the active stored revision wins, then the config file, then the seed. A chosen bundle that does
 * not validate refuses the startup without trying the next source. Expected values follow the order in the
 * {@link ContentStartup} Javadoc.
 */
class ContentStartupTest {
    private static final Path CONFIG_FILE = Path.of("config", "haruhi", "bundle.json");
    private static final Function<String, DataResult<ValidatedBundle>> VALIDATE =
            json -> BundleValidator.validate(json, JsonOps.INSTANCE);

    @BeforeAll
    static void boot() {
        ContentTestSupport.boot();
    }

    @Test
    void theActiveStoredRevisionWinsOverTheConfigFileAndTheSeed() {
        StoredRevision stored = revision(7, bundleNamed("Stored"));

        ContentStartup.Choice choice = choose(Optional.of(stored), Optional.of(bundleNamed("Config")),
                bundleNamed("Seed"));

        ContentStartup.Choice.Stored activated = assertInstanceOf(ContentStartup.Choice.Stored.class, choice);
        assertEquals(7, activated.revision().id());
        assertEquals("Stored", npcName(activated.validated()));
    }

    @Test
    void anInvalidActiveRevisionRefusesTheStartupInsteadOfFallingBack() {
        StoredRevision stored = revision(7, "{\"schema\":2}");

        ContentStartup.Choice.Refused refused = assertInstanceOf(ContentStartup.Choice.Refused.class,
                choose(Optional.of(stored), Optional.of(bundleNamed("Config")), bundleNamed("Seed")));

        assertTrue(refused.message().startsWith(
                        "Active content revision 7 no longer validates, so no content is active:"), refused.message());
        assertEquals(Optional.empty(), refused.cause());
    }

    @Test
    void theConfigFileIsImportedWhenNothingIsStored() {
        ContentStartup.Choice choice = choose(Optional.empty(), Optional.of(bundleNamed("Config")),
                bundleNamed("Seed"));

        ContentStartup.Choice.Imported imported = assertInstanceOf(ContentStartup.Choice.Imported.class, choice);
        assertEquals("startup:config/haruhi/bundle.json", imported.author());
        assertEquals("Config", npcName(imported.bundle()));
    }

    @Test
    void anInvalidConfigFileRefusesTheStartupInsteadOfUsingTheSeed() {
        ContentStartup.Choice.Refused refused = assertInstanceOf(ContentStartup.Choice.Refused.class,
                choose(Optional.empty(), Optional.of("{\"schema\":2}"), bundleNamed("Seed")));

        assertTrue(refused.message().startsWith(
                        "Content bundle from startup:config/haruhi/bundle.json does not validate, so no content"
                                + " is active:"), refused.message());
    }

    @Test
    void theSeedIsImportedWhenNeitherAStoredRevisionNorTheConfigFileExists() {
        ContentStartup.Choice choice = choose(Optional.empty(), Optional.empty(), bundleNamed("Seed"));

        ContentStartup.Choice.Imported imported = assertInstanceOf(ContentStartup.Choice.Imported.class, choice);
        assertEquals("startup:seed", imported.author());
        assertEquals("Seed", npcName(imported.bundle()));
    }

    @Test
    void anInvalidSeedRefusesTheStartup() {
        ContentStartup.Choice.Refused refused = assertInstanceOf(ContentStartup.Choice.Refused.class,
                choose(Optional.empty(), Optional.empty(), "{\"schema\":2}"));

        assertTrue(refused.message().startsWith("Content bundle from startup:seed does not validate"),
                refused.message());
    }

    @Test
    void aFailedLookupOfTheActiveRevisionRefusesWithItsCause() {
        IllegalStateException failure = new IllegalStateException("database down");
        ContentStartup.Choice.Refused refused = assertInstanceOf(ContentStartup.Choice.Refused.class,
                ContentStartup.choose(CONFIG_FILE,
                        CompletableFuture.failedFuture(failure),
                        CompletableFuture.completedFuture(Optional.of(bundleNamed("Config"))),
                        CompletableFuture.completedFuture(bundleNamed("Seed")),
                        VALIDATE).join());

        assertSame(failure, refused.cause().orElseThrow());
        assertTrue(refused.message().contains("Could not load the active content revision"), refused.message());
    }

    @Test
    void aFailedConfigReadRefusesWithItsCauseWhenNothingIsStored() {
        IllegalStateException failure = new IllegalStateException("disk error");
        ContentStartup.Choice.Refused refused = assertInstanceOf(ContentStartup.Choice.Refused.class,
                ContentStartup.choose(CONFIG_FILE,
                        CompletableFuture.completedFuture(Optional.empty()),
                        CompletableFuture.failedFuture(failure),
                        CompletableFuture.completedFuture(bundleNamed("Seed")),
                        VALIDATE).join());

        assertSame(failure, refused.cause().orElseThrow());
    }

    @Test
    void aFailedConfigReadDoesNotMatterWhileAStoredRevisionIsActive() {
        StoredRevision stored = revision(7, bundleNamed("Stored"));

        ContentStartup.Choice choice = ContentStartup.choose(CONFIG_FILE,
                CompletableFuture.completedFuture(Optional.of(stored)),
                CompletableFuture.failedFuture(new IllegalStateException("disk error")),
                CompletableFuture.completedFuture(bundleNamed("Seed")),
                VALIDATE).join();

        assertInstanceOf(ContentStartup.Choice.Stored.class, choice);
    }

    @Test
    void aFailedSeedReadRefusesWithItsCause() {
        IllegalStateException failure = new IllegalStateException("jar has no seed");
        ContentStartup.Choice.Refused refused = assertInstanceOf(ContentStartup.Choice.Refused.class,
                ContentStartup.choose(CONFIG_FILE,
                        CompletableFuture.completedFuture(Optional.empty()),
                        CompletableFuture.completedFuture(Optional.empty()),
                        CompletableFuture.failedFuture(failure),
                        VALIDATE).join());

        assertSame(failure, refused.cause().orElseThrow());
    }

    private static ContentStartup.Choice choose(
            Optional<StoredRevision> active, Optional<String> config, String seed) {
        return ContentStartup.choose(CONFIG_FILE,
                CompletableFuture.completedFuture(active),
                CompletableFuture.completedFuture(config),
                CompletableFuture.completedFuture(seed),
                VALIDATE).join();
    }

    private static StoredRevision revision(long id, String bundleJson) {
        return new StoredRevision(id, "digest-" + id, bundleJson, "tester", Instant.EPOCH, Optional.empty(), true);
    }

    /** The base bundle with its one NPC renamed, so each source is recognisable after activation. */
    private static String bundleNamed(String name) {
        var bundle = ContentTestSupport.baseBundle();
        npc(bundle).addProperty("name", name);
        return bundle.toString();
    }

    private static String npcName(ValidatedBundle validated) {
        return validated.bundle().npcs().get(id(NPC)).name().getString();
    }
}
