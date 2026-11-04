package town.lampas.haruhi.dialogue;

import com.mojang.serialization.DataResult;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.Nullable;
import town.lampas.haruhi.Haruhi;
import town.lampas.haruhi.api.HaruhiServices;
import town.lampas.haruhi.content.model.ContentBundle;
import town.lampas.haruhi.dialogue.api.DialogueFlags;
import town.lampas.haruhi.dialogue.api.DialogueService;
import town.lampas.haruhi.dialogue.flags.CharacterCache;
import town.lampas.haruhi.dialogue.flags.CharacterStore;
import town.lampas.haruhi.dialogue.flags.PlayerFlags;
import town.lampas.haruhi.dialogue.presenter.NodePresenter;
import town.lampas.haruhi.dialogue.runtime.CompiledContent;
import town.lampas.haruhi.dialogue.runtime.CompiledGraph;
import town.lampas.haruhi.dialogue.runtime.DialogueSession;
import town.lampas.haruhi.dialogue.runtime.GraphRunner;
import town.lampas.haruhi.dialogue.runtime.Step;
import town.lampas.haruhi.storage.VisitKey;

/**
 * The dialogue service: one session per player, the per-player flag and visit caches, and the queued opens that wait for
 * those caches to load. Server thread only, except the storage callbacks, which hop back through the server.
 */
public final class DialogueSessions implements DialogueService {
    /** How often pending flag batches are written: every 5 seconds. */
    static final int FLUSH_INTERVAL_TICKS = 100;
    /** Most graphs one open may chain into through {@code haruhi:open}. */
    static final int MAX_OPEN_CHAIN = 8;
    /** A session ends when the interlocutor is more than this many blocks away. */
    static final double MAX_DISTANCE_SQ = 8.0 * 8.0;
    private static final int STOP_FLUSH_SECONDS = 10;

    private final Map<UUID, DialogueSession> sessions = new HashMap<>();
    private final Map<UUID, PlayerState> players = new HashMap<>();
    private final CharacterStore store;
    private final GraphRunner runner = new GraphRunner();
    private final CompiledContent compiled = new CompiledContent();
    private int lastSessionId;

    public DialogueSessions(CharacterStore store) {
        this.store = store;
    }

    @Override
    public boolean open(ServerPlayer player, Identifier graphId, @Nullable Entity interlocutor) {
        return open(player, graphId, interlocutor, 0);
    }

    /** Ends the player's conversation, including one still queued for their flags, which then never starts. */
    @Override
    public void close(ServerPlayer player) {
        PlayerState state = players.get(player.getUUID());
        if (state != null) {
            state.pending = null;
        }
        DialogueSession session = sessions.get(player.getUUID());
        if (session != null) {
            endSession(player, session);
        }
    }

    /**
     * True from a successful {@link #open} until the conversation ends, including while the open waits for the
     * player's flags to load, so the answer does not depend on whether the cache was already loaded.
     */
    @Override
    public boolean isInDialogue(ServerPlayer player) {
        if (sessions.containsKey(player.getUUID())) {
            return true;
        }
        PlayerState state = players.get(player.getUUID());
        return state != null && state.pending != null;
    }

    /**
     * A client's pick. Ignored unless the session is the player's current one and the node is the one last sent. The
     * index is checked again on the server; a stale or unavailable index shows the node again.
     */
    public void choose(ServerPlayer player, int sessionId, String nodeId, int index) {
        DialogueSession session = sessions.get(player.getUUID());
        if (session == null || !runner.accepts(session, sessionId, nodeId)) {
            return;
        }
        ServerPlayer owner = playerOf(session);
        if (!isNear(owner, session.interlocutor())) {
            endSession(owner, session);
            return;
        }
        apply(owner, session, runner.choose(session, index), 0);
    }

    /** The player closed the screen. Ends the session without running any action. */
    public void dismiss(ServerPlayer player, int sessionId) {
        DialogueSession session = sessions.get(player.getUUID());
        if (session != null && session.id() == sessionId) {
            endSession(player, session);
        }
    }

    public Optional<DialogueSession> session(ServerPlayer player) {
        return Optional.ofNullable(sessions.get(player.getUUID()));
    }

    /** The player's flags, once they have loaded. Empty while loading, so a command does not read stale values. */
    public Optional<DialogueFlags> loadedFlags(ServerPlayer player) {
        PlayerState state = players.get(player.getUUID());
        if (state == null || !state.cache.isLoaded()) {
            return Optional.empty();
        }
        return Optional.<DialogueFlags>of(state.flags);
    }

    public void onJoin(ServerPlayer player) {
        ensureLoaded(stateOf(player));
    }

    /** The player is leaving: the session ends silently, and pending flag writes are flushed. */
    public void onLeave(ServerPlayer player) {
        sessions.remove(player.getUUID());
        players.remove(player.getUUID());
        store.flush(player.getUUID());
    }

    public void onDeath(ServerPlayer player) {
        close(player);
    }

    /** Ends sessions whose player died or whose interlocutor is gone or too far, and writes pending flags every 5 seconds. */
    public void onTick(MinecraftServer server) {
        for (DialogueSession session : List.copyOf(sessions.values())) {
            ServerPlayer player = playerOf(session);
            if (!player.isAlive() || !isNear(player, session.interlocutor())) {
                endSession(player, session);
            }
        }
        if (server.getTickCount() % FLUSH_INTERVAL_TICKS == 0) {
            store.flushPending();
        }
    }

    /** Ends every session and queued open, and waits, bounded, for the pending flag writes to reach storage. */
    public void onStopping(MinecraftServer server) {
        sessions.clear();
        players.values().forEach(state -> state.pending = null);
        try {
            store.flushAll().get(STOP_FLUSH_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Haruhi.LOGGER.error("Interrupted while writing dialogue flags at shutdown", e);
        } catch (ExecutionException | TimeoutException e) {
            Haruhi.LOGGER.error("Dialogue flags did not finish writing within {} seconds at shutdown",
                    STOP_FLUSH_SECONDS, e);
        }
    }

    private boolean open(ServerPlayer player, Identifier graphId, @Nullable Entity interlocutor, int depth) {
        if (depth > MAX_OPEN_CHAIN) {
            Haruhi.LOGGER.error("Dialogue {} would open past {} graphs in one step; not opened",
                    graphId, MAX_OPEN_CHAIN);
            return false;
        }
        ContentBundle bundle = HaruhiServices.content().active();
        if (!bundle.dialogues().containsKey(graphId)) {
            return false;
        }
        close(player);
        PlayerState state = stateOf(player);
        if (!state.cache.isLoaded()) {
            state.pending = new PendingOpen(graphId, interlocutor);
            ensureLoaded(state);
            return true;
        }
        return start(state, bundle, graphId, interlocutor, depth);
    }

    /** Starts a loaded player's conversation: records the visit, then enters the start node. */
    private boolean start(
            PlayerState state, ContentBundle bundle, Identifier graphId, @Nullable Entity interlocutor, int depth) {
        DataResult<CompiledGraph> compiledGraph = compiled.graph(bundle, graphId);
        Optional<CompiledGraph> graph = compiledGraph.result();
        if (graph.isEmpty()) {
            Haruhi.LOGGER.error("Cannot open dialogue {}: {}", graphId,
                    compiledGraph.error().map(error -> error.message()).orElse("unreadable"));
            return false;
        }
        ServerPlayer player = state.player;
        VisitKey key = new VisitKey(graphId, npcOf(interlocutor));
        Instant now = Instant.now();
        int visits = state.cache.recordVisit(key, now);
        store.recordVisit(player.getUUID(), key, now).whenComplete((saved, error) -> {
            if (error != null) {
                Haruhi.LOGGER.error("Could not record a visit to {} for {}", graphId, player.getUUID(), error);
            }
        });
        DialogueSession session = new DialogueSession(nextSessionId(), player, interlocutor, bundle, graph.get(),
                state.flags, visits, player.level().getRandom());
        sessions.put(player.getUUID(), session);
        return apply(player, session, runner.start(session), depth);
    }

    /** Carries out a step. Returns false only when the step could not start a graph. */
    private boolean apply(ServerPlayer player, DialogueSession session, Step step, int depth) {
        if (sessions.get(player.getUUID()) != session) {
            return true;
        }
        if (step instanceof Step.Show show) {
            NodePresenter.show(player, session, show.node());
        } else if (step instanceof Step.Opened opened) {
            endSession(player, session);
            return open(player, opened.graph(), session.interlocutor(), depth + 1);
        } else {
            endSession(player, session);
        }
        return true;
    }

    private void endSession(ServerPlayer player, DialogueSession session) {
        sessions.remove(player.getUUID(), session);
        NodePresenter.close(player, session.id());
    }

    private PlayerState stateOf(ServerPlayer player) {
        return players.computeIfAbsent(player.getUUID(), id -> new PlayerState(player, store));
    }

    private void ensureLoaded(PlayerState state) {
        if (state.cache.isLoaded() || state.loading) {
            return;
        }
        state.loading = true;
        MinecraftServer server = state.player.level().getServer();
        store.load(state.player.getUUID()).whenComplete((loaded, error) ->
                server.execute(() -> onLoaded(state, loaded, error)));
    }

    private void onLoaded(PlayerState state, CharacterStore.@Nullable Loaded loaded, @Nullable Throwable error) {
        state.loading = false;
        UUID id = state.player.getUUID();
        if (players.get(id) != state) {
            return;
        }
        if (error != null || loaded == null) {
            Haruhi.LOGGER.error("Could not load dialogue flags and visits for {}; the next open retries", id, error);
            players.remove(id);
            return;
        }
        state.cache.load(loaded.flags(), loaded.visits());
        PendingOpen pending = state.pending;
        state.pending = null;
        if (pending != null) {
            openPending(state.player, pending);
        }
    }

    private void openPending(ServerPlayer player, PendingOpen pending) {
        if (!isNear(player, pending.interlocutor())) {
            Haruhi.LOGGER.debug("Dropped the queued open of {}: the interlocutor is gone or too far", pending.graph());
            return;
        }
        open(player, pending.graph(), pending.interlocutor(), 0);
    }

    private int nextSessionId() {
        lastSessionId = lastSessionId == Integer.MAX_VALUE ? 1 : lastSessionId + 1;
        return lastSessionId;
    }

    private static Identifier npcOf(@Nullable Entity interlocutor) {
        if (interlocutor == null) {
            return VisitKey.NO_NPC;
        }
        return HaruhiServices.npc().profileId(interlocutor).orElse(VisitKey.NO_NPC);
    }

    /** Whether the interlocutor still stands where a conversation may go on: alive, in the same level, within 8 blocks. */
    static boolean isNear(ServerPlayer player, @Nullable Entity interlocutor) {
        if (interlocutor == null) {
            return true;
        }
        return interlocutor.isAlive()
                && interlocutor.level() == player.level()
                && player.distanceToSqr(interlocutor) <= MAX_DISTANCE_SQ;
    }

    private static ServerPlayer playerOf(DialogueSession session) {
        return Objects.requireNonNull(session.player(), "a service session always has its player");
    }

    /** A player's cache, flag view and queued open, for as long as the player stays online. */
    private static final class PlayerState {
        private final ServerPlayer player;
        private final CharacterCache cache = new CharacterCache();
        private final PlayerFlags flags;
        private @Nullable PendingOpen pending;
        private boolean loading;

        PlayerState(ServerPlayer player, CharacterStore store) {
            this.player = player;
            MinecraftServer server = player.level().getServer();
            this.flags = new PlayerFlags(player.getUUID(), cache, store, server::execute);
        }
    }

    /** An open requested before the player's cache loaded. */
    private record PendingOpen(Identifier graph, @Nullable Entity interlocutor) {}
}
