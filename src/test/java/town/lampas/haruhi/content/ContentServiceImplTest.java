package town.lampas.haruhi.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import town.lampas.haruhi.content.model.ContentBundle;

/** The service's state and listeners, without a server or storage. */
class ContentServiceImplTest {

    @BeforeAll
    static void boot() {
        ContentTestSupport.boot();
    }

    @Test
    void nothingIsActiveBeforeAnyActivation() {
        ContentServiceImpl service = new ContentServiceImpl();
        assertSame(ContentBundle.EMPTY, service.active());
        assertEquals(OptionalLong.empty(), service.activeRevisionId());
        assertEquals(Optional.empty(), service.activeDigest());
    }

    @Test
    void activateUnstoredSetsTheBundleAndClearsTheRevision() {
        ContentServiceImpl service = new ContentServiceImpl();
        ContentBundle bundle = ContentTestSupport.bundleOf(ContentTestSupport.validate(ContentTestSupport.baseBundle()));
        List<ContentBundle> seen = new ArrayList<>();
        service.addActivationListener(seen::add);

        service.activateUnstored(bundle);

        assertSame(bundle, service.active());
        assertEquals(OptionalLong.empty(), service.activeRevisionId());
        assertEquals(Optional.empty(), service.activeDigest());
        assertEquals(List.of(bundle), seen);
    }

    @Test
    void listenersRunInRegistrationOrderAndAFailingOneDoesNotStopTheRest() {
        ContentServiceImpl service = new ContentServiceImpl();
        List<String> calls = new ArrayList<>();
        service.addActivationListener(bundle -> calls.add("first"));
        service.addActivationListener(bundle -> {
            calls.add("broken");
            throw new IllegalStateException("listener failure, expected by this test");
        });
        service.addActivationListener(bundle -> calls.add("last"));

        service.activateUnstored(ContentBundle.EMPTY);

        assertEquals(List.of("first", "broken", "last"), calls);
        assertSame(ContentBundle.EMPTY, service.active());
    }

    @Test
    void parseAndValidateReturnsTheBundleOrEveryProblem() {
        ContentServiceImpl service = new ContentServiceImpl();

        DataResult<ContentBundle> valid = service.parseAndValidate(ContentTestSupport.baseBundle().toString());
        assertTrue(valid.error().isEmpty());
        assertEquals(1, valid.result().orElseThrow().npcs().size());

        DataResult<ContentBundle> invalid = service.parseAndValidate("{\"schema\":2}");
        assertTrue(invalid.error().isPresent());
        assertEquals("bundle: schema 2 is not supported, this build reads schema 1",
                invalid.error().get().message());
    }

    @Test
    void validateKeepsTheCanonicalFormThatARevisionStores() {
        ContentServiceImpl service = new ContentServiceImpl();
        String input = ContentTestSupport.baseBundle().toString();
        ValidatedBundle validated = service.validate(input).result().orElseThrow();
        assertEquals(BundleDigest.canonicalJson(JsonParser.parseString(input)), validated.canonicalJson());
        assertEquals(BundleDigest.sha256Hex(validated.canonicalJson()), validated.digest());
    }

    @Test
    void detachForgetsTheContentOfTheStoppedServer() {
        ContentServiceImpl service = new ContentServiceImpl();
        ContentBundle bundle =
                ContentTestSupport.bundleOf(ContentTestSupport.validate(ContentTestSupport.baseBundle()));
        service.activate(bundle, OptionalLong.of(2), Optional.of("digest-of-revision-2"));
        service.startupFinished();

        service.detach();

        assertSame(ContentBundle.EMPTY, service.active());
        assertEquals(OptionalLong.empty(), service.activeRevisionId());
        assertEquals(Optional.empty(), service.activeDigest());
        assertFalse(service.isStartupComplete());
    }

    @Test
    void startupIsCompleteFromTheEndOfTheStartupLoadUntilTheServerStops() {
        ContentServiceImpl service = new ContentServiceImpl();
        assertFalse(service.isStartupComplete());

        service.startupFinished();
        assertTrue(service.isStartupComplete());

        service.detach();
        assertFalse(service.isStartupComplete());
    }
}
