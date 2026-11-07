package town.lampas.haruhi.dialogue.presenter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import town.lampas.haruhi.content.model.NpcProfile;
import town.lampas.haruhi.dialogue.api.DialogueSpeaker;

/**
 * Who speaks a node: the node's own speaker, the conversation's {@link DialogueSpeaker}, or the interlocutor NPC's
 * profile, and when the interlocutor is drawn. A bound villager or lectern speaks through a {@link DialogueSpeaker}.
 */
class SpeakerTest {
    private static final Identifier CLERK = Identifier.fromNamespaceAndPath("haruhi", "test/clerk");
    private static final Identifier GUARD = Identifier.fromNamespaceAndPath("haruhi", "test/guard");
    private static final Identifier CLERK_PORTRAIT = Identifier.fromNamespaceAndPath("haruhi", "textures/clerk.png");
    private static final Identifier SKIN = Identifier.withDefaultNamespace("textures/entity/player/wide/steve.png");
    private static final Map<Identifier, NpcProfile> PROFILES = Map.of(
            CLERK, profile(CLERK, "Clerk", Optional.of(CLERK_PORTRAIT)),
            GUARD, profile(GUARD, "Guard", Optional.empty()));
    private static final DialogueSpeaker VILLAGER = DialogueSpeaker.of(Optional.empty(), Component.literal("Librarian"));
    private static final DialogueSpeaker VILLAGER_AS_CLERK = DialogueSpeaker.of(Optional.of(CLERK),
            Component.literal("Librarian"));

    private static NpcProfile profile(Identifier id, String name, Optional<Identifier> portrait) {
        return new NpcProfile(id, Component.literal(name), Optional.empty(), SKIN, Optional.empty(), portrait,
                Optional.empty(), 1.0F);
    }

    @Test
    void aBoundEntityWithoutAProfileSpeaksWithItsOwnNameAndIsDrawn() {
        Speaker.Voice voice = Speaker.resolve(Optional.empty(), VILLAGER, true, Optional.empty(), PROFILES);

        assertEquals("Librarian", voice.name().getString());
        assertTrue(voice.interlocutorSpeaks(), "the villager's head is the headshot");
        assertEquals(Optional.empty(), voice.portrait());
    }

    @Test
    void aBoundEntityWithAProfileSpeaksAsThatProfileAndIsStillDrawn() {
        Speaker.Voice plain = Speaker.resolve(Optional.empty(), VILLAGER_AS_CLERK, true, Optional.empty(), PROFILES);
        Speaker.Voice named = Speaker.resolve(Optional.of(CLERK), VILLAGER_AS_CLERK, true, Optional.empty(), PROFILES);

        for (Speaker.Voice voice : new Speaker.Voice[] {plain, named}) {
            assertEquals("Clerk", voice.name().getString());
            assertTrue(voice.interlocutorSpeaks());
            assertEquals(Optional.of(CLERK_PORTRAIT), voice.portrait());
        }
    }

    @Test
    void aSpeakerProfileMissingFromTheRevisionFallsBackToTheEntitysName() {
        Speaker.Voice voice = Speaker.resolve(Optional.empty(), VILLAGER_AS_CLERK, true, Optional.empty(), Map.of());

        assertEquals("Librarian", voice.name().getString());
        assertTrue(voice.interlocutorSpeaks());
        assertEquals(Optional.empty(), voice.portrait());
    }

    @Test
    void aNodeNamingAnotherProfileSpeaksAsItWithoutTheInterlocutor() {
        Speaker.Voice voice = Speaker.resolve(Optional.of(GUARD), VILLAGER_AS_CLERK, true, Optional.empty(), PROFILES);

        assertEquals("Guard", voice.name().getString());
        assertFalse(voice.interlocutorSpeaks(), "the villager is not the guard");
    }

    @Test
    void aBoundBlockSpeaksWithItsNameAndHasNothingToDraw() {
        Speaker.Voice voice = Speaker.resolve(Optional.empty(),
                DialogueSpeaker.of(Optional.empty(), Component.literal("Lectern")), false, Optional.empty(), PROFILES);

        assertEquals("Lectern", voice.name().getString());
        assertFalse(voice.interlocutorSpeaks());
    }

    @Test
    void withoutASpeakerAnNpcSpeaksWithItsProfileAsBefore() {
        Speaker.Voice own = Speaker.resolve(Optional.empty(), DialogueSpeaker.NONE, true, Optional.of(CLERK), PROFILES);
        Speaker.Voice other = Speaker.resolve(Optional.of(GUARD), DialogueSpeaker.NONE, true, Optional.of(CLERK),
                PROFILES);
        Speaker.Voice missing = Speaker.resolve(Optional.empty(), DialogueSpeaker.NONE, true, Optional.of(CLERK),
                Map.of());

        assertEquals("Clerk", own.name().getString());
        assertTrue(own.interlocutorSpeaks());
        assertEquals("Guard", other.name().getString());
        assertFalse(other.interlocutorSpeaks());
        assertEquals("", missing.name().getString(), "an NPC whose profile is gone has no name, as before");
        assertTrue(missing.interlocutorSpeaks());
    }

    @Test
    void withoutASpeakerAConversationWithAnEntityThatIsNotAnNpcHasNoSpeakerAsBefore() {
        Speaker.Voice voice = Speaker.resolve(Optional.empty(), DialogueSpeaker.NONE, true, Optional.empty(), PROFILES);

        assertEquals("", voice.name().getString());
        assertFalse(voice.interlocutorSpeaks());
    }
}
