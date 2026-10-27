package id.jawa.feature.store;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * File-backed persistence for {@link InMemoryStore}, following the file
 * pattern of {@link id.jawa.domain.store.FileAuthStore}: writes go to a
 * sibling {@code .tmp} file and are moved into place atomically so a
 * crash mid-write cannot leave a half-written store file.
 * <p>
 * Load policy mirrors {@link StoreJson#fromJson(String)}: a missing file
 * yields {@code null} (like {@code FileAuthStore.load()}), while an
 * empty or corrupt file yields an empty store rather than throwing —
 * the store is a rebuildable cache, so losing a damaged snapshot is
 * preferable to failing startup. Callers that must know a file was
 * corrupt should check the file contents before loading.
 */
public final class FileStorePersistence {

    private final Path file;

    /**
     * Creates a file-backed persistence helper for the given path.
     *
     * @param file file path for the store JSON
     */
    public FileStorePersistence(Path file) {
        this.file = file;
    }

    /** @return the file this helper reads from and writes to */
    public Path file() {
        return file;
    }

    /**
     * Loads the store from the file.
     *
     * @return restored store, an empty store for an empty or corrupt
     *         file, or null when the file does not exist
     * @throws IOException if the file cannot be read
     */
    public InMemoryStore load() throws IOException {
        if (!Files.exists(file)) return null;
        return StoreJson.fromJson(Files.readString(file));
    }

    /**
     * Saves the store to the file atomically.
     *
     * @param store store to save; must not be null
     * @throws IOException if the file cannot be written
     */
    public void save(InMemoryStore store) throws IOException {
        if (store == null) throw new IllegalArgumentException("store must not be null");
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, StoreJson.toJson(store));
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Deletes the persisted store file, if present.
     *
     * @throws IOException if deletion fails
     */
    public void clear() throws IOException {
        Files.deleteIfExists(file);
    }
}
