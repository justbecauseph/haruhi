package town.lampas.haruhi.dialogue.presenter;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import town.lampas.haruhi.api.HaruhiServices;
import town.lampas.haruhi.content.model.DialogueNode;
import town.lampas.haruhi.content.model.NpcProfile;
import town.lampas.haruhi.dialogue.api.DialogueSpeaker;
import town.lampas.haruhi.dialogue.runtime.DialogueSession;

/**
 * Who a node is spoken by: the speaker's name and portrait, and the interlocutor's entity id when the interlocutor is the
 * speaker. The speaker profile is the node's own, else the session speaker's (see {@link DialogueSpeaker}), else the
 * interlocutor NPC's profile, else none.
 *
 * @param portrait the speaker profile's 2D portrait, empty when the profile has none or there is no profile
 */
record Speaker(Component name, OptionalInt entityId, Optional<Identifier> portrait) {

    static Speaker of(DialogueSession session, DialogueNode node) {
        Entity interlocutor = session.interlocutor();
        Optional<Identifier> interlocutorProfile = interlocutor == null
                ? Optional.empty()
                : HaruhiServices.npc().profileId(interlocutor);
        Voice voice = resolve(node.speaker(), session.speaker(), interlocutor != null, interlocutorProfile,
                session.content().npcs());
        return new Speaker(voice.name(),
                voice.interlocutorSpeaks() ? OptionalInt.of(interlocutor.getId()) : OptionalInt.empty(),
                voice.portrait());
    }

    /**
     * The rules of {@link #of} without an entity. The conversation's own voice is the session speaker's profile, else
     * the interlocutor NPC's. A node without a {@code speaker}, or whose {@code speaker} is that voice, speaks with it:
     * the profile's name and portrait, else the session speaker's name, and the interlocutor speaks when the
     * conversation has a voice at all. Any other node speaks as its own profile, without the interlocutor.
     */
    static Voice resolve(Optional<Identifier> nodeSpeaker, DialogueSpeaker session, boolean hasInterlocutor,
            Optional<Identifier> interlocutorProfile, Map<Identifier, NpcProfile> profiles) {
        Optional<Identifier> own = session.profile().or(() -> interlocutorProfile);
        Optional<Identifier> profile = nodeSpeaker.or(() -> own);
        Optional<NpcProfile> speaking = profile.map(profiles::get);
        boolean ownVoice = nodeSpeaker.isEmpty() || nodeSpeaker.equals(own);
        Component name = speaking.map(NpcProfile::name)
                .orElseGet(() -> ownVoice ? session.name().orElse(Component.empty()) : Component.empty());
        boolean interlocutorSpeaks = hasInterlocutor && ownVoice && (own.isPresent() || session.name().isPresent());
        return new Voice(name, interlocutorSpeaks, speaking.flatMap(NpcProfile::portrait));
    }

    /** What {@link #resolve} decides: the name and portrait shown, and whether the interlocutor is the speaker. */
    record Voice(Component name, boolean interlocutorSpeaks, Optional<Identifier> portrait) {}
}
