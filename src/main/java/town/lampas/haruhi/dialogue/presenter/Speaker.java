package town.lampas.haruhi.dialogue.presenter;

import java.util.Optional;
import java.util.OptionalInt;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import town.lampas.haruhi.api.HaruhiServices;
import town.lampas.haruhi.content.model.DialogueNode;
import town.lampas.haruhi.content.model.NpcProfile;
import town.lampas.haruhi.dialogue.runtime.DialogueSession;

/**
 * Who a node is spoken by: the speaker's name and portrait, and the interlocutor's entity id when the interlocutor is the
 * speaker. The speaker profile is the node's own, else the interlocutor's profile, else none.
 *
 * @param portrait the speaker profile's 2D portrait, empty when the profile has none or there is no profile
 */
record Speaker(Component name, OptionalInt entityId, Optional<Identifier> portrait) {

    static Speaker of(DialogueSession session, DialogueNode node) {
        Entity interlocutor = session.interlocutor();
        Optional<Identifier> interlocutorProfile = interlocutor == null
                ? Optional.empty()
                : HaruhiServices.npc().profileId(interlocutor);
        Optional<Identifier> profile = node.speaker().or(() -> interlocutorProfile);
        Optional<NpcProfile> speaking = profile.map(id -> session.content().npcs().get(id));
        Component name = speaking.map(NpcProfile::name).orElse(Component.empty());
        boolean interlocutorSpeaks = interlocutor != null
                && interlocutorProfile.isPresent()
                && profile.equals(interlocutorProfile);
        return new Speaker(
                name,
                interlocutorSpeaks ? OptionalInt.of(interlocutor.getId()) : OptionalInt.empty(),
                speaking.flatMap(NpcProfile::portrait));
    }
}
