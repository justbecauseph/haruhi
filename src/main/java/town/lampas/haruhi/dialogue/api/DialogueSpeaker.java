package town.lampas.haruhi.dialogue.api;

import java.util.Objects;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Who speaks for the interlocutor or the place in a conversation's nodes that name no {@code speaker} of their own, such
 * as a vanilla villager or a lectern a binding opened a graph on.
 *
 * <p>Such a node shows {@code profile}'s name and portrait, or {@code name} when there is no profile or the active
 * revision lacks it. When the conversation has an interlocutor, that entity is the speaker of those nodes and of nodes
 * whose {@code speaker} is {@code profile}, so the dialogue screen draws its head when no portrait applies.
 *
 * <p>{@link #NONE} keeps the default: a {@code haruhi:npc} interlocutor speaks with its own profile, and any other
 * conversation shows no speaker for such nodes.
 *
 * @param profile an NPC profile id of the active content, which also keys the conversation's visit count
 * @param name shown when there is no profile, or the active revision lacks it
 */
public record DialogueSpeaker(Optional<Identifier> profile, Optional<Component> name) {
    /** No speaker of its own: the interlocutor NPC's profile, if any, speaks. */
    public static final DialogueSpeaker NONE = new DialogueSpeaker(Optional.empty(), Optional.empty());

    public DialogueSpeaker {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(name, "name");
    }

    /** A speaker with an optional profile, named {@code name} when the profile is absent or missing. */
    public static DialogueSpeaker of(Optional<Identifier> profile, Component name) {
        return new DialogueSpeaker(profile, Optional.of(Objects.requireNonNull(name, "name")));
    }

    /** True for {@link #NONE}, the default of a conversation that names no speaker of its own. */
    public boolean isNone() {
        return profile.isEmpty() && name.isEmpty();
    }
}
