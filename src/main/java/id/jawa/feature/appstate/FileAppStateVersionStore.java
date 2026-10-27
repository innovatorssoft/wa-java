package id.jawa.feature.appstate;

import id.jawa.domain.store.SqliteAuthStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Stream;

/**
 * Durable {@link AppStateVersionStore} — the Increment 5 counterpart of
 * {@link FileAppStateKeyStorage}, following the same two-backend
 * pattern wa-java already uses for key material:
 *
 * <ul>
 *   <li>SQLite branch: one row per collection in the
 *       {@code appstate_versions} table of the {@link SqliteAuthStore}
 *       database (see its {@code loadAppStateVersions} /
 *       {@code saveAppStateVersion}).</li>
 *   <li>File branch: one file per collection under
 *       {@code <signalDir>/appstate-versions/}, named
 *       {@code <url-base64(collection)>.appver}, written atomically
 *       (temp file + {@code ATOMIC_MOVE}) like the app-state key files.</li>
 * </ul>
 *
 * <p>File / map text form (shared with the SQLite {@code index_map}
 * column, so the backends stay interchangeable): a
 * {@code version=<long>} line, a {@code hash=<standard-base64, 128
 * bytes>} line, then one
 * {@code <standard-base64 indexMac>:<standard-base64 valueMac>} line
 * per live index. The map separator is {@code :} rather than
 * {@code =} deliberately: standard-base64 keys can end in {@code =}
 * padding, so splitting a map line on {@code =} would corrupt the
 * key; neither base64 alphabet contains {@code :}. A file or row that does not parse (bad version,
 * hash not 128 bytes, unparseable map line) is skipped with a warning
 * and loads as "no stored state" for that collection — never as a
 * half-decoded state, because a half-decoded LtHash would corrupt the
 * next patch's snapshot/patch MACs.
 *
 * <p>Fallback, stated plainly: this store is only wired in when the
 * client has a durable backend — a {@link SqliteAuthStore}, or a
 * {@code signalDir}. A client built on a plain {@code FileAuthStore}
 * with no {@code signalDir} has no directory the client can derive
 * (the {@code AuthStore} interface exposes no path), so it keeps the
 * {@link InMemoryAppStateVersionStore} default and versions are lost
 * on restart; its resync-before-patch still refreshes them from the
 * server on the next session (see {@code JaWaClient.appPatch}).
 */
public final class FileAppStateVersionStore implements AppStateVersionStore {

    private static final Logger LOG = LoggerFactory.getLogger(FileAppStateVersionStore.class);
    private static final String SUFFIX = ".appver";

    private final Path baseDir;
    private final SqliteAuthStore sqliteStore;
    private final ConcurrentMap<String, LtHashState> cache = new ConcurrentHashMap<>();

    public FileAppStateVersionStore(Path baseDir) {
        this(baseDir, null);
    }

    public FileAppStateVersionStore(SqliteAuthStore sqliteStore) {
        this(null, sqliteStore);
    }

    public FileAppStateVersionStore(Path baseDir, SqliteAuthStore sqliteStore) {
        this.baseDir = baseDir;
        this.sqliteStore = sqliteStore;
        if (baseDir != null) {
            try {
                Files.createDirectories(baseDir);
            } catch (IOException e) {
                throw new UncheckedIOException("cannot create app-state version dir " + baseDir, e);
            }
        }
        loadAll();
    }

    private void loadAll() {
        if (sqliteStore != null) {
            try {
                cache.putAll(sqliteStore.loadAppStateVersions());
                LOG.info("Loaded {} app-state version(s) from SQLite database", cache.size());
            } catch (IOException e) {
                LOG.warn("Failed loading app-state versions from SQLite: {}", e.toString());
            }
            return;
        }
        if (baseDir != null && Files.isDirectory(baseDir)) {
            try (Stream<Path> files = Files.list(baseDir)) {
                files.filter(p -> p.getFileName().toString().endsWith(SUFFIX))
                    .forEach(this::loadOne);
            } catch (IOException e) {
                LOG.warn("Failed listing app-state version dir {}: {}", baseDir, e.toString());
            }
            LOG.info("Loaded {} app-state version(s) from {}", cache.size(), baseDir);
        }
    }

    private void loadOne(Path file) {
        String filename = file.getFileName().toString();
        String encoded = filename.substring(0, filename.length() - SUFFIX.length());
        String collection;
        try {
            collection = new String(Base64.getUrlDecoder().decode(encoded), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            LOG.warn("Skipping app-state version file with unparseable name: {}", file);
            return;
        }
        try {
            String text = Files.readString(file);
            LtHashState state = parse(text);
            if (state == null) {
                LOG.warn("Skipping unparseable app-state version file: {}", file);
                return;
            }
            cache.put(collection, state);
        } catch (IOException e) {
            LOG.warn("Failed reading app-state version {}: {}", file, e.toString());
        }
    }

    @Override
    public LtHashState load(String collectionName) {
        return collectionName == null ? null : cache.get(collectionName);
    }

    @Override
    public void save(String collectionName, LtHashState state) {
        if (collectionName == null) throw new IllegalArgumentException("collectionName must not be null");
        if (state == null) throw new IllegalArgumentException("state must not be null");
        cache.put(collectionName, state);
        if (sqliteStore != null) {
            sqliteStore.saveAppStateVersion(collectionName, state);
            return;
        }
        if (baseDir != null) {
            try {
                String encoded = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(collectionName.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                Path tmp = baseDir.resolve(encoded + SUFFIX + ".tmp");
                Path dest = baseDir.resolve(encoded + SUFFIX);
                Files.writeString(tmp, format(state));
                Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                LOG.warn("Failed persisting app-state version {}: {}", collectionName, e.toString());
            }
        }
    }

    /** Serialises one state to the shared text form (version/hash/map lines). */
    static String format(LtHashState state) {
        StringBuilder sb = new StringBuilder();
        sb.append("version=").append(state.version()).append('\n');
        sb.append("hash=").append(Base64.getEncoder().encodeToString(state.hash())).append('\n');
        sb.append(formatIndexMap(state.indexValueMap()));
        return sb.toString();
    }

    /** Parses the shared text form; returns null when it does not parse. */
    static LtHashState parse(String text) {
        if (text == null) return null;
        Long version = null;
        byte[] hash = null;
        Map<String, byte[]> map = new LinkedHashMap<>();
        for (String line : text.split("\n")) {
            if (line.isBlank() || line.startsWith("#")) continue;
            try {
                if (line.startsWith("version=")) {
                    version = Long.parseLong(line.substring("version=".length()));
                } else if (line.startsWith("hash=")) {
                    hash = Base64.getDecoder().decode(line.substring("hash=".length()));
                } else {
                    // Map line: <base64 indexMac>:<base64 valueMac>.
                    // ':' is the separator because a standard-base64
                    // key may itself contain '=' padding.
                    int colon = line.indexOf(':');
                    if (colon < 0) return null;
                    map.put(line.substring(0, colon),
                        Base64.getDecoder().decode(line.substring(colon + 1)));
                }
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        if (version == null || hash == null || hash.length != 128) return null;
        return new LtHashState(version, hash, map);
    }

    /**
     * Formats an index-value map as one
     * {@code <base64 indexMac>:<base64 valueMac>} line per entry.
     * Keys are already the standard-base64 strings
     * {@link LtHashState#mapKey(byte[])} produces; the separator is
     * {@code :} because those keys can end in {@code =} padding.
     */
    public static String formatIndexMap(Map<String, byte[]> map) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, byte[]> e : map.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) continue;
            sb.append(e.getKey()).append(':')
                .append(Base64.getEncoder().encodeToString(e.getValue())).append('\n');
        }
        return sb.toString();
    }

    /**
     * Parses the index-map text form; returns null when any line does
     * not parse (callers must then skip the whole state, not use a
     * partial map).
     */
    public static Map<String, byte[]> parseIndexMap(String text) {
        if (text == null) return null;
        Map<String, byte[]> map = new LinkedHashMap<>();
        for (String line : text.split("\n")) {
            if (line.isBlank() || line.startsWith("#")) continue;
            int colon = line.indexOf(':');
            if (colon < 0) return null;
            try {
                map.put(line.substring(0, colon), Base64.getDecoder().decode(line.substring(colon + 1)));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return map;
    }
}
