package town.lampas.haruhi.content;

import com.google.gson.JsonElement;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.thread.BlockableEventLoop;
import town.lampas.haruhi.Haruhi;
import town.lampas.haruhi.api.HaruhiServices;
import town.lampas.haruhi.content.model.ContentBundle;
import town.lampas.haruhi.storage.HaruhiStorage;
import town.lampas.haruhi.storage.RevisionSummary;
import town.lampas.haruhi.storage.StoredRevision;

/**
 * The content service and the revision operations behind {@code /haruhi content} and the startup load. Activation and
 * listener calls run on the server thread. The revision methods need a running server and return futures that complete
 * on the server thread.
 *
 * <p>The startup load, revision activations and rollbacks run one at a time, in the order they were requested, so the
 * revision storage marks active and the bundle active in memory agree once they finish. Each activation is stamped
 * with a time later than the one before it, even when the clock has not moved, so rollback can always tell which of
 * two activations came last.
 */
public final class ContentServiceImpl implements ContentService {
    /** The precision of an activation time in storage: PostgreSQL timestamptz keeps microseconds. */
    private static final ChronoUnit STAMP_UNIT = ChronoUnit.MICROS;

    private final Supplier<HaruhiStorage> storage;
    private final Clock clock;
    private final AtomicReference<Instant> lastStamp = new AtomicReference<>(Instant.MIN);
    private volatile ContentBundle active = ContentBundle.EMPTY;
    private volatile OptionalLong revisionId = OptionalLong.empty();
    private volatile Optional<String> digest = Optional.empty();
    private volatile boolean startupComplete;
    private final List<Consumer<ContentBundle>> listeners = new CopyOnWriteArrayList<>();
    private final ActivationQueue activations = new ActivationQueue();
    private volatile Attached attached;

    /** The running server: the thread content changes on, and the ops bundle text parses with. */
    private record Attached(BlockableEventLoop<?> thread, Supplier<DynamicOps<JsonElement>> ops) {}

    ContentServiceImpl() {
        this(HaruhiServices::storage, Clock.systemUTC());
    }

    /** For tests. {@code storage} is read at each use, as {@link HaruhiServices#storage()} is. */
    ContentServiceImpl(Supplier<HaruhiStorage> storage, Clock clock) {
        this.storage = Objects.requireNonNull(storage);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public ContentBundle active() {
        return active;
    }

    @Override
    public OptionalLong activeRevisionId() {
        return revisionId;
    }

    /** The digest of the active stored revision; empty before any is active and after {@link #activateUnstored}. */
    public Optional<String> activeDigest() {
        return digest;
    }

    @Override
    public boolean isStartupComplete() {
        return startupComplete;
    }

    @Override
    public void addActivationListener(Consumer<ContentBundle> listener) {
        listeners.add(Objects.requireNonNull(listener));
    }

    @Override
    public DataResult<ContentBundle> parseAndValidate(String bundleJson) {
        return validate(bundleJson).map(ValidatedBundle::bundle);
    }

    @Override
    public void activateUnstored(ContentBundle bundle) {
        activate(Objects.requireNonNull(bundle), OptionalLong.empty(), Optional.empty());
    }

    /**
     * Parses and validates bundle JSON, keeping the canonical form and digest a revision would store. Parses with the
     * server's registries when a server is running, and with plain JSON otherwise.
     */
    public DataResult<ValidatedBundle> validate(String bundleJson) {
        return BundleValidator.validate(bundleJson, ops());
    }

    /** Stores a validated bundle as a revision. Does not activate it. */
    public CompletableFuture<StoredRevision> store(ValidatedBundle bundle, String author) {
        BlockableEventLoop<?> thread = requireServer();
        return ServerThread.hop(thread, storage()
                .storeRevision(bundle.digest(), bundle.canonicalJson(), author, clock.instant()));
    }

    /**
     * Loads, validates and activates a stored revision, and records {@code actor} as the activator. A revision that no
     * longer validates is refused and stays inactive. Starts once every activation, rollback and startup load
     * requested before it has finished.
     */
    public CompletableFuture<StoredRevision> activateRevision(long id, String actor) {
        BlockableEventLoop<?> thread = requireServer();
        return activations.submit(() -> activateInStep(thread, id, actor));
    }

    /**
     * Activates the stored revision with the latest {@code activatedAt} other than the active one. Fails when no other
     * revision has ever been activated. The lookup runs after the activations requested before it.
     */
    public CompletableFuture<StoredRevision> rollback(String actor) {
        BlockableEventLoop<?> thread = requireServer();
        return activations.submit(() -> ServerThread.hop(thread, storage().listRevisions(Integer.MAX_VALUE))
                .thenCompose(revisions -> {
                    Optional<RevisionSummary> target = revisions.stream()
                            .filter(revision -> !revision.active())
                            .filter(revision -> revision.activatedAt().isPresent())
                            .max(Comparator.<RevisionSummary, Instant>comparing(
                                    revision -> revision.activatedAt().orElseThrow()));
                    if (target.isEmpty()) {
                        return CompletableFuture.<StoredRevision>failedFuture(
                                new IllegalStateException("No earlier content revision has been activated."));
                    }
                    return activateInStep(thread, target.get().id(), actor);
                }));
    }

    /** Server lifecycle: the server that content parses against and activates on. */
    void attach(MinecraftServer server) {
        attach(server, () -> RegistryOps.create(JsonOps.INSTANCE, server.registryAccess()));
    }

    /** For tests. {@code thread} stands in for the server, and bundle text parses with {@code ops}. */
    void attach(BlockableEventLoop<?> thread, Supplier<DynamicOps<JsonElement>> ops) {
        this.attached = new Attached(Objects.requireNonNull(thread), Objects.requireNonNull(ops));
    }

    /**
     * Server lifecycle: forgets the stopped server and the content that was active on it. A singleplayer session can
     * start another server in the same JVM, and that server starts from EMPTY until its own startup load finishes.
     */
    void detach() {
        this.attached = null;
        this.active = ContentBundle.EMPTY;
        this.revisionId = OptionalLong.empty();
        this.digest = Optional.empty();
        this.startupComplete = false;
        activations.reset();
    }

    /**
     * Runs the startup load as one activation step, so an activation requested while it runs waits for it, and then
     * marks the startup complete however the load ended. Inside {@code load}, activate a stored revision with
     * {@link #activateInStep}, never {@link #activateRevision}: the queue is busy with the load itself.
     */
    CompletableFuture<Void> startup(Supplier<CompletableFuture<Void>> load) {
        return activations.submit(load).whenComplete((ignored, error) -> {
            if (error != null) {
                Haruhi.LOGGER.error("The startup content load failed", error);
            }
            startupFinished();
        });
    }

    /** Called once the startup load has activated, imported or refused content. */
    void startupFinished() {
        this.startupComplete = true;
    }

    /** The storage revisions are read from and written to. */
    HaruhiStorage storage() {
        return storage.get();
    }

    /** {@code source}, completing on the server thread. */
    <T> CompletableFuture<T> onServerThread(CompletableFuture<T> source) {
        return ServerThread.hop(requireServer(), source);
    }

    /**
     * Makes {@code bundle} active and tells every listener, in registration order. A listener that throws is logged and
     * the rest still run.
     */
    void activate(ContentBundle bundle, OptionalLong id, Optional<String> activeDigest) {
        this.active = bundle;
        this.revisionId = id;
        this.digest = activeDigest;
        for (Consumer<ContentBundle> listener : listeners) {
            try {
                listener.accept(bundle);
            } catch (RuntimeException e) {
                Haruhi.LOGGER.error("A content activation listener failed", e);
            }
        }
    }

    /** One activation, for a step the queue is already running. */
    CompletableFuture<StoredRevision> activateInStep(long id, String actor) {
        return activateInStep(requireServer(), id, actor);
    }

    private CompletableFuture<StoredRevision> activateInStep(BlockableEventLoop<?> thread, long id, String actor) {
        return ServerThread.hop(thread, storage().loadRevision(id)).thenCompose(found -> {
            if (found.isEmpty()) {
                return CompletableFuture.<StoredRevision>failedFuture(
                        new IllegalArgumentException("There is no content revision " + id + "."));
            }
            StoredRevision revision = found.get();
            DataResult<ValidatedBundle> validated = validate(revision.bundleJson());
            if (validated.error().isPresent()) {
                return CompletableFuture.<StoredRevision>failedFuture(
                        new IllegalStateException(validated.error().get().message()));
            }
            ContentBundle bundle = validated.result().orElseThrow().bundle();
            Instant stamp = nextActivationStamp();
            return ServerThread.hop(thread, storage().activateRevision(id, actor, stamp))
                    .thenApply(ignored -> {
                        activate(bundle, OptionalLong.of(id), Optional.of(revision.digest()));
                        return new StoredRevision(revision.id(), revision.digest(), revision.bundleJson(),
                                revision.author(), revision.createdAt(), Optional.of(stamp), true);
                    });
        });
    }

    /**
     * The clock time, to the microsecond storage keeps, or one microsecond after the previous stamp when the clock has
     * not passed it. Without that, two activations in the same clock tick share a time.
     */
    private Instant nextActivationStamp() {
        Instant now = clock.instant().truncatedTo(STAMP_UNIT);
        return lastStamp.updateAndGet(last -> now.isAfter(last) ? now : last.plus(1, STAMP_UNIT));
    }

    private BlockableEventLoop<?> requireServer() {
        Attached current = attached;
        if (current == null) {
            throw new IllegalStateException("No server is running");
        }
        return current.thread();
    }

    private DynamicOps<JsonElement> ops() {
        Attached current = attached;
        if (current == null) {
            return JsonOps.INSTANCE;
        }
        return current.ops().get();
    }
}
