package town.lampas.haruhi.content.remote;

import com.google.gson.JsonObject;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The validate, import and activate requests the server holds, by request id. A request runs once: a call with an id
 * the server already holds returns that request, a failure included, so a retry never runs it twice. A finished
 * request's result is kept for {@link #KEPT} after it finished, then forgotten. Thread-safe.
 *
 * <p>At most {@link #MAX_RUNNING} requests run at once and at most {@link #MAX_HELD} are held, running or finished; a
 * new request beyond either is refused as busy, so the results held stay within bounds.
 */
final class ContentRequests {
    static final Duration KEPT = Duration.ofMinutes(15);
    static final int MAX_RUNNING = 8;
    static final int MAX_HELD = 512;

    /** What a request does, named as the answers spell it. */
    enum Kind {
        VALIDATE("validate"),
        IMPORT("import"),
        ACTIVATE("activate");

        private final String id;

        Kind(String id) {
            this.id = id;
        }

        String id() {
            return id;
        }
    }

    /**
     * A request as its call made it: the kind and the call's fields that decide what it does, without the token. Two
     * calls with one request id must agree on it.
     */
    record Call(Kind kind, String fields) {}

    /** What {@link #begin} did. */
    enum Begin {
        /** The request is new and now running; the caller starts its work. */
        STARTED,
        /** The server already holds this request with the same call; nothing new runs. */
        HELD,
        /** The server holds this request id for another call. */
        CONFLICT,
        /** Too many requests are running or held. */
        BUSY
    }

    /** A request's state. */
    sealed interface State permits Running, Done, Failed {}

    record Running() implements State {}

    /** Finished, with its result fields. */
    record Done(JsonObject result) implements State {
        Done {
            result = result.deepCopy();
        }
    }

    /** Failed before it could finish, with a reason code and a message for people. */
    record Failed(String reason, String message) implements State {}

    /** A held request: its kind and its state. */
    record Held(Kind kind, State state) {}

    private record Entry(Call call, State state, Optional<Instant> finishedAt) {}

    private final Clock clock;
    private final Map<UUID, Entry> entries = new LinkedHashMap<>();

    ContentRequests(Clock clock) {
        this.clock = Objects.requireNonNull(clock);
    }

    /** Registers {@code id} for {@code call} if it is new; see {@link Begin}. */
    synchronized Begin begin(UUID id, Call call) {
        sweep();
        Entry held = entries.get(id);
        if (held != null) {
            return held.call().equals(call) ? Begin.HELD : Begin.CONFLICT;
        }
        long running = entries.values().stream().filter(entry -> entry.state() instanceof Running).count();
        if (running >= MAX_RUNNING || entries.size() >= MAX_HELD) {
            return Begin.BUSY;
        }
        entries.put(id, new Entry(call, new Running(), Optional.empty()));
        return Begin.STARTED;
    }

    /** Finishes a running request. Ignored for an id that is not held or has already finished. */
    synchronized void finish(UUID id, State outcome) {
        if (outcome instanceof Running) {
            throw new IllegalArgumentException("A request finishes as done or failed");
        }
        Entry held = entries.get(id);
        if (held == null || !(held.state() instanceof Running)) {
            return;
        }
        entries.put(id, new Entry(held.call(), outcome, Optional.of(clock.instant())));
    }

    /** The request with this id, while the server holds it. */
    synchronized Optional<Held> get(UUID id) {
        sweep();
        Entry held = entries.get(id);
        if (held == null) {
            return Optional.empty();
        }
        State state = held.state() instanceof Done done ? new Done(done.result()) : held.state();
        return Optional.of(new Held(held.call().kind(), state));
    }

    /** How many requests are held, running or finished. */
    synchronized int size() {
        sweep();
        return entries.size();
    }

    /** Forgets every request, as when the server stops. Running work that finishes later is ignored. */
    synchronized void clear() {
        entries.clear();
    }

    private void sweep() {
        Instant now = clock.instant();
        Iterator<Entry> iterator = entries.values().iterator();
        while (iterator.hasNext()) {
            Optional<Instant> finished = iterator.next().finishedAt();
            if (finished.isPresent() && !now.isBefore(finished.get().plus(KEPT))) {
                iterator.remove();
            }
        }
    }
}
