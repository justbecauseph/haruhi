package town.lampas.haruhi.content.remote;

/**
 * Why a request failed, as {@code haruhi:content/request} reports it: a {@link #reason()} code for the Portal and a
 * message for people. Neither ever holds the fetch token.
 */
final class FetchFailure extends Exception {
    /** The Portal could not be reached, or the connection failed during the transfer. */
    static final String FETCH_FAILED = "fetch_failed";
    /** The Portal answered with a status other than 200. */
    static final String HTTP_STATUS = "http_status";
    /** The Portal did not send the whole body in time. */
    static final String TIMEOUT = "timeout";
    /** The body is not the size the call named. */
    static final String SIZE_MISMATCH = "size_mismatch";
    /** The body's SHA-256 is not the one the call named. */
    static final String SHA256_MISMATCH = "sha256_mismatch";
    /** The body is not UTF-8 text. */
    static final String NOT_UTF8 = "not_utf8";
    /** Storing the revision failed. */
    static final String STORAGE_FAILED = "storage_failed";
    /** The activation named a revision that is not stored. */
    static final String NO_SUCH_REVISION = "no_such_revision";
    /** Anything else; the message says what. */
    static final String INTERNAL = "internal";

    private final String reason;

    FetchFailure(String reason, String message) {
        super(message, null, false, false);
        this.reason = reason;
    }

    String reason() {
        return reason;
    }
}
