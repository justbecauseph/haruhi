package town.lampas.haruhi.client.dialogue;

/**
 * Decides whether a server message belongs to the conversation on screen. The server numbers each conversation, and a
 * message for another number is from an earlier or later conversation, so the client ignores it.
 */
public final class DialogueSession {
    private DialogueSession() {}

    /** True when a close message for {@code closingSessionId} should close a screen showing {@code openSessionId}. */
    public static boolean matches(int openSessionId, int closingSessionId) {
        return openSessionId == closingSessionId;
    }
}
