package town.lampas.haruhi.dialogue.presenter;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.chat.Component;
import town.lampas.haruhi.content.model.DialogueNode;
import town.lampas.haruhi.dialogue.runtime.DialogueSession;
import town.lampas.haruhi.dialogue.runtime.PresentedNode;
import town.lampas.haruhi.dialogue.runtime.VisibleChoice;
import town.lampas.haruhi.network.ChoiceView;
import town.lampas.haruhi.network.DialogueNodePayload;

/** Builds the payload that shows a node in the visual-novel screen. */
final class DialogueNodePayloads {

    private DialogueNodePayloads() {}

    /** The node's portrait overrides the speaker profile's, which overrides the 3D render the screen falls back to. */
    static DialogueNodePayload node(DialogueSession session, PresentedNode presented) {
        DialogueNode source = presented.node();
        Speaker speaker = Speaker.of(session, source);
        List<ChoiceView> views = new ArrayList<>(presented.choices().size());
        for (int index = 0; index < presented.choices().size(); index++) {
            VisibleChoice choice = presented.choices().get(index);
            views.add(new ChoiceView(index, choice.text(), choice.enabled(), choice.unavailableReason()));
        }
        return new DialogueNodePayload(
                session.id(),
                presented.graphId(),
                source.id(),
                speaker.entityId(),
                speaker.name(),
                source.text().orElse(Component.empty()),
                source.animation(),
                source.portrait().or(speaker::portrait),
                views);
    }
}
