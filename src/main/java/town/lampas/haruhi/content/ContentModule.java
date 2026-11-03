package town.lampas.haruhi.content;

/**
 * Owned by the content component. Parses and validates bundles, stores them as revisions through
 * {@link town.lampas.haruhi.api.HaruhiServices#storage()}, activates one, registers the {@link ContentService} with
 * {@link town.lampas.haruhi.api.HaruhiServices#setContent} and registers the {@code /haruhi content} commands.
 */
public final class ContentModule {
    private ContentModule() {}

    public static void init() {
        // Implemented by the content component.
    }
}
