package town.lampas.haruhi.client.npc.gecko;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs client test code against a working GeckoLib. GeckoLib's static setup registers a data component, which a unit-test
 * JVM cannot do once another test has bootstrapped Minecraft and frozen the registries. So the test code runs in a class
 * loader of its own, with fresh copies of Minecraft, GeckoLib and Haruhi from the test classpath, where
 * {@link GeckoLibSandboxClient#start} bootstraps Minecraft, sets GeckoLib up and has it bake the resource packs.
 *
 * <p>A test hands over a static, no-argument method of its own class by name. The method runs in the sandbox, so it sees
 * the sandbox copies of every class, and it reaches GeckoLib types reflectively because GeckoLib is not on the test
 * compile classpath. Its assertion failures come back unchanged.
 */
final class GeckoLibSandbox {
    private static URLClassLoader loader;

    private GeckoLibSandbox() {}

    /** Runs the static method {@code method} of {@code owner} in the sandbox, starting the sandbox the first time. */
    static synchronized void run(Class<?> owner, String method) throws Throwable {
        ClassLoader sandbox = sandbox();
        call(sandbox, Class.forName(owner.getName(), true, sandbox).getDeclaredMethod(method));
    }

    private static ClassLoader sandbox() throws Throwable {
        if (loader == null) {
            URLClassLoader created = new URLClassLoader(testClassPath(), ClassLoader.getPlatformClassLoader());
            call(created, Class.forName(GeckoLibSandboxClient.class.getName(), true, created).getDeclaredMethod("start"));
            loader = created;
        }
        return loader;
    }

    /** Calls a static method with the sandbox as the context class loader, which GeckoLib's service lookup reads. */
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

    private static URL[] testClassPath() throws MalformedURLException {
        List<URL> urls = new ArrayList<>();
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            if (!entry.isBlank()) {
                urls.add(Path.of(entry).toUri().toURL());
            }
        }
        return urls.toArray(URL[]::new);
    }
}
