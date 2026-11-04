package town.lampas.haruhi.dialogue.builtin;

import com.google.gson.JsonObject;
import com.mojang.serialization.DataResult;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import town.lampas.haruhi.api.HaruhiServices;
import town.lampas.haruhi.dialogue.api.DialogueAction;
import town.lampas.haruhi.dialogue.api.ParseContext;
import town.lampas.haruhi.storage.FlagKey;

/** The built-in actions that change flags, move the conversation, or play effects. */
final class Actions {

    private Actions() {}

    static DataResult<DialogueAction> flagSet(JsonObject json, ParseContext context) {
        return Args.<DialogueAction>parse(context, "haruhi:flag/set", json,
                Set.of("namespace", "flag", "value"), args -> {
                    FlagKey key = args.flag("namespace", "flag");
                    int value = args.integer("value", 1);
                    return ctx -> ctx.flags().set(key, value);
                });
    }

    static DataResult<DialogueAction> flagAdd(JsonObject json, ParseContext context) {
        return Args.<DialogueAction>parse(context, "haruhi:flag/add", json,
                Set.of("namespace", "flag", "delta"), args -> {
                    FlagKey key = args.flag("namespace", "flag");
                    int delta = args.integer("delta", 1);
                    return ctx -> ctx.flags().add(key, delta);
                });
    }

    static DataResult<DialogueAction> flagClear(JsonObject json, ParseContext context) {
        return Args.<DialogueAction>parse(context, "haruhi:flag/clear", json, Set.of("namespace", "flag"), args -> {
            FlagKey key = args.flag("namespace", "flag");
            return ctx -> ctx.flags().clear(key);
        });
    }

    static DataResult<DialogueAction> goTo(JsonObject json, ParseContext context) {
        return Args.<DialogueAction>parse(context, "haruhi:goto", json, Set.of("node"), args -> {
            String node = args.string("node");
            return ctx -> ctx.goTo(node);
        });
    }

    static DataResult<DialogueAction> end(JsonObject json, ParseContext context) {
        return Args.<DialogueAction>parse(context, "haruhi:end", json, Set.of(), args -> ctx -> ctx.end());
    }

    /** Opens another graph of the same bundle, so the target must be in the bundle being validated. */
    static DataResult<DialogueAction> open(JsonObject json, ParseContext context) {
        return Args.<DialogueAction>parse(context, "haruhi:open", json, Set.of("graph"), args -> {
            Identifier graph = args.id("graph");
            if (!context.bundle().dialogues().containsKey(graph)) {
                throw Args.invalid("graph '" + graph + "' is not in this bundle");
            }
            return ctx -> ctx.openGraph(graph);
        });
    }

    /** Plays the animation on the interlocutor. The NPC service is read when the action runs, not when it parses. */
    static DataResult<DialogueAction> animation(JsonObject json, ParseContext context) {
        return Args.<DialogueAction>parse(context, "haruhi:animation", json, Set.of("animation"), args -> {
            String animation = args.string("animation");
            return ctx -> ctx.interlocutor().ifPresent(npc -> HaruhiServices.npc().playAnimation(npc, animation));
        });
    }

    static DataResult<DialogueAction> sound(JsonObject json, ParseContext context) {
        return Args.<DialogueAction>parse(context, "haruhi:sound", json, Set.of("sound", "volume", "pitch"), args -> {
            Identifier sound = args.id("sound");
            float volume = args.decimal("volume", 1f);
            float pitch = args.decimal("pitch", 1f);
            if (volume < 0f) {
                throw Args.invalid("'volume' must not be negative");
            }
            if (pitch <= 0f) {
                throw Args.invalid("'pitch' must be above zero");
            }
            return ctx -> playSound(ctx.player(), sound, volume, pitch);
        });
    }

    static DataResult<DialogueAction> message(JsonObject json, ParseContext context) {
        return Args.<DialogueAction>parse(context, "haruhi:message", json, Set.of("text"), args -> {
            var text = args.text("text");
            return ctx -> ctx.player().sendSystemMessage(text);
        });
    }

    /**
     * Plays the sound to the player at the player's position. The sound id is not checked against the registry, because
     * a sound id is only a name the client resolves.
     */
    private static void playSound(ServerPlayer player, Identifier sound, float volume, float pitch) {
        ServerLevel level = player.level();
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                Holder.direct(SoundEvent.createVariableRangeEvent(sound)), SoundSource.MASTER, volume, pitch);
    }
}
