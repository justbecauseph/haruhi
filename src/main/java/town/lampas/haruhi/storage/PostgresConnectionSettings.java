package town.lampas.haruhi.storage;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import org.postgresql.Driver;

/**
 * The URL and the startup {@code options} that a pool connects with, given the {@code jdbc_url} from the configuration.
 *
 * <p>The driver gives a URL parameter priority over the data source properties, so a {@code socketTimeout} or
 * {@code options} in the URL replaces the value the storage sets. The parameters are read with the driver's own parser,
 * so the server limits follow the socket timeout that the connections really get:
 * <ul>
 *   <li>{@code lock_timeout} is half the socket timeout, rounded down, and {@code statement_timeout} is two seconds
 *       less. On a server that is answering, a lock wait or a long statement fails on the server (SQLState 55P03 or
 *       57014) and is rolled back before the client's socket read fails (08006). The limits do not cover a network
 *       stall or the commit, so a failure with 08006 leaves the outcome unknown.</li>
 *   <li>The URL's own {@code options} parameter is kept. The two server limits are appended after it, so they win over
 *       any {@code lock_timeout} or {@code statement_timeout} the URL sets. A blank value, such as {@code ?options=}
 *       or a bare {@code ?options}, adds nothing before the limits, so the limits still apply.</li>
 *   <li>A URL {@code socketTimeout} must be from {@link #MIN_SOCKET_TIMEOUT_SECONDS} to
 *       {@link #MAX_SOCKET_TIMEOUT_SECONDS} seconds. Below 5 seconds the lock limit is not below the statement limit,
 *       and at 2 seconds or less the statement limit is zero or below. Zero turns a PostgreSQL timeout off. A
 *       socketTimeout of 0 turns the socket timeout off.</li>
 * </ul>
 *
 * @param jdbcUrl the URL to connect with. It carries the merged options when the configured URL has an {@code options}
 *     parameter, even a blank one.
 * @param options the startup {@code options} value that the pool sets as a data source property. The URL carries the
 *     same value when the configured URL has an {@code options} parameter.
 */
record PostgresConnectionSettings(String jdbcUrl, String options) {
    /** The lowest socket timeout a URL may set. */
    static final int MIN_SOCKET_TIMEOUT_SECONDS = 5;
    /** The highest socket timeout a URL may set. The driver multiplies it by 1000 in an int. */
    static final int MAX_SOCKET_TIMEOUT_SECONDS = Integer.MAX_VALUE / 1000;

    /**
     * The settings for a configured URL.
     *
     * @param configuredUrl the {@code jdbc:postgresql:} URL from the configuration
     * @param defaultSocketTimeoutSeconds the socket timeout used when the URL does not set one
     * @throws IllegalArgumentException when the driver cannot parse the URL, or its socketTimeout is not a whole number
     *     of seconds from {@link #MIN_SOCKET_TIMEOUT_SECONDS} to {@link #MAX_SOCKET_TIMEOUT_SECONDS}. The message never
     *     includes the URL, which may carry a password.
     */
    static PostgresConnectionSettings of(String configuredUrl, int defaultSocketTimeoutSeconds) {
        Properties socketDefaults = new Properties();
        socketDefaults.setProperty("socketTimeout", Integer.toString(defaultSocketTimeoutSeconds));
        Properties effective = parse(configuredUrl, socketDefaults);
        int socketTimeoutSeconds = socketTimeoutSeconds(effective.getProperty("socketTimeout"));
        String limits = "-c lock_timeout=" + socketTimeoutSeconds / 2 + "s -c statement_timeout="
                + (socketTimeoutSeconds - 2) + "s";

        // Parsed without defaults, so the value is the URL's own options (or the options of a pg_service.conf the URL
        // names), and null when neither has an options parameter. A blank value is still a value: the driver gives the
        // URL priority over the pool's options property, so the property would not apply.
        String urlOptions = parse(configuredUrl, null).getProperty("options");
        if (urlOptions == null) {
            return new PostgresConnectionSettings(configuredUrl, limits);
        }
        String options = urlOptions.isBlank() ? limits : urlOptions.strip() + " " + limits;
        // The driver keeps the last value of a repeated parameter, so the appended options replace the URL's own.
        String separator = configuredUrl.indexOf('?') < 0 ? "?" : "&";
        String url = configuredUrl + separator + "options=" + URLEncoder.encode(options, StandardCharsets.UTF_8);
        return new PostgresConnectionSettings(url, options);
    }

    /** The driver's parse of the URL. The driver returns null for a URL it cannot parse. */
    private static Properties parse(String configuredUrl, Properties defaults) {
        Properties parsed = Driver.parseURL(configuredUrl, defaults);
        if (parsed == null) {
            throw new IllegalArgumentException("jdbc_url is not a PostgreSQL URL that the driver can parse");
        }
        return parsed;
    }

    private static int socketTimeoutSeconds(String text) {
        int seconds;
        try {
            seconds = Integer.parseInt(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("jdbc_url socketTimeout must be a whole number of seconds", e);
        }
        if (seconds < MIN_SOCKET_TIMEOUT_SECONDS || seconds > MAX_SOCKET_TIMEOUT_SECONDS) {
            throw new IllegalArgumentException("jdbc_url socketTimeout must be from " + MIN_SOCKET_TIMEOUT_SECONDS
                    + " to " + MAX_SOCKET_TIMEOUT_SECONDS + " seconds, found " + seconds);
        }
        return seconds;
    }
}
