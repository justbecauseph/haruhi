package town.lampas.haruhi.content;

import town.lampas.haruhi.content.model.ContentBundle;

/**
 * A bundle that passed validation, with the canonical JSON a revision stores and that JSON's SHA-256 digest.
 *
 * @param bundle the parsed bundle
 * @param canonicalJson the parsed JSON with sorted keys and no whitespace
 * @param digest the lowercase hex SHA-256 of {@code canonicalJson}
 */
public record ValidatedBundle(ContentBundle bundle, String canonicalJson, String digest) {}
