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
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Util;
import town.lampas.haruhi.Haruhi;
import town.lampas.haruhi.api.HaruhiServices;
import town.lampas.haruhi.storage.StoredRevision;

/**
 * Loads the content on server start. The active stored revision wins. With none, {@code config/haruhi/bundle.json} is
 * imported and activated if it exists, and the built-in seed bundle otherwise. A bundle that fails validation is logged
 * with every problem, and the server runs with {@link town.lampas.haruhi.content.model.ContentBundle#EMPTY}.
 */
final class ContentStartup {
    private static final String SEED_RESOURCE = "/haruhi/seed_bundle.json";

    private ContentStartup() {}

    static void run(MinecraftServer server, ContentServiceImpl service) {
        ServerThread.hop(server, HaruhiServices.storage().activeRevision()).whenComplete((active, error) -> {
            if (error != null) {
                Haruhi.LOGGER.error("Could not load the active content revision, so no content is active", error);
            } else if (active.isPresent()) {
                activateStored(service, active.get());
            } else {
                importStartupBundle(server, service);
            }
        });
    }

    private static void activateStored(ContentServiceImpl service, StoredRevision revision) {
        DataResult<ValidatedBundle> validated = service.validate(revision.bundleJson());
        if (validated.error().isPresent()) {
            Haruhi.LOGGER.error("Active content revision {} no longer validates, so no content is active:\n{}",
                    revision.id(), validated.error().get().message());
            return;
        }
        service.activate(validated.result().orElseThrow().bundle(),
                OptionalLong.of(revision.id()), Optional.of(revision.digest()));
        Haruhi.LOGGER.info("Activated content revision {}", revision.id());
    }

    private static void importStartupBundle(MinecraftServer server, ContentServiceImpl service) {
        Path file = BundleFiles.startupFile(FabricLoader.getInstance().getConfigDir());
        ServerThread.hop(server, BundleFiles.readIfRegularFile(file)).whenComplete((text, error) -> {
            if (error != null) {
                Haruhi.LOGGER.error("Could not read {}, so no content is active", file, error);
            } else if (text.isPresent()) {
                importAndActivate(service, text.get(), "startup:config/haruhi/bundle.json");
            } else {
                importSeed(server, service);
            }
        });
    }

    private static void importSeed(MinecraftServer server, ContentServiceImpl service) {
        ServerThread.hop(server, readSeed()).whenComplete((text, error) -> {
            if (error != null) {
                Haruhi.LOGGER.error("Could not read the seed content bundle, so no content is active", error);
            } else {
                importAndActivate(service, text, "startup:seed");
            }
        });
    }

    private static void importAndActivate(ContentServiceImpl service, String json, String author) {
        DataResult<ValidatedBundle> validated = service.validate(json);
        if (validated.error().isPresent()) {
            Haruhi.LOGGER.error("Content bundle from {} does not validate, so no content is active:\n{}",
                    author, validated.error().get().message());
            return;
        }
        service.store(validated.result().orElseThrow(), author)
                .thenCompose(stored -> service.activateRevision(stored.id(), author))
                .whenComplete((revision, failure) -> {
                    if (failure != null) {
                        Haruhi.LOGGER.error("Could not store and activate the content bundle from {}", author, failure);
                    } else {
                        Haruhi.LOGGER.info("Activated content revision {} from {}", revision.id(), author);
                    }
                });
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
