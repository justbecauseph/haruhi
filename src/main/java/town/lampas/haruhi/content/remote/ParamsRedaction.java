package town.lampas.haruhi.content.remote;

/**
 * Keeps a {@code haruhi:} call's params out of vanilla's "Invalid parameter invocation {}: {}, {}" DEBUG line, whose
 * arguments are the method name, the params and the error message. Used by
 * {@code town.lampas.haruhi.mixin.JsonRpcParamsLogMixin}.
 */
public final class ParamsRedaction {
    public static final String REDACTED = "<params of a haruhi: call, not logged>";

    private ParamsRedaction() {}

    /** The log arguments with the params replaced when the method is in the {@code haruhi} namespace. */
    public static Object[] redact(Object[] arguments) {
        if (arguments.length < 2 || !(arguments[0] instanceof String method) || !method.startsWith("haruhi:")) {
            return arguments;
        }
        Object[] redacted = arguments.clone();
        redacted[1] = REDACTED;
        return redacted;
    }
}
