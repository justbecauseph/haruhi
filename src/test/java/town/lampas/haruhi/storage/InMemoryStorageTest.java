package town.lampas.haruhi.storage;

/** Runs the storage contract against {@link InMemoryStorage}. */
class InMemoryStorageTest extends HaruhiStorageContractTest {
    @Override
    protected HaruhiStorage createStorage() {
        return new InMemoryStorage();
    }
}
