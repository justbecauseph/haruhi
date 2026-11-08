package town.lampas.haruhi.api;

import java.util.Objects;
import town.lampas.haruhi.binding.BindingService;
import town.lampas.haruhi.content.ContentService;
import town.lampas.haruhi.dialogue.api.DialogueService;
import town.lampas.haruhi.npc.NpcControl;
import town.lampas.haruhi.storage.HaruhiStorage;

/**
 * The cross-module service seams. Each module sets its own service during {@code init()} and reads the others'
 * through the getters. A getter throws if the owning module has not initialised yet, which means an init-order bug.
 */
public final class HaruhiServices {
    private static volatile HaruhiStorage storage;
    private static volatile ContentService content;
    private static volatile DialogueService dialogue;
    private static volatile NpcControl npc;
    private static volatile BindingService bindings;

    private HaruhiServices() {}

    public static HaruhiStorage storage() {
        return require(storage, "storage");
    }

    public static ContentService content() {
        return require(content, "content");
    }

    public static DialogueService dialogue() {
        return require(dialogue, "dialogue");
    }

    public static NpcControl npc() {
        return require(npc, "npc");
    }

    /** The bindings, read only. Set by the binding module, which initialises last. */
    public static BindingService bindings() {
        return require(bindings, "binding");
    }

    public static void setStorage(HaruhiStorage value) {
        storage = Objects.requireNonNull(value);
    }

    public static void setContent(ContentService value) {
        content = Objects.requireNonNull(value);
    }

    public static void setDialogue(DialogueService value) {
        dialogue = Objects.requireNonNull(value);
    }

    public static void setNpc(NpcControl value) {
        npc = Objects.requireNonNull(value);
    }

    public static void setBindings(BindingService value) {
        bindings = Objects.requireNonNull(value);
    }

    private static <T> T require(T value, String name) {
        if (value == null) {
            throw new IllegalStateException("Haruhi " + name + " service is not initialised");
        }
        return value;
    }
}
