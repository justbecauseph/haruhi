package town.lampas.haruhi.dialogue.presenter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.dialog.ActionButton;
import net.minecraft.server.dialog.action.Action;
import net.minecraft.server.dialog.action.StaticAction;
import org.junit.jupiter.api.Test;
import town.lampas.haruhi.content.model.ContentBundle;
import town.lampas.haruhi.dialogue.runtime.DialogueSession;
import town.lampas.haruhi.dialogue.runtime.VisibleChoice;

class VanillaDialogsTest {

    private static VisibleChoice choice(int index, boolean enabled) {
        return new VisibleChoice(index, -1, Component.literal("Choice " + index), enabled,
                enabled ? Optional.empty() : Optional.of(Component.literal("Not yet")), List.of(), Optional.empty());
    }

    private static ClickEvent.Custom clickOf(ActionButton button) {
        Action action = button.action().orElseThrow(
                () -> new AssertionError("a button with no action leaves the vanilla client waiting for a response"));
        return (ClickEvent.Custom) ((StaticAction) action).value();
    }

    @Test
    void everyChoiceButtonSendsTheChoiceClickWithItsSessionNodeAndIndex() {
        DialogueSession session = new DialogueSession(7, null, null, ContentBundle.EMPTY, null, null, 1, null);

        for (VisibleChoice choice : List.of(choice(0, true), choice(1, false))) {
            ClickEvent.Custom click = clickOf(VanillaDialogs.choiceButton(session, "menu", choice.choiceIndex(), choice));

            assertEquals(VanillaDialogs.CHOICE_CLICK, click.id());
            CompoundTag payload = (CompoundTag) click.payload().orElseThrow();
            assertEquals(7, payload.getIntOr("session", -1), "the session id the server is showing");
            assertEquals("menu", payload.getStringOr("node", ""), "the node the client was sent");
            assertEquals(choice.choiceIndex(), payload.getIntOr("choice", -1), "the index in the shown entries");
        }
    }
}
