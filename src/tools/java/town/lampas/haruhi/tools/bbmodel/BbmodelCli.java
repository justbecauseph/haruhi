package town.lampas.haruhi.tools.bbmodel;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;

/**
 * {@code .\gradlew.bat bbmodel --args='<in.bbmodel> <outputRoot> <namespace> <name>'}: converts a Blockbench project into
 * {@code <outputRoot>/assets/<namespace>/geckolib/models/<name>.geo.json}, {@code .../geckolib/animations/<name>.animation.json}
 * and {@code .../textures/entity/<name>.png}, then prints what it wrote, what it skipped, and the NPC profile fields.
 * Relative paths are resolved against the project directory, where Gradle runs the task.
 */
public final class BbmodelCli {
    private BbmodelCli() {}

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length != 4) {
            err.println("usage: bbmodel <in.bbmodel> <outputRoot> <namespace> <name>");
            err.println("  e.g. .\\gradlew.bat bbmodel --args='model.bbmodel run/resourcepacks/test haruhi_test aco'");
            return 2;
        }
        Path input = Path.of(args[0]);
        Path root = Path.of(args[1]);
        String namespace = args[2];
        String name = args[3];
        try {
            BbmodelConverter.Conversion conversion = BbmodelConverter.convert(input, name);
            BbmodelConverter.Output output = BbmodelConverter.write(conversion, root, namespace, name);
            ConversionReport report = conversion.report();
            out.println("Converted " + input + " -> " + namespace + ":" + name);
            out.println("  wrote:       " + output.geometry());
            if (conversion.animations() != null) {
                out.println("               " + output.animations());
            }
            out.println("               " + output.texture());
            out.print(report.summary());
            out.println("Profile fields:");
            out.println("  \"model\": \"" + namespace + ":" + name + "\",");
            if (conversion.animations() != null) {
                out.println("  \"animations\": \"" + namespace + ":" + name + "\",");
            }
            out.println("  \"texture\": \"" + namespace + ":textures/entity/" + name + ".png\"");
            return 0;
        } catch (IOException | IllegalArgumentException e) {
            err.println("bbmodel: " + e.getMessage());
            return 1;
        }
    }
}
