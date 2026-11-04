package town.lampas.haruhi.tools.bbmodel;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.minecraft.SharedConstants;
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

/**
 * Has GeckoLib 5.5.5 load a resource pack of converter output, as a client resource reload does, so a test can check
 * what GeckoLib baked from it. It works like the NPC client's GeckoLibSandbox: GeckoLib's static setup registers a data
 * component, which the shared test JVM cannot do once Minecraft is bootstrapped, so everything runs in a class loader of
 * its own with fresh copies of Minecraft and GeckoLib from the test classpath. GeckoLib is not on the test compile
 * classpath, so the checks reach its types reflectively.
 */
final class GeckoLibBakeSandbox {
    /** The system property that names the resource pack directory to bake. */
    static final String PACK_PROPERTY = "haruhi.test.bbmodel.pack";

    private static URLClassLoader loader;

    private GeckoLibBakeSandbox() {}

    /** Starts the sandbox on the pack named by {@link #PACK_PROPERTY}, once per test JVM. */
    static synchronized void start() throws Throwable {
        if (loader == null) {
            List<URL> urls = new ArrayList<>();
            for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
                if (!entry.isBlank()) {
                    urls.add(toUrl(entry));
                }
            }
            URLClassLoader created = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
            call(created, Class.forName(GeckoLibBakeSandbox.class.getName(), true, created).getDeclaredMethod("bake"));
            loader = created;
        }
    }

    /** Runs the static, no-argument method {@code method} of {@code owner} inside the sandbox. */
    static synchronized void run(Class<?> owner, String method) throws Throwable {
        start();
        call(loader, Class.forName(owner.getName(), true, loader).getDeclaredMethod(method));
    }

    private static URL toUrl(String entry) {
        try {
            return Path.of(entry).toUri().toURL();
        } catch (MalformedURLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void call(ClassLoader sandbox, Method method) throws Throwable {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(sandbox);
        try {
            method.setAccessible(true);
            method.invoke(null);
        } catch (InvocationTargetException failed) {
            throw failed.getCause();
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    /** Inside the sandbox: bootstraps Minecraft, sets GeckoLib up and has it bake the pack. */
    private static void bake() throws ReflectiveOperationException {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        MappedRegistry<?> components = (MappedRegistry<?>) BuiltInRegistries.DATA_COMPONENT_TYPE;
        Field frozen = MappedRegistry.class.getDeclaredField("frozen");
        frozen.setAccessible(true);
        frozen.setBoolean(components, false);
        try {
            Class.forName("com.geckolib.GeckoLibConstants", true, GeckoLibBakeSandbox.class.getClassLoader());
        } finally {
            frozen.setBoolean(components, true);
        }
        Path root = Path.of(System.getProperty(PACK_PROPERTY));
        PackResources pack = new PathPackResources(
                new PackLocationInfo("converted", Component.literal("converted"), PackSource.DEFAULT, Optional.empty()),
                root);
        MultiPackResourceManager resources = new MultiPackResourceManager(PackType.CLIENT_RESOURCES, List.of(pack));
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
}
