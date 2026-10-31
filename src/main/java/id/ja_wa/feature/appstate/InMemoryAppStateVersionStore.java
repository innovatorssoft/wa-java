package id.ja_wa.feature.appstate;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory {@link AppStateVersionStore}. Suitable for tests and for
 * single-session use; versions are lost on restart, so a later session
 * would start from {@link LtHashState#fresh()} and must re-sync before
 * patching (Baileys' {@code appPatch} resyncs first for exactly this
 * reason — the transport does the same via its resync step).
 */
public final class InMemoryAppStateVersionStore implements AppStateVersionStore {

    private final ConcurrentMap<String, LtHashState> states = new ConcurrentHashMap<>();

    @Override
    public LtHashState load(String collectionName) {
        return collectionName == null ? null : states.get(collectionName);
    }

    @Override
    public void save(String collectionName, LtHashState state) {
        if (collectionName == null) throw new IllegalArgumentException("collectionName must not be null");
        if (state == null) throw new IllegalArgumentException("state must not be null");
        states.put(collectionName, state);
    }
}
