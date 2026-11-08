package town.lampas.haruhi.content.remote;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.jsonrpc.IncomingRpcMethod;
import net.minecraft.server.jsonrpc.api.Schema;
import net.minecraft.util.ExtraCodecs;
import org.jspecify.annotations.Nullable;
import town.lampas.haruhi.Haruhi;

/**
 * Registers the {@code haruhi:content/*} methods on the Minecraft management protocol (JSON-RPC over the TLS
 * WebSocket the Portal holds). Each takes one param, an object whose fields the method documents, decoded as any JSON
 * so that a malformed field is answered with {@code invalid} instead of failing vanilla's decode, which logs the call
 * with its params. Each answers an object with a {@code status}.
 *
 * <p>{@code list} and {@code export} run off the server thread and wait for storage; the others run on it, as vanilla
 * runs management methods by default. Before the server has started they answer {@code unavailable}.
 */
public final class ContentRpc {
    public static final Identifier STATUS = Haruhi.id("content/status");
    public static final Identifier LIST = Haruhi.id("content/list");
    public static final Identifier VALIDATE = Haruhi.id("content/validate");
    public static final Identifier IMPORT = Haruhi.id("content/import");
    public static final Identifier ACTIVATE = Haruhi.id("content/activate");
    public static final Identifier REQUEST = Haruhi.id("content/request");
    public static final Identifier EXPORT = Haruhi.id("content/export");
    public static final Identifier BINDINGS = Haruhi.id("content/bindings");
    public static final List<Identifier> ALL = List.of(STATUS, LIST, VALIDATE, IMPORT, ACTIVATE, REQUEST, EXPORT,
            BINDINGS);

    private static final Codec<JsonElement> ANY = ExtraCodecs.JSON;
    private static final Schema<Long> LONG_SCHEMA = Schema.ofType("integer", Codec.LONG);
    private static final Schema<JsonElement> REVISION_SCHEMA = object()
            .withField("id", LONG_SCHEMA)
            .withField("digest", Schema.STRING_SCHEMA)
            .withField("author", Schema.STRING_SCHEMA)
            .withField("created_at", Schema.STRING_SCHEMA)
            .withField("activated_at", Schema.STRING_SCHEMA)
            .withField("activated_by", Schema.STRING_SCHEMA)
            .withField("active", Schema.BOOL_SCHEMA);
    private static final Schema<JsonElement> FETCH_PARAMS = object()
            .withField("request", Schema.UUID_SCHEMA)
            .withField("sha256", Schema.STRING_SCHEMA)
            .withField("size", LONG_SCHEMA)
            .withField("token", Schema.STRING_SCHEMA);
    private static final Schema<JsonElement> ANSWER = object()
            .withField("status", Schema.STRING_SCHEMA)
            .withField("message", Schema.STRING_SCHEMA);

    private ContentRpc() {}

    /**
     * Adds the methods to the registry the management server dispatches from. Call during mod initialisation.
     * {@code methods} gives the running server's handlers, or null while no server runs.
     */
    public static void register(Supplier<@Nullable ContentMethods> methods) {
        register(STATUS, "Haruhi: the live content revision, storage, version, schema and extensions", methods,
                ContentMethods::status, object(), ANSWER
                        .withField("revision", REVISION_SCHEMA)
                        .withField("storage", Schema.STRING_SCHEMA)
                        .withField("haruhi_version", Schema.STRING_SCHEMA)
                        .withField("schema", Schema.INT_SCHEMA)
                        .withField("extensions", Schema.STRING_SCHEMA.asArray())
                        .withField("startup_complete", Schema.BOOL_SCHEMA), true);
        register(LIST, "Haruhi: stored content revisions, newest first", methods, ContentMethods::list,
                object().withField("before", LONG_SCHEMA).withField("limit", Schema.INT_SCHEMA),
                ANSWER.withField("revisions", REVISION_SCHEMA.asArray()).withField("next", LONG_SCHEMA), false);
        register(VALIDATE, "Haruhi: fetch a bundle from the Portal and validate it; read the result with "
                + REQUEST, methods, ContentMethods::validate, FETCH_PARAMS, ANSWER, true);
        register(IMPORT, "Haruhi: fetch a bundle from the Portal, validate it and store it as a revision; read the "
                + "result with " + REQUEST, methods, ContentMethods::importBundle,
                FETCH_PARAMS.withField("author", Schema.STRING_SCHEMA), ANSWER, true);
        register(ACTIVATE, "Haruhi: activate a stored revision; read the result with " + REQUEST, methods,
                ContentMethods::activate, object()
                        .withField("request", Schema.UUID_SCHEMA)
                        .withField("revision", LONG_SCHEMA)
                        .withField("actor", Schema.STRING_SCHEMA), ANSWER, true);
        register(REQUEST, "Haruhi: the state and result of a validate, import or activate request", methods,
                ContentMethods::request, object().withField("request", Schema.UUID_SCHEMA), ANSWER
                        .withField("kind", Schema.STRING_SCHEMA)
                        .withField("valid", Schema.BOOL_SCHEMA)
                        .withField("digest", Schema.STRING_SCHEMA)
                        .withField("revision", REVISION_SCHEMA)
                        .withField("problems", Schema.STRING_SCHEMA.asArray())
                        .withField("problem_count", Schema.INT_SCHEMA)
                        .withField("reason", Schema.STRING_SCHEMA), true);
        register(EXPORT, "Haruhi: part of a stored revision's bundle text", methods, ContentMethods::export,
                object().withField("revision", LONG_SCHEMA).withField("offset", LONG_SCHEMA), ANSWER
                        .withField("revision", LONG_SCHEMA)
                        .withField("digest", Schema.STRING_SCHEMA)
                        .withField("offset", LONG_SCHEMA)
                        .withField("text", Schema.STRING_SCHEMA)
                        .withField("total", LONG_SCHEMA)
                        .withField("next", LONG_SCHEMA), false);
        register(BINDINGS, "Haruhi: block bindings, bound entities and loaded Haruhi NPCs", methods,
                ContentMethods::bindings,
                object().withField("before", Schema.STRING_SCHEMA).withField("limit", Schema.INT_SCHEMA), ANSWER
                        .withField("blocks", object().asArray())
                        .withField("entities", object().asArray())
                        .withField("npcs", object().asArray())
                        .withField("next", Schema.STRING_SCHEMA), true);
    }

    private static void register(
            Identifier id,
            String description,
            Supplier<@Nullable ContentMethods> methods,
            BiFunction<ContentMethods, @Nullable JsonElement, JsonObject> handler,
            Schema<JsonElement> params,
            Schema<JsonElement> answer,
            boolean onServerThread) {
        IncomingRpcMethod.IncomingRpcMethodBuilder<JsonElement, JsonElement> builder = IncomingRpcMethod
                .<JsonElement, JsonElement>method((api, param, client) -> answer(methods.get(), handler, param))
                .description(description)
                .param("params", params)
                .response("result", answer);
        if (!onServerThread) {
            builder.notOnMainThread();
        }
        Registry.register(BuiltInRegistries.INCOMING_RPC_METHOD, id, builder.build());
    }

    private static JsonElement answer(
            @Nullable ContentMethods methods,
            BiFunction<ContentMethods, @Nullable JsonElement, JsonObject> handler,
            @Nullable JsonElement param) {
        if (methods == null) {
            JsonObject answer = new JsonObject();
            answer.addProperty("status", "unavailable");
            answer.addProperty("message", "The server has not started");
            return answer;
        }
        return handler.apply(methods, param);
    }

    private static Schema<JsonElement> object() {
        return Schema.record(ANY);
    }
}
