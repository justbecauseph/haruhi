package town.lampas.haruhi.content.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import town.lampas.haruhi.content.remote.ContentRequests.Begin;
import town.lampas.haruhi.content.remote.ContentRequests.Call;
import town.lampas.haruhi.content.remote.ContentRequests.Done;
import town.lampas.haruhi.content.remote.ContentRequests.Failed;
import town.lampas.haruhi.content.remote.ContentRequests.Held;
import town.lampas.haruhi.content.remote.ContentRequests.Kind;
import town.lampas.haruhi.content.remote.ContentRequests.Running;

class ContentRequestsTest {
    private static final Call VALIDATE_A = new Call(Kind.VALIDATE, "sha256=aa\nsize=10");
    private static final Call VALIDATE_B = new Call(Kind.VALIDATE, "sha256=bb\nsize=10");

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-08T12:00:00Z"));
    private final ContentRequests requests = new ContentRequests(clock);

    @Test
    void aNewRequestStartsAndRunsUntilFinished() {
        UUID id = UUID.randomUUID();

        assertEquals(Begin.STARTED, requests.begin(id, VALIDATE_A));

        assertEquals(Optional.of(new Held(Kind.VALIDATE, new Running())), requests.get(id));
    }

    @Test
    void theSameCallAgainReturnsTheHeldRequestWithoutStartingIt() {
        UUID id = UUID.randomUUID();
        requests.begin(id, VALIDATE_A);

        assertEquals(Begin.HELD, requests.begin(id, VALIDATE_A));
        assertEquals(1, requests.size());
    }

    @Test
    void aFailedRequestIsReturnedAgainRatherThanRetried() {
        UUID id = UUID.randomUUID();
        requests.begin(id, VALIDATE_A);
        requests.finish(id, new Failed(FetchFailure.HTTP_STATUS, "The Portal answered HTTP 404"));

        assertEquals(Begin.HELD, requests.begin(id, VALIDATE_A));
        Held held = requests.get(id).orElseThrow();
        assertEquals(new Failed(FetchFailure.HTTP_STATUS, "The Portal answered HTTP 404"), held.state());
    }

    @Test
    void anotherCallUnderTheSameIdIsAConflict() {
        UUID id = UUID.randomUUID();
        requests.begin(id, VALIDATE_A);

        assertEquals(Begin.CONFLICT, requests.begin(id, VALIDATE_B));
        assertEquals(Begin.CONFLICT, requests.begin(id, new Call(Kind.IMPORT, VALIDATE_A.fields())));
    }

    @Test
    void aDoneResultIsAReadOnlyCopy() {
        UUID id = UUID.randomUUID();
        requests.begin(id, VALIDATE_A);
        JsonObject result = new JsonObject();
        result.addProperty("valid", true);
        requests.finish(id, new Done(result));
        result.addProperty("valid", false);

        Done first = assertInstanceOf(Done.class, requests.get(id).orElseThrow().state());
        first.result().addProperty("changed", true);

        Done again = assertInstanceOf(Done.class, requests.get(id).orElseThrow().state());
        assertTrue(again.result().get("valid").getAsBoolean());
        assertEquals(1, again.result().size());
    }

    @Test
    void finishingTwiceKeepsTheFirstOutcome() {
        UUID id = UUID.randomUUID();
        requests.begin(id, VALIDATE_A);
        requests.finish(id, new Failed(FetchFailure.TIMEOUT, "first"));
        requests.finish(id, new Failed(FetchFailure.INTERNAL, "second"));

        assertEquals(new Failed(FetchFailure.TIMEOUT, "first"), requests.get(id).orElseThrow().state());
    }

    @Test
    void finishingAnUnknownRequestIsIgnored() {
        requests.finish(UUID.randomUUID(), new Failed(FetchFailure.INTERNAL, "nobody asked"));

        assertEquals(0, requests.size());
    }

    @Test
    void aRequestCannotFinishAsRunning() {
        UUID id = UUID.randomUUID();
        requests.begin(id, VALIDATE_A);

        assertThrows(IllegalArgumentException.class, () -> requests.finish(id, new Running()));
    }

    @Test
    void aFinishedResultIsKeptForFifteenMinutesAfterItFinished() {
        UUID id = UUID.randomUUID();
        requests.begin(id, VALIDATE_A);
        clock.advance(Duration.ofMinutes(10));
        requests.finish(id, new Done(new JsonObject()));

        clock.advance(ContentRequests.KEPT.minusSeconds(1));
        assertTrue(requests.get(id).isPresent(), "the result went before 15 minutes had passed since it finished");

        clock.advance(Duration.ofSeconds(1));
        assertEquals(Optional.empty(), requests.get(id));
        // Forgotten, so the id can start a new request.
        assertEquals(Begin.STARTED, requests.begin(id, VALIDATE_B));
    }

    @Test
    void aRunningRequestIsNeverForgotten() {
        UUID id = UUID.randomUUID();
        requests.begin(id, VALIDATE_A);

        clock.advance(Duration.ofHours(2));

        assertEquals(Optional.of(new Held(Kind.VALIDATE, new Running())), requests.get(id));
    }

    @Test
    void tooManyRunningRequestsAreRefusedUntilOneFinishes() {
        UUID first = UUID.randomUUID();
        requests.begin(first, VALIDATE_A);
        for (int i = 1; i < ContentRequests.MAX_RUNNING; i++) {
            assertEquals(Begin.STARTED, requests.begin(UUID.randomUUID(), VALIDATE_A));
        }

        assertEquals(Begin.BUSY, requests.begin(UUID.randomUUID(), VALIDATE_A));

        requests.finish(first, new Done(new JsonObject()));
        assertEquals(Begin.STARTED, requests.begin(UUID.randomUUID(), VALIDATE_A));
    }

    @Test
    void tooManyHeldResultsAreRefusedUntilTheyExpire() {
        for (int i = 0; i < ContentRequests.MAX_HELD; i++) {
            UUID id = UUID.randomUUID();
            assertEquals(Begin.STARTED, requests.begin(id, VALIDATE_A));
            requests.finish(id, new Done(new JsonObject()));
        }

        assertEquals(Begin.BUSY, requests.begin(UUID.randomUUID(), VALIDATE_A));

        clock.advance(ContentRequests.KEPT);
        assertEquals(Begin.STARTED, requests.begin(UUID.randomUUID(), VALIDATE_A));
        assertEquals(1, requests.size());
    }

    @Test
    void clearForgetsEverythingAndIgnoresLateResults() {
        UUID id = UUID.randomUUID();
        requests.begin(id, VALIDATE_A);

        requests.clear();
        requests.finish(id, new Done(new JsonObject()));

        assertEquals(Optional.empty(), requests.get(id));
    }

    /** A clock the test moves. */
    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
