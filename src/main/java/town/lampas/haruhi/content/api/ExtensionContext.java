package town.lampas.haruhi.content.api;

import com.google.gson.JsonElement;
import com.mojang.serialization.DynamicOps;
import java.util.Set;
import net.minecraft.resources.Identifier;
import town.lampas.haruhi.content.model.ContentBundle;

/** What an extension's parser can see while one bundle is validated. */
public interface ExtensionContext {

    /** The id of the extension being parsed. */
    Identifier id();

    /**
     * The bundle being validated, without extensions: its flag namespaces, and the NPC profiles and dialogue graphs
     * that parsed. An entry that failed to parse is missing, and its problems are already recorded.
     */
    ContentBundle bundle();

    /**
     * Every id the bundle declares under {@code npcs}, including profiles that failed to parse. Check references
     * against these, so a broken profile does not also make every reference to it look missing.
     */
    Set<Identifier> npcIds();

    /** Every id the bundle declares under {@code dialogues}, including graphs that failed to parse. */
    Set<Identifier> graphIds();

    /**
     * The ops bundle text parses with. On a running server they are {@code RegistryOps} over the server's registry
     * access, so codecs that need registries (items, criterion triggers, predicates) work. In unit tests they may be
     * plain {@code JsonOps}.
     */
    DynamicOps<JsonElement> ops();

    /** Records {@code where: message}. Name the place inside the section in {@code where}, for example an entry's id. */
    void problem(String where, String message);

    /** Records a problem located at the extension itself: {@code extension <id>: message}. */
    default void problem(String message) {
        problem("extension " + id(), message);
    }
}
