package town.lampas.haruhi.dialogue.presenter;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundShowDialogPacket;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.dialog.ActionButton;
import net.minecraft.server.dialog.CommonButtonData;
import net.minecraft.server.dialog.CommonDialogData;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.server.dialog.DialogAction;
import net.minecraft.server.dialog.MultiActionDialog;
import net.minecraft.server.dialog.NoticeDialog;
import net.minecraft.server.dialog.action.Action;
import net.minecraft.server.dialog.action.StaticAction;
import net.minecraft.server.dialog.body.DialogBody;
import net.minecraft.server.dialog.body.PlainMessage;
import net.minecraft.server.level.ServerPlayer;
import town.lampas.haruhi.Haruhi;
import town.lampas.haruhi.dialogue.DialogueSessions;
import town.lampas.haruhi.dialogue.runtime.DialogueSession;
import town.lampas.haruhi.dialogue.runtime.PresentedNode;
import town.lampas.haruhi.dialogue.runtime.VisibleChoice;
import town.lampas.haruhi.network.DialogueChoicePayload;

/**
 * The vanilla dialog presenter. A node becomes a {@link MultiActionDialog} of choices, or a {@link NoticeDialog} with a
 * continue button. Each button is a custom click that carries the session id, node id and index, and the server
 * handles the click in {@link #handleClick}. Grayed-out choices have no action, so they cannot be picked.
 */
public final class VanillaDialogs {
    /** Custom click of a choice button: payload {@code session}, {@code node}, {@code choice}. */
    public static final Identifier CHOICE_CLICK = Haruhi.id("dialogue_choice");
    /** Custom click of the exit button, and of Escape on the choice dialog: payload {@code session}. */
    public static final Identifier DISMISS_CLICK = Haruhi.id("dialogue_dismiss");

    private static final String SESSION = "session";
    private static final String NODE = "node";
    private static final String CHOICE = "choice";
    private static final DialogAction WAIT = DialogAction.WAIT_FOR_RESPONSE;

    private VanillaDialogs() {}

    static void show(ServerPlayer player, DialogueSession session, PresentedNode presented) {
        Speaker speaker = Speaker.of(session, presented.node());
        Component title = speaker.name().getString().isEmpty() ? Component.literal("Dialogue") : speaker.name();
        Component text = presented.node().text().orElse(Component.empty());
        List<DialogBody> body = List.of(new PlainMessage(text, PlainMessage.DEFAULT_WIDTH));
        String node = presented.node().id();

        Dialog dialog;
        if (presented.choices().isEmpty()) {
            // Escape would run the button's own action, which here is the continue, so the dialog stays until continued.
            CommonDialogData common = new CommonDialogData(title, Optional.empty(), false, false, WAIT, body, List.of());
            ActionButton continueButton = new ActionButton(
                    new CommonButtonData(CommonComponents.GUI_CONTINUE, CommonButtonData.DEFAULT_WIDTH),
                    Optional.<Action>of(click(CHOICE_CLICK, session.id(), node, DialogueChoicePayload.CONTINUE)));
            dialog = new NoticeDialog(common, continueButton);
        } else {
            CommonDialogData common = new CommonDialogData(title, Optional.empty(), true, false, WAIT, body, List.of());
            List<ActionButton> buttons = new ArrayList<>(presented.choices().size());
            for (int index = 0; index < presented.choices().size(); index++) {
                buttons.add(choiceButton(session, node, index, presented.choices().get(index)));
            }
            ActionButton exit = new ActionButton(
                    new CommonButtonData(CommonComponents.GUI_CANCEL, CommonButtonData.DEFAULT_WIDTH),
                    Optional.<Action>of(dismiss(session.id())));
            dialog = new MultiActionDialog(common, buttons, Optional.of(exit), 1);
        }
        player.connection.send(new ClientboundShowDialogPacket(Holder.<Dialog>direct(dialog)));
    }

    /**
     * Handles a custom click from a vanilla dialog. Returns true when the click belongs to the dialogue presenter, whether
     * or not it was valid, so the caller stops there.
     */
    public static boolean handleClick(DialogueSessions sessions, ServerPlayer player, Identifier id, Optional<Tag> payload) {
        if (!id.equals(CHOICE_CLICK) && !id.equals(DISMISS_CLICK)) {
            return false;
        }
        Optional<CompoundTag> tag = payload.filter(CompoundTag.class::isInstance).map(CompoundTag.class::cast);
        Optional<Integer> session = tag.flatMap(values -> values.getInt(SESSION));
        if (session.isEmpty()) {
            return true;
        }
        if (id.equals(DISMISS_CLICK)) {
            sessions.dismiss(player, session.get());
            return true;
        }
        CompoundTag values = tag.get();
        sessions.choose(player, session.get(), values.getStringOr(NODE, ""), values.getIntOr(CHOICE, Integer.MIN_VALUE));
        return true;
    }

    private static ActionButton choiceButton(DialogueSession session, String node, int index, VisibleChoice choice) {
        CommonButtonData data = new CommonButtonData(
                choice.text(),
                choice.enabled() ? Optional.empty() : choice.unavailableReason(),
                CommonButtonData.DEFAULT_WIDTH);
        Optional<Action> action = choice.enabled()
                ? Optional.<Action>of(click(CHOICE_CLICK, session.id(), node, index))
                : Optional.empty();
        return new ActionButton(data, action);
    }

    private static StaticAction click(Identifier id, int sessionId, String node, int index) {
        CompoundTag tag = new CompoundTag();
        tag.putInt(SESSION, sessionId);
        tag.putString(NODE, node);
        tag.putInt(CHOICE, index);
        return new StaticAction(new ClickEvent.Custom(id, Optional.<Tag>of(tag)));
    }

    private static StaticAction dismiss(int sessionId) {
        CompoundTag tag = new CompoundTag();
        tag.putInt(SESSION, sessionId);
        return new StaticAction(new ClickEvent.Custom(DISMISS_CLICK, Optional.<Tag>of(tag)));
    }
}
