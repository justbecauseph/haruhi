package town.lampas.haruhi.content.remote;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.DataResult;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import org.jspecify.annotations.Nullable;
import town.lampas.haruhi.Haruhi;
import town.lampas.haruhi.api.HaruhiServices;
import town.lampas.haruhi.binding.BindingService;
import town.lampas.haruhi.content.ContentRefusedException;
import town.lampas.haruhi.content.ContentServiceImpl;
import town.lampas.haruhi.content.ValidatedBundle;
import town.lampas.haruhi.content.api.ContentExtensions;
import town.lampas.haruhi.content.model.ContentBundle;
import town.lampas.haruhi.content.remote.ContentRequests.Call;
import town.lampas.haruhi.content.remote.ContentRequests.Done;
import town.lampas.haruhi.content.remote.ContentRequests.Failed;
import town.lampas.haruhi.content.remote.ContentRequests.Held;
import town.lampas.haruhi.content.remote.ContentRequests.Kind;
import town.lampas.haruhi.content.remote.ContentRequests.Running;
import town.lampas.haruhi.storage.RevisionSummary;
import town.lampas.haruhi.storage.StoredRevision;

/**
 * The handlers behind the {@code haruhi:content/*} management methods, for one running server. Each takes the method's
 * one param as any JSON, checks it with {@link Params}, and returns an answer object at once; every answer has a
 * {@code status}. Validate, import and activate start a request and answer {@code accepted}; {@link #request} reads
 * its result. {@link #list} and {@link #export} read storage and wait for it up to {@link #READ_TIMEOUT}, so they run
 * off the server thread; the others run on it.
 */
public final class ContentMethods implements AutoCloseable {
    public static final int LIST_DEFAULT = 20;
    public static final int LIST_MAX = 100;
    public static final int BINDINGS_DEFAULT = 100;
    public static final int BINDINGS_MAX = 200;
    /** The most UTF-8 bytes of bundle text one export answer carries. */
    public static final int EXPORT_MAX_BYTES = 48 * 1024;
    static final Duration READ_TIMEOUT = Duration.ofSeconds(3);

    private final ContentServiceImpl content;
    private final RemoteContentConfig config;
    private final @Nullable BundleFetcher fetcher;
    private final ContentRequests requests;
    private final MinecraftServer server;
    /** The last revision export read; revisions never change, so it can answer the next chunk without storage. */
    private volatile @Nullable StoredRevision exported;

    ContentMethods(ContentServiceImpl content, RemoteContentConfig config, @Nullable BundleFetcher fetcher,
            ContentRequests requests, MinecraftServer server) {
        this.content = content;
        this.config = config;
        this.fetcher = fetcher;
        this.requests = requests;
        this.server = server;
    }

    /** The handlers for {@code server}, fetching as {@code config} says. Close them when the server stops. */
    public static ContentMethods create(ContentServiceImpl content, RemoteContentConfig config, MinecraftServer server) {
        return create(content, config, server, BundleFetcher.FETCH_TIMEOUT);
    }

    /** As {@link #create(ContentServiceImpl, RemoteContentConfig, MinecraftServer)}, with a fetch timeout for tests. */
    public static ContentMethods create(
            ContentServiceImpl content, RemoteContentConfig config, MinecraftServer server, Duration fetchTimeout) {
        return new ContentMethods(content, config, config.enabled() ? new BundleFetcher(config, fetchTimeout) : null,
                new ContentRequests(Clock.systemUTC()), server);
    }

    /** {@code haruhi:content/status}. No param fields. Server thread. */
    public JsonObject status(@Nullable JsonElement param) {
        JsonObject answer = ok();
        content.activeRevision().ifPresent(revision -> answer.add("revision", summary(revision)));
        answer.addProperty("storage", content.storage().backendName());
        answer.addProperty("haruhi_version", FabricLoader.getInstance().getModContainer(Haruhi.MOD_ID)
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown"));
        answer.addProperty("schema", ContentBundle.SCHEMA);
        JsonArray extensions = new JsonArray();
        ContentExtensions.ids().forEach(id -> extensions.add(id.toString()));
        answer.add("extensions", extensions);
        JsonObject remote = new JsonObject();
        remote.addProperty("enabled", config.enabled());
        config.origin().ifPresent(origin -> remote.addProperty("origin", origin));
        remote.addProperty("max_bytes", config.maxBytes());
        answer.add("remote", remote);
        answer.addProperty("startup_complete", content.isStartupComplete());
        return answer;
    }

    /** {@code haruhi:content/list}: {@code before} (a revision id), {@code limit} (default 20, at most 100). */
    public JsonObject list(@Nullable JsonElement param) {
        OptionalLong before;
        int limit;
        try {
            Params params = Params.of(param);
            before = params.optionalWhole("before", 1);
            limit = (int) Math.min(LIST_MAX, params.optionalWhole("limit", 1).orElse(LIST_DEFAULT));
        } catch (Params.Invalid e) {
            return invalid(e);
        }
        List<RevisionSummary> page;
        try {
            page = read(content.storage().listRevisions(before, limit + 1));
        } catch (Unavailable e) {
            return unavailable(e.getMessage());
        }
        JsonObject answer = ok();
        JsonArray revisions = new JsonArray();
        page.stream().limit(limit).forEach(revision -> revisions.add(summary(revision)));
        answer.add("revisions", revisions);
        if (page.size() > limit) {
            answer.addProperty("next", page.get(limit - 1).id());
        }
        return answer;
    }

    /** {@code haruhi:content/validate}: {@code request}, {@code sha256}, {@code size}, {@code token}. Server thread. */
    public JsonObject validate(@Nullable JsonElement param) {
        return fetchRequest(param, Kind.VALIDATE);
    }

    /** {@code haruhi:content/import}: as {@link #validate}, plus {@code author}. Server thread. */
    public JsonObject importBundle(@Nullable JsonElement param) {
        return fetchRequest(param, Kind.IMPORT);
    }

    /** {@code haruhi:content/activate}: {@code request}, {@code revision}, {@code actor}. Server thread. */
    public JsonObject activate(@Nullable JsonElement param) {
        UUID id;
        long revision;
        String actor;
        try {
            Params params = Params.of(param);
            id = params.uuid("request");
            revision = params.whole("revision", 1);
            actor = params.name("actor");
        } catch (Params.Invalid e) {
            return invalid(e);
        }
        ContentRequests.Begin begun =
                requests.begin(id, new Call(Kind.ACTIVATE, "revision=" + revision + "\nactor=" + actor));
        if (begun != ContentRequests.Begin.STARTED) {
            return begun(begun);
        }
        CompletableFuture<StoredRevision> activation;
        try {
            activation = content.activateRevision(revision, actor);
        } catch (RuntimeException e) {
            requests.finish(id, new Failed(FetchFailure.INTERNAL, describe(e)));
            return accepted();
        }
        activation.whenComplete((stored, error) -> {
            if (error == null) {
                requests.finish(id, new Done(valid(stored.digest(), Optional.of(stored.summary()))));
                return;
            }
            Throwable cause = unwrap(error);
            if (cause instanceof ContentRefusedException refused) {
                requests.finish(id, new Done(refused(ProblemList.lines(refused.getMessage()))));
            } else if (cause instanceof IllegalArgumentException) {
                requests.finish(id, new Failed(FetchFailure.NO_SUCH_REVISION, describe(cause)));
            } else {
                requests.finish(id, new Failed(FetchFailure.STORAGE_FAILED, describe(cause)));
            }
        });
        return accepted();
    }

    /**
     * {@code haruhi:content/request}: {@code request}. {@code pending}, {@code done} with the result, {@code failed}
     * with {@code reason} and {@code message}, or {@code unknown} once the server no longer holds it.
     */
    public JsonObject request(@Nullable JsonElement param) {
        UUID id;
        try {
            id = Params.of(param).uuid("request");
        } catch (Params.Invalid e) {
            return invalid(e);
        }
        Optional<Held> held = requests.get(id);
        if (held.isEmpty()) {
            return status("unknown");
        }
        JsonObject answer = switch (held.get().state()) {
            case Running running -> status("pending");
            case Done done -> {
                JsonObject result = done.result();
                result.addProperty("status", "done");
                yield result;
            }
            case Failed failed -> {
                JsonObject result = status("failed");
                result.addProperty("reason", failed.reason());
                result.addProperty("message", failed.message());
                yield result;
            }
        };
        answer.addProperty("kind", held.get().kind().id());
        return answer;
    }

    /**
     * {@code haruhi:content/export}: {@code revision}, {@code offset} (default 0). Up to {@link #EXPORT_MAX_BYTES} UTF-8
     * bytes of the stored bundle text from {@code offset}, with the text's {@code total} length, its {@code digest},
     * and {@code next} when more follows. Offsets count UTF-16 code units.
     */
    public JsonObject export(@Nullable JsonElement param) {
        long revisionId;
        long offset;
        try {
            Params params = Params.of(param);
            revisionId = params.whole("revision", 1);
            offset = params.optionalWhole("offset", 0).orElse(0);
        } catch (Params.Invalid e) {
            return invalid(e);
        }
        StoredRevision revision = exported;
        if (revision == null || revision.id() != revisionId) {
            Optional<StoredRevision> loaded;
            try {
                loaded = read(content.storage().loadRevision(revisionId));
            } catch (Unavailable e) {
                return unavailable(e.getMessage());
            }
            if (loaded.isEmpty()) {
                return status("unknown_revision");
            }
            revision = loaded.get();
            exported = revision;
        }
        String text = revision.bundleJson();
        if (offset > text.length() || TextChunks.insidePair(text, (int) offset)) {
            return invalid("offset", "offset must be at most the text's length, " + text.length()
                    + ", and not inside a surrogate pair");
        }
        int start = (int) offset;
        int end = TextChunks.end(text, start, EXPORT_MAX_BYTES);
        JsonObject answer = ok();
        answer.addProperty("revision", revision.id());
        answer.addProperty("digest", revision.digest());
        answer.addProperty("offset", start);
        answer.addProperty("text", text.substring(start, end));
        answer.addProperty("total", text.length());
        if (end < text.length()) {
            answer.addProperty("next", end);
        }
        return answer;
    }

    /**
     * {@code haruhi:content/bindings}: {@code before} (the {@code next} cursor of the previous page), {@code limit}
     * (default 100, at most 200). Server thread.
     */
    public JsonObject bindings(@Nullable JsonElement param) {
        Optional<BindingsPage.Key> after;
        int limit;
        try {
            Params params = Params.of(param);
            after = params.string("before").map(BindingsPage.Key::parse);
            limit = (int) Math.min(BINDINGS_MAX, params.optionalWhole("limit", 1).orElse(BINDINGS_DEFAULT));
        } catch (Params.Invalid e) {
            return invalid(e);
        } catch (IllegalArgumentException e) {
            return invalid("before", "before must be the next cursor of an earlier page");
        }
        BindingService.Listing bindings = HaruhiServices.bindings().list(server);
        return BindingsPage.page(bindings.blocks(), bindings.entities(), HaruhiServices.npc().loadedNpcs(server),
                content.active().npcs().keySet(), after, limit);
    }

    /** Forgets every request and stops the fetcher. */
    @Override
    public void close() {
        requests.clear();
        if (fetcher != null) {
            fetcher.close();
        }
    }

    private JsonObject fetchRequest(@Nullable JsonElement param, Kind kind) {
        UUID id;
        String sha256;
        long size;
        String token;
        String author;
        try {
            Params params = Params.of(param);
            id = params.uuid("request");
            sha256 = params.sha256("sha256");
            size = params.whole("size", 1);
            token = params.token("token");
            author = kind == Kind.IMPORT ? params.name("author") : "";
        } catch (Params.Invalid e) {
            return invalid(e);
        }
        if (fetcher == null) {
            JsonObject answer = status("remote_disabled");
            answer.addProperty("message", "remote.enabled is false in config/" + RemoteContentConfig.FILE);
            return answer;
        }
        if (size > config.maxBytes()) {
            JsonObject answer = status("too_large");
            answer.addProperty("message", "size " + size + " is over remote.max_bytes, " + config.maxBytes());
            return answer;
        }
        String fields = "sha256=" + sha256 + "\nsize=" + size + (kind == Kind.IMPORT ? "\nauthor=" + author : "");
        ContentRequests.Begin begun = requests.begin(id, new Call(kind, fields));
        if (begun != ContentRequests.Begin.STARTED) {
            return begun(begun);
        }
        try {
            content.onServerThread(fetcher.fetch(id, token, size, sha256))
                    .thenCompose(text -> afterFetch(kind, id, author, text))
                    .whenComplete((result, error) -> requests.finish(id, error == null
                            ? new Done(result)
                            : failure(error)));
        } catch (RuntimeException e) {
            requests.finish(id, new Failed(FetchFailure.INTERNAL, describe(e)));
        }
        return accepted();
    }

    /** On the server thread: validates the fetched text, and for an import stores it. */
    private CompletableFuture<JsonObject> afterFetch(Kind kind, UUID id, String author, String text) {
        DataResult<ValidatedBundle> validated = content.validate(text);
        if (validated.error().isPresent()) {
            return CompletableFuture.completedFuture(refused(ProblemList.lines(validated.error().get().message())));
        }
        ValidatedBundle bundle = validated.result().orElseThrow();
        if (kind == Kind.VALIDATE) {
            return CompletableFuture.completedFuture(valid(bundle.digest(), Optional.empty()));
        }
        return content.store(bundle, recordedAuthor(author, id))
                .handle((stored, error) -> {
                    if (error != null) {
                        throw new CompletionException(
                                new FetchFailure(FetchFailure.STORAGE_FAILED, describe(unwrap(error))));
                    }
                    return valid(stored.digest(), Optional.of(stored.summary()));
                });
    }

    /** The author an import records: the call's {@code author} and the request id, by which the Portal finds it. */
    static String recordedAuthor(String author, UUID request) {
        return author + " (request " + request + ")";
    }

    private static Failed failure(Throwable error) {
        Throwable cause = unwrap(error);
        if (cause instanceof FetchFailure failure) {
            return new Failed(failure.reason(), failure.getMessage());
        }
        return new Failed(FetchFailure.INTERNAL, describe(cause));
    }

    private static JsonObject valid(String digest, Optional<RevisionSummary> revision) {
        JsonObject result = new JsonObject();
        result.addProperty("valid", true);
        result.addProperty("digest", digest);
        revision.ifPresent(summary -> result.add("revision", summary(summary)));
        ProblemList.add(result, List.of());
        return result;
    }

    private static JsonObject refused(List<String> problems) {
        JsonObject result = new JsonObject();
        result.addProperty("valid", false);
        ProblemList.add(result, problems);
        return result;
    }

    /** A revision summary as the answers carry it. Times are ISO-8601 instants. */
    static JsonObject summary(RevisionSummary revision) {
        JsonObject json = new JsonObject();
        json.addProperty("id", revision.id());
        json.addProperty("digest", revision.digest());
        json.addProperty("author", revision.author());
        json.addProperty("created_at", revision.createdAt().toString());
        revision.activatedAt().ifPresent(at -> json.addProperty("activated_at", at.toString()));
        revision.activatedBy().ifPresent(by -> json.addProperty("activated_by", by));
        json.addProperty("active", revision.active());
        return json;
    }

    /** Waits for a storage read, up to {@link #READ_TIMEOUT}. */
    private static <T> T read(CompletableFuture<T> future) throws Unavailable {
        try {
            return future.get(READ_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new Unavailable("Storage did not answer within " + READ_TIMEOUT.toSeconds() + " seconds");
        } catch (ExecutionException e) {
            throw new Unavailable("Storage failed: " + describe(unwrap(e.getCause())));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Unavailable("Interrupted while reading storage");
        }
    }

    /** A storage read that did not answer. */
    private static final class Unavailable extends Exception {
        Unavailable(String message) {
            super(message, null, false, false);
        }
    }

    private static Throwable unwrap(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static String describe(Throwable error) {
        return error.getMessage() == null ? error.getClass().getName() : error.getMessage();
    }

    private static JsonObject begun(ContentRequests.Begin begun) {
        return switch (begun) {
            case STARTED, HELD -> accepted();
            case CONFLICT -> {
                JsonObject answer = status("conflict");
                answer.addProperty("message", "This request id is held for another call");
                yield answer;
            }
            case BUSY -> {
                JsonObject answer = status("busy");
                answer.addProperty("message", "Too many requests are running or held; try again later");
                yield answer;
            }
        };
    }

    private static JsonObject accepted() {
        return status("accepted");
    }

    private static JsonObject ok() {
        return status("ok");
    }

    private static JsonObject unavailable(String message) {
        JsonObject answer = status("unavailable");
        answer.addProperty("message", message);
        return answer;
    }

    private static JsonObject invalid(Params.Invalid invalid) {
        return invalid(invalid.field(), invalid.getMessage());
    }

    private static JsonObject invalid(String field, String message) {
        JsonObject answer = status("invalid");
        answer.addProperty("field", field);
        answer.addProperty("message", message);
        return answer;
    }

    private static JsonObject status(String status) {
        JsonObject answer = new JsonObject();
        answer.addProperty("status", status);
        return answer;
    }
}
