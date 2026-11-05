package town.lampas.haruhi.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.postgresql.Driver;

/**
 * The server limits for each socket timeout the URL can give, worked out from the rule: lock_timeout is half the socket
 * timeout, statement_timeout is two seconds less. These run without Docker.
 */
class PostgresConnectionSettingsTest {
    private static final int DEFAULT_SOCKET_TIMEOUT = 10;
    private static final String URL = "jdbc:postgresql://db.example:5432/haruhi";

    @Test
    void theDefaultSocketTimeoutGivesHalfForTheLockAndTwoLessForTheStatement() {
        PostgresConnectionSettings settings = PostgresConnectionSettings.of(URL, DEFAULT_SOCKET_TIMEOUT);

        assertEquals(URL, settings.jdbcUrl());
        assertEquals("-c lock_timeout=5s -c statement_timeout=8s", settings.options());
    }

    @Test
    void aSocketTimeoutInTheUrlSetsTheLimitsFromItsValue() {
        // 20 seconds: lock 10, statement 18.
        assertEquals("-c lock_timeout=10s -c statement_timeout=18s",
                PostgresConnectionSettings.of(URL + "?socketTimeout=20", DEFAULT_SOCKET_TIMEOUT).options());
    }

    @Test
    void theLowestSocketTimeoutKeepsTheLockLimitBelowTheStatementLimit() {
        // 5 seconds: lock 2, statement 3.
        assertEquals("-c lock_timeout=2s -c statement_timeout=3s",
                PostgresConnectionSettings.of(URL + "?socketTimeout=5", DEFAULT_SOCKET_TIMEOUT).options());
    }

    @Test
    void theHighestSocketTimeoutGivesLimitsThatFitTheDriversMilliseconds() {
        // 2147483 seconds: lock 1073741 (rounded down), statement 2147481.
        assertEquals("-c lock_timeout=1073741s -c statement_timeout=2147481s",
                PostgresConnectionSettings.of(URL + "?socketTimeout=2147483", DEFAULT_SOCKET_TIMEOUT).options());
    }

    @Test
    void aSocketTimeoutOutsideTheAcceptedRangeIsRefused() {
        for (String value : List.of("0", "-1", "4", "2147484", "abc")) {
            assertThrows(IllegalArgumentException.class,
                    () -> PostgresConnectionSettings.of(URL + "?socketTimeout=" + value, DEFAULT_SOCKET_TIMEOUT),
                    "socketTimeout=" + value);
        }
    }

    @Test
    void theUrlsOwnOptionsComeFirstAndTheLimitsLastAndTheDriverReadsBackTheSameOptions() {
        // The URL sets search_path and a lock_timeout of 0, which would switch the lock limit off.
        String url = URL + "?options=-c%20search_path%3Dharuhi%20-c%20lock_timeout%3D0";

        PostgresConnectionSettings settings = PostgresConnectionSettings.of(url, DEFAULT_SOCKET_TIMEOUT);

        String expected = "-c search_path=haruhi -c lock_timeout=0 -c lock_timeout=5s -c statement_timeout=8s";
        assertEquals(expected, settings.options());
        Properties parsed = Driver.parseURL(settings.jdbcUrl(), null);
        assertEquals(expected, parsed.getProperty("options"));
    }

    /** A blank options parameter adds nothing, so the connection gets the two limits and no other options. */
    @Test
    void anEmptyOptionsParameterLeavesBothLimitsOnTheConnection() {
        assertLimitsReachTheConnection("?options=");
    }

    @Test
    void aBareOptionsParameterLeavesBothLimitsOnTheConnection() {
        assertLimitsReachTheConnection("?options");
    }

    @Test
    void aBlankOptionsParameterLeavesBothLimitsOnTheConnection() {
        assertLimitsReachTheConnection("?options=%20");
    }

    @Test
    void aUrlThatIsNotPostgreSqlIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> PostgresConnectionSettings.of("jdbc:mysql://db.example/haruhi", DEFAULT_SOCKET_TIMEOUT));
    }

    /**
     * Checks the options a pooled connection gets for the default URL plus the query. The pool's options property is
     * the driver's default, so the effective options are the driver's parse of the URL with that property. With the
     * default socket timeout of 10 seconds, the limits are lock 5 and statement 8.
     */
    private static void assertLimitsReachTheConnection(String query) {
        PostgresConnectionSettings settings = PostgresConnectionSettings.of(URL + query, DEFAULT_SOCKET_TIMEOUT);

        Properties poolProperties = new Properties();
        poolProperties.setProperty("options", settings.options());
        String effective = Driver.parseURL(settings.jdbcUrl(), poolProperties).getProperty("options");

        assertEquals("-c lock_timeout=5s -c statement_timeout=8s", effective, query);
    }
}
