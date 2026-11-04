package town.lampas.haruhi.content;

import com.google.gson.JsonElement;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import town.lampas.haruhi.Haruhi;
import town.lampas.haruhi.api.HaruhiServices;
import town.lampas.haruhi.content.model.ContentBundle;
import town.lampas.haruhi.storage.RevisionSummary;
import town.lampas.haruhi.storage.StoredRevision;

/**
 * The content service and the revision operations behind {@code /haruhi content} and the startup load. Activation and
 * listener calls run on the server thread. The revision methods need a running server and return futures that complete
 * on the server thread. Revision activations and rollbacks run one at a time, in the order they were requested.
 */
public final class ContentServiceImpl implements ContentService {
    private volatile ContentBundle active = ContentBundle.EMPTY;
    private volatile OptionalLong revisionId = OptionalLong.empty();
    private volatile Optional<String> digest = Optional.empty();
    private volatile boolean startupComplete;
    private final List<Consumer<ContentBundle>> listeners = new CopyOnWriteArrayList<>();
    private final ActivationQueue activations = new ActivationQueue();
    private volatile MinecraftServer server;

    ContentServiceImpl() {}

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
        MinecraftServer current = requireServer();
        return ServerThread.hop(current, HaruhiServices.storage()
                .storeRevision(bundle.digest(), bundle.canonicalJson(), author, Instant.now()));
    }

    /**
     * Loads, validates and activates a stored revision, and records {@code actor} as the activator. A revision that no
     * longer validates is refused and stays inactive.
     */
    public CompletableFuture<StoredRevision> activateRevision(long id, String actor) {
        MinecraftServer current = requireServer();
        return activations.submit(() -> activateNow(current, id, actor));
    }

    /**
     * Activates the stored revision with the latest {@code activatedAt} other than the active one. Fails when no other
     * revision has ever been activated. The lookup runs after the activations requested before it.
     */
    public CompletableFuture<StoredRevision> rollback(String actor) {
        MinecraftServer current = requireServer();
        return activations.submit(() -> ServerThread.hop(current,
                        HaruhiServices.storage().listRevisions(Integer.MAX_VALUE))
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
                    return activateNow(current, target.get().id(), actor);
                }));
    }

    /** Server lifecycle: the server that content parses against and activates on. */
    void attach(MinecraftServer server) {
        this.server = server;
    }

    /**
     * Server lifecycle: forgets the stopped server and the content that was active on it. A singleplayer session can
     * start another server in the same JVM, and that server starts from EMPTY until its own startup load finishes.
     */
    void detach() {
        this.server = null;
        this.active = ContentBundle.EMPTY;
        this.revisionId = OptionalLong.empty();
        this.digest = Optional.empty();
        this.startupComplete = false;
        activations.reset();
    }

    /** Called once the startup load has activated, imported or refused content. */
    void startupFinished() {
        this.startupComplete = true;
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

    /** One activation, without the queue. Callers go through {@link #activations}. */
    private CompletableFuture<StoredRevision> activateNow(MinecraftServer current, long id, String actor) {
        return ServerThread.hop(current, HaruhiServices.storage().loadRevision(id)).thenCompose(found -> {
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
            Instant now = Instant.now();
            return ServerThread.hop(current, HaruhiServices.storage().activateRevision(id, actor, now))
                    .thenApply(ignored -> {
                        activate(bundle, OptionalLong.of(id), Optional.of(revision.digest()));
                        return new StoredRevision(revision.id(), revision.digest(), revision.bundleJson(),
                                revision.author(), revision.createdAt(), Optional.of(now), true);
                    });
        });
    }

    private MinecraftServer requireServer() {
        MinecraftServer current = server;
        if (current == null) {
            throw new IllegalStateException("No server is running");
        }
        return current;
    }

    private DynamicOps<JsonElement> ops() {
        MinecraftServer current = server;
        if (current == null) {
            return JsonOps.INSTANCE;
        }
        return RegistryOps.create(JsonOps.INSTANCE, current.registryAccess());
    }
}
