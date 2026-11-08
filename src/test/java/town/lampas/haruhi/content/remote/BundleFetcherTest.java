package town.lampas.haruhi.content.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The fetch and its checks, against a Portal stand-in on a local HTTP server. */
class BundleFetcherTest {
    private static final String TOKEN = "tok_3f9a-secret.value";
    private static final UUID REQUEST = UUID.fromString("6f1c2a4e-1b2c-4d3e-8f90-123456789abc");
    private static final String PATH = "/api/v1/haruhi/requests/" + REQUEST + "/bundle";
    private static final byte[] BUNDLE = "{\"schema\":1,\"npcs\":{\"a:b\":{\"name\":\"Mira’s\"}}}"
            .getBytes(StandardCharsets.UTF_8);

    private HttpServer server;
    private final List<String> paths = Collections.synchronizedList(new ArrayList<>());
    private final List<String> authorizations = Collections.synchronizedList(new ArrayList<>());
    private volatile Consumer<HttpExchange> respond = exchange -> send(exchange, 200, BUNDLE);
    private BundleFetcher fetcher;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            paths.add(exchange.getRequestURI().getRawPath());
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            respond.accept(exchange);
        });
        server.start();
        fetcher = new BundleFetcher(config(server.getAddress().getPort(), BUNDLE.length), Duration.ofSeconds(2));
    }

    @AfterEach
    void stop() {
        fetcher.close();
        server.stop(0);
    }

    @Test
    void fetchesTheRequestsBundleWithTheTokenAsABearer() {
        String text = fetcher.fetch(REQUEST, TOKEN, BUNDLE.length, BundleFetcher.sha256Hex(BUNDLE)).join();

        assertEquals(new String(BUNDLE, StandardCharsets.UTF_8), text);
        assertEquals(List.of(PATH), paths);
        assertEquals(List.of("Bearer " + TOKEN), authorizations);
    }

    @Test
    void theShaMayBeGivenInUpperCase() {
        String text = fetcher.fetch(REQUEST, TOKEN, BUNDLE.length,
                BundleFetcher.sha256Hex(BUNDLE).toUpperCase()).join();

        assertEquals(new String(BUNDLE, StandardCharsets.UTF_8), text);
    }

    @Test
    void aStatusOtherThan200Fails() {
        respond = exchange -> send(exchange, 401, "{\"error\":\"bad token\"}".getBytes(StandardCharsets.UTF_8));

        FetchFailure failure = failure(fetcher.fetch(REQUEST, TOKEN, BUNDLE.length, BundleFetcher.sha256Hex(BUNDLE)));

        assertEquals(FetchFailure.HTTP_STATUS, failure.reason());
        assertEquals("The Portal answered HTTP 401", failure.getMessage());
    }

    @Test
    void aRedirectIsNotFollowed() {
        respond = exchange -> {
            exchange.getResponseHeaders().add("Location", "/elsewhere");
            send(exchange, 302, new byte[0]);
        };

        FetchFailure failure = failure(fetcher.fetch(REQUEST, TOKEN, BUNDLE.length, BundleFetcher.sha256Hex(BUNDLE)));

        assertEquals(FetchFailure.HTTP_STATUS, failure.reason());
        assertEquals(List.of(PATH), paths, "the fetch went on to the redirect target");
    }

    @Test
    void aShorterBodyThanTheCallNamedFails() {
        byte[] shorter = Arrays.copyOf(BUNDLE, BUNDLE.length - 1);
        respond = exchange -> send(exchange, 200, shorter);

        FetchFailure failure = failure(fetcher.fetch(REQUEST, TOKEN, BUNDLE.length, BundleFetcher.sha256Hex(BUNDLE)));

        assertEquals(FetchFailure.SIZE_MISMATCH, failure.reason());
    }

    @Test
    void aLongerDeclaredBodyIsRefusedBeforeItIsRead() {
        byte[] longer = (new String(BUNDLE, StandardCharsets.UTF_8) + " ").getBytes(StandardCharsets.UTF_8);
        respond = exchange -> send(exchange, 200, longer);

        FetchFailure failure = failure(fetcher.fetch(REQUEST, TOKEN, BUNDLE.length, BundleFetcher.sha256Hex(BUNDLE)));

        assertEquals(FetchFailure.SIZE_MISMATCH, failure.reason());
        assertTrue(failure.getMessage().contains("declared"), failure.getMessage());
    }

    @Test
    void aLongerChunkedBodyIsCutOffOnceItPassesTheSize() {
        byte[] large = new byte[256 * 1024];
        Arrays.fill(large, (byte) ' ');
        // Length 0 makes the server send the body chunked, with no Content-Length.
        respond = exchange -> {
            try (OutputStream out = start(exchange, 200, 0)) {
                out.write(large);
            } catch (IOException ignored) {
                // The fetcher hangs up once the body is too long.
            }
        };

        FetchFailure failure = failure(fetcher.fetch(REQUEST, TOKEN, BUNDLE.length, BundleFetcher.sha256Hex(BUNDLE)));

        assertEquals(FetchFailure.SIZE_MISMATCH, failure.reason());
        assertTrue(failure.getMessage().contains("longer"), failure.getMessage());
    }

    @Test
    void aBodyWithAnotherShaFails() {
        byte[] other = new String(BUNDLE, StandardCharsets.UTF_8).replace("Mira", "Miro").getBytes(StandardCharsets.UTF_8);
        respond = exchange -> send(exchange, 200, other);

        FetchFailure failure = failure(fetcher.fetch(REQUEST, TOKEN, BUNDLE.length, BundleFetcher.sha256Hex(BUNDLE)));

        assertEquals(FetchFailure.SHA256_MISMATCH, failure.reason());
    }

    @Test
    void aBodyThatIsNotUtf8Fails() {
        byte[] latin1 = "{\"name\":\"Miré\"}".getBytes(StandardCharsets.ISO_8859_1);
        respond = exchange -> send(exchange, 200, latin1);

        FetchFailure failure = failure(fetcher.fetch(REQUEST, TOKEN, latin1.length, BundleFetcher.sha256Hex(latin1)));

        assertEquals(FetchFailure.NOT_UTF8, failure.reason());
    }

    @Test
    void aPortalThatDoesNotAnswerInTimeFails() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        respond = exchange -> {
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            send(exchange, 200, BUNDLE);
        };
        try (BundleFetcher quick = new BundleFetcher(config(server.getAddress().getPort(), BUNDLE.length),
                Duration.ofMillis(300))) {
            FetchFailure failure = failure(quick.fetch(REQUEST, TOKEN, BUNDLE.length, BundleFetcher.sha256Hex(BUNDLE)));

            assertEquals(FetchFailure.TIMEOUT, failure.reason());
        } finally {
            release.countDown();
        }
    }

    @Test
    void anUnreachablePortalFails() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        try (BundleFetcher unreachable = new BundleFetcher(config(closedPort, BUNDLE.length), Duration.ofSeconds(2))) {
            FetchFailure failure =
                    failure(unreachable.fetch(REQUEST, TOKEN, BUNDLE.length, BundleFetcher.sha256Hex(BUNDLE)));

            assertEquals(FetchFailure.FETCH_FAILED, failure.reason());
        }
    }

    @Test
    void noFailureMessageCarriesTheToken() {
        List<Consumer<HttpExchange>> answers = List.of(
                exchange -> send(exchange, 403, TOKEN.getBytes(StandardCharsets.UTF_8)),
                exchange -> send(exchange, 200, TOKEN.getBytes(StandardCharsets.UTF_8)),
                exchange -> send(exchange, 200, new byte[0]));
        for (Consumer<HttpExchange> answer : answers) {
            respond = answer;
            FetchFailure failure =
                    failure(fetcher.fetch(REQUEST, TOKEN, BUNDLE.length, BundleFetcher.sha256Hex(BUNDLE)));
            assertFalse(failure.getMessage().contains(TOKEN), failure.getMessage());
        }
    }

    @Test
    void aFetcherNeedsRemoteContentEnabled() {
        assertThrows(IllegalArgumentException.class, () -> new BundleFetcher(RemoteContentConfig.DISABLED));
    }

    private static RemoteContentConfig config(int port, long maxBytes) {
        return new RemoteContentConfig(true, Optional.of("http://127.0.0.1:" + port), maxBytes);
    }

    private static FetchFailure failure(CompletableFuture<String> fetch) {
        try {
            fetch.join();
        } catch (CompletionException e) {
            Throwable cause = e;
            while (cause instanceof CompletionException && cause.getCause() != null) {
                cause = cause.getCause();
            }
            return assertInstanceOf(FetchFailure.class, cause);
        }
        throw new AssertionError("the fetch succeeded");
    }

    private static void send(HttpExchange exchange, int status, byte[] body) {
        try (OutputStream out = start(exchange, status, body.length == 0 ? -1 : body.length)) {
            out.write(body);
        } catch (IOException ignored) {
            // The fetcher may hang up first.
        }
    }

    private static OutputStream start(HttpExchange exchange, int status, long length) throws IOException {
        exchange.sendResponseHeaders(status, length);
        return exchange.getResponseBody();
    }
}
