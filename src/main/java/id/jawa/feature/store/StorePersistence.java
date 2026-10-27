package id.jawa.feature.store;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Explicit store persistence lifecycle for {@link InMemoryStore}.
 *
 * <p>Baileys' {@code makeInMemoryStore} exposes {@code toJSON} /
 * {@code fromJSON} and leaves <em>when</em> to save to the caller; this
 * helper is the wa-java equivalent of that decision point. Saving is
 * deliberately <em>explicit and manual only</em>: nothing in
 * {@link InMemoryStore} auto-saves on mutation (auto-saving on every
 * mutation would be a behaviour change and a write amplification), and
 * nothing in {@code JaWaClient} calls these methods. A caller that
 * wants durability saves at its own checkpoints, e.g.:
 * {@code StorePersistence.saveStore(client.store(), path)} on shutdown
 * and {@code StorePersistence.loadStore(path)} on startup, replacing
 * the client's store contents with the loaded snapshot. The client
 * lifecycle methods {@code JaWaClient.saveStore(Path)} /
 * {@code JaWaClient.loadStore(Path)} (Increment 5) wrap exactly
 * these two calls, with {@code loadStore} installing the snapshot
 * via {@code InMemoryStore.replaceWith}.
 *
 * <p>Both methods delegate to {@link FileStorePersistence} /
 * {@link StoreJson}, so the format and failure policy are theirs:
 * saves are atomic (sibling {@code .tmp} + move); a missing file
 * loads as {@code null}; an empty or corrupt file loads as an empty
 * store rather than throwing, because the store is a rebuildable
 * cache, not credentials.
 */
public final class StorePersistence {

    private StorePersistence() {}

    /**
     * Saves the store to the file, atomically.
     *
     * @param store store to save; must not be null
     * @param file destination file path; must not be null
     * @throws IOException if the file cannot be written
     */
    public static void saveStore(InMemoryStore store, Path file) throws IOException {
        if (file == null) throw new IllegalArgumentException("file must not be null");
        new FileStorePersistence(file).save(store);
    }

    /**
     * Loads a store from the file.
     *
     * @param file source file path; must not be null
     * @return restored store, an empty store for an empty or corrupt
     *         file, or null when the file does not exist
     * @throws IOException if the file cannot be read
     */
    public static InMemoryStore loadStore(Path file) throws IOException {
        if (file == null) throw new IllegalArgumentException("file must not be null");
        return new FileStorePersistence(file).load();
    }
}
