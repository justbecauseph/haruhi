package town.lampas.haruhi.client.npc.gecko;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * What {@link GeckoLibSandbox} runs inside its class loader to get GeckoLib working. Never call it from a test directly:
 * it changes static state that the rest of the test JVM shares.
 */
final class GeckoLibSandboxClient {
    /** One of Haruhi's own GeckoLib files: the default humanoid model. Its pack root is 5 directories up. */
    private static final String MOD_ASSET = "/assets/haruhi/geckolib/models/humanoid.geo.json";
    /** The test fixtures in {@code src/test/resources}: GeckoLib files that bake, fail to bake, or have odd names. */
    private static final String FIXTURE_PACK = "/town/lampas/haruhi/client/npc/gecko/pack";

    private static ResourceManager resources;

    private GeckoLibSandboxClient() {}

    /**
     * Bootstraps Minecraft, sets GeckoLib up, and has GeckoLib bake the mod's assets and the fixture pack as a client
     * resource reload does.
     */
    static void start() throws ReflectiveOperationException, URISyntaxException {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        registerGeckoLibDataComponents();
        standInForTheClient();

        PackResources mod = pack("haruhi", root(MOD_ASSET, 5));
        PackResources fixtures = pack("fixtures", root(FIXTURE_PACK, 0));
        resources = new MultiPackResourceManager(PackType.CLIENT_RESOURCES, List.of(mod, fixtures));
        PreparableReloadListener geckoLib = (PreparableReloadListener)
                Class.forName("com.geckolib.cache.GeckoLibResources").getConstructor().newInstance();
        PreparableReloadListener.SharedState state = new PreparableReloadListener.SharedState(resources);
        geckoLib.prepareSharedState(state);
        geckoLib.reload(state, Runnable::run, new PreparableReloadListener.PreparationBarrier() {
            @Override
            public <T> CompletableFuture<T> wait(T value) {
                return CompletableFuture.completedFuture(value);
            }
        }, Runnable::run).join();
    }

    /** The resource packs GeckoLib baked, for checking which files they hold. */
    static ResourceManager resources() {
        return resources;
    }

    /**
     * GeckoLib registers a data component when its constants load. In the game that happens during mod setup, before
     * Minecraft freezes its registries; here the bootstrap has frozen them, so the data component registry is opened for
     * that one registration.
     */
    private static void registerGeckoLibDataComponents() throws ReflectiveOperationException {
        MappedRegistry<?> components = (MappedRegistry<?>) BuiltInRegistries.DATA_COMPONENT_TYPE;
        Field frozen = MappedRegistry.class.getDeclaredField("frozen");
        frozen.setAccessible(true);
        frozen.setBoolean(components, false);
        try {
            Class.forName("com.geckolib.GeckoLibConstants", true, GeckoLibSandboxClient.class.getClassLoader());
        } finally {
            frozen.setBoolean(components, true);
        }
    }

    /**
     * GeckoLib stamps a triggered animation with the current client tick, which it reads from {@link Minecraft}. A client
     * instance without a level makes it read the GLFW clock instead, which needs no window. The instance is allocated
     * without running its constructor, so no other client code runs.
     */
    private static void standInForTheClient() throws ReflectiveOperationException {
        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        Field theUnsafe = unsafeType.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Method allocate = unsafeType.getMethod("allocateInstance", Class.class);
        Object client = allocate.invoke(theUnsafe.get(null), Minecraft.class);
        Field instance = Minecraft.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, client);
    }

    private static PackResources pack(String id, Path root) {
        return new PathPackResources(
                new PackLocationInfo(id, Component.literal(id), PackSource.DEFAULT, Optional.empty()), root);
    }

    /** The directory {@code levels} above the classpath resource {@code name}. */
    private static Path root(String name, int levels) throws URISyntaxException {
        URL url = GeckoLibSandboxClient.class.getResource(name);
        if (url == null) {
            throw new IllegalStateException("missing test resource " + name);
        }
        Path path = Path.of(url.toURI());
        for (int i = 0; i < levels; i++) {
            path = path.getParent();
        }
        return path;
    }
}
