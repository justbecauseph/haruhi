package town.lampas.haruhi.content.remote;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Fetches one request's bundle from the Portal: {@code GET <origin>/api/v1/haruhi/requests/<request>/bundle} with the
 * request's token as a bearer. It never follows a redirect, so the token goes to the configured origin only. The body
 * is refused as soon as it passes the size the call named, and its size, SHA-256 and UTF-8 are checked before anything
 * parses it. Runs off the server thread; the token is never logged or kept.
 */
final class BundleFetcher implements AutoCloseable {
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    /** The whole fetch, from the request to the last byte of the body. */
    static final Duration FETCH_TIMEOUT = Duration.ofSeconds(60);

    private final RemoteContentConfig config;
    private final HttpClient client;
    private final Duration timeout;

    BundleFetcher(RemoteContentConfig config) {
        this(config, FETCH_TIMEOUT);
    }

    /** For tests: a shorter {@code timeout}. */
    BundleFetcher(RemoteContentConfig config, Duration timeout) {
        if (!config.enabled()) {
            throw new IllegalArgumentException("Remote content is not enabled");
        }
        this.config = config;
        this.timeout = Objects.requireNonNull(timeout);
        // HTTP/1.1: one GET needs nothing more, and it avoids an h2c upgrade on a plain-http loopback origin.
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    RemoteContentConfig config() {
        return config;
    }

    /**
     * The bundle text of {@code request}. Fails with a {@link FetchFailure} when the Portal cannot be reached, answers
     * other than 200, sends a body of another size or SHA-256 than the call named, or sends bytes that are not UTF-8.
     *
     * @param size the body's length in bytes, at most {@link RemoteContentConfig#maxBytes()}
     * @param sha256 the body's SHA-256, lowercase hex
     */
    CompletableFuture<String> fetch(UUID request, String token, long size, String sha256) {
        URI uri = config.bundleUri(request);
        HttpRequest get = HttpRequest.newBuilder(uri)
                .GET()
                .timeout(timeout)
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .build();
        CompletableFuture<HttpResponse<byte[]>> sent;
        try {
            // A body that is not the bundle is not read: 0 bytes are allowed, so the subscriber cancels at once.
            sent = client.sendAsync(get, info -> new LimitedBody(info.statusCode() == 200 ? size : 0,
                    info.headers().firstValueAsLong("content-length"), info.statusCode() != 200));
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(
                    new FetchFailure(FetchFailure.INTERNAL, "The fetch could not start: " + e.getClass().getName()));
        }
        return sent.copy()
                .orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .handle((response, error) -> {
                    if (error != null) {
                        // Cancelling the client's own future stops the exchange, so a stalled body is not read on.
                        sent.cancel(true);
                        throw new CompletionException(failure(error));
                    }
                    return checked(response, size, sha256);
                });
    }

    /** Checks the response and decodes it; throws a {@link CompletionException} around a {@link FetchFailure}. */
    static String checked(HttpResponse<byte[]> response, long size, String sha256) {
        if (response.statusCode() != 200) {
            throw new CompletionException(new FetchFailure(FetchFailure.HTTP_STATUS,
                    "The Portal answered HTTP " + response.statusCode()));
        }
        return verify(response.body(), size, sha256);
    }

    /** The body as text, after its size, SHA-256 and UTF-8 checks. */
    static String verify(byte[] body, long size, String sha256) {
        if (body.length != size) {
            throw new CompletionException(new FetchFailure(FetchFailure.SIZE_MISMATCH,
                    "The Portal sent " + body.length + " bytes, not the " + size + " the call named"));
        }
        String actual = sha256Hex(body);
        if (!actual.equals(sha256.toLowerCase(Locale.ROOT))) {
            throw new CompletionException(new FetchFailure(FetchFailure.SHA256_MISMATCH,
                    "The body's SHA-256 is " + actual + ", not the one the call named"));
        }
        try {
            CharBuffer text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body));
            return text.toString();
        } catch (CharacterCodingException e) {
            throw new CompletionException(new FetchFailure(FetchFailure.NOT_UTF8, "The body is not UTF-8 text"));
        }
    }

    static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("The JRE has no SHA-256", e);
        }
    }

    /** The failure a transport error stands for. Messages name the error's type, not the URL or the request. */
    private static FetchFailure failure(Throwable error) {
        Throwable cause = error;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof FetchFailure failure) {
            return failure;
        }
        if (cause instanceof HttpTimeoutException || cause instanceof TimeoutException) {
            return new FetchFailure(FetchFailure.TIMEOUT, "The Portal did not send the bundle within "
                    + "the time allowed");
        }
        if (cause instanceof IOException) {
            return new FetchFailure(FetchFailure.FETCH_FAILED, "The fetch from the Portal failed: "
                    + cause.getClass().getSimpleName() + (cause.getMessage() == null ? "" : ": " + cause.getMessage()));
        }
        return new FetchFailure(FetchFailure.INTERNAL, "The fetch failed: " + cause.getClass().getName());
    }

    @Override
    public void close() {
        client.shutdownNow();
    }

    /**
     * Collects the body, and gives up as soon as it passes {@code limit} bytes, or before reading when the declared
     * length already does. With {@code discard}, it reads nothing and completes empty, for a status other than 200.
     */
    static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final long limit;
        private final OptionalLong declared;
        private final boolean discard;
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        private long received;

        LimitedBody(long limit, OptionalLong declared, boolean discard) {
            this.limit = limit;
            this.declared = declared;
            this.discard = discard;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            if (discard) {
                subscription.cancel();
                body.complete(new byte[0]);
                return;
            }
            if (declared.isPresent() && declared.getAsLong() > limit) {
                subscription.cancel();
                body.completeExceptionally(tooLong("The Portal declared a body of " + declared.getAsLong() + " bytes, more"));
                return;
            }
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            if (body.isDone()) {
                return;
            }
            for (ByteBuffer item : items) {
                received += item.remaining();
                if (received > limit) {
                    subscription.cancel();
                    body.completeExceptionally(tooLong("The Portal sent a longer body"));
                    return;
                }
                byte[] chunk = new byte[item.remaining()];
                item.get(chunk);
                bytes.writeBytes(chunk);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            body.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            body.complete(bytes.toByteArray());
        }

        private FetchFailure tooLong(String what) {
            return new FetchFailure(FetchFailure.SIZE_MISMATCH, what + " than the " + limit + " bytes the call named");
        }
    }
}
