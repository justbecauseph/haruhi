package town.lampas.haruhi.content.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RemoteContentConfigTest {

    @Test
    void aMissingFileIsWrittenWithRemoteContentOff(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("config").resolve(RemoteContentConfig.FILE);

        RemoteContentConfig config = RemoteContentConfig.loadOrCreate(file);

        assertEquals(RemoteContentConfig.DISABLED, config);
        assertTrue(Files.exists(file));
        assertEquals(RemoteContentConfig.DISABLED, RemoteContentConfig.parse(Files.readString(file)),
                "the written defaults read back as the defaults");
    }

    @Test
    void theDefaultsAreOffWithFourMebibytes() {
        RemoteContentConfig config = RemoteContentConfig.parse("{}");

        assertFalse(config.enabled());
        assertEquals(Optional.empty(), config.origin());
        assertEquals(4L * 1024 * 1024, config.maxBytes());
    }

    @Test
    void anEnabledConfigBuildsTheBundleUrlFromItsOriginAndTheRequest() {
        RemoteContentConfig config = RemoteContentConfig.parse("""
                { "remote": { "enabled": true, "origin": "https://Dev.Lampas.Town/", "max_bytes": 1048576 } }
                """);
        UUID request = UUID.fromString("6f1c2a4e-1b2c-4d3e-8f90-123456789abc");

        assertTrue(config.enabled());
        assertEquals(Optional.of("https://dev.lampas.town"), config.origin());
        assertEquals(1048576, config.maxBytes());
        assertEquals(URI.create("https://dev.lampas.town/api/v1/haruhi/requests/" + request + "/bundle"),
                config.bundleUri(request));
    }

    @Test
    void aPortKeepsItsPlace() {
        assertEquals("https://portal.example:8443", RemoteContentConfig.origin("https://portal.example:8443"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:3000", "http://127.0.0.1:3000", "http://[::1]:3000"})
    void plainHttpIsAllowedOnlyForLoopback(String origin) {
        assertEquals(origin, RemoteContentConfig.origin(origin));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://dev.lampas.town",
            "ftp://dev.lampas.town",
            "https://dev.lampas.town/api",
            "https://dev.lampas.town?x=1",
            "https://dev.lampas.town#top",
            "https://user:pass@dev.lampas.town",
            "dev.lampas.town",
            "https://",
            "not a url"})
    void anOriginThatIsNotJustASchemeHostAndPortIsRefused(String origin) {
        assertThrows(IllegalArgumentException.class, () -> RemoteContentConfig.origin(origin));
    }

    @Test
    void enabledWithoutAnOriginIsRefused() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> RemoteContentConfig.parse("{ \"remote\": { \"enabled\": true } }"));

        assertTrue(error.getMessage().contains("remote.origin"), error.getMessage());
    }

    @Test
    void anOriginMayBeSetWhileDisabled() {
        RemoteContentConfig config = RemoteContentConfig.parse(
                "{ \"remote\": { \"enabled\": false, \"origin\": \"https://dev.lampas.town\" } }");

        assertFalse(config.enabled());
        assertEquals(Optional.of("https://dev.lampas.town"), config.origin());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{ \"remote\": { \"enable\": true } }",
            "{ \"remotes\": {} }",
            "{ \"remote\": { \"enabled\": \"yes\" } }",
            "{ \"remote\": { \"origin\": 5 } }",
            "{ \"remote\": { \"max_bytes\": 0 } }",
            "{ \"remote\": { \"max_bytes\": 67108865 } }",
            "{ \"remote\": { \"max_bytes\": 2.5 } }",
            "{ \"remote\": { \"max_bytes\": 1e3 } }",
            "{ \"remote\": [] }",
            "[]",
            "{"})
    void aMisspeltOrMistypedSettingIsRefused(String json) {
        assertThrows(IllegalArgumentException.class, () -> RemoteContentConfig.parse(json));
    }

    @Test
    void anUnreadableFileIsAnIllegalState(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(RemoteContentConfig.FILE);
        Files.writeString(file, "{ \"remote\": { \"enable\": true } }");

        IllegalStateException error =
                assertThrows(IllegalStateException.class, () -> RemoteContentConfig.loadOrCreate(file));

        assertTrue(error.getMessage().contains("remote.enable"), error.getMessage());
    }
}
