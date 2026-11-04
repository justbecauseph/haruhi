package town.lampas.haruhi.content;

import com.mojang.serialization.DataResult;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Util;
import town.lampas.haruhi.Haruhi;
import town.lampas.haruhi.api.HaruhiServices;
import town.lampas.haruhi.storage.StoredRevision;

/**
 * Loads the content on server start. The active stored revision wins. With none, {@code config/haruhi/bundle.json} is
 * imported and activated if it exists, and the built-in seed bundle otherwise. The chosen bundle is the only one tried:
 * when it fails validation, it is logged with every problem and the server runs with
 * {@link town.lampas.haruhi.content.model.ContentBundle#EMPTY}. {@link ContentService#isStartupComplete()} turns true
 * once the load has finished, however it ended.
 */
final class ContentStartup {
    static final String CONFIG_AUTHOR = "startup:config/haruhi/bundle.json";
    static final String SEED_AUTHOR = "startup:seed";
    private static final String SEED_RESOURCE = "/haruhi/seed_bundle.json";

    private ContentStartup() {}

    /** What the startup activates. */
    sealed interface Choice permits Choice.Stored, Choice.Imported, Choice.Refused {

        /** The active stored revision, which validated. It is activated as it is. */
        record Stored(StoredRevision revision, ValidatedBundle validated) implements Choice {}

        /** Bundle text that validated. It is stored under {@code author}, then activated. */
        record Imported(ValidatedBundle bundle, String author) implements Choice {}

        /** Nothing activates. {@code message} is logged, with {@code cause} when a read failed. */
        record Refused(String message, Optional<Throwable> cause) implements Choice {}
    }

    static CompletableFuture<Void> run(MinecraftServer server, ContentServiceImpl service) {
        Path file = BundleFiles.startupFile(FabricLoader.getInstance().getConfigDir());
        // The three reads start together. choose awaits only the ones the decision needs.
        return choose(
                        file,
                        ServerThread.hop(server, HaruhiServices.storage().activeRevision()),
                        ServerThread.hop(server, BundleFiles.readIfRegularFile(file)),
                        ServerThread.hop(server, readSeed()),
                        service::validate)
                .thenCompose(choice -> activate(service, choice))
                .whenComplete((ignored, error) -> service.startupFinished());
    }

    /**
     * Decides what the startup activates from the three reads. A failed read of a source that is not needed does not
     * matter: a failed config read is ignored while a stored revision is active.
     */
    static CompletableFuture<Choice> choose(
            Path configFile,
            CompletableFuture<Optional<StoredRevision>> active,
            CompletableFuture<Optional<String>> config,
            CompletableFuture<String> seed,
            Function<String, DataResult<ValidatedBundle>> validate) {
        return active.handle((found, error) -> {
            if (error != null) {
                return done(refusal("Could not load the active content revision, so no content is active", error));
            }
            if (found.isPresent()) {
                return done(stored(found.get(), validate));
            }
            return fromConfig(configFile, config, seed, validate);
        }).thenCompose(choice -> choice);
    }

    private static CompletableFuture<Choice> fromConfig(
            Path configFile,
            CompletableFuture<Optional<String>> config,
            CompletableFuture<String> seed,
            Function<String, DataResult<ValidatedBundle>> validate) {
        return config.handle((text, error) -> {
            if (error != null) {
                return done(refusal("Could not read " + configFile + ", so no content is active", error));
            }
            if (text.isPresent()) {
                return done(imported(text.get(), CONFIG_AUTHOR, validate));
            }
            return fromSeed(seed, validate);
        }).thenCompose(choice -> choice);
    }

    private static CompletableFuture<Choice> fromSeed(
            CompletableFuture<String> seed, Function<String, DataResult<ValidatedBundle>> validate) {
        return seed.handle((text, error) -> error != null
                        ? done(refusal("Could not read the seed content bundle, so no content is active", error))
                        : done(imported(text, SEED_AUTHOR, validate)))
                .thenCompose(choice -> choice);
    }

    private static Choice stored(StoredRevision revision, Function<String, DataResult<ValidatedBundle>> validate) {
        DataResult<ValidatedBundle> validated = validate.apply(revision.bundleJson());
        if (validated.error().isPresent()) {
            return refusal("Active content revision " + revision.id()
                    + " no longer validates, so no content is active:\n" + validated.error().get().message());
        }
        return new Choice.Stored(revision, validated.result().orElseThrow());
    }

    private static Choice imported(String json, String author, Function<String, DataResult<ValidatedBundle>> validate) {
        DataResult<ValidatedBundle> validated = validate.apply(json);
        if (validated.error().isPresent()) {
            return refusal("Content bundle from " + author + " does not validate, so no content is active:\n"
                    + validated.error().get().message());
        }
        return new Choice.Imported(validated.result().orElseThrow(), author);
    }

    private static Choice refusal(String message) {
        return new Choice.Refused(message, Optional.empty());
    }

    private static Choice refusal(String message, Throwable cause) {
        return new Choice.Refused(message, Optional.of(cause));
    }

    private static CompletableFuture<Choice> done(Choice choice) {
        return CompletableFuture.completedFuture(choice);
    }

    private static CompletableFuture<Void> activate(ContentServiceImpl service, Choice choice) {
        return switch (choice) {
            case Choice.Stored stored -> {
                StoredRevision revision = stored.revision();
                service.activate(stored.validated().bundle(), OptionalLong.of(revision.id()),
                        Optional.of(revision.digest()));
                Haruhi.LOGGER.info("Activated content revision {}", revision.id());
                yield CompletableFuture.completedFuture(null);
            }
            case Choice.Imported imported -> service.store(imported.bundle(), imported.author())
                    .thenCompose(stored -> service.activateRevision(stored.id(), imported.author()))
                    .<Void>handle((revision, failure) -> {
                        if (failure != null) {
                            Haruhi.LOGGER.error("Could not store and activate the content bundle from {}",
                                    imported.author(), failure);
                        } else {
                            Haruhi.LOGGER.info("Activated content revision {} from {}", revision.id(),
                                    imported.author());
                        }
                        return null;
                    });
            case Choice.Refused refused -> {
                if (refused.cause().isPresent()) {
                    Haruhi.LOGGER.error(refused.message(), refused.cause().get());
                } else {
                    Haruhi.LOGGER.error(refused.message());
                }
                yield CompletableFuture.completedFuture(null);
            }
        };
    }

    private static CompletableFuture<String> readSeed() {
        return CompletableFuture.supplyAsync(() -> {
            try (InputStream in = ContentStartup.class.getResourceAsStream(SEED_RESOURCE)) {
                if (in == null) {
                    throw new IllegalStateException("The jar has no " + SEED_RESOURCE);
                }
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, Util.ioPool());
    }
}
